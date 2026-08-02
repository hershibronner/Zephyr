package app.zephyr.fitness.domain

import app.zephyr.fitness.data.db.DailyStepsEntity
import app.zephyr.fitness.data.db.FoodLogEntity
import app.zephyr.fitness.data.db.SessionEntity
import app.zephyr.fitness.data.db.WeightEntity
import app.zephyr.fitness.data.db.ZephyrDatabase
import app.zephyr.fitness.data.prefs.SettingsStore
import app.zephyr.fitness.data.prefs.ZephyrSettings
import dev.zephyr.core.energy.AdaptiveTdee
import dev.zephyr.core.energy.AdaptiveTdeeResult
import dev.zephyr.core.energy.BasalMetabolicRate
import dev.zephyr.core.energy.CalorieTarget
import dev.zephyr.core.energy.CalorieTargetResult
import dev.zephyr.core.energy.DailyEnergyRecord
import dev.zephyr.core.energy.MacroCalculator
import dev.zephyr.core.energy.MacroTargets
import dev.zephyr.core.energy.TargetAdjustment
import dev.zephyr.core.ledger.EnergyBalance
import dev.zephyr.core.ledger.LedgerSummary
import dev.zephyr.core.ledger.MacroTotals
import dev.zephyr.core.ledger.WeeklySummary
import dev.zephyr.core.steps.DailySteps
import dev.zephyr.core.steps.StepGoalEngine
import dev.zephyr.core.steps.StepPace
import dev.zephyr.core.steps.StepPaceStatus
import dev.zephyr.core.streak.DayOutcome
import dev.zephyr.core.streak.StreakCalculator
import dev.zephyr.core.streak.StreakResult
import dev.zephyr.core.trend.WeightEntry as TrendEntry
import dev.zephyr.core.trend.WeightTrend
import dev.zephyr.core.trend.WeightTrendResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/** Everything the Today screen renders, resolved into one consistent snapshot. */
data class TodayState(
    val date: LocalDate,
    val ready: Boolean,
    val balance: EnergyBalance,
    val stepStatus: StepPaceStatus,
    val macroTargets: MacroTargets,
    val target: CalorieTargetResult,
    val adaptiveTdee: AdaptiveTdeeResult?,
    val trend: WeightTrendResult,
    val streak: StreakResult,
    val week: WeeklySummary,
    val sessionsToday: Int,
    val entries: List<FoodLogEntity>,
    val suggestedStepGoal: Int?,
    val settings: ZephyrSettings,
) {
    val steps: Int get() = stepStatus.steps
    val trendWeightKg: Double? get() = trend.currentTrendKg
    val weeklyRateKg: Double? get() = trend.weeklyRateKg
}

/**
 * Assembles the daily ledger.
 *
 * Everything derives from a single history window read once, so the ring, the streak, the weekly
 * summary and the adaptive calibration can never disagree with each other about what happened —
 * the class of bug where the headline says you're on track and the chart below says you aren't.
 *
 * All arithmetic lives in `dev.zephyr.core`; this class only feeds it. That split is what lets the
 * numbers be tested without an Android device, so what remains here is deliberately dull plumbing.
 */
class TodayRepository(
    private val db: ZephyrDatabase,
    private val settingsStore: SettingsStore,
) {

    companion object {
        /** Long enough for streaks and adaptive calibration, short enough to stay cheap. */
        const val HISTORY_DAYS = 120L
    }

    fun observe(today: LocalDate, now: () -> LocalTime = LocalTime::now): Flow<TodayState> {
        val from = today.minusDays(HISTORY_DAYS)
        return combine(
            settingsStore.settings,
            db.foodDao().observeLogBetween(from, today),
            db.sessionDao().observeBetween(from, today),
            db.stepsDao().observeRecent(HISTORY_DAYS.toInt()),
            db.weightDao().observeAll(),
        ) { settings, food, sessions, steps, weights ->
            build(today, now(), settings, food, sessions, steps, weights)
        }
    }

    private fun build(
        today: LocalDate,
        time: LocalTime,
        settings: ZephyrSettings,
        food: List<FoodLogEntity>,
        sessions: List<SessionEntity>,
        stepHistory: List<DailyStepsEntity>,
        weights: List<WeightEntity>,
    ): TodayState {
        val trend = WeightTrend.calculate(weights.map { TrendEntry(it.date, it.weightKg) })
        val todaysFood = food.filter { it.date == today }
        val todaysSessions = sessions.filter { it.date == today }
        val stepsToday = stepHistory.firstOrNull { it.date == today }?.steps ?: 0
        val stepStatus = StepPace.status(stepsToday, settings.stepGoal, time)

        val profile = settings.profile
        if (profile == null) {
            // Pre-onboarding there is no honest target to show, so the screen renders a neutral
            // zero state rather than inventing one from defaults that fit nobody.
            return TodayState(
                date = today,
                ready = false,
                balance = EnergyBalance(today, 0, 0, 0, 0, MacroTotals()),
                stepStatus = stepStatus,
                macroTargets = MacroTargets(0, 0, 0, 0),
                target = CalorieTargetResult(0, 0, 0, TargetAdjustment.NONE, 0.0),
                adaptiveTdee = null,
                trend = trend,
                streak = StreakResult(0, 0, false, null),
                week = LedgerSummary.weekly(emptyList()),
                sessionsToday = todaysSessions.size,
                entries = todaysFood,
                suggestedStepGoal = null,
                settings = settings,
            )
        }

        val bmr = BasalMetabolicRate.forProfile(profile, today)
        val formulaTdee = bmr * profile.activityLevel.multiplier

        val adaptive = calibrate(today, formulaTdee, food, sessions, trend)
        val maintenance = if (settings.useAdaptiveTdee && adaptive?.measuredTdeeKcal != null) {
            adaptive.tdeeKcal.toDouble()
        } else {
            formulaTdee
        }

        val target = CalorieTarget.forProfile(profile, today, maintenanceOverride = maintenance)
        val targetKcal = settings.calorieTargetOverride ?: target.targetKcal

        val macros = MacroCalculator
            .calculate(targetKcal, profile.goalWeightKg, profile.goalPace.isDeficit)
            .let { computed ->
                settings.proteinTargetOverride?.let { computed.copy(proteinG = it) } ?: computed
            }

        val balance = balanceFor(today, todaysFood, todaysSessions, targetKcal, macros.proteinG, maintenance)

        val stepsByDate = stepHistory.associate { it.date to it.steps }
        val streak = streakFrom(today, food, sessions, stepsByDate, settings, maintenance, profile.goalPace.isDeficit)
        val week = weekFrom(today, food, sessions, targetKcal, macros.proteinG, maintenance)

        val suggestedGoal = if (settings.stepGoalIsManual) {
            null
        } else {
            StepGoalEngine
                .suggest(stepHistory.map { DailySteps(it.date, it.steps) }, settings.stepGoal)
                .goal
                .takeIf { it != settings.stepGoal }
        }

        return TodayState(
            date = today,
            ready = true,
            balance = balance,
            stepStatus = stepStatus,
            macroTargets = macros,
            target = target,
            adaptiveTdee = adaptive,
            trend = trend,
            streak = streak,
            week = week,
            sessionsToday = todaysSessions.size,
            entries = todaysFood,
            suggestedStepGoal = suggestedGoal,
            settings = settings,
        )
    }

    private fun balanceFor(
        date: LocalDate,
        food: List<FoodLogEntity>,
        sessions: List<SessionEntity>,
        targetKcal: Int,
        proteinTargetG: Int,
        maintenance: Double,
    ): EnergyBalance {
        val totals = food.fold(MacroTotals()) { acc, entry ->
            acc + MacroTotals(
                proteinG = entry.proteinG.roundToInt(),
                carbsG = entry.carbsG.roundToInt(),
                fatG = entry.fatG.roundToInt(),
            )
        }
        return EnergyBalance(
            date = date,
            targetKcal = targetKcal,
            consumedKcal = food.sumOf { it.kcal },
            exerciseKcal = sessions.sumOf { it.netKcal },
            proteinTargetG = proteinTargetG,
            macros = totals,
            maintenanceKcal = maintenance.roundToInt(),
        )
    }

    /**
     * Learns real maintenance from the history window. Only days with food logged contribute — a
     * blank day would otherwise read as a zero-calorie day and drag the estimate into fiction.
     */
    private fun calibrate(
        today: LocalDate,
        formulaTdee: Double,
        food: List<FoodLogEntity>,
        sessions: List<SessionEntity>,
        trend: WeightTrendResult,
    ): AdaptiveTdeeResult? {
        val window = today.minusDays(AdaptiveTdee.FULL_CONFIDENCE_DAYS.toLong())
        val intake = food.filter { !it.date.isBefore(window) }
            .groupBy { it.date }
            .mapValues { (_, entries) -> entries.sumOf { it.kcal } }
        if (intake.size < AdaptiveTdee.MIN_DAYS) return null

        val burn = sessions.filter { !it.date.isBefore(window) }
            .groupBy { it.date }
            .mapValues { (_, list) -> list.sumOf { it.netKcal } }

        val records = intake.keys.sorted().map { date ->
            DailyEnergyRecord(date, intake.getValue(date), burn[date] ?: 0)
        }

        val startDate = records.first().date
        val startTrend = trend.points.firstOrNull { !it.date.isBefore(startDate) }?.trendKg
        val endTrend = trend.points.lastOrNull()?.trendKg

        return AdaptiveTdee.calculate(formulaTdee, records, startTrend, endTrend)
    }

    private fun streakFrom(
        today: LocalDate,
        food: List<FoodLogEntity>,
        sessions: List<SessionEntity>,
        stepsByDate: Map<LocalDate, Int>,
        settings: ZephyrSettings,
        maintenance: Double,
        inDeficit: Boolean,
    ): StreakResult {
        val sessionDates = sessions.map { it.date }.toSet()
        val burnByDate = sessions.groupBy { it.date }.mapValues { (_, l) -> l.sumOf { it.netKcal } }

        val outcomes = food.groupBy { it.date }.map { (date, entries) ->
            val consumed = entries.sumOf { it.kcal }
            val target = settings.calorieTargetOverride ?: settings.profile
                ?.let { CalorieTarget.forProfile(it, date, maintenanceOverride = maintenance).targetKcal }
                ?: return@map DayOutcome(date, true, false, false, date in sessionDates)

            DayOutcome(
                date = date,
                loggedFood = true,
                withinCalorieTarget = consumed <= target + (burnByDate[date] ?: 0),
                hitStepGoal = (stepsByDate[date] ?: 0) >= settings.stepGoal,
                completedSession = date in sessionDates,
            )
        }
        return StreakCalculator.calculate(outcomes, today)
    }

    private fun weekFrom(
        today: LocalDate,
        food: List<FoodLogEntity>,
        sessions: List<SessionEntity>,
        targetKcal: Int,
        proteinTargetG: Int,
        maintenance: Double,
    ): WeeklySummary {
        val from = today.minusDays(6)
        val balances = (0..6).map { offset ->
            val date = from.plusDays(offset.toLong())
            balanceFor(
                date = date,
                food = food.filter { it.date == date },
                sessions = sessions.filter { it.date == date },
                targetKcal = targetKcal,
                proteinTargetG = proteinTargetG,
                maintenance = maintenance,
            )
        }
        return LedgerSummary.weekly(balances)
    }
}
