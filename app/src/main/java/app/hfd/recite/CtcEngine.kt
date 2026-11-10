package app.hfd.recite

import app.hfd.core.ctc.CtcRecognizer
import app.hfd.core.ctc.OrtCtcModel
import app.hfd.diag.Diag

/**
 * The CTC recogniser on the phone: mrmuminov's Qur'an FastConformer ([SpeechModel.CTC]) run by
 * ONNX Runtime on the CPU. It reads an utterance in one pass, with no decoder: on an ARM core some
 * 30 ms per second of audio (ctcspeed.yml), and on the bench 97 % of words right against the
 * Whisper model's 94.5 %, most of all for a muffled or noisy voice. Use it from one thread.
 */
class CtcEngine private constructor(private val model: OrtCtcModel, private val threads: Int) : AutoCloseable {
    private val recognizer = CtcRecognizer(model)
    private var closed = false

    /** As [Whisper.transcribe]: the text heard in [pcm], or [expected]'s words checked ("?\n…"); null if it failed. */
    fun transcribe(pcm: FloatArray, expected: String = ""): String? {
        check(!closed) { "closed" }
        val started = System.currentTimeMillis()
        val text = runCatching { recognizer.transcribe(pcm, expected) }.onFailure { Diag.error("ctc.text", it) }.getOrNull()
        // The text is in recite.partial / recite.heard (and ctc.selftest).
        Diag.log(
            "ctc.text", "seconds" to pcm.size / Whisper.SAMPLE_RATE.toFloat(), "ms" to System.currentTimeMillis() - started,
            "threads" to threads, "check" to expected.startsWith("?").takeIf { it }, "failed" to (text == null).takeIf { it },
        )
        return text
    }

    override fun close() {
        if (!closed) {
            closed = true
            runCatching { model.close() }
        }
    }

    companion object {
        /**
         * Two threads: on an ARM server core 3 s of audio take 57 ms with two and 72–81 with four
         * (four only win past ten seconds: 20 s in 246 ms against 350). ctc.text has the phone's.
         */
        private const val THREADS = 2

        /** Null where ONNX Runtime or the model doesn't load (logged: ctc.load). */
        fun load(path: String): CtcEngine? {
            val started = System.currentTimeMillis()
            val model = runCatching { OrtCtcModel(path, THREADS) }
                .onFailure { Diag.error("ctc.load", it) }
                .getOrNull()
            // Throwable too: a missing native library is an Error, not an Exception (runCatching takes both).
            Diag.log("ctc.load", "ok" to (model != null), "ms" to System.currentTimeMillis() - started, "bytes" to java.io.File(path).length(), "threads" to THREADS)
            return model?.let { CtcEngine(it, THREADS) }
        }
    }
}
