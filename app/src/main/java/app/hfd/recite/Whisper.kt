package app.hfd.recite

import android.util.Log

/**
 * whisper.cpp through JNI (app/src/main/cpp): speech recognition on the phone. One instance per
 * loaded model; use it from one thread at a time.
 */
class Whisper private constructor(private var ctx: Long) : AutoCloseable {

    /** What was heard in [pcm] (16 kHz mono, −1…1), or null if recognition failed. */
    fun transcribe(pcm: FloatArray, threads: Int = THREADS): String? {
        check(ctx != 0L) { "closed" }
        return nativeTranscribe(ctx, pcm, threads, audioContext(pcm.size))?.toString(Charsets.UTF_8)?.trim()
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
                .onFailure { Log.w(TAG, "whisper.cpp unavailable", it) }
                .isSuccess
        }

        fun load(path: String): Whisper? {
            if (!available) return null
            val ctx = nativeInit(path)
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
        @JvmStatic private external fun nativeTranscribe(ctx: Long, samples: FloatArray, threads: Int, audioCtx: Int): ByteArray?
    }
}
