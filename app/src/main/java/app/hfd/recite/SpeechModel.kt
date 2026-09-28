package app.hfd.recite

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * Tarteel's Qur'an-tuned Whisper (huggingface.co/tarteel-ai, Apache-2.0), converted to
 * whisper.cpp's format by .github/workflows/model.yml and published on the "speech-model"
 * pre-release. Checked against [sha256] after download.
 */
data class SpeechModel(val file: String, val sha256: String, val bytes: Long) {
    val url: String get() = "https://github.com/kream0/hfd/releases/download/speech-model/$file"

    companion object {
        val DEFAULT = SpeechModel(
            file = "ggml-tiny-ar-quran-q8_0.bin",
            sha256 = "ef01ab441b004f9e6f1ea98d397b43452b3a45efab7c3928ab37af655dde8b52",
            bytes = 43_500_000,
        )
    }
}

sealed interface ModelState {
    data object Missing : ModelState
    data class Downloading(val progress: Float) : ModelState
    data class Ready(val file: File) : ModelState
    data class Failed(val message: String) : ModelState
}

/** The speech model on the phone: downloaded once (outside the backup), then used offline. */
class ModelStore(context: Context, private val http: OkHttpClient, private val scope: CoroutineScope) {
    val model = SpeechModel.DEFAULT
    // noBackupFilesDir: tens of MB that Android's backup shouldn't carry.
    private val dir = File(context.noBackupFilesDir, "models")
    private val target = File(dir, model.file)
    private val _state = MutableStateFlow<ModelState>(if (target.isFile) ModelState.Ready(target) else ModelState.Missing)
    val state: StateFlow<ModelState> = _state.asStateFlow()
    private var job: Job? = null

    fun download() {
        if (job?.isActive == true || _state.value is ModelState.Ready) return
        job = scope.launch { fetch() }
    }

    fun delete() {
        job?.cancel()
        dir.deleteRecursively()
        _state.value = ModelState.Missing
    }

    private suspend fun fetch() {
        _state.value = ModelState.Downloading(0f)
        try {
            withContext(Dispatchers.IO) {
                dir.mkdirs()
                // Older model files (another size or version) go.
                dir.listFiles()?.filter { it.name != target.name }?.forEach { it.delete() }
                val part = File(dir, target.name + ".part")
                http.newCall(Request.Builder().url(model.url).build()).execute().use { response ->
                    if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
                    val body = response.body ?: throw IOException("Empty download")
                    val total = body.contentLength().takeIf { it > 0 } ?: model.bytes
                    val digest = MessageDigest.getInstance("SHA-256")
                    part.outputStream().use { out ->
                        body.byteStream().use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            var lastEmit = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                digest.update(buffer, 0, n)
                                done += n
                                val now = System.currentTimeMillis()
                                if (now - lastEmit > 250) {
                                    lastEmit = now
                                    _state.value = ModelState.Downloading((done.toFloat() / total).coerceIn(0f, 1f))
                                }
                            }
                        }
                    }
                    val sha = digest.digest().joinToString("") { "%02x".format(it) }
                    if (!sha.equals(model.sha256, ignoreCase = true)) {
                        part.delete()
                        throw IOException("Checksum mismatch")
                    }
                    if (!part.renameTo(target)) throw IOException("Couldn't save the model")
                }
            }
            _state.value = ModelState.Ready(target)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("ModelStore", "model download failed", e)
            _state.value = ModelState.Failed(e.message ?: "Download failed")
        }
    }
}
