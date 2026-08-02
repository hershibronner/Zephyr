package dev.zephyr.core.ledger

import dev.zephyr.core.model.KCAL_PER_KG
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

data class MacroTotals(
    val proteinG: Int = 0,
    val carbsG: Int = 0,
    val fatG: Int = 0,
) {
    operator fun plus(other: MacroTotals) = MacroTotals(
        proteinG + other.proteinG,
        carbsG + other.carbsG,
        fatG + other.fatG,
    )
}

enum class BalanceState {
    /** Comfortably within the target. */
    ON_TRACK,

    /** Close to the target — worth knowing, not worth alarming about. */
    NEARLY_THERE,

    /** Past the target for the day. */
    OVER,

    /** Well under target. Chronic under-eating is a problem too, not a win. */
    UNDER_FUELLED,
}

/**
 * The single daily number the whole app orbits.
 *
 * @param targetKcal the day's budget from [dev.zephyr.core.energy.CalorieTarget]
 * @param consumedKcal logged food
 * @param exerciseKcal *net* burn from logged sessions (see [dev.zephyr.core.activity.ActivityBurn.netKcal])
 */
data class EnergyBalance(
    val date: LocalDate,
    val targetKcal: Int,
    val consumedKcal: Int,
    val exerciseKcal: Int,
    val proteinTargetG: Int,
    val macros: MacroTotals,
    /** Maintenance calories for the day, kept alongside the target so progress can be projected. */
    val maintenanceKcal: Int = targetKcal,
) {
    /** What's left to eat today. Negative means over budget. */
    val remainingKcal: Int get() = targetKcal + exerciseKcal - consumedKcal

    val adjustedTargetKcal: Int get() = targetKcal + exerciseKcal

    val fractionConsumed: Float
        get() = if (adjustedTargetKcal <= 0) 0f else (consumedKcal.toFloat() / adjustedTargetKcal)

    val proteinFraction: Float
        get() = if (proteinTargetG <= 0) 0f else (macros.proteinG.toFloat() / proteinTargetG).coerceIn(0f, 1f)

    val proteinRemainingG: Int get() = max(proteinTargetG - macros.proteinG, 0)

    val state: BalanceState
        get() = when {
            consumedKcal == 0 -> BalanceState.ON_TRACK
            remainingKcal < -50 -> BalanceState.OVER
            remainingKcal <= 100 -> BalanceState.NEARLY_THERE
            fractionConsumed < 0.6f -> BalanceState.UNDER_FUELLED
            else -> BalanceState.ON_TRACK
        }
}

data class WeeklySummary(
    val daysLogged: Int,
    val averageIntakeKcal: Int,
    val averageBurnKcal: Int,
    val averageTargetKcal: Int,
    /** Mean daily surplus/deficit against target; negative is under budget. */
    val averageDeltaKcal: Int,
    val adherencePercent: Int,
    val projectedWeeklyKg: Double,
) {
    val isEmpty: Boolean get() = daysLogged == 0
}

object LedgerSummary {

    /** A day counts as adherent if intake landed within this fraction of the adjusted target. */
    const val ADHERENCE_TOLERANCE = 0.10

    /**
     * The window a weekly summary should cover: the last [count] *complete* days, ending yesterday.
     *
     * Today is deliberately excluded. A day in progress has only part of its food logged, so
     * including it drags the average intake down and reports a deficit far deeper than the user is
     * actually running — at breakfast it would claim they are losing three times their target rate.
     * A projection that is wrong every morning is worse than no projection.
     */
    fun lastCompleteDays(today: LocalDate, count: Int = 7): List<LocalDate> =
        (count downTo 1).map { today.minusDays(it.toLong()) }

    fun weekly(balances: List<EnergyBalance>): WeeklySummary {
        val logged = balances.filter { it.consumedKcal > 0 }
        if (logged.isEmpty()) {
            return WeeklySummary(0, 0, 0, 0, 0, 0, 0.0)
        }

        val avgIntake = logged.sumOf { it.consumedKcal }.toDouble() / logged.size
        val avgBurn = logged.sumOf { it.exerciseKcal }.toDouble() / logged.size
        val avgTarget = logged.sumOf { it.targetKcal }.toDouble() / logged.size
        val avgDelta = logged.sumOf { (it.consumedKcal - it.adjustedTargetKcal) }.toDouble() / logged.size

        val adherent = logged.count { isAdherent(it) }

        // Energy balance against *maintenance*, not against the target: eating exactly to a
        // 500 kcal deficit target should project real loss, not zero change.
        val avgImbalance = logged.sumOf {
            (it.consumedKcal - it.maintenanceKcal - it.exerciseKcal)
        }.toDouble() / logged.size

        return WeeklySummary(
            daysLogged = logged.size,
            averageIntakeKcal = avgIntake.roundToInt(),
            averageBurnKcal = avgBurn.roundToInt(),
            averageTargetKcal = avgTarget.roundToInt(),
            averageDeltaKcal = avgDelta.roundToInt(),
            adherencePercent = (adherent * 100.0 / logged.size).roundToInt(),
            // Projected from behaviour. The scale's own verdict lives in WeightTrend, and where the
            // two disagree over several weeks, WeightTrend is the one telling the truth.
            projectedWeeklyKg = avgImbalance * 7.0 / KCAL_PER_KG,
        )
    }

    fun weeklyFor(today: LocalDate, balanceOn: (LocalDate) -> EnergyBalance): WeeklySummary =
        weekly(lastCompleteDays(today).map(balanceOn))

    fun isAdherent(balance: EnergyBalance): Boolean {
        if (balance.consumedKcal <= 0) return false
        val tolerance = balance.adjustedTargetKcal * ADHERENCE_TOLERANCE
        return abs(balance.consumedKcal - balance.adjustedTargetKcal) <= tolerance ||
            balance.consumedKcal < balance.adjustedTargetKcal
    }
}
