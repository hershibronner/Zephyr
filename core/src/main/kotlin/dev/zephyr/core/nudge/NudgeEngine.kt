package dev.zephyr.core.nudge

import dev.zephyr.core.ledger.EnergyBalance
import dev.zephyr.core.plan.Prescription
import dev.zephyr.core.steps.StepPace
import dev.zephyr.core.steps.StepPaceStatus
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Everything the coach is allowed to know when deciding whether to speak.
 *
 * Passing a snapshot rather than repositories keeps the decision logic a pure function, which is
 * the whole point: notification behaviour is otherwise almost impossible to test, and untested
 * notification logic is how apps end up buzzing at 3am or nagging about a run the user already did.
 */
data class CoachState(
    val now: LocalDateTime,
    val balance: EnergyBalance,
    val stepStatus: StepPaceStatus,
    val stepsInLastHour: Int,
    val streakDays: Int,
    val streakSecuredToday: Boolean,
    val todaysPrescriptions: List<Prescription> = emptyList(),
    val completedSessionToday: Boolean = false,
    val missedYesterday: List<Prescription> = emptyList(),
    val lastFoodLogAt: LocalDateTime? = null,
    val daysSinceWeighIn: Int? = null,
    val alreadySentKeys: Set<String> = emptySet(),
    val sentTodayCount: Int = 0,
    val isTrackingSession: Boolean = false,
)

/**
 * Decides what the app says and when.
 *
 * The engine is built around one belief: a notification is only worth sending if it arrives while
 * the user can still change the outcome, and if it names the next physical action. "You're 1,400
 * steps short — that's a 13 minute walk" is actionable at 6pm; the same fact at 11pm is just
 * criticism. Everything below is filtered through that, plus quiet hours, a daily budget, and
 * deduplication, because the fastest way to make this app useless is to get its notifications
 * turned off in week two.
 */
object NudgeEngine {

    private val MORNING_WINDOW = LocalTime.of(7, 0)..LocalTime.of(10, 0)
    private val MIDDAY_WINDOW = LocalTime.of(12, 0)..LocalTime.of(16, 0)
    private val EVENING_WINDOW = LocalTime.of(17, 30)..LocalTime.of(20, 30)
    private val WEEKLY_REVIEW_WINDOW = LocalTime.of(18, 0)..LocalTime.of(20, 0)

    private const val INACTIVITY_STEP_THRESHOLD = 250
    private const val BEHIND_PACE_FRACTION = 0.15
    private const val SESSION_REMINDER_MINUTES = 30L

    fun evaluate(state: CoachState, settings: NudgeSettings): List<Nudge> {
        val time = state.now.toLocalTime()
        val date = state.now.toLocalDate()

        // A tracked session in progress means the user is already doing the thing. Saying anything
        // now is noise at best and a distraction mid-run at worst.
        if (state.isTrackingSession) return emptyList()

        val candidates = buildList {
            addAll(celebrations(state, date.toString()))

            if (settings.quietHours.contains(time)) return@buildList

            morningPlan(state, settings, time, date.toString())?.let(::add)
            addAll(sessionReminders(state, time, date.toString()))
            addAll(mealReminders(state, time, date.toString()))
            inactivity(state, settings, time, date.toString())?.let(::add)
            behindOnSteps(state, settings, time, date.toString())?.let(::add)
            closeTheRing(state, settings, time, date.toString())?.let(::add)
            streakAtRisk(state, settings, time, date.toString())?.let(::add)
            missedSession(state, settings, time, date.toString())?.let(::add)
            weighInReminder(state, time, date.toString())?.let(::add)
            weeklyReview(state, time, date.toString())?.let(::add)
        }

        val allowed = candidates
            .filter { it.category in settings.enabledCategories }
            .filter { it.key !in state.alreadySentKeys }

        val (celebrations, demands) = allowed.partition { it.isCelebration }
        val budget = (settings.maxPerDay - state.sentTodayCount).coerceAtLeast(0)

        // When the budget is tight, the most urgent thing should survive, not the first thing found.
        val rankedDemands = demands
            .sortedByDescending { it.priority.ordinal }
            .take(budget)

        return celebrations + rankedDemands
    }

    private fun celebrations(state: CoachState, day: String): List<Nudge> = buildList {
        val steps = state.stepStatus
        if (steps.goal > 0 && steps.steps >= steps.goal) {
            add(
                Nudge(
                    key = "goal-steps-$day",
                    category = NudgeCategory.CELEBRATION,
                    title = "Step goal smashed 🎯",
                    body = "${format(steps.steps)} steps. That's the goal, done.",
                    priority = NudgePriority.LOW,
                    isCelebration = true,
                ),
            )
        }
    }

    private fun morningPlan(state: CoachState, settings: NudgeSettings, time: LocalTime, day: String): Nudge? {
        if (time !in MORNING_WINDOW) return null

        val session = state.todaysPrescriptions.firstOrNull()
        val body = buildString {
            append("${format(state.stepStatus.goal)} steps · ${format(state.balance.targetKcal)} kcal")
            append(" · ${state.balance.proteinTargetG}g protein")
            if (session != null) {
                append("\n${session.headline} at ${formatTime(session.slot.timeOfDay)}")
            }
        }
        val title = if (session != null) "Today: ${session.headline}" else "Here's today"

        return Nudge(
            key = "morning-$day",
            category = NudgeCategory.PLAN,
            title = title,
            body = body,
            priority = NudgePriority.DEFAULT,
            actions = listOf(NudgeAction.OPEN_TODAY),
        )
    }

    private fun sessionReminders(state: CoachState, time: LocalTime, day: String): List<Nudge> =
        state.todaysPrescriptions.mapNotNull { prescription ->
            val minutesUntil = Duration.between(time, prescription.slot.timeOfDay).toMinutes()
            if (minutesUntil !in 0..SESSION_REMINDER_MINUTES) return@mapNotNull null
            if (state.completedSessionToday) return@mapNotNull null

            Nudge(
                key = "session-${prescription.slot.id}-$day",
                category = NudgeCategory.SESSION,
                title = "${prescription.headline} in $minutesUntil min",
                body = prescription.detail,
                priority = NudgePriority.HIGH,
                actions = listOf(NudgeAction.START_SESSION, NudgeAction.SNOOZE),
            )
        }

    private fun mealReminders(state: CoachState, time: LocalTime, day: String): List<Nudge> {
        val hoursSinceLog = state.lastFoodLogAt
            ?.let { Duration.between(it, state.now).toHours() }

        // Only prompt at natural checkpoints, and only when nothing has been logged recently.
        val checkpoint = when {
            time in LocalTime.of(13, 30)..LocalTime.of(14, 30) -> "lunch"
            time in LocalTime.of(19, 30)..LocalTime.of(20, 30) -> "dinner"
            else -> return emptyList()
        }
        if (state.balance.consumedKcal > 0 && (hoursSinceLog == null || hoursSinceLog < 4)) return emptyList()

        return listOf(
            Nudge(
                key = "meal-$checkpoint-$day",
                category = NudgeCategory.FOOD,
                title = "Log your $checkpoint",
                body = "Takes ten seconds and it's the difference between guessing and knowing.",
                priority = NudgePriority.LOW,
                actions = listOf(NudgeAction.LOG_FOOD),
            ),
        )
    }

    private fun inactivity(state: CoachState, settings: NudgeSettings, time: LocalTime, day: String): Nudge? {
        if (!settings.allowsInactivityNudges) return null
        if (time.hour !in 9..20) return null
        if (state.stepsInLastHour >= INACTIVITY_STEP_THRESHOLD) return null
        if (state.stepStatus.steps >= state.stepStatus.goal) return null

        return Nudge(
            key = "inactive-$day-${time.hour}",
            category = NudgeCategory.MOVEMENT,
            title = "You've been still for an hour",
            body = "Five minutes on your feet. That's all this is asking.",
            priority = NudgePriority.LOW,
            actions = listOf(NudgeAction.START_WALK, NudgeAction.SNOOZE),
        )
    }

    private fun behindOnSteps(state: CoachState, settings: NudgeSettings, time: LocalTime, day: String): Nudge? {
        if (!settings.allowsChasing) return null
        if (time !in MIDDAY_WINDOW) return null

        val status = state.stepStatus
        if (status.goal <= 0) return null
        if (status.deficit < status.goal * BEHIND_PACE_FRACTION) return null

        return Nudge(
            key = "behind-steps-$day",
            category = NudgeCategory.MOVEMENT,
            title = "You're ${format(status.deficit)} steps behind pace",
            body = "Still early. A ${StepPace.minutesToWalk(status.deficit)} minute walk puts you level.",
            priority = NudgePriority.DEFAULT,
            actions = listOf(NudgeAction.START_WALK),
        )
    }

    private fun closeTheRing(state: CoachState, settings: NudgeSettings, time: LocalTime, day: String): Nudge? {
        if (!settings.allowsChasing) return null
        if (time !in EVENING_WINDOW) return null

        val status = state.stepStatus
        val remaining = status.remaining
        if (remaining <= 0) return null
        // Beyond a certain gap this stops being an invitation and becomes a reproach.
        if (remaining > status.goal * 0.45) return null

        val minutes = StepPace.minutesToWalk(remaining)
        return Nudge(
            key = "close-ring-$day",
            category = NudgeCategory.MOVEMENT,
            title = "${format(remaining)} steps to go",
            body = "That's about $minutes minutes. Close it out.",
            priority = NudgePriority.HIGH,
            actions = listOf(NudgeAction.START_WALK),
        )
    }

    private fun streakAtRisk(state: CoachState, settings: NudgeSettings, time: LocalTime, day: String): Nudge? {
        if (!settings.allowsChasing) return null
        if (state.streakDays < 3 || state.streakSecuredToday) return null
        if (time !in EVENING_WINDOW) return null

        return Nudge(
            key = "streak-risk-$day",
            category = NudgeCategory.STREAK,
            title = "${state.streakDays} days on the line",
            body = "Log today's food or get your steps in and the streak holds.",
            priority = NudgePriority.HIGH,
            actions = listOf(NudgeAction.LOG_FOOD, NudgeAction.START_WALK),
        )
    }

    private fun missedSession(state: CoachState, settings: NudgeSettings, time: LocalTime, day: String): Nudge? {
        if (!settings.allowsChasing) return null
        if (state.missedYesterday.isEmpty()) return null
        if (time !in MORNING_WINDOW) return null

        val missed = state.missedYesterday.first()
        return Nudge(
            key = "missed-$day",
            category = NudgeCategory.SESSION,
            title = "Yesterday's ${missed.slot.type.label.lowercase()} didn't happen",
            body = "One session doesn't undo anything. Want to move it to today?",
            priority = NudgePriority.DEFAULT,
            actions = listOf(NudgeAction.VIEW_PLAN),
        )
    }

    private fun weighInReminder(state: CoachState, time: LocalTime, day: String): Nudge? {
        val days = state.daysSinceWeighIn ?: return null
        if (days < 3) return null
        if (time !in MORNING_WINDOW) return null

        return Nudge(
            key = "weigh-in-$day",
            category = NudgeCategory.PLAN,
            title = "Time to step on the scale",
            body = "It's been $days days. The trend line needs data to stay honest.",
            priority = NudgePriority.LOW,
            actions = listOf(NudgeAction.LOG_WEIGHT),
        )
    }

    private fun weeklyReview(state: CoachState, time: LocalTime, day: String): Nudge? {
        if (state.now.dayOfWeek != DayOfWeek.SUNDAY) return null
        if (time !in WEEKLY_REVIEW_WINDOW) return null

        return Nudge(
            key = "weekly-review-$day",
            category = NudgeCategory.PLAN,
            title = "Your week, in one screen",
            body = "See what moved, what didn't, and what next week looks like.",
            priority = NudgePriority.DEFAULT,
            actions = listOf(NudgeAction.OPEN_TODAY),
        )
    }

    private fun format(value: Int): String =
        if (value >= 1000) "%,d".format(value) else value.toString()

    private fun formatTime(time: LocalTime): String {
        val hour = if (time.hour % 12 == 0) 12 else time.hour % 12
        val suffix = if (time.hour < 12) "am" else "pm"
        return if (time.minute == 0) "$hour$suffix" else "%d:%02d%s".format(hour, time.minute, suffix)
    }
}
