package app.hfd.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import app.hfd.core.playback.EveryAyah
import app.hfd.core.playback.PlanItem
import app.hfd.core.playback.PlanSpec
import app.hfd.core.playback.Reciter
import app.hfd.core.quran.AyahRef

/**
 * Playlist items carry `hfd://` URIs only: `hfd://ayah/<folder>/<SSSAAA>.mp3` (resolved to the
 * file on the phone or everyayah.com when opened) and `hfd://silence/<ms>` for gaps.
 */
object Items {
    const val SCHEME = "hfd"
    const val HOST_AYAH = "ayah"
    const val HOST_SILENCE = "silence"
    const val EXTRA_FADILA = "hfd.fadila"

    fun ayahUri(reciter: Reciter, ref: AyahRef): Uri =
        Uri.parse("$SCHEME://$HOST_AYAH/${reciter.folder}/${EveryAyah.fileName(ref)}")

    fun silenceUri(ms: Long): Uri = Uri.parse("$SCHEME://$HOST_SILENCE/${ms.coerceAtLeast(100)}")

    fun silenceMs(item: MediaItem): Long? =
        item.localConfiguration?.uri?.takeIf { it.host == HOST_SILENCE }?.lastPathSegment?.toLongOrNull()

    /** "Āyat al-Kursī · 2:255 · 3/5", as the notification and lock screen show it. */
    fun title(fadila: String, item: PlanItem, basmalaLabel: String): String = when (item) {
        is PlanItem.Basmala -> "$fadila · $basmalaLabel"
        is PlanItem.Ayah -> "$fadila · ${item.ref}" + reps(item.rep, item.of)
        is PlanItem.Gap -> "$fadila · ${item.ref}" + reps(item.cursor.rep, null)
    }

    private fun reps(rep: Int, of: Int?): String = when (of) {
        1 -> ""
        PlanSpec.INFINITE -> " · $rep/∞"
        null -> " · $rep"
        else -> " · $rep/$of"
    }

    fun build(
        item: PlanItem,
        spec: PlanSpec,
        fadilaId: String,
        fadilaTitle: String,
        reciter: Reciter,
        basmalaLabel: String,
        gapMs: (PlanItem.Gap) -> Long,
    ): MediaItem {
        val (uri, mime) = when (item) {
            is PlanItem.Ayah -> ayahUri(reciter, item.ref) to MimeTypes.AUDIO_MPEG
            is PlanItem.Basmala -> ayahUri(reciter, EveryAyah.BASMALA) to MimeTypes.AUDIO_MPEG
            is PlanItem.Gap -> silenceUri(gapMs(item)) to MimeTypes.AUDIO_WAV
        }
        val title = when (item) {
            is PlanItem.Gap -> title(fadilaTitle, PlanItem.Ayah(item.cursor, item.ref, spec.repeatEach), basmalaLabel)
            else -> title(fadilaTitle, item, basmalaLabel)
        }
        val metadata = MediaMetadata.Builder()
            .setTitle(title)
            .setArtist(reciter.name)
            .setAlbumTitle(fadilaTitle)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setExtras(Bundle().apply { putString(EXTRA_FADILA, fadilaId) })
            .build()
        return MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(uri)
            .setMimeType(mime)
            .setMediaMetadata(metadata)
            .build()
    }
}
