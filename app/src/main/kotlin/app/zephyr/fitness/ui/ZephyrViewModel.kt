package app.zephyr.fitness.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.zephyr.fitness.AppContainer
import app.zephyr.fitness.data.db.FoodEntity
import app.zephyr.fitness.data.db.FoodLogEntity
import app.zephyr.fitness.data.db.MealSlot
import app.zephyr.fitness.data.db.PlannedSlotEntity
import app.zephyr.fitness.data.db.RoutePointEntity
import app.zephyr.fitness.data.db.SessionEntity
import app.zephyr.fitness.data.db.WeightEntity
import app.zephyr.fitness.data.food.MealEstimate
import app.zephyr.fitness.domain.TodayState
import app.zephyr.fitness.tracking.TrackingState
import app.zephyr.fitness.ui.screens.ObDraft
import app.zephyr.fitness.ui.screens.ObStep
import app.zephyr.fitness.update.UpdateManifest
import app.zephyr.fitness.update.UpdateState
import dev.zephyr.core.activity.ActivityType
import dev.zephyr.core.activity.GeoPoint
import dev.zephyr.core.activity.SessionVerdict
import dev.zephyr.core.activity.Splits
import dev.zephyr.core.energy.BasalMetabolicRate
import dev.zephyr.core.fasting.Fasting
import dev.zephyr.core.fasting.FastingPlan
import dev.zephyr.core.fasting.FastingStatus
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
import dev.zephyr.core.trend.WeighInPrompt
import dev.zephyr.core.trend.WeighInReminder
import dev.zephyr.core.trend.WeightTrend
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.MonthDay
import java.time.temporal.ChronoUnit
import kotlin.math.roundToInt

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

    // ---- Food capture ---------------------------------------------------------------------

    private val _foodFlow = MutableStateFlow<FoodFlow>(FoodFlow.Idle)
    val foodFlow: StateFlow<FoodFlow> = _foodFlow.asStateFlow()

    val recentFoods: StateFlow<List<FoodEntity>> = container.database.foodDao()
        .observeRecent(20)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Photo estimation needs the user's own key, so the button only appears once one is saved. */
    val hasApiKey: StateFlow<Boolean> = container.settingsStore.anthropicKey
        .map { !it.isNullOrBlank() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    fun openCapture() {
        _foodFlow.value = FoodFlow.Capturing()
    }

    fun closeFoodFlow() {
        _foodFlow.value = FoodFlow.Idle
    }

    fun saveApiKey(key: String?) {
        viewModelScope.launch { container.settingsStore.setAnthropicKey(key) }
    }

    /**
     * Looks a barcode up locally first, then against Open Food Facts.
     *
     * A product seen once is cached, so the second scan of the same yoghurt works with no signal at
     * all — which matters in a supermarket, where reception is usually poor.
     */
    fun onBarcode(barcode: String) {
        if ((_foodFlow.value as? FoodFlow.Capturing)?.busy == true) return
        _foodFlow.value = FoodFlow.Capturing(busy = true, status = "Looking up $barcode…")

        viewModelScope.launch {
            val cached = container.database.foodDao().findByBarcode(barcode)
            if (cached != null) {
                _foodFlow.value = FoodFlow.Portioning(cached, defaultGrams(cached))
                return@launch
            }

            val found = runCatching { container.openFoodFacts.lookup(barcode) }.getOrNull()
            _foodFlow.value = if (found == null) {
                // Not a failure state: plenty of real products simply are not in the database, and
                // the useful next step is to type it in, not to be told the scan broke.
                FoodFlow.Capturing(
                    busy = false,
                    status = "Not in the food database. Add it by hand and Zephyr will remember it.",
                )
            } else {
                val id = container.database.foodDao().upsertFood(found)
                val stored = found.copy(id = id)
                FoodFlow.Portioning(stored, defaultGrams(stored))
            }
        }
    }

    fun onMealPhoto(jpegBase64: String) {
        if ((_foodFlow.value as? FoodFlow.Capturing)?.busy == true) return
        _foodFlow.value = FoodFlow.Capturing(busy = true, status = "Working out what's on the plate…")

        viewModelScope.launch {
            val key = container.settingsStore.anthropicKey.first()
            if (key.isNullOrBlank()) {
                _foodFlow.value = FoodFlow.Failed("Add an Anthropic API key in Settings to use photos.")
                return@launch
            }
            _foodFlow.value = runCatching { container.mealPhotoAnalyser.analyse(jpegBase64, key) }
                .fold(
                    onSuccess = { estimate ->
                        if (estimate.items.isEmpty()) {
                            FoodFlow.Failed(
                                estimate.note.ifBlank { "No food found in that photo. Try again closer." },
                            )
                        } else {
                            FoodFlow.Reviewing(estimate)
                        }
                    },
                    onFailure = { FoodFlow.Failed(it.message ?: "Could not read that photo") },
                )
        }
    }

    fun updatePortion(grams: String) {
        val current = _foodFlow.value as? FoodFlow.Portioning ?: return
        _foodFlow.value = current.copy(grams = grams.filter { it.isDigit() }.take(4))
    }

    /**
     * Writes a portion to the log, scaling the per-100g figures to what was actually eaten. This is
     * the number that decides whether the day's ledger means anything.
     */
    fun logPortion(slot: MealSlot? = null) {
        val current = _foodFlow.value as? FoodFlow.Portioning ?: return
        val grams = current.grams.toDoubleOrNull()?.takeIf { it > 0 } ?: return
        val food = current.food
        val factor = grams / 100.0

        viewModelScope.launch {
            container.database.foodDao().insertLog(
                FoodLogEntity(
                    date = _selectedDate.value,
                    slot = slot ?: slotForNow(),
                    foodId = food.id.takeIf { it != 0L },
                    name = listOfNotNull(food.brand, food.name).joinToString(" "),
                    quantityGrams = grams,
                    kcal = (food.kcalPer100 * factor).roundToInt(),
                    proteinG = food.proteinPer100 * factor,
                    carbsG = food.carbsPer100 * factor,
                    fatG = food.fatPer100 * factor,
                ),
            )
            if (food.id != 0L) {
                container.database.foodDao().markUsed(food.id, LocalDate.now().toEpochDay())
            }
            _foodFlow.value = FoodFlow.Idle
        }
    }

    /** Logs each identified dish separately, so a wrong one can be deleted without losing the meal. */
    fun logEstimate(estimate: MealEstimate, slot: MealSlot? = null) {
        viewModelScope.launch {
            val mealSlot = slot ?: slotForNow()
            estimate.items.forEach { item ->
                container.database.foodDao().insertLog(
                    FoodLogEntity(
                        date = _selectedDate.value,
                        slot = mealSlot,
                        name = item.name,
                        quantityGrams = item.grams.takeIf { it > 0 },
                        kcal = item.kcal,
                        proteinG = item.proteinG,
                        carbsG = item.carbsG,
                        fatG = item.fatG,
                    ),
                )
            }
            _foodFlow.value = FoodFlow.Idle
        }
    }

    fun startPortioning(food: FoodEntity) {
        _foodFlow.value = FoodFlow.Portioning(food, defaultGrams(food))
    }

    /** The packet's own serving size when it has one — far likelier to be right than a flat 100 g. */
    private fun defaultGrams(food: FoodEntity): String =
        (food.servingGrams?.takeIf { it > 0 } ?: 100.0).roundToInt().toString()

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

    /** The session just saved, so the screen can confirm it landed instead of just emptying. */
    private val _lastSaved = MutableStateFlow<SessionEntity?>(null)
    val lastSaved: StateFlow<SessionEntity?> = _lastSaved.asStateFlow()

    fun clearLastSaved() {
        _lastSaved.value = null
    }

    /**
     * Saves the finished session and its route, then folds it into the day's ledger.
     *
     * Duration alone is enough to save. Requiring distance meant a session recorded indoors, or one
     * that ended before GPS ever locked, vanished without a word — the user did the work and the app
     * showed them nothing. Only a sub-ten-second start-then-stop is treated as a mis-tap.
     */
    fun finishTracking(onSaved: () -> Unit = {}) {
        val finished = container.trackingRepository.finish()
        viewModelScope.launch {
            if (finished.elapsedSeconds >= MIN_SAVEABLE_SECONDS) {
                val startedAt = finished.startedAtMillis
                val entity = SessionEntity(
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
                )
                val sessionId = container.database.sessionDao().insert(entity)
                _lastSaved.value = entity.copy(id = sessionId)
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

    /** The activity chosen but not yet started — selecting is deliberately not starting. */
    private val _selectedActivity = MutableStateFlow(ActivityType.RUN)
    val selectedActivity: StateFlow<ActivityType> = _selectedActivity.asStateFlow()

    fun selectActivity(type: ActivityType) {
        _selectedActivity.value = type
    }

    // ---- Session reports ------------------------------------------------------------------

    private val _openSession = MutableStateFlow<SessionReport?>(null)
    val openSession: StateFlow<SessionReport?> = _openSession.asStateFlow()

    fun openSessionReport(session: SessionEntity) {
        viewModelScope.launch {
            val points = container.database.sessionDao().pointsFor(session.id).map { point ->
                GeoPoint(
                    latitude = point.latitude,
                    longitude = point.longitude,
                    altitudeMetres = point.altitudeMetres,
                    timestampMillis = point.timestampMillis,
                    accuracyMetres = point.accuracyMetres,
                )
            }
            val type = runCatching { ActivityType.valueOf(session.type) }.getOrDefault(ActivityType.OTHER)
            val longest = container.database.sessionDao()
                .longestSince(session.type, session.date.minusDays(30))

            _openSession.value = SessionReport(
                session = session,
                points = points,
                verdict = SessionVerdict.describe(
                    type = type,
                    distanceMetres = session.distanceMetres,
                    durationSeconds = session.durationSeconds,
                    splits = Splits.perMile(points),
                    // Compared against the previous best, not including this one.
                    longestRecentMetres = longest?.takeIf { it > session.distanceMetres },
                ),
            )
        }
    }

    fun closeSessionReport() {
        _openSession.value = null
    }

    // ---- Fasting --------------------------------------------------------------------------

    val fastingPlan: StateFlow<FastingPlan> = container.settingsStore.fastingPlan
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), FastingPlan.OFF)

    /**
     * The fasting clock, recomputed every minute against the last logged meal.
     *
     * A countdown that only moves when something else happens to redraw the screen reads as broken,
     * so this ticks on its own rather than waiting for the ledger to change.
     */
    val fastingStatus: StateFlow<FastingStatus> = combine(
        fastingPlan,
        container.database.foodDao().observeLogBetween(today.minusDays(2), today.plusDays(1)),
        tickerFlow(),
    ) { plan, entries, now ->
        val lastMeal = entries.maxByOrNull { it.loggedAtEpochMillis }?.let { entry ->
            Instant.ofEpochMilli(entry.loggedAtEpochMillis).atZone(ZoneId.systemDefault()).toLocalDateTime()
        }
        Fasting.status(plan, lastMeal, now)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        Fasting.status(FastingPlan.OFF, null, LocalDateTime.now()),
    )

    fun recommendedFastingPlan(): FastingPlan =
        Fasting.recommend(state.value.settings.profile?.goalPace ?: GoalPace.LOSE_STEADY)

    fun setFastingPlan(plan: FastingPlan) {
        viewModelScope.launch { container.settingsStore.setFastingPlan(plan) }
    }

    // ---- Weigh-in prompting ---------------------------------------------------------------

    private val _weighInDraft = MutableStateFlow("")
    val weighInDraft: StateFlow<String> = _weighInDraft.asStateFlow()

    fun updateWeighInDraft(value: String) {
        _weighInDraft.value = value
    }

    /**
     * Whether to ask for a weight right now. Recomputed on the same minute tick as the fast, so a
     * snooze that has run out surfaces without the user having to leave and re-enter the app.
     */
    val weighInPrompt: StateFlow<WeighInPrompt> = combine(
        container.database.weightDao().observeAll(),
        container.settingsStore.weighInSnooze,
        tickerFlow(),
    ) { weights, snooze, now ->
        val (snoozeAt, count) = snooze
        WeighInReminder.evaluate(
            now = now,
            lastWeighIn = weights.maxByOrNull { it.date }?.date,
            lastSnoozeAt = snoozeAt?.let {
                Instant.ofEpochMilli(it).atZone(ZoneId.systemDefault()).toLocalDateTime()
            },
            snoozeCount = count,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeighInPrompt.None)

    fun submitWeighIn() {
        val pounds = _weighInDraft.value.toDoubleOrNull() ?: return
        logWeightPounds(pounds)
        _weighInDraft.value = ""
        viewModelScope.launch { container.settingsStore.clearWeighInSnooze() }
    }

    /** Puts the ask off by an hour. It comes back, and keeps coming back, until a number lands. */
    fun snoozeWeighIn() {
        val attempt = (weighInPrompt.value as? WeighInPrompt.Due)?.attempt ?: 0
        viewModelScope.launch {
            container.settingsStore.snoozeWeighIn(System.currentTimeMillis(), attempt + 1)
        }
    }

    /** One emission a minute — enough for a countdown, cheap enough to leave running. */
    private fun tickerFlow(): Flow<LocalDateTime> = flow {
        while (true) {
            emit(LocalDateTime.now())
            delay(60_000)
        }
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
