package app.hfd.core.fadail

import app.hfd.core.quran.SuraMeta

/** Structural checks on the bundled dataset; the unit tests require an empty result. */
object FadailValidator {
    private val ID = Regex("^[a-z0-9]+(-[a-z0-9]+)*$")
    private val TRUSTED_HOSTS = listOf("https://sunnah.com/", "https://dorar.net/")

    fun validate(file: FadailFile, suras: List<SuraMeta>): List<String> {
        val errors = mutableListOf<String>()
        val counts = suras.associate { it.index to it.ayas }
        if (file.schema != FadailFile.SCHEMA) errors += "schema ${file.schema} != ${FadailFile.SCHEMA}"
        val seen = HashSet<String>()
        for (f in file.fadail) {
            val at = "[${f.id}]"
            if (!ID.matches(f.id)) errors += "$at id must be a lowercase slug"
            if (!seen.add(f.id)) errors += "$at duplicate id"
            if (f.title.ar.isNullOrBlank() || f.title.fr.isBlank() || f.title.en.isBlank()) errors += "$at title needs ar, fr and en"
            if (f.virtue.fr.isBlank() || f.virtue.en.isBlank()) errors += "$at virtue needs fr and en"
            if (f.ranges.isEmpty()) errors += "$at no ranges"
            for (r in f.ranges) {
                val max = counts[r.sura]
                when {
                    max == null -> errors += "$at sūra ${r.sura} doesn't exist"
                    r.from < 1 || r.to < r.from -> errors += "$at bad range $r"
                    r.to > max -> errors += "$at range $r goes past the end of sūra ${r.sura} ($max āyāt)"
                }
            }
            val all = f.ayat
            if (all.size != all.toSet().size) errors += "$at ranges overlap"
            if (f.sources.isEmpty()) errors += "$at needs at least one source"
            for (s in f.sources) {
                if (s.collection.isBlank() || s.number.isBlank() || s.narrator.isBlank()) errors += "$at incomplete source $s"
                if (TRUSTED_HOSTS.none { s.url.startsWith(it) }) errors += "$at source url must be on sunnah.com or dorar.net: ${s.url}"
            }
            if (f.grading.by.isBlank()) errors += "$at grading needs a grader"
            if (f.occasions.isEmpty()) errors += "$at needs an occasion"
        }
        return errors
    }
}
