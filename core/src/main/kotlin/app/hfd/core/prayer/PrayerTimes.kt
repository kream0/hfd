package app.hfd.core.prayer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.tan

/** Twilight angles of the usual calculation methods (Isha may be a fixed delay instead). */
@Serializable
enum class PrayerMethod(val fajrAngle: Double, val ishaAngle: Double?, val ishaMinutes: Int?) {
    @SerialName("mwl") MWL(18.0, 17.0, null),
    @SerialName("isna") ISNA(15.0, 15.0, null),
    @SerialName("egypt") EGYPT(19.5, 17.5, null),
    @SerialName("makkah") UMM_AL_QURA(18.5, null, 90),
    @SerialName("karachi") KARACHI(18.0, 18.0, null),
    /** Union des Organisations Islamiques de France: 12° / 12°. */
    @SerialName("uoif") UOIF(12.0, 12.0, null),
}

enum class Prayer { FAJR, DHUHR, ASR, MAGHRIB, ISHA }

data class PrayerDay(val date: LocalDate, val times: Map<Prayer, ZonedDateTime>, val sunrise: ZonedDateTime)

/**
 * Prayer times from the sun's position (the standard spherical-astronomy formulas, as used by
 * PrayTimes / adhan), for the Shāfiʿī Asr shadow ratio of 1. At high latitudes, when the
 * twilight angle is never reached or would fall too deep in the night, Fajr and Isha use the
 * middle-of-the-night rule. Times come out in [zone], daylight saving included.
 */
class PrayerCalculator(
    private val latitude: Double,
    private val longitude: Double,
    private val method: PrayerMethod = PrayerMethod.MWL,
) {
    fun day(date: LocalDate, zone: ZoneId): PrayerDay {
        val offset = zone.rules.getOffset(date.atTime(12, 0).atZone(zone).toInstant()).totalSeconds / 3600.0
        val jd = julian(date) - longitude / (15 * 24)

        // Two passes: the first from rough guesses, the second from the first results.
        var t = doubleArrayOf(5.0, 6.0, 12.0, 13.0, 18.0, 18.0, 18.0)
        repeat(2) { t = compute(jd, t) }

        val base = -longitude / 15 + offset
        val fajr0 = t[0] + base
        val sunrise = t[1] + base
        val dhuhr = t[2] + base
        val asr = t[3] + base
        val sunset = t[4] + base
        val maghrib = t[5] + base
        var isha = t[6] + base
        if (method.ishaMinutes != null) isha = maghrib + method.ishaMinutes / 60.0

        // Middle of the night: Fajr no earlier than half the night before sunrise, Isha no later than half after sunset.
        val night = fix24(sunrise - sunset)
        val half = night / 2
        val fajr = if (fajr0.isNaN() || fix24(sunrise - fajr0) > half) sunrise - half else fajr0
        if (method.ishaMinutes == null && (isha.isNaN() || fix24(isha - sunset) > half)) isha = sunset + half

        fun at(hours: Double): ZonedDateTime {
            val secs = Math.round(hours * 3600)
            val dayShift = Math.floorDiv(secs, 86_400L)
            val inDay = Math.floorMod(secs, 86_400L)
            return LocalDateTime.of(date.plusDays(dayShift), LocalTime.ofSecondOfDay(inDay)).atZone(zone)
        }
        return PrayerDay(
            date = date,
            times = mapOf(
                Prayer.FAJR to at(fajr),
                Prayer.DHUHR to at(dhuhr),
                Prayer.ASR to at(asr),
                Prayer.MAGHRIB to at(maghrib),
                Prayer.ISHA to at(isha),
            ),
            sunrise = at(sunrise),
        )
    }

    /** Local solar times (hours, before timezone), refined from [guess]. */
    private fun compute(jd: Double, guess: DoubleArray): DoubleArray {
        val g = DoubleArray(7) { guess[it] / 24 }
        return doubleArrayOf(
            sunAngleTime(jd, method.fajrAngle, g[0], ccw = true),
            sunAngleTime(jd, RISE_SET, g[1], ccw = true),
            midDay(jd, g[2]),
            asrTime(jd, 1.0, g[3]),
            sunAngleTime(jd, RISE_SET, g[4], ccw = false),
            sunAngleTime(jd, RISE_SET, g[5], ccw = false),
            method.ishaAngle?.let { sunAngleTime(jd, it, g[6], ccw = false) } ?: Double.NaN,
        )
    }

    private data class Sun(val declination: Double, val equation: Double)

    private fun sun(jd: Double): Sun {
        val d = jd - 2451545.0
        val g = fixAngle(357.529 + 0.98560028 * d)
        val q = fixAngle(280.459 + 0.98564736 * d)
        val l = fixAngle(q + 1.915 * dsin(g) + 0.020 * dsin(2 * g))
        val e = 23.439 - 0.00000036 * d
        val ra = darctan2(dcos(e) * dsin(l), dcos(l)) / 15
        val eqt = q / 15 - fix24(ra)
        val decl = darcsin(dsin(e) * dsin(l))
        return Sun(decl, eqt)
    }

    private fun midDay(jd: Double, time: Double): Double = fix24(12 - sun(jd + time).equation)

    private fun sunAngleTime(jd: Double, angle: Double, time: Double, ccw: Boolean): Double {
        val decl = sun(jd + time).declination
        val noon = midDay(jd, time)
        val cosT = (-dsin(angle) - dsin(decl) * dsin(latitude)) / (dcos(decl) * dcos(latitude))
        if (cosT < -1 || cosT > 1) return Double.NaN
        val t = darccos(cosT) / 15
        return noon + if (ccw) -t else t
    }

    private fun asrTime(jd: Double, factor: Double, time: Double): Double {
        val decl = sun(jd + time).declination
        val angle = -darccot(factor + dtan(abs(latitude - decl)))
        return sunAngleTime(jd, angle, time, ccw = false)
    }

    companion object {
        /** Sun's upper limb at the horizon, with refraction. */
        private const val RISE_SET = 0.833

        fun julian(date: LocalDate): Double {
            var y = date.year
            var m = date.monthValue
            if (m <= 2) {
                y -= 1
                m += 12
            }
            val a = floor(y / 100.0)
            val b = 2 - a + floor(a / 4)
            return floor(365.25 * (y + 4716)) + floor(30.6001 * (m + 1)) + date.dayOfMonth + b - 1524.5
        }

        private fun dsin(d: Double) = sin(Math.toRadians(d))
        private fun dcos(d: Double) = cos(Math.toRadians(d))
        private fun dtan(d: Double) = tan(Math.toRadians(d))
        private fun darcsin(x: Double) = Math.toDegrees(asin(x))
        private fun darccos(x: Double) = Math.toDegrees(acos(x))
        private fun darctan2(y: Double, x: Double) = Math.toDegrees(atan2(y, x))
        private fun darccot(x: Double) = Math.toDegrees(atan(1 / x))
        private fun fixAngle(a: Double) = a - 360 * floor(a / 360)
        private fun fix24(h: Double) = h - 24 * floor(h / 24)
    }
}
