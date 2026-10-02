package app.hfd.playback

import android.content.Context
import android.util.Log
import app.hfd.core.HfdJson
import app.hfd.core.playback.AyahTimings
import app.hfd.core.playback.Reciter
import app.hfd.core.playback.WordTimings
import java.util.concurrent.ConcurrentHashMap

/** Āya byte ranges of the whole-sūra recitations ([Reciter.timings]), read from the assets once. */
class TimingsRepo(private val context: Context) {
    private val loaded = ConcurrentHashMap<String, AyahTimings>()

    fun of(reciter: Reciter): AyahTimings? {
        val asset = reciter.timings ?: return null
        loaded[asset]?.let { return it }
        return runCatching {
            context.assets.open(asset).use { HfdJson.decodeFromString(AyahTimings.serializer(), it.readBytes().decodeToString()) }
        }.onFailure { Log.w("Timings", "Could not read $asset", it) }
            .getOrNull()
            ?.also { loaded[asset] = it }
    }
}

/** When each word starts in each reciter's recordings (tools/words/words.py), read from the assets once. */
class WordsRepo(private val context: Context) {
    private val loaded = ConcurrentHashMap<String, WordTimings>()
    private val missing = ConcurrentHashMap.newKeySet<String>()

    fun of(reciter: Reciter): WordTimings? {
        loaded[reciter.id]?.let { return it }
        if (reciter.id in missing) return null
        val asset = "audio/words/${reciter.id}.json"
        return runCatching {
            context.assets.open(asset).use { HfdJson.decodeFromString(WordTimings.serializer(), it.readBytes().decodeToString()) }
        }.onFailure {
            missing += reciter.id
            Log.w("Timings", "Could not read $asset", it)
        }.getOrNull()
            ?.also { loaded[reciter.id] = it }
    }
}
