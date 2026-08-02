package app.zephyr.fitness.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.zephyr.fitness.AppContainer
import app.zephyr.fitness.data.db.FoodLogEntity
import app.zephyr.fitness.data.db.MealSlot
import app.zephyr.fitness.data.db.PlannedSlotEntity
import app.zephyr.fitness.data.db.WeightEntity
import app.zephyr.fitness.domain.TodayRepository
import app.zephyr.fitness.domain.TodayState
import app.zephyr.fitness.ui.screens.OnboardingDraft
import dev.zephyr.core.energy.BasalMetabolicRate
import dev.zephyr.core.energy.CalorieTarget
import dev.zephyr.core.energy.CalorieTargetResult
import dev.zephyr.core.energy.MacroCalculator
import dev.zephyr.core.energy.MacroTargets
import dev.zephyr.core.ledger.EnergyBalance
import dev.zephyr.core.ledger.LedgerSummary
import dev.zephyr.core.ledger.MacroTotals
import dev.zephyr.core.model.UserProfile
import dev.zephyr.core.plan.WeeklyTemplate
import dev.zephyr.core.steps.StepPace
import dev.zephyr.core.streak.StreakResult
import dev.zephyr.core.trend.WeightTrend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay

class ZephyrViewModel(private val container: AppContainer) : ViewModel() {

    private val today = LocalDate.now()

    val state: StateFlow<TodayState> = container.todayRepository
        .observe(today)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyState())

    private val _draft = MutableStateFlow(OnboardingDraft())
    val draft: StateFlow<OnboardingDraft> = _draft.asStateFlow()

    fun updateDraft(draft: OnboardingDraft) {
        _draft.value = draft
    }

    /**
     * Live preview of the plan the current draft would produce, so onboarding can show the target
     * before committing it. Returns null until enough has been entered to compute anything honest.
     */
    fun previewPlan(draft: OnboardingDraft): Pair<CalorieTargetResult, MacroTargets>? {
        val profile = draft.toProfile() ?: return null
        val target = CalorieTarget.forProfile(profile, today)
        val macros = MacroCalculator.calculate(
            targetKcal = target.targetKcal,
            goalWeightKg = profile.goalWeightKg,
            inDeficit = profile.goalPace.isDeficit,
        )
        return target to macros
    }

    fun completeOnboarding(draft: OnboardingDraft, onDone: () -> Unit) {
        val profile = draft.toProfile() ?: return
        viewModelScope.launch {
            container.settingsStore.saveProfile(profile)
            container.database.weightDao().upsert(WeightEntity(today, profile.weightKg))

            // Seed the weekly skeleton so the coach has something to remind them about from day one.
            // An empty plan means an app that never asks anything of you, which is the failure mode.
            if (container.database.planDao().count() == 0) {
                WeeklyTemplate.default().slots.forEach { slot ->
                    container.database.planDao().upsert(
                        PlannedSlotEntity(
                            dayOfWeek = slot.dayOfWeek.value,
                            type = slot.type.name,
                            timeOfDay = slot.timeOfDay,
                            enabled = true,
                        ),
                    )
                }
            }

            container.settingsStore.completeOnboarding()
            onDone()
        }
    }

    fun quickAddCalories(kcal: Int, name: String = "Quick add", slot: MealSlot = MealSlot.SNACK) {
        if (kcal <= 0) return
        viewModelScope.launch {
            container.database.foodDao().insertLog(
                FoodLogEntity(
                    date = today,
                    slot = slot,
                    name = name,
                    quantityGrams = null,
                    kcal = kcal,
                ),
            )
        }
    }

    fun logWeight(weightKg: Double) {
        if (weightKg !in 30.0..300.0) return
        viewModelScope.launch {
            container.database.weightDao().upsert(WeightEntity(today, weightKg))
            // Targets are derived from body weight, so a weigh-in has to update the profile too or
            // the plan slowly drifts out of date as the user succeeds.
            container.settingsStore.updateWeight(weightKg)
        }
    }

    fun syncSteps() {
        viewModelScope.launch { container.stepRepository.sync(today) }
    }

    private fun emptyState() = TodayState(
        date = today,
        ready = false,
        balance = EnergyBalance(today, 0, 0, 0, 0, MacroTotals()),
        stepStatus = StepPace.status(0, 0, LocalTime.now()),
        macroTargets = MacroTargets(0, 0, 0, 0),
        target = CalorieTargetResult(0, 0, 0, dev.zephyr.core.energy.TargetAdjustment.NONE, 0.0),
        adaptiveTdee = null,
        trend = WeightTrend.calculate(emptyList()),
        streak = StreakResult(0, 0, false, null),
        week = LedgerSummary.weekly(emptyList()),
        sessionsToday = 0,
        entries = emptyList(),
        suggestedStepGoal = null,
        settings = app.zephyr.fitness.data.prefs.ZephyrSettings(
            onboarded = false,
            profile = null,
            stepGoal = dev.zephyr.core.steps.StepGoalEngine.DEFAULT_GOAL,
            stepGoalIsManual = false,
            nudges = dev.zephyr.core.nudge.NudgeSettings(),
            useAdaptiveTdee = true,
            calorieTargetOverride = null,
            proteinTargetOverride = null,
            disclaimerAccepted = false,
        ),
    )

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ZephyrViewModel(container) as T
    }
}

/** Converts the onboarding form into a profile, or null while it's still incomplete. */
fun OnboardingDraft.toProfile(): UserProfile? {
    val year = birthYear.toIntOrNull() ?: return null
    val height = heightCm.toDoubleOrNull() ?: return null
    val weight = weightKg.toDoubleOrNull() ?: return null
    val goal = goalWeightKg.toDoubleOrNull() ?: weight
    if (!yearValid || !heightValid || !weightValid) return null

    return UserProfile(
        sex = sex,
        // Only the year is asked for — a full birth date is more personal data than the equation
        // needs, and age in years is all Mifflin-St Jeor consumes.
        birthDate = MonthDay.of(1, 1).atYear(year),
        heightCm = height,
        weightKg = weight,
        goalWeightKg = goal,
        activityLevel = activity,
        goalPace = pace,
    )
}
