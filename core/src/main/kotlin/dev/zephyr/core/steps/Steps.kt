package dev.zephyr.core.steps

import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

data class DailySteps(val date: LocalDate, val steps: Int)

data class StepGoalSuggestion(
    val goal: Int,
    val baselineMedian: Int,
    val reason: String,
)

/**
 * Chooses a step goal from the user's own history rather than folklore.
 *
 * The famous 10,000 figure came from a 1960s Japanese pedometer marketing campaign, not physiology,
 * and handing it to someone who currently walks 3,000 sets them up to fail on day one. A goal
 * anchored to a recent median and nudged upward is one they will actually hit — and hitting it is
 * the entire mechanism, because a goal that gets missed daily stops being motivating and becomes
 * background noise.
 */
object StepGoalEngine {

    const val DEFAULT_GOAL = 8_000
    const val MIN_GOAL = 3_000
    const val MAX_GOAL = 20_000
    const val PROGRESSION_RATE = 0.08
    const val MIN_DAYS_FOR_BASELINE = 5

    fun suggest(history: List<DailySteps>, currentGoal: Int? = null): StepGoalSuggestion {
        val recent = history.sortedByDescending { it.date }.take(14)
        if (recent.size < MIN_DAYS_FOR_BASELINE) {
            return StepGoalSuggestion(
                goal = currentGoal ?: DEFAULT_GOAL,
                baselineMedian = 0,
                reason = "Not enough history yet — starting from a standard goal.",
            )
        }

        val median = median(recent.map { it.steps })
        val raised = (median * (1 + PROGRESSION_RATE)).roundToInt()
        val rounded = roundToNearest(raised, 250)
        val goal = rounded.coerceIn(MIN_GOAL, MAX_GOAL)

        val reason = when {
            currentGoal != null && goal > currentGoal ->
                "You've been averaging $median steps — nudging the goal up."
            currentGoal != null && goal < currentGoal ->
                "Your goal was out of reach lately; easing it to something you'll hit."
            else -> "Based on your recent median of $median steps."
        }
        return StepGoalSuggestion(goal, median, reason)
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2 else sorted[mid]
    }

    private fun roundToNearest(value: Int, step: Int): Int = ((value + step / 2) / step) * step
}

data class StepPaceStatus(
    val steps: Int,
    val goal: Int,
    /** Steps the user would normally have by this time of day. */
    val expectedByNow: Int,
    val deficit: Int,
    val onTrack: Boolean,
    val projectedEndOfDay: Int,
) {
    val fractionOfGoal: Float get() = if (goal <= 0) 0f else (steps.toFloat() / goal).coerceIn(0f, 1f)
    val remaining: Int get() = max(goal - steps, 0)
}

/**
 * Judges progress against the *shape* of a normal day, not a straight line.
 *
 * Nobody walks uniformly from midnight to midnight; steps cluster around commutes, lunch and
 * evenings. A linear split would declare someone hopelessly behind at 9am every single day, and an
 * alert that cries wolf every morning is one the user swipes away permanently. The default curve
 * below is a typical waking distribution, and it is the basis for the "you're behind" nudge.
 */
object StepPace {

    /** Cumulative fraction of a day's steps typically completed by the end of each hour. */
    private val CUMULATIVE_BY_HOUR = doubleArrayOf(
        0.00, 0.00, 0.00, 0.00, 0.00, 0.01, // 00:00–05:59
        0.03, 0.08, 0.16, 0.23, 0.29, 0.35, // 06:00–11:59
        0.43, 0.51, 0.57, 0.63, 0.69, 0.76, // 12:00–17:59
        0.84, 0.90, 0.95, 0.98, 1.00, 1.00, // 18:00–23:59
    )

    fun expectedFraction(time: LocalTime): Double {
        val hour = time.hour
        val startOfHour = if (hour == 0) 0.0 else CUMULATIVE_BY_HOUR[hour - 1]
        val endOfHour = CUMULATIVE_BY_HOUR[hour]
        val withinHour = time.minute / 60.0
        return startOfHour + (endOfHour - startOfHour) * withinHour
    }

    fun status(steps: Int, goal: Int, time: LocalTime): StepPaceStatus {
        val fraction = expectedFraction(time)
        val expected = (goal * fraction).roundToInt()
        val projected = if (fraction > 0.05) min((steps / fraction).roundToInt(), 100_000) else goal
        return StepPaceStatus(
            steps = steps,
            goal = goal,
            expectedByNow = expected,
            deficit = max(expected - steps, 0),
            onTrack = steps >= expected,
            projectedEndOfDay = projected,
        )
    }

    /** Rough distance for a step count, for the "that's a 12 minute walk" framing in nudges. */
    fun minutesToWalk(steps: Int, stepsPerMinute: Int = 110): Int =
        if (steps <= 0) 0 else max(1, (steps.toDouble() / stepsPerMinute).roundToInt())
}
