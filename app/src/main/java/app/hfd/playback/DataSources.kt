@file:OptIn(UnstableApi::class)

package app.hfd.playback

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import app.hfd.core.playback.EveryAyah
import app.hfd.core.playback.Reciters
import app.hfd.download.AudioStore
import java.io.File
import java.io.IOException
import kotlin.math.min

/** Process-wide cache for streamed āyāt (SimpleCache must be a singleton per folder). */
object StreamCache {
    private const val MAX_BYTES = 256L * 1024 * 1024

    @Volatile
    private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache = cache ?: synchronized(this) {
        cache ?: SimpleCache(
            File(context.cacheDir, "stream-cache"),
            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
            StandaloneDatabaseProvider(context.applicationContext),
        ).also { cache = it }
    }
}

/**
 * `hfd://ayah/<everyayah folder>/<SSSAAA>.mp3` → the file on the phone if it's there, otherwise
 * the everyayah.com URL, or for a whole-sūra recitation the āya's bytes of the sūra file
 * (streamed through the cache, keyed so replays are instant).
 */
class AyahResolver(private val store: AudioStore, private val timings: TimingsRepo) : ResolvingDataSource.Resolver {
    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        val uri = dataSpec.uri
        if (uri.scheme != Items.SCHEME || uri.host != Items.HOST_AYAH) return dataSpec
        val segments = uri.pathSegments
        if (segments.size != 2) throw IOException("Malformed āya uri: $uri")
        val key = "${segments[0]}/${segments[1]}"
        if (store.has(key)) return dataSpec.withUri(Uri.fromFile(File(store.root, key)))
        val t = Reciters.byFolder(segments[0])?.let { timings.of(it) }
        if (t != null) {
            // The āya is a stretch of its sūra's file: positions asked for are within that stretch.
            val ref = EveryAyah.refOf(segments[1]) ?: throw IOException("Malformed āya uri: $uri")
            val bytes = t.bytes(ref) ?: throw IOException("No timing for $ref")
            val url = t.url(ref) ?: throw IOException("No file for $ref")
            val size = bytes.last - bytes.first + 1
            if (dataSpec.position >= size) throw IOException("Position past the āya")
            val length = if (dataSpec.length == C.LENGTH_UNSET.toLong()) size - dataSpec.position else min(dataSpec.length, size - dataSpec.position)
            return dataSpec.buildUpon()
                .setUri(Uri.parse(url))
                .setPosition(bytes.first + dataSpec.position)
                .setLength(length)
                // With the range: an update that re-times the āya doesn't replay the old cut.
                .setKey("rg:$key@${bytes.first}-${bytes.last}")
                .build()
        }
        return dataSpec.buildUpon()
            .setUri(Uri.parse(EveryAyah.BASE + key))
            .setKey("ea:$key")
            .build()
    }
}

/**
 * `hfd://silence/<ms>`: a WAV file of silence generated on the fly, so a gap is an ordinary
 * item in the playlist (with its own title, and seekable like any other).
 */
class SilenceDataSource : BaseDataSource(/* isNetwork = */ false) {
    private var uri: Uri? = null
    private var header = ByteArray(0)
    private var position = 0L
    private var remaining = 0L

    override fun open(dataSpec: DataSpec): Long {
        transferInitializing(dataSpec)
        val ms = dataSpec.uri.lastPathSegment?.toLongOrNull() ?: throw IOException("Malformed silence uri")
        val dataBytes = ms * SAMPLE_RATE / 1000 * BYTES_PER_SAMPLE
        header = wavHeader(dataBytes)
        val total = header.size + dataBytes
        position = dataSpec.position
        if (position > total) throw IOException("Position past end")
        remaining = if (dataSpec.length != C.LENGTH_UNSET.toLong()) min(dataSpec.length, total - position) else total - position
        uri = dataSpec.uri
        transferStarted(dataSpec)
        return remaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining <= 0) return C.RESULT_END_OF_INPUT
        val n = min(length.toLong(), remaining).toInt()
        for (i in 0 until n) {
            val p = position + i
            buffer[offset + i] = if (p < header.size) header[p.toInt()] else 0
        }
        position += n
        remaining -= n
        bytesTransferred(n)
        return n
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        if (uri != null) {
            uri = null
            transferEnded()
        }
    }

    companion object {
        const val SAMPLE_RATE = 22_050L
        const val BYTES_PER_SAMPLE = 2L // 16-bit mono

        fun wavHeader(dataBytes: Long): ByteArray {
            val b = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN)
            b.put("RIFF".toByteArray()).putInt((36 + dataBytes).toInt()).put("WAVE".toByteArray())
            b.put("fmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
                .putInt(SAMPLE_RATE.toInt()).putInt((SAMPLE_RATE * BYTES_PER_SAMPLE).toInt())
                .putShort(BYTES_PER_SAMPLE.toInt().toShort()).putShort(16)
            b.put("data".toByteArray()).putInt(dataBytes.toInt())
            return b.array()
        }
    }

    class Factory : DataSource.Factory {
        override fun createDataSource(): DataSource = SilenceDataSource()
    }
}

/** Files to disk, silence to [SilenceDataSource], everything else through the cached network stack. */
class RoutingDataSource(
    private val local: DataSource,
    private val silence: DataSource,
    private val remote: DataSource,
) : DataSource {
    private var active: DataSource? = null

    override fun addTransferListener(transferListener: TransferListener) {
        local.addTransferListener(transferListener)
        silence.addTransferListener(transferListener)
        remote.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        val uri = dataSpec.uri
        val source = when {
            uri.scheme == Items.SCHEME && uri.host == Items.HOST_SILENCE -> silence
            uri.scheme == "file" || uri.scheme == "content" || uri.scheme == "asset" -> local
            else -> remote
        }
        active = source
        return source.open(dataSpec)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
        active?.read(buffer, offset, length) ?: throw IOException("Data source not opened")

    override fun getUri(): Uri? = active?.uri

    override fun getResponseHeaders(): Map<String, List<String>> = active?.responseHeaders ?: emptyMap()

    override fun close() {
        try {
            active?.close()
        } finally {
            active = null
        }
    }

    class Factory(
        private val local: DataSource.Factory,
        private val silence: DataSource.Factory,
        private val remote: DataSource.Factory,
    ) : DataSource.Factory {
        override fun createDataSource(): DataSource =
            RoutingDataSource(local.createDataSource(), silence.createDataSource(), remote.createDataSource())
    }
}
