package dev.zephyr.core.trend

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WeighInScheduleTest {

    // 2026-03-09 is a Monday; 2026-03-11 a Wednesday.
    private val mondayEvening = LocalDateTime.of(2026, 3, 9, 20, 0)
    private val wednesdayEvening = LocalDateTime.of(2026, 3, 11, 20, 0)

    private fun evaluate(
        now: LocalDateTime,
        lastWeighIn: LocalDate? = null,
        lastSnoozeAt: LocalDateTime? = null,
        snoozeCount: Int = 0,
    ) = WeighInReminder.evaluate(
        now = now,
        lastWeighIn = lastWeighIn,
        lastSnoozeAt = lastSnoozeAt,
        snoozeCount = snoozeCount,
    )

    @Test
    fun `asks on a scheduled evening`() {
        assertIs<WeighInPrompt.Due>(evaluate(mondayEvening))
    }

    @Test
    fun `stays quiet on a day that is not scheduled`() {
        assertEquals(WeighInPrompt.None, evaluate(wednesdayEvening))
    }

    @Test
    fun `stays quiet during the day and speaks up in the evening`() {
        assertEquals(WeighInPrompt.None, evaluate(LocalDateTime.of(2026, 3, 9, 11, 0)))
        assertIs<WeighInPrompt.Due>(evaluate(LocalDateTime.of(2026, 3, 9, 19, 45)))
    }

    @Test
    fun `a weight entered today silences it immediately`() {
        assertEquals(
            WeighInPrompt.None,
            evaluate(mondayEvening, lastWeighIn = LocalDate.of(2026, 3, 9)),
        )
    }

    @Test
    fun `an old weigh-in does not count for today`() {
        assertIs<WeighInPrompt.Due>(
            evaluate(mondayEvening, lastWeighIn = LocalDate.of(2026, 3, 6)),
        )
    }

    @Test
    fun `later holds it off for the snooze period`() {
        val snoozed = mondayEvening
        assertEquals(
            WeighInPrompt.None,
            evaluate(snoozed.plusMinutes(30), lastSnoozeAt = snoozed, snoozeCount = 1),
        )
    }

    @Test
    fun `it comes back an hour later`() {
        val snoozed = mondayEvening
        assertIs<WeighInPrompt.Due>(
            evaluate(snoozed.plusMinutes(61), lastSnoozeAt = snoozed, snoozeCount = 1),
        )
    }

    @Test
    fun `it keeps coming back until a number is entered`() {
        var now = mondayEvening
        var snoozes = 0

        repeat(4) {
            val prompt = evaluate(now, lastSnoozeAt = now.minusMinutes(61), snoozeCount = snoozes)
            assertIs<WeighInPrompt.Due>(prompt, "gave up after $snoozes snoozes")
            snoozes += 1
            now = now.plusMinutes(61)
        }

        // Entering the weight stops it on the spot, however many times it was put off.
        assertEquals(
            WeighInPrompt.None,
            evaluate(now, lastWeighIn = now.toLocalDate(), lastSnoozeAt = now.minusMinutes(61), snoozeCount = snoozes),
        )
    }

    @Test
    fun `the nagging never survives into the next day`() {
        val nextMorning = LocalDateTime.of(2026, 3, 10, 9, 0)
        assertEquals(
            WeighInPrompt.None,
            evaluate(nextMorning, lastSnoozeAt = mondayEvening, snoozeCount = 3),
        )
    }

    @Test
    fun `a snooze from a previous day does not suppress today's ask`() {
        val friday = LocalDateTime.of(2026, 3, 13, 20, 0)
        assertIs<WeighInPrompt.Due>(
            evaluate(friday, lastSnoozeAt = mondayEvening, snoozeCount = 2),
        )
    }

    @Test
    fun `the default schedule asks twice a week at each end`() {
        val schedule = WeighInSchedule.DEFAULT
        assertEquals(2, schedule.days.size)
        assertTrue(DayOfWeek.MONDAY in schedule.days)
        assertTrue(DayOfWeek.FRIDAY in schedule.days)
    }

    @Test
    fun `the attempt count is passed through so the wording can change`() {
        val prompt = evaluate(mondayEvening, snoozeCount = 2)
        assertEquals(2, assertIs<WeighInPrompt.Due>(prompt).attempt)
    }

    @Test
    fun `no reminder wording shames the user`() {
        (0..5).forEach { attempt ->
            val message = WeighInReminder.message(attempt).lowercase()
            assertTrue(message.isNotBlank())
            listOf("lazy", "fat", "failed", "avoiding", "excuse").forEach {
                assertTrue(it !in message, "\"$message\" shames the user")
            }
        }
    }
}
