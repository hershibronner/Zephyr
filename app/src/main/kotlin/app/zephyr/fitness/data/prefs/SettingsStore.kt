package app.zephyr.fitness.data.prefs

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.zephyr.core.model.ActivityLevel
import dev.zephyr.core.fasting.FastingPlan
import dev.zephyr.core.model.GoalPace
import dev.zephyr.core.model.Sex
import dev.zephyr.core.model.UnitSystem
import dev.zephyr.core.model.UserProfile
import dev.zephyr.core.nudge.CoachTone
import dev.zephyr.core.nudge.NudgeCategory
import dev.zephyr.core.nudge.NudgeSettings
import dev.zephyr.core.nudge.QuietHours
import dev.zephyr.core.nudge.defaultMaxPerDay
import dev.zephyr.core.steps.StepGoalEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import java.time.LocalTime

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "zephyr_settings")

/** Everything the user configured, in one observable object. */
data class ZephyrSettings(
    val onboarded: Boolean,
    val profile: UserProfile?,
    val stepGoal: Int,
    val stepGoalIsManual: Boolean,
    val nudges: NudgeSettings,
    val useAdaptiveTdee: Boolean,
    val calorieTargetOverride: Int?,
    val proteinTargetOverride: Int?,
    val disclaimerAccepted: Boolean,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val ONBOARDED = booleanPreferencesKey("onboarded")
        val DISCLAIMER = booleanPreferencesKey("disclaimer_accepted")
        val SEX = stringPreferencesKey("sex")
        val BIRTH_DATE = longPreferencesKey("birth_date")
        val HEIGHT_CM = doublePreferencesKey("height_cm")
        val WEIGHT_KG = doublePreferencesKey("weight_kg")
        val GOAL_WEIGHT_KG = doublePreferencesKey("goal_weight_kg")
        val ACTIVITY = stringPreferencesKey("activity_level")
        val GOAL_PACE = stringPreferencesKey("goal_pace")
        val UNITS = stringPreferencesKey("units")

        val STEP_GOAL = intPreferencesKey("step_goal")
        val STEP_GOAL_MANUAL = booleanPreferencesKey("step_goal_manual")

        val TONE = stringPreferencesKey("coach_tone")
        val QUIET_START = intPreferencesKey("quiet_start")
        val QUIET_END = intPreferencesKey("quiet_end")
        val CATEGORIES = stringSetPreferencesKey("nudge_categories")
        val MAX_PER_DAY = intPreferencesKey("nudge_max_per_day")

        val ADAPTIVE_TDEE = booleanPreferencesKey("adaptive_tdee")
        val CALORIE_OVERRIDE = intPreferencesKey("calorie_override")
        val PROTEIN_OVERRIDE = intPreferencesKey("protein_override")

        /**
         * The user's own Anthropic API key, for photo calorie estimation.
         *
         * Held here rather than shipped in the APK: a key baked into a published app is extractable
         * by anyone who downloads it and would be billed to whoever shipped it.
         */
        val ANTHROPIC_KEY = stringPreferencesKey("anthropic_api_key")

        val FASTING_PLAN = stringPreferencesKey("fasting_plan")

        /** Snooze state for the weigh-in ask, so "Later" survives the app being killed. */
        val WEIGH_SNOOZE_AT = longPreferencesKey("weigh_snooze_at")
        val WEIGH_SNOOZE_COUNT = intPreferencesKey("weigh_snooze_count")
    }

    val settings: Flow<ZephyrSettings> = context.dataStore.data.map { prefs ->
        val tone = prefs[Keys.TONE]?.let { runCatching { CoachTone.valueOf(it) }.getOrNull() }
            ?: CoachTone.BALANCED

        ZephyrSettings(
            onboarded = prefs[Keys.ONBOARDED] ?: false,
            profile = readProfile(prefs),
            stepGoal = prefs[Keys.STEP_GOAL] ?: StepGoalEngine.DEFAULT_GOAL,
            stepGoalIsManual = prefs[Keys.STEP_GOAL_MANUAL] ?: false,
            nudges = NudgeSettings(
                tone = tone,
                quietHours = QuietHours(
                    start = LocalTime.ofSecondOfDay((prefs[Keys.QUIET_START] ?: 77_400).toLong()),
                    end = LocalTime.ofSecondOfDay((prefs[Keys.QUIET_END] ?: 25_200).toLong()),
                ),
                enabledCategories = prefs[Keys.CATEGORIES]
                    ?.mapNotNull { name -> runCatching { NudgeCategory.valueOf(name) }.getOrNull() }
                    ?.toSet()
                    ?: NudgeCategory.entries.toSet(),
                maxPerDay = prefs[Keys.MAX_PER_DAY] ?: tone.defaultMaxPerDay(),
            ),
            useAdaptiveTdee = prefs[Keys.ADAPTIVE_TDEE] ?: true,
            calorieTargetOverride = prefs[Keys.CALORIE_OVERRIDE],
            proteinTargetOverride = prefs[Keys.PROTEIN_OVERRIDE],
            disclaimerAccepted = prefs[Keys.DISCLAIMER] ?: false,
        )
    }

    private fun readProfile(prefs: Preferences): UserProfile? {
        val birthDay = prefs[Keys.BIRTH_DATE] ?: return null
        val height = prefs[Keys.HEIGHT_CM] ?: return null
        val weight = prefs[Keys.WEIGHT_KG] ?: return null

        return UserProfile(
            sex = prefs[Keys.SEX]?.let { runCatching { Sex.valueOf(it) }.getOrNull() } ?: Sex.UNSPECIFIED,
            birthDate = LocalDate.ofEpochDay(birthDay),
            heightCm = height,
            weightKg = weight,
            goalWeightKg = prefs[Keys.GOAL_WEIGHT_KG] ?: weight,
            activityLevel = prefs[Keys.ACTIVITY]
                ?.let { runCatching { ActivityLevel.valueOf(it) }.getOrNull() }
                ?: ActivityLevel.LIGHT,
            goalPace = prefs[Keys.GOAL_PACE]
                ?.let { runCatching { GoalPace.valueOf(it) }.getOrNull() }
                ?: GoalPace.LOSE_STEADY,
            units = prefs[Keys.UNITS]
                ?.let { runCatching { UnitSystem.valueOf(it) }.getOrNull() }
                ?: UnitSystem.METRIC,
        )
    }

    suspend fun saveProfile(profile: UserProfile) {
        context.dataStore.edit { prefs ->
            prefs[Keys.SEX] = profile.sex.name
            prefs[Keys.BIRTH_DATE] = profile.birthDate.toEpochDay()
            prefs[Keys.HEIGHT_CM] = profile.heightCm
            prefs[Keys.WEIGHT_KG] = profile.weightKg
            prefs[Keys.GOAL_WEIGHT_KG] = profile.goalWeightKg
            prefs[Keys.ACTIVITY] = profile.activityLevel.name
            prefs[Keys.GOAL_PACE] = profile.goalPace.name
            prefs[Keys.UNITS] = profile.units.name
        }
    }

    /** Keeps the profile's weight in step with the latest weigh-in so targets stay current. */
    suspend fun updateWeight(weightKg: Double) {
        context.dataStore.edit { it[Keys.WEIGHT_KG] = weightKg }
    }

    suspend fun completeOnboarding() {
        context.dataStore.edit {
            it[Keys.ONBOARDED] = true
            it[Keys.DISCLAIMER] = true
        }
    }

    suspend fun setStepGoal(goal: Int, manual: Boolean) {
        context.dataStore.edit {
            it[Keys.STEP_GOAL] = goal
            it[Keys.STEP_GOAL_MANUAL] = manual
        }
    }

    suspend fun setTone(tone: CoachTone) {
        context.dataStore.edit {
            it[Keys.TONE] = tone.name
            // The cap tracks the tone unless the user has pinned it themselves.
            it[Keys.MAX_PER_DAY] = tone.defaultMaxPerDay()
        }
    }

    suspend fun setQuietHours(start: LocalTime, end: LocalTime) {
        context.dataStore.edit {
            it[Keys.QUIET_START] = start.toSecondOfDay()
            it[Keys.QUIET_END] = end.toSecondOfDay()
        }
    }

    suspend fun setCategoryEnabled(category: NudgeCategory, enabled: Boolean) {
        context.dataStore.edit { prefs ->
            val current = prefs[Keys.CATEGORIES]?.toMutableSet()
                ?: NudgeCategory.entries.map { it.name }.toMutableSet()
            if (enabled) current += category.name else current -= category.name
            prefs[Keys.CATEGORIES] = current
        }
    }

    suspend fun setAdaptiveTdee(enabled: Boolean) {
        context.dataStore.edit { it[Keys.ADAPTIVE_TDEE] = enabled }
    }

    suspend fun setCalorieOverride(kcal: Int?) {
        context.dataStore.edit { prefs ->
            if (kcal == null) prefs.remove(Keys.CALORIE_OVERRIDE) else prefs[Keys.CALORIE_OVERRIDE] = kcal
        }
    }

    /** Observable so the Food screen can offer photo estimation only once a key exists. */
    val anthropicKey: Flow<String?> = context.dataStore.data
        .map { it[Keys.ANTHROPIC_KEY]?.takeIf(String::isNotBlank) }

    val fastingPlan: Flow<FastingPlan> = context.dataStore.data.map { prefs ->
        prefs[Keys.FASTING_PLAN]
            ?.let { runCatching { FastingPlan.valueOf(it) }.getOrNull() }
            ?: FastingPlan.OFF
    }

    suspend fun setFastingPlan(plan: FastingPlan) {
        context.dataStore.edit { it[Keys.FASTING_PLAN] = plan.name }
    }

    /** Epoch millis of the last "Later", and how many times today's ask has been put off. */
    val weighInSnooze: Flow<Pair<Long?, Int>> = context.dataStore.data.map { prefs ->
        prefs[Keys.WEIGH_SNOOZE_AT] to (prefs[Keys.WEIGH_SNOOZE_COUNT] ?: 0)
    }

    suspend fun snoozeWeighIn(atEpochMillis: Long, count: Int) {
        context.dataStore.edit { prefs ->
            prefs[Keys.WEIGH_SNOOZE_AT] = atEpochMillis
            prefs[Keys.WEIGH_SNOOZE_COUNT] = count
        }
    }

    suspend fun clearWeighInSnooze() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.WEIGH_SNOOZE_AT)
            prefs.remove(Keys.WEIGH_SNOOZE_COUNT)
        }
    }

    suspend fun setAnthropicKey(key: String?) {
        context.dataStore.edit { prefs ->
            val trimmed = key?.trim()
            if (trimmed.isNullOrBlank()) prefs.remove(Keys.ANTHROPIC_KEY)
            else prefs[Keys.ANTHROPIC_KEY] = trimmed
        }
    }

    suspend fun setProteinOverride(grams: Int?) {
        context.dataStore.edit { prefs ->
            if (grams == null) prefs.remove(Keys.PROTEIN_OVERRIDE) else prefs[Keys.PROTEIN_OVERRIDE] = grams
        }
    }
}
