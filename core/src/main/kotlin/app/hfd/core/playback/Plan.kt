package app.hfd.core.playback

import app.hfd.core.quran.AyahRef
import kotlinx.serialization.Serializable

/** Silence after each āya, as a multiple of that āya's length (time to repeat it aloud). */
@Serializable
enum class GapMode(val factor: Float) {
    NONE(0f),
    HALF(0.5f),
    ONE(1f),
    ONE_HALF(1.5f),
}

/**
 * What to play: [ayat] in order (a faḍīla or a sub-range of it), each repeated [repeatEach]
 * times ([INFINITE] = until skipped), the whole list [repeatRange] times, with a gap after
 * every recitation and, optionally, the basmala before āya 1 of a sūra other than 1 and 9.
 */
@Serializable
data class PlanSpec(
    val ayat: List<AyahRef>,
    val repeatEach: Int = 1,
    val repeatRange: Int = 1,
    val gap: GapMode = GapMode.NONE,
    val basmala: Boolean = true,
) {
    init {
        require(ayat.isNotEmpty()) { "Nothing to play" }
        require(repeatEach == INFINITE || repeatEach >= 1) { "repeatEach $repeatEach" }
        require(repeatRange == INFINITE || repeatRange >= 1) { "repeatRange $repeatRange" }
    }

    fun hasBasmala(index: Int): Boolean {
        val ref = ayat[index]
        return basmala && ref.aya == 1 && ref.sura != 1 && ref.sura != 9
    }

    companion object {
        const val INFINITE = -1
    }
}

/** Where a plan item sits: pass over the range (1-based), āya index in [PlanSpec.ayat], repetition (1-based). */
@Serializable
data class Cursor(val pass: Int = 1, val index: Int = 0, val rep: Int = 1)

sealed interface PlanItem {
    val cursor: Cursor
    val ref: AyahRef

    /** The basmala recited before āya 1 of a sūra (played once per pass). */
    data class Basmala(override val cursor: Cursor, override val ref: AyahRef) : PlanItem

    /** One recitation of [ref]: repetition [rep] of [of] ([PlanSpec.INFINITE] for ∞). */
    data class Ayah(override val cursor: Cursor, override val ref: AyahRef, val of: Int) : PlanItem {
        val rep: Int get() = cursor.rep
    }

    /** Silence after a recitation of [ref], [factor] × its length. */
    data class Gap(override val cursor: Cursor, override val ref: AyahRef, val factor: Float) : PlanItem

    /** Stable id: "sūra:āya:repetition#pass", with a suffix for gaps and the basmala. */
    val mediaId: String
        get() = when (this) {
            is Ayah -> "${ref.sura}:${ref.aya}:${cursor.rep}#${cursor.pass}"
            is Gap -> "${ref.sura}:${ref.aya}:${cursor.rep}#${cursor.pass}/gap"
            is Basmala -> "${ref.sura}:${ref.aya}:0#${cursor.pass}/basmala"
        }
}

/**
 * Builds the flat list of items the player plays, e.g. basmala, āya ×1, gap, āya ×2, gap, next
 * āya… Plans can be endless (∞ repeats), so they're produced lazily from a [Cursor] and the
 * player is fed a window at a time.
 */
object PlaybackPlan {

    /** Every item from [from] on (the basmala first when [from] starts an āya that has one). */
    fun items(spec: PlanSpec, from: Cursor = Cursor()): Sequence<PlanItem> = sequence {
        var pass = from.pass
        var index = from.index.coerceIn(0, spec.ayat.lastIndex)
        var rep = from.rep.coerceAtLeast(1)
        while (spec.repeatRange == PlanSpec.INFINITE || pass <= spec.repeatRange) {
            while (index < spec.ayat.size) {
                val ref = spec.ayat[index]
                if (rep == 1 && spec.hasBasmala(index)) yield(PlanItem.Basmala(Cursor(pass, index, 1), ref))
                while (spec.repeatEach == PlanSpec.INFINITE || rep <= spec.repeatEach) {
                    val c = Cursor(pass, index, rep)
                    yield(PlanItem.Ayah(c, ref, spec.repeatEach))
                    if (spec.gap != GapMode.NONE) yield(PlanItem.Gap(c, ref, spec.gap.factor))
                    rep++
                }
                index++
                rep = 1
            }
            pass++
            index = 0
        }
    }

    /** The items that follow [item] (to extend a window that's running out). */
    fun after(spec: PlanSpec, item: PlanItem): Sequence<PlanItem> {
        // [item] is among the first items generated from its own cursor (basmala, āya, gap).
        val head = items(spec, item.cursor).take(3).toList()
        val i = head.indexOfFirst { it.mediaId == item.mediaId }
        return items(spec, item.cursor).drop(if (i < 0) 0 else i + 1)
    }

    /** Cursor of the next āya (first repetition); null at the very end of a finite plan. */
    fun nextAyah(spec: PlanSpec, at: Cursor): Cursor? = when {
        at.index < spec.ayat.lastIndex -> Cursor(at.pass, at.index + 1, 1)
        spec.repeatRange == PlanSpec.INFINITE || at.pass < spec.repeatRange -> Cursor(at.pass + 1, 0, 1)
        else -> null
    }

    /** Cursor of the previous āya (first repetition); the first āya restarts itself. */
    fun previousAyah(spec: PlanSpec, at: Cursor): Cursor = when {
        at.index > 0 -> Cursor(at.pass, at.index - 1, 1)
        at.pass > 1 -> Cursor(at.pass - 1, spec.ayat.lastIndex, 1)
        else -> Cursor(at.pass, 0, 1)
    }

    /** Parses [PlanItem.mediaId] back into an item of [spec]; null if it doesn't belong to it. */
    fun decode(spec: PlanSpec, mediaId: String): PlanItem? {
        val parsed = MediaIds.parse(mediaId) ?: return null
        val index = spec.ayat.indexOf(parsed.ref)
        if (index < 0) return null
        val c = Cursor(parsed.pass, index, if (parsed.kind == MediaIds.Kind.BASMALA) 1 else parsed.rep)
        return when (parsed.kind) {
            MediaIds.Kind.AYAH -> PlanItem.Ayah(c, parsed.ref, spec.repeatEach)
            MediaIds.Kind.GAP -> PlanItem.Gap(c, parsed.ref, spec.gap.factor)
            MediaIds.Kind.BASMALA -> PlanItem.Basmala(c, parsed.ref)
        }
    }
}

/** mediaId parsing without a spec (for UI that only needs the āya and repetition). */
object MediaIds {
    enum class Kind { AYAH, GAP, BASMALA }
    data class Parsed(val ref: AyahRef, val rep: Int, val pass: Int, val kind: Kind)

    fun parse(mediaId: String): Parsed? {
        val main = mediaId.substringBefore('/')
        val kind = when (mediaId.substringAfter('/', "")) {
            "" -> Kind.AYAH
            "gap" -> Kind.GAP
            "basmala" -> Kind.BASMALA
            else -> return null
        }
        val parts = main.substringBefore('#').split(':')
        if (parts.size != 3) return null
        val sura = parts[0].toIntOrNull() ?: return null
        val aya = parts[1].toIntOrNull() ?: return null
        val rep = parts[2].toIntOrNull() ?: return null
        val pass = main.substringAfter('#', "").toIntOrNull() ?: return null
        if (sura !in 1..114 || aya < 1) return null
        return Parsed(AyahRef(sura, aya), rep, pass, kind)
    }
}
