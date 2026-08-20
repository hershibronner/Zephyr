package app.zephyr.fitness.ui

import dev.zephyr.core.model.LB_PER_KG
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Imperial display.
 *
 * Zephyr is an American app, so it speaks pounds, feet and inches, miles and minutes-per-mile.
 * Every formula in `dev.zephyr.core` is metric because that's what Mifflin-St Jeor and the MET
 * tables are defined in, so conversion happens here and only here — at the boundary between the
 * numbers the app reasons about and the numbers it shows. Converting early and carrying imperial
 * values around is how the two quietly drift apart.
 */
object Units {

    const val CM_PER_INCH = 2.54
    const val M_PER_MILE = 1609.344
    const val FT_PER_M = 3.280839895

    fun kgToLb(kg: Double) = kg * LB_PER_KG
    fun lbToKg(lb: Double) = lb / LB_PER_KG
    fun ftInToCm(feet: Int, inches: Int) = (feet * 12 + inches) * CM_PER_INCH
    fun metresToMiles(m: Double) = m / M_PER_MILE
    fun metresToFeet(m: Double) = m * FT_PER_M

    fun cmToFtIn(cm: Double): Pair<Int, Int> {
        val total = (cm / CM_PER_INCH).roundToInt()
        return total / 12 to total % 12
    }

    fun weight(kg: Double, decimals: Int = 1): String =
        String.format(Locale.US, "%.${decimals}f lb", kgToLb(kg))

    fun height(cm: Double): String {
        val (feet, inches) = cmToFtIn(cm)
        return "$feet'$inches\""
    }

    fun miles(metres: Double, decimals: Int = 2): String =
        String.format(Locale.US, "%.${decimals}f mi", metresToMiles(metres))

    fun feet(metres: Double): String = "${number(metresToFeet(metres).roundToInt())} ft"

    /** Pace in minutes per mile — the only pace an American runner reads instinctively. */
    fun paceSecondsPerMile(distanceMetres: Double, durationSeconds: Long): Double? {
        if (distanceMetres <= 0 || durationSeconds <= 0) return null
        return durationSeconds / metresToMiles(distanceMetres)
    }

    fun pace(secondsPerMile: Double?): String {
        if (secondsPerMile == null || secondsPerMile.isNaN() || secondsPerMile.isInfinite()) return "--:--"
        val total = secondsPerMile.roundToInt()
        return String.format(Locale.US, "%d:%02d", total / 60, total % 60)
    }

    /** Signed weekly rate, e.g. "-1.1 lb". */
    fun rateLb(kgPerWeek: Double): String =
        String.format(Locale.US, "%s%.1f lb", if (kgPerWeek >= 0) "+" else "", kgToLb(kgPerWeek))

    /** Thousands separators, because 8000 and 8,000 read at different speeds. */
    fun number(value: Int): String =
        if (value >= 1000 || value <= -1000) String.format(Locale.US, "%,d", value) else value.toString()

    fun duration(totalSeconds: Long): String {
        val s = totalSeconds.coerceAtLeast(0)
        val h = s / 3600
        val m = (s % 3600) / 60
        val sec = s % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, sec)
        else String.format(Locale.US, "%d:%02d", m, sec)
    }
}
