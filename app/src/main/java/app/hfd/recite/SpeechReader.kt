package app.hfd.recite

import app.hfd.BuildConfig
import app.hfd.core.recite.Arabic
import app.hfd.diag.Diag

/**
 * The recogniser a listener reads with (a Recite session, the search by reciting), loaded at its
 * first use and kept until [close]: the CTC model when it is on the phone and reads the reference
 * clip right, else Whisper (its encoder on the NPU where there is a build for the phone). Use it
 * from one thread, the one that reads.
 */
class SpeechReader(private val models: () -> ReciteModels) {
    private var whisper: Whisper? = null
    private var ctc: CtcEngine? = null
    /** The CTC model didn't load or misread the reference clip: Whisper reads. */
    private var ctcFailed = false

    /** Whether a model is loaded (any thread: for a "loading" line). */
    @Volatile var loaded = false
        private set

    /**
     * What reads an utterance: its sound, and what the follower expects or wants checked (empty
     * for a free reading); null where no model is on the phone or none loads.
     */
    fun reader(): ((FloatArray, String) -> String?)? {
        ctc?.let { return it::transcribe }
        whisper?.let { w -> return { pcm, expected -> w.transcribe(pcm, expected) } }
        val m = models()
        if (m.ctc != null && !ctcFailed) {
            val engine = CtcEngine.load(m.ctc.path)
            if (engine != null && ctcReads(engine)) {
                ctc = engine
                loaded = true
                return engine::transcribe
            }
            engine?.close()
            ctcFailed = true
        }
        val file = m.whisper ?: return null
        val w = Whisper.load(file.path)?.also { whisper = it; npuOn(it) } ?: return null
        loaded = true
        return { pcm, expected -> w.transcribe(pcm, expected) }
    }

    /** The CTC model on the reference clip (al-Ikhlāṣ 112:1): used only if it reads it as it is. */
    private fun ctcReads(engine: CtcEngine): Boolean {
        val pcm = ReciteSession.referenceClip() ?: return true
        val started = System.currentTimeMillis()
        val text = engine.transcribe(pcm)
        val right = text != null && Arabic.skeleton(text) == Arabic.skeleton(REFERENCE_TEXT)
        Diag.log("ctc.selftest", "text" to text, "right" to right, "ms" to System.currentTimeMillis() - started)
        return right
    }

    /**
     * The encoder on the NPU if this phone has a build of it ([NpuEncoder]), used once it reads the
     * reference clip (al-Ikhlāṣ 112:1) as it is: QNN set up, then one reading (some 150 ms).
     */
    private fun npuOn(w: Whisper) {
        val encoder = ReciteSession.npuEncoder() ?: return
        val binary = encoder.file ?: return
        val pcm = ReciteSession.referenceClip() ?: return
        if (!encoder.trying(BuildConfig.VERSION_CODE)) return
        try {
            if (w.setUpNpu(binary) != null) return
            val started = System.currentTimeMillis()
            val text = w.transcribe(pcm, onNpu = true)
            val right = text != null && Arabic.skeleton(text) == Arabic.skeleton(REFERENCE_TEXT)
            w.npu = right
            Diag.log("npu.selftest", "text" to text, "right" to right, "ms" to System.currentTimeMillis() - started)
            // Read wrong: its encoder output against the CPU's on the same clip tells a layout, a
            // scale or a precision fault apart (the CPU reading takes some 500 ms, once).
            if (!right) {
                w.transcribe(pcm, onNpu = false)
                Diag.log("npu.compare", "when" to "selftest", "result" to w.npuCompare())
            }
        } finally {
            encoder.tried()
        }
    }

    /**
     * Once a run, when the session closes (not at its start, where it held up the first readings):
     * the model on a reference recitation (al-Ikhlāṣ 112:1, Alafasy), to tell a recognition
     * problem on the phone from a problem with what the microphone hears. (Threads, measured on
     * the owner's phone on 2 Oct: 4 take 1.38 s a reading, 6 take 1.47 s, 8 take 1.62 s.)
     */
    private fun selfTest(w: Whisper) {
        if (selfTested) return
        selfTested = true
        val pcm = ReciteSession.referenceClip() ?: return
        val started = System.currentTimeMillis()
        val text = w.transcribe(pcm, onNpu = false)
        Diag.log("whisper.selftest", "expected" to REFERENCE_TEXT, "text" to text, "ms" to System.currentTimeMillis() - started)
        // The NPU's reading of the same clip against the CPU's, how close its encoder comes.
        if (w.npu) {
            val npuText = w.transcribe(pcm, onNpu = true)
            Diag.log("npu.compare", "when" to "close", "text" to npuText, "result" to w.npuCompare())
        }
    }

    /** Frees the models, after Whisper's once-a-run reading of the reference clip. On the reading thread. */
    fun close() {
        whisper?.let(::selfTest)
        whisper?.close()
        whisper = null
        ctc?.close()
        ctc = null
        loaded = false
    }

    companion object {
        @Volatile private var selfTested = false
        /** What the reference clip says (al-Ikhlāṣ 112:1). */
        private const val REFERENCE_TEXT = "قُلْ هُوَ ٱللَّهُ أَحَدٌ"
    }
}
