package dev.zephyr.core.nudge

import java.time.LocalTime

/**
 * Notification categories. These map one-to-one onto Android notification channels so a user can
 * silence meal reminders without also silencing the reminder for the run they asked to be reminded
 * about. Coarse "all notifications" control is how an app gets muted wholesale and then quietly
 * stops working.
 */
enum class NudgeCategory(val channelId: String, val channelName: String, val description: String) {
    PLAN("plan", "Daily plan", "Your morning briefing and weekly review"),
    SESSION("session", "Workout reminders", "Reminders for sessions you've scheduled"),
    MOVEMENT("movement", "Move reminders", "Nudges when you've been still too long or are behind on steps"),
    FOOD("food", "Meal logging", "Reminders to log what you've eaten"),
    STREAK("streak", "Streak protection", "Warnings when a streak is about to break"),
    CELEBRATION("celebration", "Wins", "Goals hit, records broken, milestones reached"),
}

enum class NudgePriority { LOW, DEFAULT, HIGH }

/** Deep-link targets, so tapping a notification lands where the user can act immediately. */
enum class NudgeAction(val label: String, val route: String) {
    OPEN_TODAY("Open", "today"),
    LOG_FOOD("Log it", "food/add"),
    START_WALK("Start walk", "move/start?type=WALK"),
    START_SESSION("Start", "move/start"),
    LOG_WEIGHT("Log weight", "progress/weigh-in"),
    VIEW_PLAN("View plan", "plan"),
    SNOOZE("Snooze 1h", "snooze"),
}

data class Nudge(
    /** Stable per-occurrence identity, used to avoid sending the same thing twice. */
    val key: String,
    val category: NudgeCategory,
    val title: String,
    val body: String,
    val priority: NudgePriority = NudgePriority.DEFAULT,
    val actions: List<NudgeAction> = listOf(NudgeAction.OPEN_TODAY),
    /** Celebrations are rewards, not demands, so they don't count against the daily budget. */
    val isCelebration: Boolean = false,
)

enum class CoachTone(val label: String, val description: String) {
    GENTLE("Gentle", "A morning plan and a weekly review. Encouragement only."),
    BALANCED("Balanced", "Plan, reminders, and a push when you're falling behind."),
    RELENTLESS("Relentless", "Everything, including hourly move reminders. No hiding."),
}

data class QuietHours(val start: LocalTime, val end: LocalTime) {
    /** Handles windows that wrap past midnight, which is the normal case for sleep. */
    fun contains(time: LocalTime): Boolean =
        if (start <= end) time >= start && time < end else time >= start || time < end

    companion object {
        val DEFAULT = QuietHours(LocalTime.of(21, 30), LocalTime.of(7, 0))
    }
}

data class NudgeSettings(
    val tone: CoachTone = CoachTone.BALANCED,
    val quietHours: QuietHours = QuietHours.DEFAULT,
    val enabledCategories: Set<NudgeCategory> = NudgeCategory.entries.toSet(),
    val maxPerDay: Int = tone.defaultMaxPerDay(),
) {
    val allowsInactivityNudges: Boolean get() = tone == CoachTone.RELENTLESS
    val allowsChasing: Boolean get() = tone != CoachTone.GENTLE
}

fun CoachTone.defaultMaxPerDay(): Int = when (this) {
    CoachTone.GENTLE -> 2
    CoachTone.BALANCED -> 5
    CoachTone.RELENTLESS -> 12
}
