package app.hfd.core.progress

import app.hfd.core.srs.Rating
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Where a rating came from. */
@Serializable
enum class Mode {
    @SerialName("learn") LEARN,
    @SerialName("review") REVIEW,
    @SerialName("test") TEST,
    /** Recited aloud and checked by speech recognition. */
    @SerialName("recite") RECITE,
}

/**
 * One line of the append-only log (progress/events.jsonl). The log is the source of truth:
 * every card, stat and streak is rebuilt from it. Field names are short to keep the log small.
 */
@Serializable
sealed class Event {
    /** Epoch milliseconds. */
    abstract val at: Long

    /** Listening to āya [k]: [ms] of listening, [n] recitations heard to ≥ 90 %. */
    @Serializable
    @SerialName("listen")
    data class Listen(override val at: Long, val k: String, val ms: Long, val n: Int, val r: String? = null) : Event()

    /** A self-rating after reciting āya [k] from memory; drives FSRS. [ms]: time spent on it. */
    @Serializable
    @SerialName("rate")
    data class Rate(override val at: Long, val k: String, val rating: Rating, val mode: Mode, val ms: Long = 0) : Event()

    /** Reciting several āyāt in a row from memory ([keys] in order): tests the links between them. */
    @Serializable
    @SerialName("chain")
    data class Chain(override val at: Long, val keys: List<String>, val ok: Boolean, val ms: Long = 0) : Event()

    /**
     * Āya [k] recited aloud from memory and checked by speech recognition: [n] words, [miss] the
     * indices of those recited wrongly, skipped or shown as a hint; [rating] follows from them
     * (see Tracker.ratingFor) and drives FSRS like a self-rating.
     */
    @Serializable
    @SerialName("recite")
    data class Recite(
        override val at: Long,
        val k: String,
        val n: Int,
        val miss: List<Int> = emptyList(),
        val rating: Rating,
        val ms: Long = 0,
    ) : Event()

    /** A full test of faḍīla [f] finished: [good] of [total] āyāt recalled. */
    @Serializable
    @SerialName("test")
    data class Test(override val at: Long, val f: String, val total: Int, val good: Int) : Event()
}

val EventJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = false
    classDiscriminator = "t"
}

object EventLog {
    fun encode(e: Event): String = EventJson.encodeToString(Event.serializer(), e)

    /** Null for a line that can't be read (kept in the file, skipped in replay). */
    fun decode(line: String): Event? =
        if (line.isBlank()) null else runCatching { EventJson.decodeFromString(Event.serializer(), line) }.getOrNull()
}
