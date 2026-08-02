package dev.zephyr.core.plan

import dev.zephyr.core.activity.ActivityType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * One slot in the user's weekly skeleton: "I run on Tuesday evenings."
 *
 * The user owns the *shape* of the week — which days, which activity, what time. The app owns the
 * *load* inside it. That division is deliberate: schedules imposed by an app collide with real life
 * and get abandoned, while progression left to the user either stalls or ramps into injury.
 */
data class PlannedSlot(
    val id: Long = 0,
    val dayOfWeek: DayOfWeek,
    val type: ActivityType,
    val timeOfDay: LocalTime,
    val enabled: Boolean = true,
)

data class WeeklyTemplate(val slots: List<PlannedSlot>) {
    fun slotsFor(day: DayOfWeek): List<PlannedSlot> =
        slots.filter { it.enabled && it.dayOfWeek == day }.sortedBy { it.timeOfDay }

    val sessionsPerWeek: Int get() = slots.count { it.enabled }

    companion object {
        /** A balanced default: two runs, a weekend hike, two lifting days. */
        fun default(): WeeklyTemplate = WeeklyTemplate(
            listOf(
                PlannedSlot(dayOfWeek = DayOfWeek.MONDAY, type = ActivityType.STRENGTH, timeOfDay = LocalTime.of(18, 0)),
                PlannedSlot(dayOfWeek = DayOfWeek.TUESDAY, type = ActivityType.RUN, timeOfDay = LocalTime.of(7, 0)),
                PlannedSlot(dayOfWeek = DayOfWeek.THURSDAY, type = ActivityType.STRENGTH, timeOfDay = LocalTime.of(18, 0)),
                PlannedSlot(dayOfWeek = DayOfWeek.FRIDAY, type = ActivityType.RUN, timeOfDay = LocalTime.of(7, 0)),
                PlannedSlot(dayOfWeek = DayOfWeek.SATURDAY, type = ActivityType.HIKE, timeOfDay = LocalTime.of(9, 0)),
            ),
        )
    }
}

/** What the app is asking the user to do in a specific session. */
data class Prescription(
    val slot: PlannedSlot,
    val date: LocalDate,
    val targetDistanceMetres: Double? = null,
    val targetDurationMinutes: Int? = null,
    val targetElevationMetres: Double? = null,
    val isDeload: Boolean = false,
    val headline: String,
    val detail: String,
)

data class FitnessBaseline(
    /** Longest single run in the last month, metres. Null when there's nothing to go on. */
    val longestRunMetres: Double? = null,
    val longestHikeMinutes: Int? = null,
    val weeklyRunVolumeMetres: Double? = null,
)

/**
 * Advances training load week over week inside the user's chosen skeleton.
 *
 * Running volume rises by at most [WEEKLY_INCREASE] and every fourth week is a deload, because the
 * classic failure mode for a motivated beginner is a fortnight of enthusiastic ramping followed by
 * shin splints and a month off — which costs far more progress than the conservative ramp ever did.
 * Sessions alternate between an easy run and a longer one so the week has a genuinely hard day and
 * a genuinely easy one, rather than three identical medium efforts that build nothing.
 */
object ProgressionEngine {

    const val WEEKLY_INCREASE = 0.10
    const val DELOAD_EVERY = 4
    const val DELOAD_FACTOR = 0.70
    const val STARTING_RUN_METRES = 2_000.0
    const val MAX_RUN_METRES = 30_000.0
    const val STARTING_HIKE_MINUTES = 60

    /**
     * @param weekIndex zero-based number of weeks since the plan started
     */
    fun prescriptionsForWeek(
        template: WeeklyTemplate,
        weekStart: LocalDate,
        weekIndex: Int,
        baseline: FitnessBaseline,
    ): List<Prescription> {
        val isDeload = weekIndex > 0 && (weekIndex + 1) % DELOAD_EVERY == 0
        val runSlots = template.slots.filter { it.enabled && it.type == ActivityType.RUN }
            .sortedBy { it.dayOfWeek.value }

        return template.slots.filter { it.enabled }.sortedBy { it.dayOfWeek.value }.map { slot ->
            val date = weekStart.plusDays((slot.dayOfWeek.value - weekStart.dayOfWeek.value).toLong().let {
                if (it < 0) it + 7 else it
            })
            when (slot.type) {
                ActivityType.RUN -> runPrescription(slot, date, weekIndex, isDeload, baseline, runSlots)
                ActivityType.HIKE -> hikePrescription(slot, date, weekIndex, isDeload, baseline)
                ActivityType.STRENGTH -> Prescription(
                    slot = slot,
                    date = date,
                    isDeload = isDeload,
                    headline = if (isDeload) "Strength — light week" else "Strength session",
                    detail = if (isDeload) {
                        "Same movements, drop a set. Recovery is where the adaptation happens."
                    } else {
                        "Add weight where you hit the top of your rep range last time."
                    },
                )
                ActivityType.WALK -> Prescription(
                    slot = slot,
                    date = date,
                    targetDurationMinutes = 30,
                    headline = "Easy walk",
                    detail = "30 minutes at a conversational pace.",
                )
                else -> Prescription(
                    slot = slot,
                    date = date,
                    headline = slot.type.label,
                    detail = "Logged as ${slot.type.label.lowercase()}.",
                )
            }
        }
    }

    private fun runPrescription(
        slot: PlannedSlot,
        date: LocalDate,
        weekIndex: Int,
        isDeload: Boolean,
        baseline: FitnessBaseline,
        runSlots: List<PlannedSlot>,
    ): Prescription {
        val start = baseline.longestRunMetres?.coerceAtLeast(STARTING_RUN_METRES) ?: STARTING_RUN_METRES
        // Compounding growth, but deload weeks don't advance the underlying progression.
        val effectiveWeeks = weekIndex - (weekIndex / DELOAD_EVERY)
        var target = start * Math.pow(1.0 + WEEKLY_INCREASE, effectiveWeeks.toDouble())

        // The last run of the week is the long one; the others sit at ~65% of it.
        val isLongRun = runSlots.isNotEmpty() && runSlots.last().dayOfWeek == slot.dayOfWeek
        if (!isLongRun) target *= 0.65
        if (isDeload) target *= DELOAD_FACTOR

        target = min(target, MAX_RUN_METRES)
        val rounded = roundToNearest(target, 250.0)

        return Prescription(
            slot = slot,
            date = date,
            targetDistanceMetres = rounded,
            isDeload = isDeload,
            headline = when {
                isDeload -> "Easy run — ${formatKm(rounded)}"
                isLongRun -> "Long run — ${formatKm(rounded)}"
                else -> "Run — ${formatKm(rounded)}"
            },
            detail = when {
                isDeload -> "Deload week. Keep it comfortable — this is what lets next week go up."
                isLongRun -> "Steady effort. You should be able to speak in short sentences."
                else -> "Conversational pace. If you're gasping, slow down."
            },
        )
    }

    private fun hikePrescription(
        slot: PlannedSlot,
        date: LocalDate,
        weekIndex: Int,
        isDeload: Boolean,
        baseline: FitnessBaseline,
    ): Prescription {
        val start = baseline.longestHikeMinutes?.coerceAtLeast(STARTING_HIKE_MINUTES) ?: STARTING_HIKE_MINUTES
        val effectiveWeeks = weekIndex - (weekIndex / DELOAD_EVERY)
        var minutes = start * Math.pow(1.0 + WEEKLY_INCREASE, effectiveWeeks.toDouble())
        if (isDeload) minutes *= DELOAD_FACTOR
        val capped = min(minutes, 300.0).roundToInt()

        return Prescription(
            slot = slot,
            date = date,
            targetDurationMinutes = capped,
            targetElevationMetres = (capped * 3.0),
            isDeload = isDeload,
            headline = "Hike — ${capped} min",
            detail = "Find some climbing. Elevation is where hiking earns its calories.",
        )
    }

    private fun roundToNearest(value: Double, step: Double): Double = (value / step).roundToInt() * step

    private fun formatKm(metres: Double): String {
        val km = metres / 1000.0
        return if (km % 1.0 == 0.0) "${km.roundToInt()} km" else "%.1f km".format(km)
    }
}

data class CompletedSession(
    val date: LocalDate,
    val type: ActivityType,
)

data class AdherenceResult(
    val planned: Int,
    val completed: Int,
    val missed: List<Prescription>,
    val percent: Int,
)

/**
 * Compares the week's plan against what actually happened. Feeds both the Plan screen and the
 * missed-session nudge — the app can only chase you about a workout if it knows you skipped it.
 */
object AdherenceCalculator {

    fun evaluate(
        prescriptions: List<Prescription>,
        completed: List<CompletedSession>,
        upTo: LocalDate,
    ): AdherenceResult {
        val due = prescriptions.filter { !it.date.isAfter(upTo) }
        if (due.isEmpty()) {
            return AdherenceResult(0, 0, emptyList(), 100)
        }

        val remaining = completed.toMutableList()
        val missed = mutableListOf<Prescription>()

        for (prescription in due) {
            // A session counts if it happened on the day or the day after — real life slides.
            val match = remaining.firstOrNull {
                it.type == prescription.slot.type &&
                    ChronoUnit.DAYS.between(prescription.date, it.date) in 0..1
            }
            if (match != null) remaining.remove(match) else missed += prescription
        }

        val done = due.size - missed.size
        return AdherenceResult(
            planned = due.size,
            completed = done,
            missed = missed,
            percent = (done * 100.0 / due.size).roundToInt(),
        )
    }
}

/** Double progression for lifting: earn the rep range, then earn the weight. */
object StrengthProgression {

    data class SetResult(val reps: Int, val weightKg: Double)

    data class NextPrescription(
        val weightKg: Double,
        val targetReps: Int,
        val isDeload: Boolean,
        val note: String,
    )

    const val UPPER_INCREMENT_KG = 2.5
    const val LOWER_INCREMENT_KG = 5.0
    const val DELOAD_FACTOR = 0.9

    /**
     * @param lastSession sets from the most recent time this exercise was performed
     * @param repRange the working range, e.g. 8..12
     * @param consecutiveFailures times in a row the user has missed the bottom of the range
     */
    fun next(
        lastSession: List<SetResult>,
        repRange: IntRange,
        consecutiveFailures: Int = 0,
        isLowerBody: Boolean = false,
    ): NextPrescription? {
        if (lastSession.isEmpty()) return null
        val workingWeight = lastSession.maxOf { it.weightKg }
        val topSets = lastSession.filter { it.weightKg == workingWeight }
        val minReps = topSets.minOf { it.reps }
        val increment = if (isLowerBody) LOWER_INCREMENT_KG else UPPER_INCREMENT_KG

        return when {
            // Stalled twice — back off rather than grinding into a plateau.
            consecutiveFailures >= 2 -> NextPrescription(
                weightKg = roundToPlate(workingWeight * DELOAD_FACTOR),
                targetReps = repRange.first,
                isDeload = true,
                note = "Stalled twice. Dropping 10% to rebuild momentum — this is normal.",
            )
            minReps >= repRange.last -> NextPrescription(
                weightKg = workingWeight + increment,
                targetReps = repRange.first,
                isDeload = false,
                note = "You owned every set. Add ${increment}kg.",
            )
            else -> NextPrescription(
                weightKg = workingWeight,
                targetReps = min(minReps + 1, repRange.last),
                isDeload = false,
                note = "Same weight — add a rep to each set.",
            )
        }
    }

    /** Real gyms have 1.25 kg plates at best. */
    private fun roundToPlate(weight: Double): Double = max((weight / 1.25).roundToInt() * 1.25, 1.25)
}
