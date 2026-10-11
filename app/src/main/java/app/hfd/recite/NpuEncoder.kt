package app.hfd.recite

import android.content.Context
import android.os.Build
import app.hfd.diag.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Tarteel's tiny encoder compiled for a Snapdragon's NPU (its Hexagon) by .github/workflows/npu.yml,
 * on the "speech-model" pre-release: Recite's encoder runs there instead of on the CPU (npu.c), on
 * a phone whose Hexagon it was compiled for. Downloaded once, checked against its SHA-256.
 */
class NpuEncoder(context: Context, private val http: OkHttpClient) {
    data class Compiled(val file: String, val sha256: String) {
        val url: String get() = "https://github.com/kream0/hfd/releases/download/speech-model/$file"
    }

    /** The build for this phone's Hexagon, if there is one. */
    val compiled: Compiled? = if (Build.VERSION.SDK_INT >= 31) BUILDS[Build.SOC_MODEL] else null

    private val dir = File(context.noBackupFilesDir, "npu")
    private val lock = Mutex()

    /** The compiled encoder on the phone, if downloaded. */
    val file: File? get() = compiled?.let { File(dir, it.file) }?.takeIf { it.isFile }

    /**
     * Before setting it up: false if setting it up stopped the app last time with this version
     * (a crash in QNN's or the DSP's code can't be caught): then the CPU reads, until an update.
     */
    fun trying(version: Int): Boolean {
        val mark = File(dir, "trying")
        if (mark.isFile && mark.readText().trim() == version.toString()) {
            Diag.log("npu.skipped", "reason" to "stopped the app before", "version" to version)
            return false
        }
        runCatching { dir.mkdirs(); mark.writeText(version.toString()) }
        return true
    }

    /** It was set up (or failed) without stopping the app. */
    fun tried() {
        File(dir, "trying").delete()
    }

    /** Downloads it if this phone has a build and it isn't there yet. */
    suspend fun fetch(): File? = lock.withLock {
        val c = compiled ?: return null
        file?.let { return it }
        val target = File(dir, c.file)
        val started = System.currentTimeMillis()
        runCatching {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                // Builds for another QNN version go.
                dir.listFiles()?.filter { it.name != target.name && it.name != "trying" }?.forEach { it.delete() }
                val part = File(dir, target.name + ".part")
                http.newCall(Request.Builder().url(c.url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val digest = MessageDigest.getInstance("SHA-256")
                    part.outputStream().use { out ->
                        response.body!!.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                            }
                        }
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!sha.equals(c.sha256, ignoreCase = true)) {
                        part.delete()
                        throw IOException("checksum $sha")
                    }
                    if (!part.renameTo(target)) throw IOException("couldn't save it")
                }
            }
        }.onSuccess {
            Diag.log("npu.download", "file" to c.file, "bytes" to target.length(), "ms" to System.currentTimeMillis() - started)
        }.onFailure {
            Diag.error("npu.download", it, "file" to c.file)
        }
        file
    }

    companion object {
        /**
         * By SoC (Build.SOC_MODEL), the build for its Hexagon: the Snapdragon 7 Gen 4 (SM7750, the
         * owner's phone) has a v73 (Qualcomm's SDK). QNN 2.42, as the app's runtime (qnn-runtime).
         */
        private val BUILDS = mapOf(
            "SM7750" to Compiled("encoder-tiny-ar-quran-qnn242-v73.bin", "24eefdd069d3fbb94fd2f1312d6cf4ad290158c4c25bef0d6e62ac3eca64625b"),
        )
    }
}
