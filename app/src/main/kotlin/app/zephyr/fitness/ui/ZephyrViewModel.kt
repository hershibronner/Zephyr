package app.zephyr.fitness.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.zephyr.fitness.AppContainer
import app.zephyr.fitness.data.db.FoodLogEntity
import app.zephyr.fitness.data.db.MealSlot
import app.zephyr.fitness.data.db.PlannedSlotEntity
import app.zephyr.fitness.data.db.RoutePointEntity
import app.zephyr.fitness.data.db.SessionEntity
import app.zephyr.fitness.data.db.WeightEntity
import app.zephyr.fitness.domain.TodayState
import app.zephyr.fitness.tracking.TrackingState
import app.zephyr.fitness.ui.screens.ObDraft
import app.zephyr.fitness.ui.screens.ObStep
import app.zephyr.fitness.update.UpdateManifest
import app.zephyr.fitness.update.UpdateState
import dev.zephyr.core.activity.ActivityType
import dev.zephyr.core.energy.BasalMetabolicRate
import dev.zephyr.core.energy.CalorieTarget
import dev.zephyr.core.energy.CalorieTargetResult
import dev.zephyr.core.energy.MacroCalculator
import dev.zephyr.core.energy.MacroTargets
import dev.zephyr.core.energy.TargetAdjustment
import dev.zephyr.core.ledger.EnergyBalance
import dev.zephyr.core.ledger.LedgerSummary
import dev.zephyr.core.ledger.MacroTotals
import dev.zephyr.core.model.ActivityLevel
import dev.zephyr.core.model.GoalPace
import dev.zephyr.core.model.Sex
import dev.zephyr.core.model.UserProfile
import dev.zephyr.core.plan.FitnessBaseline
import dev.zephyr.core.plan.PlannedSlot
import dev.zephyr.core.plan.Prescription
import dev.zephyr.core.plan.ProgressionEngine
import dev.zephyr.core.plan.WeeklyTemplate
import dev.zephyr.core.steps.StepGoalEngine
import dev.zephyr.core.steps.StepPace
import dev.zephyr.core.streak.StreakResult
import dev.zephyr.core.trend.WeightTrend
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalTime
import java.time.MonthDay
import java.time.temporal.ChronoUnit

@Suppress("OPT_IN_USAGE")
class ZephyrViewModel(private val container: AppContainer) : ViewModel() {

    private val today = LocalDate.now()

    private val _selectedDate = MutableStateFlow(today)
    val selectedDate: StateFlow<LocalDate> = _selectedDate.asStateFlow()

    /** The day on screen, which is today unless the date strip says otherwise. */
    val state: StateFlow<TodayState> = _selectedDate
        .flatMapLatest { date -> container.todayRepository.observe(date) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyState())

    /** Days with anything logged, so the date strip can mark them. */
    val loggedDates: StateFlow<Set<LocalDate>> = container.database.foodDao()
        .observeLogBetween(today.minusDays(30), today)
        .map { entries -> entries.map { it.date }.toSet() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _draft = MutableStateFlow(ObDraft())
    val draft: StateFlow<ObDraft> = _draft.asStateFlow()

    private val _prescription = MutableStateFlow<Prescription?>(null)
    val prescription: StateFlow<Prescription?> = _prescription.asStateFlow()

    private val _update = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val update: StateFlow<UpdateState> = _update.asStateFlow()

    /**
     * True when Android still needs the user to allow this app to install packages. Surfaced rather
     * than handled silently: the permission screen opens in Settings, and without a word of
     * explanation the trip back looks like the Install button simply did nothing.
     */
    private val _needsInstallPermission = MutableStateFlow(false)
    val needsInstallPermission: StateFlow<Boolean> = _needsInstallPermission.asStateFlow()

    /** A version the user waved away, so re-checking doesn't resurrect the same banner. */
    private var dismissedVersion: Int? = null

    /** The session in progress, owned by the service so it survives this ViewModel. */
    val tracking: StateFlow<TrackingState> = container.trackingRepository.state

    val sessionHistory: StateFlow<List<SessionEntity>> = container.database.sessionDao()
        .observeRecent(30)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refreshPrescription()
        checkForUpdate()
    }

    /**
     * Looks for a newer build. Silent when there isn't one — an update check that announces itself
     * every launch is just noise, so only an actual update surfaces in the UI.
     *
     * Safe to call on every resume: a download or a finished download is never interrupted, and a
     * version the user already dismissed stays dismissed.
     */
    fun checkForUpdate() {
        when (_update.value) {
            is UpdateState.Downloading, is UpdateState.ReadyToInstall, is UpdateState.Checking -> return
            else -> Unit
        }
        viewModelScope.launch {
            _update.value = UpdateState.Checking
            _update.value = runCatching { container.updateManager.check() }
                .fold(
                    onSuccess = { manifest ->
                        when {
                            manifest == null -> UpdateState.UpToDate
                            manifest.versionCode == dismissedVersion -> UpdateState.UpToDate
                            else -> UpdateState.Available(manifest)
                        }
                    },
                    // A failed check is not worth interrupting anyone over; they came here to log food.
                    onFailure = { UpdateState.Failed(it.message ?: "Could not reach the update server") },
                )
        }
    }

    fun downloadUpdate(manifest: UpdateManifest) {
        if (_update.value is UpdateState.Downloading) return
        viewModelScope.launch {
            _update.value = UpdateState.Downloading(manifest, 0f)
            _update.value = runCatching {
                container.updateManager.download(manifest) { fraction ->
                    _update.value = UpdateState.Downloading(manifest, fraction)
                }
            }.fold(
                onSuccess = { file -> UpdateState.ReadyToInstall(manifest, file) },
                onFailure = { UpdateState.Failed(it.message ?: "Download failed") },
            )
        }
    }

    /**
     * Hands the verified APK to the system installer, first sending the user to grant install
     * permission if they haven't — without it the installer silently refuses.
     */
    fun installUpdate(file: File) {
        val manager = container.updateManager
        if (!manager.canInstall()) {
            _needsInstallPermission.value = true
            manager.requestInstallPermission()
            return
        }
        _needsInstallPermission.value = false
        manager.install(file)
    }

    /** Re-read on resume, so returning from the Settings screen clears the prompt. */
    fun refreshInstallPermission() {
        if (container.updateManager.canInstall()) _needsInstallPermission.value = false
    }

    fun dismissUpdate() {
        (_update.value as? UpdateState.Available)?.let { dismissedVersion = it.manifest.versionCode }
        _update.value = UpdateState.Idle
    }

    fun updateDraft(draft: ObDraft) {
        _draft.value = draft
    }

    fun selectDate(date: LocalDate) {
        if (!date.isAfter(today)) _selectedDate.value = date
    }

    /**
     * The plan the current draft would produce, so onboarding can show the number before committing
     * it. Null until enough has been entered to compute anything honest.
     */
    fun previewPlan(draft: ObDraft): Pair<CalorieTargetResult, MacroTargets>? {
        val profile = draft.toProfile() ?: return null
        val target = CalorieTarget.forProfile(profile, today)
        val macros = MacroCalculator.calculate(
            targetKcal = target.targetKcal,
            goalWeightKg = profile.goalWeightKg,
            inDeficit = profile.goalPace.isDeficit,
        )
        return target to macros
    }

    fun completeOnboarding(draft: ObDraft, onDone: () -> Unit) {
        val profile = draft.toProfile() ?: return
        viewModelScope.launch {
            container.settingsStore.saveProfile(profile)
            container.database.weightDao().upsert(WeightEntity(today, profile.weightKg))

            // Seed the weekly skeleton so the coach has something to hold them to from day one. An
            // empty plan means an app that never asks anything of you, which is the failure mode.
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
            refreshPrescription()
            onDone()
        }
    }

    fun quickAddCalories(kcal: Int, name: String = "Quick add", proteinG: Double = 0.0) {
        if (kcal <= 0) return
        viewModelScope.launch {
            container.database.foodDao().insertLog(
                FoodLogEntity(
                    // Lands on the day being viewed, so last night's dinner can be added this morning.
                    date = _selectedDate.value,
                    slot = slotForNow(),
                    name = name,
                    quantityGrams = null,
                    kcal = kcal,
                    proteinG = proteinG,
                ),
            )
        }
    }

    fun deleteFood(entry: FoodLogEntity) {
        viewModelScope.launch { container.database.foodDao().deleteLog(entry) }
    }

    /** Takes pounds, because that's what the screen asks for. */
    fun logWeightPounds(pounds: Double) {
        val kg = Units.lbToKg(pounds)
        if (kg !in 30.0..300.0) return
        viewModelScope.launch {
            container.database.weightDao().upsert(WeightEntity(_selectedDate.value, kg))
            // Targets derive from body weight, so a weigh-in has to move the profile too or the plan
            // slowly drifts out of date as the user succeeds.
            container.settingsStore.updateWeight(kg)
        }
    }

    fun syncSteps() {
        viewModelScope.launch { container.stepRepository.sync(today) }
    }

    // ---- Tracking -------------------------------------------------------------------------

    /**
     * Body weight for the calorie model, from the saved profile. Falls back to an average rather
     * than zero, so a session recorded before onboarding finishes still gets a defensible number.
     */
    fun trackingWeightKg(): Double = state.value.settings.profile?.weightKg ?: DEFAULT_WEIGHT_KG

    /**
     * Saves the finished session and its route, then folds it into the day's ledger.
     *
     * Sessions shorter than a few seconds or with no distance are discarded rather than saved: an
     * accidental start-then-stop should not litter the history or claim calories.
     */
    fun finishTracking(onSaved: () -> Unit = {}) {
        val finished = container.trackingRepository.finish()
        viewModelScope.launch {
            if (finished.elapsedSeconds >= MIN_SAVEABLE_SECONDS && finished.distanceMetres > 0) {
                val startedAt = finished.startedAtMillis
                val sessionId = container.database.sessionDao().insert(
                    SessionEntity(
                        date = Instant.ofEpochMilli(startedAt).atZone(ZoneId.systemDefault()).toLocalDate(),
                        type = finished.type.name,
                        startEpochMillis = startedAt,
                        endEpochMillis = System.currentTimeMillis(),
                        durationSeconds = finished.elapsedSeconds,
                        distanceMetres = finished.distanceMetres,
                        elevationGainMetres = finished.elevationGainMetres,
                        elevationLossMetres = finished.elevationLossMetres,
                        kcal = finished.kcal,
                        netKcal = finished.netKcal,
                    ),
                )
                container.database.sessionDao().insertPoints(
                    finished.points.map { point ->
                        RoutePointEntity(
                            sessionId = sessionId,
                            latitude = point.latitude,
                            longitude = point.longitude,
                            altitudeMetres = point.altitudeMetres,
                            timestampMillis = point.timestampMillis,
                            accuracyMetres = point.accuracyMetres,
                        )
                    },
                )
                refreshPrescription()
            }
            onSaved()
        }
    }

    fun discardTracking() {
        container.trackingRepository.discard()
    }

    private fun refreshPrescription() {
        viewModelScope.launch {
            val slots = container.database.planDao().all()
                .filter { it.enabled }
                .map { entity ->
                    PlannedSlot(
                        id = entity.id,
                        dayOfWeek = DayOfWeek.of(entity.dayOfWeek),
                        type = runCatching { ActivityType.valueOf(entity.type) }.getOrDefault(ActivityType.OTHER),
                        timeOfDay = entity.timeOfDay,
                        enabled = true,
                    )
                }
            if (slots.isEmpty()) {
                _prescription.value = null
                return@launch
            }

            val weekStart = today.with(DayOfWeek.MONDAY)
            val planStart = container.planStartDate().with(DayOfWeek.MONDAY)
            val weekIndex = ChronoUnit.WEEKS.between(planStart, weekStart).toInt().coerceAtLeast(0)
            val longestRun = container.database.sessionDao()
                .longestSince(ActivityType.RUN.name, today.minusDays(30))

            _prescription.value = ProgressionEngine
                .prescriptionsForWeek(
                    template = WeeklyTemplate(slots),
                    weekStart = weekStart,
                    weekIndex = weekIndex,
                    baseline = FitnessBaseline(longestRunMetres = longestRun, longestHikeMinutes = null),
                )
                .firstOrNull { it.date == today }
        }
    }

    private fun slotForNow(): MealSlot {
        val hour = LocalTime.now().hour
        return when {
            hour < 11 -> MealSlot.BREAKFAST
            hour < 15 -> MealSlot.LUNCH
            hour < 21 -> MealSlot.DINNER
            else -> MealSlot.SNACK
        }
    }

    private fun emptyState() = TodayState(
        date = today,
        ready = false,
        balance = EnergyBalance(today, 0, 0, 0, 0, MacroTotals()),
        stepStatus = StepPace.status(0, StepGoalEngine.DEFAULT_GOAL, LocalTime.now()),
        macroTargets = MacroTargets(0, 0, 0, 0),
        target = CalorieTargetResult(0, 0, 0, TargetAdjustment.NONE, 0.0),
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
            stepGoal = StepGoalEngine.DEFAULT_GOAL,
            stepGoalIsManual = false,
            nudges = dev.zephyr.core.nudge.NudgeSettings(),
            useAdaptiveTdee = true,
            calorieTargetOverride = null,
            proteinTargetOverride = null,
            disclaimerAccepted = false,
        ),
    )

    private companion object {
        /** Used only if a session somehow starts before a profile exists. */
        const val DEFAULT_WEIGHT_KG = 75.0

        /** Below this, a session is a mis-tap rather than a workout. */
        const val MIN_SAVEABLE_SECONDS = 10L
    }

    class Factory(private val container: AppContainer) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = ZephyrViewModel(container) as T
    }
}

/**
 * Commits the imperial onboarding draft into the metric profile every formula expects. This is the
 * one place the two unit systems meet.
 */
fun ObDraft.toProfile(): UserProfile? {
    val age = age.toIntOrNull() ?: return null
    val heightCm = heightCm ?: return null
    val weightKg = weightKg ?: return null
    val goalKg = goalWeightKg ?: weightKg

    return UserProfile(
        sex = sex ?: Sex.UNSPECIFIED,
        // Only an age is asked for — a full birth date is more personal data than the equation
        // needs, and age in years is all Mifflin-St Jeor consumes.
        birthDate = MonthDay.of(1, 1).atYear(LocalDate.now().year - age),
        heightCm = heightCm,
        weightKg = weightKg,
        goalWeightKg = goalKg,
        activityLevel = activity ?: ActivityLevel.LIGHT,
        goalPace = pace ?: GoalPace.LOSE_STEADY,
    )
}
