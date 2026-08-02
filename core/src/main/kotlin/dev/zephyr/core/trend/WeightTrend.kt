package dev.zephyr.core.trend

import dev.zephyr.core.model.KCAL_PER_KG
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

data class WeightEntry(val date: LocalDate, val weightKg: Double)

data class TrendPoint(val date: LocalDate, val rawKg: Double?, val trendKg: Double)

data class WeightTrendResult(
    val points: List<TrendPoint>,
    val currentTrendKg: Double?,
    /** Signed kilograms per week; negative is loss. */
    val weeklyRateKg: Double?,
    val totalChangeKg: Double?,
) {
    val isEmpty: Boolean get() = points.isEmpty()
}

/**
 * Exponentially smoothed body weight.
 *
 * Day-to-day scale readings move several kilograms on water, sodium, glycogen and gut contents —
 * swings that dwarf the ~70 g/day of actual fat loss a sane deficit produces. Reacting to raw
 * weigh-ins is therefore reacting to noise, and it is why people abandon working plans after a
 * bad morning on the scale. The smoothed series is the only number worth showing a target against.
 *
 * Uses a low smoothing factor (Hacker's Diet style) so the trend lags reality by roughly a week but
 * is almost immune to single-day noise. Gaps between weigh-ins are handled by advancing the trend
 * one day at a time, holding its value, so a missed week doesn't create a false cliff.
 */
object WeightTrend {

    const val SMOOTHING = 0.10

    fun calculate(entries: List<WeightEntry>, smoothing: Double = SMOOTHING): WeightTrendResult {
        if (entries.isEmpty()) {
            return WeightTrendResult(emptyList(), null, null, null)
        }

        // One entry per day; if the user weighed in twice, the last reading wins.
        val byDate = entries.sortedBy { it.date }.associate { it.date to it.weightKg }
        val start = byDate.keys.first()
        val end = byDate.keys.last()

        val points = mutableListOf<TrendPoint>()
        var trend = byDate.getValue(start)
        var date = start
        while (!date.isAfter(end)) {
            val raw = byDate[date]
            if (raw != null) {
                trend += (raw - trend) * smoothing
            }
            points += TrendPoint(date = date, rawKg = raw, trendKg = trend)
            date = date.plusDays(1)
        }

        return WeightTrendResult(
            points = points,
            currentTrendKg = points.last().trendKg,
            weeklyRateKg = weeklyRate(points),
            totalChangeKg = points.last().trendKg - points.first().trendKg,
        )
    }

    /**
     * Rate of change over the most recent [windowDays], expressed per week. Needs at least a week
     * of span to say anything — below that the smoothing hasn't caught up and the answer would be
     * confidently wrong.
     */
    fun weeklyRate(points: List<TrendPoint>, windowDays: Long = 14): Double? {
        if (points.size < 2) return null
        val last = points.last()
        val cutoff = last.date.minusDays(windowDays)
        val window = points.filter { !it.date.isBefore(cutoff) }
        if (window.size < 2) return null

        val first = window.first()
        val days = ChronoUnit.DAYS.between(first.date, last.date)
        if (days < 7) return null

        return (last.trendKg - first.trendKg) / days * 7.0
    }

    /**
     * Projected date of reaching [goalKg] at the current rate, or null when the trend is flat or
     * moving away from the goal. Deliberately returns null rather than a distant fantasy date.
     */
    fun projectGoalDate(result: WeightTrendResult, goalKg: Double, from: LocalDate): LocalDate? {
        val current = result.currentTrendKg ?: return null
        val rate = result.weeklyRateKg ?: return null
        val remaining = goalKg - current
        if (abs(remaining) < 0.1) return from
        if (abs(rate) < 0.02) return null
        // Moving the wrong way.
        if (remaining > 0 != rate > 0) return null

        val weeks = remaining / rate
        if (weeks <= 0 || weeks > 260) return null
        return from.plusDays((weeks * 7).roundToInt().toLong())
    }

    /** Average daily energy imbalance implied by a trend rate, for the "what this means" copy. */
    fun impliedDailyImbalanceKcal(weeklyRateKg: Double): Int =
        (weeklyRateKg * KCAL_PER_KG / 7.0).roundToInt()
}
