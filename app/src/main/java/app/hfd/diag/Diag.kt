package app.hfd.diag

import android.os.Build
import android.util.Log
import app.hfd.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.UUID

/**
 * Diagnostics for the developer, who has no phone to test on (the owner, the app's only user,
 * asked for them): events as JSON lines, posted every ten seconds to a ntfy.sh topic and read
 * back through .github/workflows/diag.yml. What the Recite mode hears and does, the model and
 * native library, playback and download errors, crashes. Never audio, location or progress.
 * Settings → About → Send diagnostics.
 */
object Diag {
    const val URL = "https://ntfy.sh/hfd-diag-6641caac476110c0b784"
    private const val TAG = "Diag"
    /** ntfy.sh keeps messages up to 4 KB as text. */
    private const val MAX_BYTES = 3_500

    /** This run of the app, to tell runs apart in the log. */
    val session: String = UUID.randomUUID().toString().take(6)
    private val lines = ArrayList<String>()
    @Volatile private var http: OkHttpClient? = null
    @Volatile private var enabled: () -> Boolean = { false }

    fun start(client: OkHttpClient, scope: CoroutineScope, isEnabled: () -> Boolean) {
        http = client
        enabled = isEnabled
        log(
            "app.start",
            "version" to BuildConfig.VERSION_NAME, "code" to BuildConfig.VERSION_CODE,
            "device" to "${Build.MANUFACTURER} ${Build.MODEL}", "android" to Build.VERSION.SDK_INT,
            "abi" to Build.SUPPORTED_ABIS.joinToString(","), "cores" to Runtime.getRuntime().availableProcessors(),
        )
        scope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(10_000) // ntfy.sh allows a request every few seconds
                flush()
            }
        }
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching {
                error("crash", e, "thread" to thread.name)
                // Not on the crashing thread (no network on the main thread): a second at most.
                Thread { flush() }.apply { start(); join(2_000) }
            }
            previous?.uncaughtException(thread, e)
        }
    }

    fun log(event: String, vararg fields: Pair<String, Any?>) {
        Log.d(TAG, event + " " + fields.joinToString(" ") { "${it.first}=${it.second}" })
        if (!enabled()) return
        val line = buildJsonObject {
            put("t", System.currentTimeMillis())
            put("s", session)
            put("e", event)
            for ((k, v) in fields) when (v) {
                null -> put(k, JsonNull)
                is Number -> put(k, v)
                is Boolean -> put(k, v)
                else -> put(k, v.toString().take(600))
            }
        }.toString()
        synchronized(lines) {
            lines += line
            if (lines.size > 400) lines.removeAt(0)
        }
    }

    fun error(event: String, e: Throwable, vararg fields: Pair<String, Any?>) = log(
        event, *fields,
        "error" to e.toString(),
        "at" to e.stackTrace.take(8).joinToString(" < ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" },
    )

    /** Posts what's waiting (blocking; call off the main thread). */
    fun flush() {
        val client = http ?: return
        val batch = synchronized(lines) { lines.toList().also { lines.clear() } }
        if (batch.isEmpty()) return
        var chunk = StringBuilder()
        val chunks = mutableListOf<String>()
        for (line in batch) {
            if (chunk.isNotEmpty() && chunk.length + line.length + 1 > MAX_BYTES) {
                chunks += chunk.toString()
                chunk = StringBuilder()
            }
            chunk.append(line).append('\n')
        }
        if (chunk.isNotEmpty()) chunks += chunk.toString()
        for ((i, body) in chunks.withIndex()) {
            val ok = runCatching {
                client.newCall(Request.Builder().url(URL).post(body.toRequestBody()).build()).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (!ok) {
                // Offline: keep them for the next try (the newest first to go if it lasts).
                val rest = chunks.drop(i).flatMap { it.trimEnd().split('\n') }
                synchronized(lines) {
                    lines.addAll(0, rest)
                    while (lines.size > 400) lines.removeAt(0)
                }
                return
            }
        }
    }
}
