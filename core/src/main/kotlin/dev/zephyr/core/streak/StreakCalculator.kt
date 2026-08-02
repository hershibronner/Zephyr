package dev.zephyr.core.streak

import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A day the user did the things that matter, regardless of whether the scale agreed. */
data class DayOutcome(
    val date: LocalDate,
    val loggedFood: Boolean,
    val withinCalorieTarget: Boolean,
    val hitStepGoal: Boolean,
    val completedSession: Boolean,
)

data class StreakResult(
    val current: Int,
    val longest: Int,
    /** True when today is not yet secured and losing the streak is still avoidable. */
    val atRisk: Boolean,
    val lastQualifyingDate: LocalDate?,
)

/**
 * Streaks over *adherence*, not perfection.
 *
 * A streak that breaks the first time someone eats a birthday cake teaches them the app is
 * fragile and that one bad day has ruined the week — which is precisely the all-or-nothing
 * thinking that ends diets. So a day qualifies on effort: log your food and either stay within
 * budget, hit your steps, or train. Showing up counts.
 */
object StreakCalculator {

    fun qualifies(day: DayOutcome): Boolean =
        day.loggedFood && (day.withinCalorieTarget || day.hitStepGoal || day.completedSession)

    /**
     * @param today used to decide whether an unfinished today breaks the chain — it shouldn't,
     *   it should raise [StreakResult.atRisk] while there's still time to act.
     */
    fun calculate(outcomes: List<DayOutcome>, today: LocalDate): StreakResult {
        if (outcomes.isEmpty()) {
            return StreakResult(0, 0, atRisk = false, lastQualifyingDate = null)
        }

        val qualifying = outcomes.filter { qualifies(it) }.map { it.date }.distinct().sorted()
        if (qualifying.isEmpty()) {
            return StreakResult(0, 0, atRisk = false, lastQualifyingDate = null)
        }

        val longest = longestRun(qualifying)
        val last = qualifying.last()

        // Today still in progress doesn't break anything; yesterday counts as the anchor.
        val daysSinceLast = ChronoUnit.DAYS.between(last, today)
        val current = when (daysSinceLast) {
            0L -> runEndingAt(qualifying, last)
            1L -> runEndingAt(qualifying, last)
            else -> 0
        }

        return StreakResult(
            current = current,
            longest = longest,
            atRisk = current > 0 && daysSinceLast == 1L,
            lastQualifyingDate = last,
        )
    }

    private fun runEndingAt(sortedDates: List<LocalDate>, end: LocalDate): Int {
        var count = 0
        var expected = end
        for (date in sortedDates.asReversed()) {
            if (date == expected) {
                count++
                expected = expected.minusDays(1)
            } else if (date.isBefore(expected)) {
                break
            }
        }
        return count
    }

    private fun longestRun(sortedDates: List<LocalDate>): Int {
        var longest = 1
        var run = 1
        for (i in 1 until sortedDates.size) {
            run = if (ChronoUnit.DAYS.between(sortedDates[i - 1], sortedDates[i]) == 1L) run + 1 else 1
            if (run > longest) longest = run
        }
        return longest
    }

    /** Milestones worth a celebration screen. Rare enough to stay meaningful. */
    val MILESTONES = listOf(3, 7, 14, 30, 60, 100, 200, 365)

    fun milestoneReached(streak: Int): Int? = MILESTONES.firstOrNull { it == streak }
}
