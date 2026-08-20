package dev.zephyr.core.fasting

import dev.zephyr.core.model.GoalPace
import java.time.Duration
import java.time.LocalDateTime
import kotlin.math.roundToInt

/**
 * A fasting protocol, named the way people actually say it: "16:8" means sixteen hours fasting and
 * an eight-hour eating window.
 */
enum class FastingPlan(val fastingHours: Int, val label: String, val blurb: String) {
    OFF(0, "Not fasting", "Eat whenever you like"),
    TWELVE(12, "12:12", "Just a clean overnight gap — easiest place to start"),
    FOURTEEN(14, "14:10", "Dinner at 8, breakfast at 10. Barely feels like a rule"),
    SIXTEEN(16, "16:8", "The common one. Skip breakfast, eat noon to eight"),
    EIGHTEEN(18, "18:6", "Serious. One big meal and a smaller one"),
    TWENTY(20, "20:4", "Hard. Only worth it if the shorter ones stopped working");

    val eatingHours: Int get() = 24 - fastingHours
}

enum class FastingPhase {
    /** No plan chosen. */
    OFF,

    /** Inside the fast — the window has not opened yet. */
    FASTING,

    /** The window is open and there is still time left in it. */
    EATING,

    /** Past the eating window's close, so the next fast has effectively started. */
    WINDOW_CLOSED,
}

/**
 * Where the user stands right now.
 *
 * @param opensAt when eating becomes allowed again; null when there is nothing to wait for
 * @param fraction 0..1 through the current fast, for a ring or bar
 */
data class FastingStatus(
    val plan: FastingPlan,
    val phase: FastingPhase,
    val lastMealAt: LocalDateTime?,
    val opensAt: LocalDateTime?,
    val elapsedMinutes: Long,
    val remainingMinutes: Long,
    val fraction: Float,
) {
    val isFasting: Boolean get() = phase == FastingPhase.FASTING

    /** "3h 20m" — the only format anyone reads on a countdown. */
    val remainingLabel: String get() = formatMinutes(remainingMinutes)
    val elapsedLabel: String get() = formatMinutes(elapsedMinutes)
}

fun formatMinutes(total: Long): String {
    val safe = total.coerceAtLeast(0)
    val hours = safe / 60
    val minutes = safe % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

/**
 * Intermittent fasting, computed from the one fact the app already knows: when food was last logged.
 *
 * There is deliberately no separate "start fast" button. A fast that has to be declared is a fast
 * people forget to declare, and then the app is confidently wrong. Eating is what ends a fast, and
 * logging food is something the user is doing anyway — so the clock derives itself from the ledger
 * and cannot drift out of step with what was actually eaten.
 */
object Fasting {

    /**
     * A starting protocol matched to the goal.
     *
     * Deliberately conservative. Fasting is a tool for making a calorie deficit easier to hold, not
     * a competition, and someone who starts at 20:4 abandons the whole idea inside a week. The
     * longer windows are available in the picker for anyone who wants them.
     */
    fun recommend(pace: GoalPace): FastingPlan = when {
        !pace.isDeficit -> FastingPlan.TWELVE
        pace.kgPerWeek <= -0.9 / 2.2046226218 -> FastingPlan.SIXTEEN
        else -> FastingPlan.FOURTEEN
    }

    fun status(
        plan: FastingPlan,
        lastMealAt: LocalDateTime?,
        now: LocalDateTime,
    ): FastingStatus {
        if (plan == FastingPlan.OFF) {
            return FastingStatus(plan, FastingPhase.OFF, lastMealAt, null, 0, 0, 0f)
        }

        // Nothing logged yet: there is no honest clock to show, so the fast reads as not started
        // rather than inventing a start time.
        if (lastMealAt == null) {
            return FastingStatus(plan, FastingPhase.OFF, null, null, 0, 0, 0f)
        }

        val opensAt = lastMealAt.plusHours(plan.fastingHours.toLong())
        val closesAt = opensAt.plusHours(plan.eatingHours.toLong())
        val elapsed = Duration.between(lastMealAt, now).toMinutes().coerceAtLeast(0)
        val targetMinutes = plan.fastingHours * 60L

        return when {
            now.isBefore(opensAt) -> FastingStatus(
                plan = plan,
                phase = FastingPhase.FASTING,
                lastMealAt = lastMealAt,
                opensAt = opensAt,
                elapsedMinutes = elapsed,
                remainingMinutes = Duration.between(now, opensAt).toMinutes().coerceAtLeast(0),
                fraction = (elapsed.toFloat() / targetMinutes).coerceIn(0f, 1f),
            )

            now.isBefore(closesAt) -> FastingStatus(
                plan = plan,
                phase = FastingPhase.EATING,
                lastMealAt = lastMealAt,
                opensAt = opensAt,
                elapsedMinutes = elapsed,
                remainingMinutes = Duration.between(now, closesAt).toMinutes().coerceAtLeast(0),
                fraction = 1f,
            )

            else -> FastingStatus(
                plan = plan,
                phase = FastingPhase.WINDOW_CLOSED,
                lastMealAt = lastMealAt,
                opensAt = opensAt,
                elapsedMinutes = elapsed,
                remainingMinutes = 0,
                fraction = 1f,
            )
        }
    }

    /**
     * What the fasting card should say. Encouragement while it is hard, and a plain statement of
     * fact once it is not — never a scold for eating, which is the behaviour the app is trying to
     * make sustainable rather than punish.
     */
    fun message(status: FastingStatus): String = when (status.phase) {
        FastingPhase.OFF -> "Log a meal and the clock starts itself."

        FastingPhase.FASTING -> when {
            status.remainingMinutes <= 30 -> "Almost there — ${status.remainingLabel} to go."
            status.fraction >= 0.75f -> "${status.remainingLabel} left. The hard part is behind you."
            status.fraction >= 0.4f -> "${status.remainingLabel} to go. Water and coffee are free."
            else -> "Fasting. Window opens in ${status.remainingLabel}."
        }

        FastingPhase.EATING -> "Window is open — ${status.remainingLabel} left in it."

        FastingPhase.WINDOW_CLOSED ->
            "Window closed. Nothing wrong with eating anyway; the next one starts when you do."
    }

    /** Hours between the last meal and now, for display next to a weigh-in or a plan. */
    fun hoursSince(lastMealAt: LocalDateTime?, now: LocalDateTime): Int? {
        if (lastMealAt == null) return null
        return (Duration.between(lastMealAt, now).toMinutes() / 60.0).roundToInt()
    }
}
