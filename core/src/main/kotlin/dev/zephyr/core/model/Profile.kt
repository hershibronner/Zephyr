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

/** Pounds in a kilogram. Zephyr computes in metric and speaks in imperial. */
const val LB_PER_KG = 2.2046226218

/**
 * Direction and speed of the goal.
 *
 * The rates are whole and half pounds because that is how the people using this think about it —
 * nobody sets out to lose 0.45 kg a week. The stored value stays metric since every formula in
 * this package is, but the numbers were chosen so the imperial labels are exact rather than
 * awkwardly converted.
 */
enum class GoalPace(val kgPerWeek: Double, val label: String) {
    GAIN_SLOW(0.5 / LB_PER_KG, "Gain ½ lb a week"),
    MAINTAIN(0.0, "Stay where I am"),
    LOSE_EASY(-0.5 / LB_PER_KG, "Lose ½ lb a week"),
    LOSE_STEADY(-1.0 / LB_PER_KG, "Lose 1 lb a week"),
    LOSE_FAST(-1.5 / LB_PER_KG, "Lose 1½ lb a week"),
    LOSE_AGGRESSIVE(-2.0 / LB_PER_KG, "Lose 2 lb a week");

    val isDeficit: Boolean get() = kgPerWeek < 0

    val lbPerWeek: Double get() = kgPerWeek * LB_PER_KG
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
