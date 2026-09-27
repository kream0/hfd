package app.hfd.core.quran

import kotlinx.serialization.Serializable

/** One āya, addressed as sūra + āya number (both 1-based). Keyed "sūra:āya" everywhere. */
@Serializable
data class AyahRef(val sura: Int, val aya: Int) : Comparable<AyahRef> {
    val key: String get() = "$sura:$aya"

    override fun compareTo(other: AyahRef): Int =
        if (sura != other.sura) sura.compareTo(other.sura) else aya.compareTo(other.aya)

    override fun toString(): String = key

    companion object {
        /** Parses "2:255"; null for anything else. */
        fun parse(key: String): AyahRef? {
            val i = key.indexOf(':')
            if (i <= 0) return null
            val s = key.substring(0, i).toIntOrNull() ?: return null
            val a = key.substring(i + 1).toIntOrNull() ?: return null
            return if (s in 1..114 && a >= 1) AyahRef(s, a) else null
        }
    }
}
