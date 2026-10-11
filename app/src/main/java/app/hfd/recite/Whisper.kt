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
    fun transcribe(pcm: FloatArray, expected: String = "", threads: Int = THREADS, onNpu: Boolean = npu): String? {
        check(ctx != 0L) { "closed" }
        // Measured on the phone: every other reading on the big cores only (a thread each), the
        // others where the system puts them (whisper.text: pinned, ms).
        val pinned = BIG_CORES != 0L && readings++ % 2 == 0
        val n = if (pinned) java.lang.Long.bitCount(BIG_CORES).coerceAtMost(8) else threads
        val pin = nativePin(if (pinned) BIG_CORES else -1L)
        val started = System.currentTimeMillis()
        val bias = expected.takeIf { it.isNotEmpty() }?.toByteArray(Charsets.UTF_8)
        val text = nativeTranscribe(ctx, pcm, n, audioContext(pcm.size), bias, onNpu)?.toString(Charsets.UTF_8)?.trim()
        // Whether the encoder ran on the NPU, its time there, and its error if it failed (then the CPU read it).
        val last = if (onNpu) nativeNpuLast(ctx) else null
        // The text is in recite.partial / recite.heard (and whisper.selftest).
        Diag.log(
            "whisper.text", "seconds" to pcm.size / SAMPLE_RATE.toFloat(), "ms" to System.currentTimeMillis() - started,
            "threads" to n, "pinned" to pinned, "pinError" to pin.takeIf { it != 0 },
            "npu" to (last?.get(0) == 1L), "npuMs" to last?.let { it[1] / 1000f }, "npuError" to last?.get(2)?.takeIf { it != 0L },
        )
        return text
    }

    /** Readings run their encoder on the NPU (once [setUpNpu] worked and its reading checked out). */
    @Volatile var npu = false

    /**
     * The encoder on the NPU: QNN with Tarteel's encoder compiled for this phone's Hexagon
     * ([NpuEncoder]), as a second state of the model (the CPU's stays). Null if it is set up, else why.
     */
    fun setUpNpu(binary: java.io.File): String? {
        check(ctx != 0L) { "closed" }
        val started = System.currentTimeMillis()
        val error = runCatching { nativeNpu(ctx, binary.path, libraryDir.orEmpty()) }.getOrElse { it.toString() }
        Diag.log(
            "npu.init", "ok" to (error == null), "error" to error, "ms" to System.currentTimeMillis() - started,
            "info" to runCatching { nativeNpuInfo(ctx) }.getOrNull(),
        )
        return error
    }

    /**
     * The NPU's encoder output from its last reading against the CPU's from its own (read the same
     * audio on both first): cosine, sizes, NaN / infinite values. Null if either is missing.
     */
    fun npuCompare(): String? = if (ctx == 0L) null else runCatching { nativeNpuCompare(ctx) }.getOrNull()

    private var readings = 0

    override fun close() {
        if (ctx != 0L) {
            nativeFree(ctx)
            ctx = 0
        }
    }

    companion object {
        private const val TAG = "Whisper"
        const val SAMPLE_RATE = 16_000

        /** The app's native library folder (QNN's, read by the NPU's DSP too); set at start. */
        @Volatile var libraryDir: String? = null

        /** Big cores do the work; more threads than that only contend. */
        val THREADS: Int = Runtime.getRuntime().availableProcessors().coerceIn(2, 8).let { (it / 2).coerceAtLeast(2) }

        /**
         * The fast cores, as a mask (bit i: core i): those within 80 % of the top speed (a
         * Snapdragon 7 Gen 4's five Cortex-A720s, not its three A520s). 0 if unknown or all alike.
         */
        private val BIG_CORES: Long by lazy {
            val khz = (0 until 32).map { cpu ->
                runCatching { java.io.File("/sys/devices/system/cpu/cpu$cpu/cpufreq/cpuinfo_max_freq").readText().trim().toLong() }.getOrNull()
            }
            val top = khz.filterNotNull().maxOrNull() ?: return@lazy 0L
            var mask = 0L
            khz.forEachIndexed { cpu, k -> if (k != null && k >= top * 0.8) mask = mask or (1L shl cpu) }
            if (java.lang.Long.bitCount(mask) == khz.count { it != null }) 0L else mask
        }

        private fun cpuFeatures(): Set<String> = runCatching {
            java.io.File("/proc/cpuinfo").readLines().firstOrNull { it.startsWith("Features") }
                ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.toSet()
        }.getOrNull().orEmpty()

        /**
         * False where the native library isn't there (not a 64-bit ARM phone, or tests), or where
         * none of ggml's CPU builds loads: it loads the best one this phone's cores run (any 64-bit
         * ARM; whisper_jni.c, CMakeLists.txt).
         */
        val available: Boolean by lazy {
            Diag.log("whisper.cpu", "features" to cpuFeatures().sorted().joinToString(" "))
            runCatching {
                System.loadLibrary("hfdwhisper")
                checkNotNull(nativeBackends()) { "no CPU build of ggml loaded" }
            }
                .onFailure { Log.w(TAG, "whisper.cpp unavailable", it); Diag.error("whisper.library", it) }
                .onSuccess { Diag.log("whisper.library", "ok" to true, "system" to it) }
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

        @JvmStatic private external fun nativeBackends(): String?
        @JvmStatic private external fun nativePin(mask: Long): Int
        @JvmStatic private external fun nativeInit(path: String): Long
        @JvmStatic private external fun nativeFree(ctx: Long)
        @JvmStatic private external fun nativeTranscribe(ctx: Long, samples: FloatArray, threads: Int, audioCtx: Int, expected: ByteArray?, npu: Boolean): ByteArray?
        @JvmStatic private external fun nativeNpu(ctx: Long, binary: String, libDir: String): String?
        @JvmStatic private external fun nativeNpuLast(ctx: Long): LongArray
        @JvmStatic private external fun nativeNpuInfo(ctx: Long): String?
        @JvmStatic private external fun nativeNpuCompare(ctx: Long): String?
    }
}
