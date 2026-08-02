package dev.zephyr.core.plan

import dev.zephyr.core.activity.ActivityType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProgressionEngineTest {

    // A Monday.
    private val weekStart = LocalDate.of(2026, 6, 1)

    private val template = WeeklyTemplate(
        listOf(
            PlannedSlot(id = 1, dayOfWeek = DayOfWeek.TUESDAY, type = ActivityType.RUN, timeOfDay = LocalTime.of(7, 0)),
            PlannedSlot(id = 2, dayOfWeek = DayOfWeek.THURSDAY, type = ActivityType.STRENGTH, timeOfDay = LocalTime.of(18, 0)),
            PlannedSlot(id = 3, dayOfWeek = DayOfWeek.FRIDAY, type = ActivityType.RUN, timeOfDay = LocalTime.of(7, 0)),
            PlannedSlot(id = 4, dayOfWeek = DayOfWeek.SATURDAY, type = ActivityType.HIKE, timeOfDay = LocalTime.of(9, 0)),
        ),
    )

    private val baseline = FitnessBaseline(longestRunMetres = 5000.0, longestHikeMinutes = 90)

    @Test
    fun `produces one prescription per enabled slot`() {
        val week = ProgressionEngine.prescriptionsForWeek(template, weekStart, 0, baseline)
        assertEquals(4, week.size)
    }

    @Test
    fun `prescriptions land on the right dates`() {
        val week = ProgressionEngine.prescriptionsForWeek(template, weekStart, 0, baseline)
        val tuesday = week.first { it.slot.id == 1L }
        assertEquals(DayOfWeek.TUESDAY, tuesday.date.dayOfWeek)
        assertEquals(weekStart.plusDays(1), tuesday.date)

        val saturday = week.first { it.slot.id == 4L }
        assertEquals(weekStart.plusDays(5), saturday.date)
    }

    @Test
    fun `disabled slots are skipped`() {
        val partly = WeeklyTemplate(template.slots.map { it.copy(enabled = it.id != 3L) })
        val week = ProgressionEngine.prescriptionsForWeek(partly, weekStart, 0, baseline)
        assertEquals(3, week.size)
        assertTrue(week.none { it.slot.id == 3L })
    }

    @Test
    fun `the last run of the week is the long one`() {
        val week = ProgressionEngine.prescriptionsForWeek(template, weekStart, 0, baseline)
        val tuesday = week.first { it.slot.id == 1L }.targetDistanceMetres!!
        val friday = week.first { it.slot.id == 3L }.targetDistanceMetres!!

        assertTrue(friday > tuesday, "long run ($friday m) should exceed the easy run ($tuesday m)")
    }

    @Test
    fun `weekly volume increases but never faster than the ten percent rule`() {
        // The classic injury path is a motivated beginner adding 40% a week for a fortnight.
        var previous: Double? = null
        for (week in 0..2) {
            val prescriptions = ProgressionEngine.prescriptionsForWeek(template, weekStart, week, baseline)
            val volume = prescriptions.mapNotNull { it.targetDistanceMetres }.sum()
            if (previous != null) {
                val growth = (volume - previous) / previous
                assertTrue(
                    growth <= ProgressionEngine.WEEKLY_INCREASE + 0.03,
                    "week $week grew volume by ${(growth * 100).toInt()}%",
                )
                assertTrue(growth > 0, "week $week did not progress at all")
            }
            previous = volume
        }
    }

    @Test
    fun `every fourth week is a deload`() {
        val week3 = ProgressionEngine.prescriptionsForWeek(template, weekStart, 3, baseline)
        assertTrue(week3.all { it.isDeload }, "week index 3 should be the recovery week")

        val week2 = ProgressionEngine.prescriptionsForWeek(template, weekStart, 2, baseline)
        assertTrue(week2.none { it.isDeload })
    }

    @Test
    fun `a deload week actually reduces the load`() {
        val hard = ProgressionEngine.prescriptionsForWeek(template, weekStart, 2, baseline)
            .mapNotNull { it.targetDistanceMetres }.sum()
        val easy = ProgressionEngine.prescriptionsForWeek(template, weekStart, 3, baseline)
            .mapNotNull { it.targetDistanceMetres }.sum()

        assertTrue(easy < hard, "deload volume $easy was not lower than $hard")
    }

    @Test
    fun `a complete beginner starts at a survivable distance`() {
        val week = ProgressionEngine.prescriptionsForWeek(template, weekStart, 0, FitnessBaseline())
        val longest = week.mapNotNull { it.targetDistanceMetres }.max()

        assertTrue(longest <= 3000.0, "prescribed ${longest}m to someone with no running history")
    }

    @Test
    fun `distance is capped so a long plan cannot prescribe an ultramarathon`() {
        val week = ProgressionEngine.prescriptionsForWeek(template, weekStart, 80, baseline)
        val longest = week.mapNotNull { it.targetDistanceMetres }.max()
        assertTrue(longest <= ProgressionEngine.MAX_RUN_METRES)
    }

    @Test
    fun `strength and hike sessions get prescriptions too`() {
        val week = ProgressionEngine.prescriptionsForWeek(template, weekStart, 1, baseline)
        val strength = week.first { it.slot.type == ActivityType.STRENGTH }
        val hike = week.first { it.slot.type == ActivityType.HIKE }

        assertTrue(strength.headline.isNotBlank())
        assertNotNull(hike.targetDurationMinutes)
        assertTrue(hike.targetDurationMinutes!! > 0)
    }

    @Test
    fun `the default template is a sane balanced week`() {
        val default = WeeklyTemplate.default()
        assertTrue(default.sessionsPerWeek in 4..6)
        assertTrue(default.slots.any { it.type == ActivityType.RUN })
        assertTrue(default.slots.any { it.type == ActivityType.STRENGTH })
        assertTrue(default.slots.any { it.type == ActivityType.HIKE })
    }
}

class AdherenceCalculatorTest {

    private val weekStart = LocalDate.of(2026, 6, 1)
    private val template = WeeklyTemplate(
        listOf(
            PlannedSlot(id = 1, dayOfWeek = DayOfWeek.TUESDAY, type = ActivityType.RUN, timeOfDay = LocalTime.of(7, 0)),
            PlannedSlot(id = 2, dayOfWeek = DayOfWeek.THURSDAY, type = ActivityType.STRENGTH, timeOfDay = LocalTime.of(18, 0)),
        ),
    )
    private val prescriptions =
        ProgressionEngine.prescriptionsForWeek(template, weekStart, 0, FitnessBaseline())

    @Test
    fun `sessions not yet due are not counted as missed`() {
        val result = AdherenceCalculator.evaluate(prescriptions, emptyList(), upTo = weekStart)
        assertEquals(0, result.planned)
        assertEquals(100, result.percent)
    }

    @Test
    fun `a completed session on the day counts`() {
        val done = listOf(CompletedSession(weekStart.plusDays(1), ActivityType.RUN))
        val result = AdherenceCalculator.evaluate(prescriptions, done, upTo = weekStart.plusDays(1))

        assertEquals(1, result.planned)
        assertEquals(1, result.completed)
        assertEquals(100, result.percent)
    }

    @Test
    fun `a session done a day late still counts because life slides`() {
        val done = listOf(CompletedSession(weekStart.plusDays(2), ActivityType.RUN))
        val result = AdherenceCalculator.evaluate(prescriptions, done, upTo = weekStart.plusDays(2))
        assertEquals(1, result.completed)
        assertTrue(result.missed.isEmpty())
    }

    @Test
    fun `a skipped session is reported as missed`() {
        val result = AdherenceCalculator.evaluate(prescriptions, emptyList(), upTo = weekStart.plusDays(3))
        assertEquals(2, result.planned)
        assertEquals(0, result.completed)
        assertEquals(2, result.missed.size)
        assertEquals(0, result.percent)
    }

    @Test
    fun `the wrong kind of session does not satisfy a plan slot`() {
        // Going for a run does not tick off leg day.
        val done = listOf(CompletedSession(weekStart.plusDays(3), ActivityType.RUN))
        val result = AdherenceCalculator.evaluate(prescriptions, done, upTo = weekStart.plusDays(3))
        assertTrue(result.missed.any { it.slot.type == ActivityType.STRENGTH })
    }

    @Test
    fun `one session cannot satisfy two planned slots`() {
        val twoRuns = WeeklyTemplate(
            listOf(
                PlannedSlot(id = 1, dayOfWeek = DayOfWeek.TUESDAY, type = ActivityType.RUN, timeOfDay = LocalTime.of(7, 0)),
                PlannedSlot(id = 2, dayOfWeek = DayOfWeek.WEDNESDAY, type = ActivityType.RUN, timeOfDay = LocalTime.of(7, 0)),
            ),
        )
        val week = ProgressionEngine.prescriptionsForWeek(twoRuns, weekStart, 0, FitnessBaseline())
        val done = listOf(CompletedSession(weekStart.plusDays(1), ActivityType.RUN))

        val result = AdherenceCalculator.evaluate(week, done, upTo = weekStart.plusDays(2))
        assertEquals(1, result.completed)
        assertEquals(1, result.missed.size)
    }
}

class StrengthProgressionTest {

    @Test
    fun `no history yields no prescription`() {
        assertNull(StrengthProgression.next(emptyList(), 8..12))
    }

    @Test
    fun `hitting the top of the range earns more weight`() {
        val last = listOf(
            StrengthProgression.SetResult(12, 60.0),
            StrengthProgression.SetResult(12, 60.0),
            StrengthProgression.SetResult(12, 60.0),
        )
        val next = StrengthProgression.next(last, 8..12)!!
        assertEquals(62.5, next.weightKg)
        assertEquals(8, next.targetReps)
        assertTrue(!next.isDeload)
    }

    @Test
    fun `mid-range performance earns a rep, not weight`() {
        val last = listOf(
            StrengthProgression.SetResult(9, 60.0),
            StrengthProgression.SetResult(9, 60.0),
        )
        val next = StrengthProgression.next(last, 8..12)!!
        assertEquals(60.0, next.weightKg)
        assertEquals(10, next.targetReps)
    }

    @Test
    fun `the weakest set governs progression`() {
        // Two strong sets and one that fell apart is not a session to add weight to.
        val last = listOf(
            StrengthProgression.SetResult(12, 60.0),
            StrengthProgression.SetResult(12, 60.0),
            StrengthProgression.SetResult(7, 60.0),
        )
        val next = StrengthProgression.next(last, 8..12)!!
        assertEquals(60.0, next.weightKg)
    }

    @Test
    fun `stalling twice triggers a deload rather than more grinding`() {
        val last = listOf(StrengthProgression.SetResult(6, 100.0))
        val next = StrengthProgression.next(last, 8..12, consecutiveFailures = 2)!!

        assertTrue(next.isDeload)
        assertTrue(next.weightKg < 100.0)
    }

    @Test
    fun `lower body lifts advance in larger jumps`() {
        val last = listOf(StrengthProgression.SetResult(12, 100.0))
        val upper = StrengthProgression.next(last, 8..12, isLowerBody = false)!!
        val lower = StrengthProgression.next(last, 8..12, isLowerBody = true)!!

        assertTrue(lower.weightKg > upper.weightKg)
    }

    @Test
    fun `deload weight lands on a loadable increment`() {
        val last = listOf(StrengthProgression.SetResult(6, 87.5))
        val next = StrengthProgression.next(last, 8..12, consecutiveFailures = 3)!!
        val remainder = (next.weightKg / 1.25) % 1.0
        assertTrue(remainder < 0.001, "${next.weightKg}kg cannot be loaded on a real barbell")
    }
}
