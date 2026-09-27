package app.hfd.core.prayer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * Compares with adhan-js 4 (tools/prayer_reference.js): 6 cities from Makkah to Oslo, 5 methods,
 * solstices and equinoxes. adhan rounds to the minute and uses slightly different ephemerides:
 * everything agrees within 2 minutes except Asr in Oslo in January (2½ min, the sun barely
 * clears the horizon). Reminders fire 15 minutes after the prayer anyway.
 */
class PrayerTimesTest {
    private val rows = Json.parseToJsonElement(javaClass.getResource("/prayer_reference.json")!!.readText()) as JsonArray

    private fun method(key: String) = PrayerMethod.entries.first { m ->
        when (key) {
            "mwl" -> m == PrayerMethod.MWL
            "isna" -> m == PrayerMethod.ISNA
            "egypt" -> m == PrayerMethod.EGYPT
            "makkah" -> m == PrayerMethod.UMM_AL_QURA
            "uoif" -> m == PrayerMethod.UOIF
            else -> false
        }
    }

    @Test
    fun agreesWithAdhanWithinThreeMinutes() {
        var worst = 0L
        val failures = mutableListOf<String>()
        for (row in rows) {
            val r = row.jsonObject
            fun s(k: String) = r[k]!!.jsonPrimitive.content
            val calc = PrayerCalculator(r["lat"]!!.jsonPrimitive.double, r["lng"]!!.jsonPrimitive.double, method(s("method")))
            val day = calc.day(LocalDate.parse(s("date")), ZoneId.of(s("tz")))
            val expected = mapOf(
                Prayer.FAJR to s("fajr"), Prayer.DHUHR to s("dhuhr"), Prayer.ASR to s("asr"),
                Prayer.MAGHRIB to s("maghrib"), Prayer.ISHA to s("isha"),
            )
            for ((p, iso) in expected) {
                val diff = Duration.between(Instant.parse(iso), day.times.getValue(p).toInstant()).abs().seconds
                worst = maxOf(worst, diff)
                if (diff > 180) failures += "${s("city")} ${s("method")} ${s("date")} $p: expected $iso, got ${day.times[p]} (${diff}s)"
            }
            val sunriseDiff = Duration.between(Instant.parse(s("sunrise")), day.sunrise.toInstant()).abs().seconds
            if (sunriseDiff > 120) failures += "${s("city")} ${s("date")} sunrise off by ${sunriseDiff}s"
        }
        assertTrue("worst ${worst}s\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun timesAreInOrder() {
        val paris = PrayerCalculator(48.8566, 2.3522, PrayerMethod.UOIF)
        var d = LocalDate.of(2026, 1, 1)
        repeat(365) {
            val t = paris.day(d, ZoneId.of("Europe/Paris")).times
            val order = listOf(Prayer.FAJR, Prayer.DHUHR, Prayer.ASR, Prayer.MAGHRIB, Prayer.ISHA).map { t.getValue(it) }
            assertTrue("$d $order", order.zipWithNext().all { (a, b) -> a.isBefore(b) })
            d = d.plusDays(1)
        }
    }
}
