package dev.zephyr.core.streak

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StreakCalculatorTest {

    private val today = LocalDate.of(2026, 5, 20)

    private fun day(
        offset: Long,
        logged: Boolean = true,
        withinTarget: Boolean = true,
        steps: Boolean = false,
        session: Boolean = false,
    ) = DayOutcome(today.minusDays(offset), logged, withinTarget, steps, session)

    @Test
    fun `no history means no streak`() {
        val result = StreakCalculator.calculate(emptyList(), today)
        assertEquals(0, result.current)
        assertNull(result.lastQualifyingDate)
    }

    @Test
    fun `consecutive good days build a streak`() {
        val outcomes = (0L..4L).map { day(it) }
        val result = StreakCalculator.calculate(outcomes, today)
        assertEquals(5, result.current)
        assertEquals(5, result.longest)
    }

    @Test
    fun `a day over budget still counts if the user hit their steps`() {
        // The all-or-nothing failure mode: one indulgent meal must not erase two weeks of work.
        val outcomes = listOf(
            day(0, withinTarget = false, steps = true),
            day(1),
            day(2),
        )
        val result = StreakCalculator.calculate(outcomes, today)
        assertEquals(3, result.current)
    }

    @Test
    fun `an over-budget unmoved day does not qualify on its own merits`() {
        assertFalse(
            StreakCalculator.qualifies(
                DayOutcome(today, loggedFood = true, withinCalorieTarget = false, hitStepGoal = false, completedSession = false),
            ),
        )
    }

    @Test
    fun `a failing day only breaks the chain once it is in the past`() {
        val outcomes = listOf(
            day(0, withinTarget = false, steps = false, session = false),
            day(1),
            day(2),
        )

        // While that day is still today, the streak survives — there is time left to walk it off,
        // and killing it early removes the reason to bother.
        val duringTheDay = StreakCalculator.calculate(outcomes, today)
        assertEquals(2, duringTheDay.current)
        assertTrue(duringTheDay.atRisk)

        // Once the day has passed unsaved, the chain is genuinely broken.
        val theNextMorning = StreakCalculator.calculate(outcomes, today.plusDays(1))
        assertEquals(0, theNextMorning.current)
        assertEquals(2, theNextMorning.longest)
    }

    @Test
    fun `not logging food disqualifies the day regardless of activity`() {
        assertFalse(
            StreakCalculator.qualifies(
                DayOutcome(today, loggedFood = false, withinCalorieTarget = true, hitStepGoal = true, completedSession = true),
            ),
        )
    }

    @Test
    fun `a gap resets the current streak but not the longest`() {
        val outcomes = listOf(day(0), day(1), day(5), day(6), day(7), day(8), day(9))
        val result = StreakCalculator.calculate(outcomes, today)
        assertEquals(2, result.current)
        assertEquals(5, result.longest)
    }

    @Test
    fun `an unfinished today does not break the streak but flags risk`() {
        // Yesterday qualified, today hasn't yet — the moment the app should speak up.
        val outcomes = (1L..6L).map { day(it) }
        val result = StreakCalculator.calculate(outcomes, today)

        assertEquals(6, result.current)
        assertTrue(result.atRisk, "streak should be flagged at risk while it can still be saved")
    }

    @Test
    fun `a secured today is not at risk`() {
        val outcomes = (0L..6L).map { day(it) }
        val result = StreakCalculator.calculate(outcomes, today)
        assertFalse(result.atRisk)
    }

    @Test
    fun `a streak abandoned days ago is gone`() {
        val outcomes = (5L..10L).map { day(it) }
        val result = StreakCalculator.calculate(outcomes, today)
        assertEquals(0, result.current)
        assertFalse(result.atRisk)
        assertEquals(6, result.longest)
    }

    @Test
    fun `milestones fire only on exact days`() {
        assertEquals(7, StreakCalculator.milestoneReached(7))
        assertNull(StreakCalculator.milestoneReached(8))
        assertEquals(100, StreakCalculator.milestoneReached(100))
    }

    @Test
    fun `duplicate entries for one day do not inflate the count`() {
        val outcomes = listOf(day(0), day(0), day(1), day(1))
        val result = StreakCalculator.calculate(outcomes, today)
        assertEquals(2, result.current)
    }
}
