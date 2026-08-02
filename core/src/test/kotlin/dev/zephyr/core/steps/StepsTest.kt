package dev.zephyr.core.steps

import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StepGoalEngineTest {

    private val today = LocalDate.of(2026, 3, 1)

    private fun history(vararg steps: Int) =
        steps.mapIndexed { i, s -> DailySteps(today.minusDays(i.toLong()), s) }

    @Test
    fun `falls back to a default without enough history`() {
        val suggestion = StepGoalEngine.suggest(history(4000, 5000))
        assertEquals(StepGoalEngine.DEFAULT_GOAL, suggestion.goal)
    }

    @Test
    fun `sets a reachable goal for a mostly sedentary user`() {
        val suggestion = StepGoalEngine.suggest(history(3000, 3200, 2800, 3100, 2900, 3300, 3000))

        assertTrue(
            suggestion.goal in 3000..4000,
            "suggested ${suggestion.goal} to someone walking ~3000 — that goal gets missed daily",
        )
        assertTrue(suggestion.goal > 3000, "the goal should still ask for slightly more")
    }

    @Test
    fun `raises the goal for an active user`() {
        val suggestion = StepGoalEngine.suggest(history(12000, 13000, 11500, 12500, 12000, 13500, 12200))
        assertTrue(suggestion.goal > 12000)
    }

    @Test
    fun `never suggests outside the sane bounds`() {
        val tiny = StepGoalEngine.suggest(history(100, 50, 80, 120, 90, 70, 60))
        val huge = StepGoalEngine.suggest(history(*IntArray(10) { 60_000 }))

        assertTrue(tiny.goal >= StepGoalEngine.MIN_GOAL)
        assertTrue(huge.goal <= StepGoalEngine.MAX_GOAL)
    }

    @Test
    fun `eases an unreachable goal downward`() {
        val suggestion = StepGoalEngine.suggest(
            history(4000, 4200, 3800, 4100, 3900, 4300, 4000),
            currentGoal = 12_000,
        )
        assertTrue(suggestion.goal < 12_000)
    }

    @Test
    fun `uses the median so one big day does not skew the goal`() {
        val withOutlier = StepGoalEngine.suggest(history(4000, 4000, 4000, 4000, 4000, 4000, 40_000))
        assertTrue(
            withOutlier.goal < 6000,
            "a single 40k day dragged the goal to ${withOutlier.goal}",
        )
    }
}

class StepPaceTest {

    @Test
    fun `expected progress is near zero overnight`() {
        assertTrue(StepPace.expectedFraction(LocalTime.of(3, 0)) < 0.02)
    }

    @Test
    fun `expected progress reaches everything by end of day`() {
        assertEquals(1.0, StepPace.expectedFraction(LocalTime.of(23, 59)), 0.02)
    }

    @Test
    fun `expected progress never decreases through the day`() {
        var previous = -1.0
        for (hour in 0..23) {
            for (minute in listOf(0, 30)) {
                val fraction = StepPace.expectedFraction(LocalTime.of(hour, minute))
                assertTrue(fraction >= previous, "fraction dropped at $hour:$minute")
                previous = fraction
            }
        }
    }

    @Test
    fun `does not cry wolf at breakfast`() {
        // 400 steps by 8am is completely normal and must not read as failing.
        val status = StepPace.status(steps = 400, goal = 8000, time = LocalTime.of(8, 0))
        assertTrue(
            status.deficit < 1500,
            "declared a ${status.deficit} step deficit at 8am — this is the alert users mute",
        )
    }

    @Test
    fun `flags a genuinely behind afternoon`() {
        val status = StepPace.status(steps = 1200, goal = 8000, time = LocalTime.of(16, 0))
        assertTrue(!status.onTrack)
        assertTrue(status.deficit > 3000)
    }

    @Test
    fun `recognises being ahead of pace`() {
        val status = StepPace.status(steps = 7000, goal = 8000, time = LocalTime.of(14, 0))
        assertTrue(status.onTrack)
        assertEquals(0, status.deficit)
    }

    @Test
    fun `fraction of goal is clamped at one`() {
        val status = StepPace.status(steps = 16_000, goal = 8000, time = LocalTime.of(20, 0))
        assertEquals(1f, status.fractionOfGoal)
        assertEquals(0, status.remaining)
    }

    @Test
    fun `walk time estimate is human-scaled`() {
        // 1400 steps at ~110 steps/min is roughly a quarter of an hour.
        assertEquals(13, StepPace.minutesToWalk(1400))
        assertEquals(0, StepPace.minutesToWalk(0))
    }
}
