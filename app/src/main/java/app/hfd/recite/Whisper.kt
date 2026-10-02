package app.hfd.recite

import android.util.Log
import app.hfd.diag.Diag

/**
 * whisper.cpp through JNI (app/src/main/cpp): speech recognition on the phone. One instance per
 * loaded model; use it from one thread at a time.
 */
class Whisper private constructor(private var ctx: Long) : AutoCloseable {

    /**
     * What was heard in [pcm] (16 kHz mono, −1…1), or null if recognition failed; steered toward
     * [expected] (what the reciter may be saying, [app.hfd.core.recite.Follower.expected]) if given.
     */
    fun transcribe(pcm: FloatArray, expected: String = "", threads: Int = THREADS): String? {
        check(ctx != 0L) { "closed" }
        val started = System.currentTimeMillis()
        val bias = expected.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        val text = nativeTranscribe(ctx, pcm, threads, audioContext(pcm.size), bias)?.toString(Charsets.UTF_8)?.trim()
        Diag.log("whisper.text", "seconds" to pcm.size / SAMPLE_RATE.toFloat(), "ms" to System.currentTimeMillis() - started, "threads" to threads, "text" to text)
        return text
    }

    override fun close() {
        if (ctx != 0L) {
            nativeFree(ctx)
            ctx = 0
        }
    }

    companion object {
        private const val TAG = "Whisper"
        const val SAMPLE_RATE = 16_000

        /** Big cores do the work; more threads than that only contend. */
        val THREADS: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8).let { (it / 2).coerceAtLeast(2) }

        /** False where the native library isn't there (not a 64-bit ARM phone, or tests). */
        val available: Boolean by lazy {
            runCatching { System.loadLibrary("hfdwhisper") }
                .onFailure { Log.w(TAG, "whisper.cpp unavailable", it); Diag.error("whisper.library", it) }
                .onSuccess { Diag.log("whisper.library", "ok" to true) }
                .isSuccess
        }

        fun load(path: String): Whisper? {
            if (!available) return null
            val started = System.currentTimeMillis()
            val ctx = nativeInit(path)
            Diag.log("whisper.load", "ok" to (ctx != 0L), "ms" to System.currentTimeMillis() - started, "bytes" to java.io.File(path).length())
            return if (ctx == 0L) null else Whisper(ctx)
        }

        /**
         * The encoder's audio context: always the full 30 s window (0). A shorter one would be
         * faster, but Tarteel's model was trained on full windows and falls apart with it
         * (word error 13 % → 76 % on EveryAyah clips, tools/model/evaluate.py).
         */
        @Suppress("UNUSED_PARAMETER")
        fun audioContext(samples: Int): Int = 0

        @JvmStatic private external fun nativeInit(path: String): Long
        @JvmStatic private external fun nativeFree(ctx: Long)
        @JvmStatic private external fun nativeTranscribe(ctx: Long, samples: FloatArray, threads: Int, audioCtx: Int, expected: ByteArray?): ByteArray?
    }
}
