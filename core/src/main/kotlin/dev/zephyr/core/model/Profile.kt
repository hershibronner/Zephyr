package dev.zephyr.core.model

import java.time.LocalDate
import java.time.Period

/**
 * Biological sex, used only for the BMR equation. [UNSPECIFIED] averages the male and female
 * constants rather than forcing a choice — a small accuracy cost, and the adaptive calibration
 * in [dev.zephyr.core.energy.AdaptiveTdee] washes it out within a few weeks anyway.
 */
enum class Sex { MALE, FEMALE, UNSPECIFIED }

/**
 * Day-to-day activity *excluding* deliberate exercise. Exercise is added to the ledger from
 * logged sessions, so folding it into this multiplier as well would count it twice — the most
 * common way calorie apps quietly overfeed a deficit.
 */
enum class ActivityLevel(val multiplier: Double, val label: String) {
    SEDENTARY(1.20, "Desk job, little walking"),
    LIGHT(1.30, "On your feet some of the day"),
    MODERATE(1.38, "Mostly on your feet"),
    HIGH(1.45, "Physical job, constant movement");
}

enum class UnitSystem { METRIC, IMPERIAL }

/** Direction and speed of the goal, in kilograms of body mass per week. */
enum class GoalPace(val kgPerWeek: Double, val label: String) {
    GAIN_SLOW(0.25, "Gain 0.25 kg/week"),
    MAINTAIN(0.0, "Maintain"),
    LOSE_EASY(-0.25, "Lose 0.25 kg/week"),
    LOSE_STEADY(-0.5, "Lose 0.5 kg/week"),
    LOSE_FAST(-0.75, "Lose 0.75 kg/week"),
    LOSE_AGGRESSIVE(-1.0, "Lose 1 kg/week");

    val isDeficit: Boolean get() = kgPerWeek < 0
}

/**
 * Everything the energy model needs about the user. Weight is kilograms and height centimetres
 * throughout the domain layer; unit preference is a display concern handled in the UI.
 */
data class UserProfile(
    val sex: Sex,
    val birthDate: LocalDate,
    val heightCm: Double,
    val weightKg: Double,
    val goalWeightKg: Double,
    val activityLevel: ActivityLevel,
    val goalPace: GoalPace,
    val units: UnitSystem = UnitSystem.METRIC,
) {
    fun ageOn(date: LocalDate): Int = Period.between(birthDate, date).years

    /** Body mass index, used for the safety checks that keep goals in a healthy range. */
    val bmi: Double get() = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))

    val goalBmi: Double get() = goalWeightKg / ((heightCm / 100.0) * (heightCm / 100.0))
}

/** Energy released per kilogram of body mass. The standard figure for mixed tissue loss. */
const val KCAL_PER_KG = 7700.0
