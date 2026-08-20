package dev.zephyr.core.trend

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * When to ask for a weight, and how hard to push.
 *
 * @param days the days a weigh-in is wanted. Two, at the ends of the week, so the trend has a
 *   regular cadence without the app turning into a scale-obsession machine.
 * @param promptAt evening, because morning weight is the honest one and asking at night is what
 *   gets someone onto the scale when they wake up.
 */
data class WeighInSchedule(
    val days: Set<DayOfWeek> = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY),
    val promptAt: LocalTime = LocalTime.of(19, 30),
    val snoozeMinutes: Long = 60,
) {
    companion object {
        val DEFAULT = WeighInSchedule()
    }
}

sealed interface WeighInPrompt {
    data object None : WeighInPrompt

    /**
     * Ask now. [attempt] counts how many times this same day's prompt has already been put off,
     * so the wording can acknowledge the nagging rather than repeat itself verbatim.
     */
    data class Due(val date: LocalDate, val attempt: Int) : WeighInPrompt
}

/**
 * Decides whether to ask for a weigh-in right now.
 *
 * The snooze is deliberately persistent: "Later" moves the ask an hour down the road rather than
 * cancelling it, and it keeps coming back until a number is entered or the day ends. That is the
 * behaviour the user asked for, and it is defensible because the whole adaptive-TDEE engine — the
 * thing that stops the plan going stale — is starved without regular weigh-ins. It is also bounded:
 * it never survives past midnight, and entering a weight silences it immediately.
 */
object WeighInReminder {

    fun evaluate(
        now: LocalDateTime,
        schedule: WeighInSchedule = WeighInSchedule.DEFAULT,
        lastWeighIn: LocalDate?,
        lastSnoozeAt: LocalDateTime?,
        snoozeCount: Int,
    ): WeighInPrompt {
        val today = now.toLocalDate()

        if (today.dayOfWeek !in schedule.days) return WeighInPrompt.None

        // Already weighed today: nothing to ask for.
        if (lastWeighIn == today) return WeighInPrompt.None

        // Too early in the day.
        if (now.toLocalTime().isBefore(schedule.promptAt)) return WeighInPrompt.None

        // Snoozed within the last hour — wait out the rest of it.
        if (lastSnoozeAt != null && lastSnoozeAt.toLocalDate() == today) {
            val since = Duration.between(lastSnoozeAt, now).toMinutes()
            if (since < schedule.snoozeMinutes) return WeighInPrompt.None
        }

        return WeighInPrompt.Due(today, snoozeCount)
    }

    /** Wording that changes as it re-asks, so the third prompt does not read like a stuck record. */
    fun message(attempt: Int): String = when {
        attempt <= 0 -> "Time to weigh in. Takes ten seconds and it's what keeps your target honest."
        attempt == 1 -> "Still need that weight — the trend needs feeding."
        attempt == 2 -> "Third ask. Zephyr genuinely can't tell if this is working without it."
        else -> "One number and this stops. Promise."
    }
}
