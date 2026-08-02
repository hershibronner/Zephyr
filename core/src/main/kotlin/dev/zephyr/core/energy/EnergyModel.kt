package dev.zephyr.core.energy

import dev.zephyr.core.model.KCAL_PER_KG
import dev.zephyr.core.model.Sex
import dev.zephyr.core.model.UserProfile
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Mifflin-St Jeor resting metabolic rate — the best-validated of the simple predictive equations.
 *
 * kcal/day = 10·kg + 6.25·cm − 5·age + s, where s is +5 for males and −161 for females.
 */
object BasalMetabolicRate {

    fun forProfile(profile: UserProfile, on: LocalDate): Double =
        calculate(profile.weightKg, profile.heightCm, profile.ageOn(on), profile.sex)

    fun calculate(weightKg: Double, heightCm: Double, ageYears: Int, sex: Sex): Double {
        val base = 10.0 * weightKg + 6.25 * heightCm - 5.0 * ageYears
        val offset = when (sex) {
            Sex.MALE -> 5.0
            Sex.FEMALE -> -161.0
            // Midpoint of the two constants rather than a coin flip.
            Sex.UNSPECIFIED -> -78.0
        }
        return max(base + offset, 0.0)
    }
}

/**
 * Total daily energy expenditure *before* logged exercise: BMR scaled by non-exercise activity.
 * Sessions the user records are added on top of this by the daily ledger.
 */
object TdeeCalculator {

    fun forProfile(profile: UserProfile, on: LocalDate): Double =
        BasalMetabolicRate.forProfile(profile, on) * profile.activityLevel.multiplier
}

/**
 * The reason a calorie target ended up where it did. Surfaced in the UI so the number is
 * explainable rather than magic — people hit targets they understand.
 */
enum class TargetAdjustment {
    /** The requested pace was applied as-is. */
    NONE,

    /** Deficit was capped at [CalorieTarget.MAX_DEFICIT_FRACTION] of maintenance. */
    CAPPED_TO_PERCENTAGE,

    /** Target was raised to stay above the absolute minimum intake for the user's sex. */
    RAISED_TO_FLOOR,

    /** Target was raised to stay meaningfully above resting metabolic rate. */
    RAISED_ABOVE_BMR,
}

data class CalorieTargetResult(
    val maintenanceKcal: Int,
    val targetKcal: Int,
    val dailyDeltaKcal: Int,
    val adjustment: TargetAdjustment,
    /** Achievable weekly rate given any safety adjustment, which may be gentler than requested. */
    val effectiveKgPerWeek: Double,
)

/**
 * Turns a goal pace into a daily calorie target, with guard rails.
 *
 * The rails are not decoration. An unbounded deficit slider is how these apps end up prescribing
 * 900 kcal to someone who typed an ambitious goal, which is both a Play health-policy problem and
 * the fastest route to muscle loss — the opposite of looking better. So: no more than a quarter
 * off maintenance, never below resting metabolic rate plus a margin, and never below a hard floor.
 */
object CalorieTarget {

    const val MAX_DEFICIT_FRACTION = 0.25

    /**
     * Intake is not allowed below resting metabolic rate.
     *
     * This sits at 1.0, not above it. An earlier 1.10 margin fired for essentially every realistic
     * user requesting the app's own recommended 0.5 kg/week — quietly watering a typical goal down
     * to 0.12 kg/week and showing a safety warning every single time. A warning that appears in the
     * normal case teaches the user to ignore it, which makes the app less safe rather than more.
     * The rails that do the real work are the deficit cap above and the absolute floors below.
     */
    const val BMR_SAFETY_MARGIN = 1.0
    const val FLOOR_MALE = 1500
    const val FLOOR_FEMALE = 1200

    fun forProfile(profile: UserProfile, on: LocalDate, maintenanceOverride: Double? = null): CalorieTargetResult {
        val bmr = BasalMetabolicRate.forProfile(profile, on)
        val maintenance = maintenanceOverride ?: (bmr * profile.activityLevel.multiplier)
        return calculate(
            maintenanceKcal = maintenance,
            bmrKcal = bmr,
            kgPerWeek = profile.goalPace.kgPerWeek,
            sex = profile.sex,
        )
    }

    fun calculate(maintenanceKcal: Double, bmrKcal: Double, kgPerWeek: Double, sex: Sex): CalorieTargetResult {
        val requestedDelta = kgPerWeek * KCAL_PER_KG / 7.0
        var adjustment = TargetAdjustment.NONE
        var delta = requestedDelta

        // Surplus needs no protection; only deficits get clamped.
        if (delta < 0) {
            val maxDeficit = maintenanceKcal * MAX_DEFICIT_FRACTION
            if (abs(delta) > maxDeficit) {
                delta = -maxDeficit
                adjustment = TargetAdjustment.CAPPED_TO_PERCENTAGE
            }
        }

        var target = maintenanceKcal + delta

        val bmrFloor = bmrKcal * BMR_SAFETY_MARGIN
        val hardFloor = when (sex) {
            Sex.FEMALE -> FLOOR_FEMALE
            else -> FLOOR_MALE
        }.toDouble()

        // A single floor, so one rail can't be silently undercut by another being applied after it.
        val safetyFloor = max(bmrFloor, hardFloor)
        if (target < safetyFloor) {
            target = safetyFloor
            adjustment = if (hardFloor >= bmrFloor) {
                TargetAdjustment.RAISED_TO_FLOOR
            } else {
                TargetAdjustment.RAISED_ABOVE_BMR
            }
        }

        // Degenerate case: someone whose resting rate is already close to their maintenance has no
        // room for a safe deficit at all. Prescribing above maintenance while they asked to lose
        // would be absurd, so the honest answer is maintenance and an effective rate of ~0 — the UI
        // reads the adjustment field and explains why the goal can't be met by diet alone.
        if (kgPerWeek < 0 && target > maintenanceKcal) {
            target = maintenanceKcal
        }

        val effectiveDelta = target - maintenanceKcal
        return CalorieTargetResult(
            maintenanceKcal = maintenanceKcal.roundToInt(),
            targetKcal = target.roundToInt(),
            dailyDeltaKcal = effectiveDelta.roundToInt(),
            adjustment = adjustment,
            effectiveKgPerWeek = effectiveDelta * 7.0 / KCAL_PER_KG,
        )
    }
}

data class MacroTargets(
    val proteinG: Int,
    val fatG: Int,
    val carbsG: Int,
    val kcal: Int,
)

/**
 * Macro split for body recomposition.
 *
 * Protein is anchored to *goal* body weight, not current: in a deficit it is the lever that decides
 * whether the weight you lose is fat or muscle, and it is the single biggest determinant of whether
 * the mirror changes as fast as the scale. Fat is set to a hormonal-health minimum, and carbohydrate
 * takes whatever calories remain, because carbs are what actually fuel the running and hiking.
 */
object MacroCalculator {

    const val PROTEIN_G_PER_KG_DEFICIT = 2.0
    const val PROTEIN_G_PER_KG_MAINTENANCE = 1.8
    const val FAT_G_PER_KG = 0.8
    const val KCAL_PER_G_PROTEIN = 4
    const val KCAL_PER_G_CARB = 4
    const val KCAL_PER_G_FAT = 9

    fun calculate(targetKcal: Int, goalWeightKg: Double, inDeficit: Boolean): MacroTargets {
        val proteinPerKg = if (inDeficit) PROTEIN_G_PER_KG_DEFICIT else PROTEIN_G_PER_KG_MAINTENANCE
        val protein = (goalWeightKg * proteinPerKg).roundToInt()
        val fat = (goalWeightKg * FAT_G_PER_KG).roundToInt()

        val proteinKcal = protein * KCAL_PER_G_PROTEIN
        val fatKcal = fat * KCAL_PER_G_FAT
        // Guard against a very low target being entirely consumed by protein and fat.
        val remaining = max(targetKcal - proteinKcal - fatKcal, 0)
        val carbs = remaining / KCAL_PER_G_CARB

        return MacroTargets(
            proteinG = protein,
            fatG = fat,
            carbsG = carbs,
            kcal = targetKcal,
        )
    }
}

enum class TdeeConfidence { NONE, LOW, MEDIUM, HIGH }

data class AdaptiveTdeeResult(
    /** The number the app should actually budget against. */
    val tdeeKcal: Int,
    val formulaTdeeKcal: Int,
    /** Null until there is enough history to measure anything. */
    val measuredTdeeKcal: Int?,
    val confidence: TdeeConfidence,
    val daysOfData: Int,
)

/** One day of complete-enough data for calibration. */
data class DailyEnergyRecord(
    val date: LocalDate,
    val intakeKcal: Int,
    val exerciseKcal: Int,
)

/**
 * Learns actual maintenance calories from observed reality instead of trusting the equation.
 *
 * Over a window, energy conservation gives:
 *
 *     measuredTDEE = meanIntake + meanExerciseBurn − (trendWeightChange · 7700 / days)
 *
 * A user who loses 0.5 kg over 14 days while eating 2,000 kcal was running a ~275 kcal/day deficit,
 * so their true maintenance is ~2,275 — regardless of what Mifflin-St Jeor predicted. Prediction
 * equations carry ±15% error for an individual, and that error is exactly why people stall on a
 * "correct" target and conclude their metabolism is broken.
 *
 * The measured figure is blended in gradually as evidence accumulates, capped below full weight so
 * a single bad logging week can't swing the target wildly.
 */
object AdaptiveTdee {

    const val MIN_DAYS = 14
    const val FULL_CONFIDENCE_DAYS = 28
    const val MAX_MEASURED_WEIGHT = 0.8

    /** Ignore implausible results — almost always under-logged food, not a broken metabolism. */
    const val MIN_PLAUSIBLE_TDEE = 1000.0
    const val MAX_PLAUSIBLE_TDEE = 8000.0

    /**
     * @param records consecutive days of logged intake and exercise
     * @param trendWeightStartKg smoothed weight at the start of the window (never a raw weigh-in)
     * @param trendWeightEndKg smoothed weight at the end of the window
     */
    fun calculate(
        formulaTdee: Double,
        records: List<DailyEnergyRecord>,
        trendWeightStartKg: Double?,
        trendWeightEndKg: Double?,
    ): AdaptiveTdeeResult {
        val days = records.size
        if (days < MIN_DAYS || trendWeightStartKg == null || trendWeightEndKg == null) {
            return AdaptiveTdeeResult(
                tdeeKcal = formulaTdee.roundToInt(),
                formulaTdeeKcal = formulaTdee.roundToInt(),
                measuredTdeeKcal = null,
                confidence = if (days == 0) TdeeConfidence.NONE else TdeeConfidence.LOW,
                daysOfData = days,
            )
        }

        val meanIntake = records.sumOf { it.intakeKcal }.toDouble() / days
        val meanExercise = records.sumOf { it.exerciseKcal }.toDouble() / days
        val weightChangeKg = trendWeightEndKg - trendWeightStartKg
        val dailyImbalance = weightChangeKg * KCAL_PER_KG / days

        val measured = meanIntake + meanExercise - dailyImbalance

        if (measured < MIN_PLAUSIBLE_TDEE || measured > MAX_PLAUSIBLE_TDEE) {
            return AdaptiveTdeeResult(
                tdeeKcal = formulaTdee.roundToInt(),
                formulaTdeeKcal = formulaTdee.roundToInt(),
                measuredTdeeKcal = null,
                confidence = TdeeConfidence.LOW,
                daysOfData = days,
            )
        }

        val progress = (days - MIN_DAYS).toDouble() / (FULL_CONFIDENCE_DAYS - MIN_DAYS)
        val weight = min(progress.coerceIn(0.0, 1.0) * MAX_MEASURED_WEIGHT + 0.2, MAX_MEASURED_WEIGHT)
        val blended = formulaTdee * (1 - weight) + measured * weight

        val confidence = when {
            days >= FULL_CONFIDENCE_DAYS -> TdeeConfidence.HIGH
            days >= 21 -> TdeeConfidence.MEDIUM
            else -> TdeeConfidence.LOW
        }

        return AdaptiveTdeeResult(
            tdeeKcal = blended.roundToInt(),
            formulaTdeeKcal = formulaTdee.roundToInt(),
            measuredTdeeKcal = measured.roundToInt(),
            confidence = confidence,
            daysOfData = days,
        )
    }
}
