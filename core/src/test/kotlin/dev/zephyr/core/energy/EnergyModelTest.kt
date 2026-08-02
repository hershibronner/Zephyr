package dev.zephyr.core.energy

import dev.zephyr.core.model.ActivityLevel
import dev.zephyr.core.model.GoalPace
import dev.zephyr.core.model.Sex
import dev.zephyr.core.model.UserProfile
import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BasalMetabolicRateTest {

    @Test
    fun `matches published Mifflin-St Jeor value for a male`() {
        // 80kg, 180cm, 30y male: 10*80 + 6.25*180 - 5*30 + 5 = 1780
        val bmr = BasalMetabolicRate.calculate(80.0, 180.0, 30, Sex.MALE)
        assertEquals(1780.0, bmr, 0.5)
    }

    @Test
    fun `matches published Mifflin-St Jeor value for a female`() {
        // 65kg, 165cm, 30y female: 650 + 1031.25 - 150 - 161 = 1370.25
        val bmr = BasalMetabolicRate.calculate(65.0, 165.0, 30, Sex.FEMALE)
        assertEquals(1370.25, bmr, 0.5)
    }

    @Test
    fun `unspecified sex falls between the male and female results`() {
        val male = BasalMetabolicRate.calculate(70.0, 175.0, 35, Sex.MALE)
        val female = BasalMetabolicRate.calculate(70.0, 175.0, 35, Sex.FEMALE)
        val neutral = BasalMetabolicRate.calculate(70.0, 175.0, 35, Sex.UNSPECIFIED)
        assertTrue(neutral in female..male, "expected $neutral between $female and $male")
    }

    @Test
    fun `never returns a negative rate for extreme inputs`() {
        val bmr = BasalMetabolicRate.calculate(30.0, 100.0, 120, Sex.FEMALE)
        assertTrue(bmr >= 0.0)
    }
}

class CalorieTargetTest {

    private fun profile(
        sex: Sex = Sex.MALE,
        weight: Double = 85.0,
        pace: GoalPace = GoalPace.LOSE_STEADY,
    ) = UserProfile(
        sex = sex,
        birthDate = LocalDate.of(1990, 1, 1),
        heightCm = 180.0,
        weightKg = weight,
        goalWeightKg = 75.0,
        activityLevel = ActivityLevel.LIGHT,
        goalPace = pace,
    )

    private val today = LocalDate.of(2026, 1, 1)

    @Test
    fun `half a kilo per week is a 550 kcal daily deficit`() {
        val result = CalorieTarget.calculate(
            maintenanceKcal = 2600.0,
            bmrKcal = 1800.0,
            kgPerWeek = -0.5,
            sex = Sex.MALE,
        )
        assertEquals(TargetAdjustment.NONE, result.adjustment)
        assertEquals(2600 - 550, result.targetKcal)
        assertEquals(-550, result.dailyDeltaKcal)
    }

    @Test
    fun `caps an aggressive deficit at a quarter of maintenance`() {
        // 1 kg/week asks for 1100 kcal/day, which is 55% of a 2000 kcal maintenance.
        val result = CalorieTarget.calculate(
            maintenanceKcal = 2000.0,
            bmrKcal = 1400.0,
            kgPerWeek = -1.0,
            sex = Sex.MALE,
        )
        assertTrue(result.adjustment != TargetAdjustment.NONE)
        assertTrue(
            result.targetKcal >= 1500,
            "target ${result.targetKcal} fell below the safety floor",
        )
        assertTrue(
            abs(result.effectiveKgPerWeek) < 1.0,
            "effective rate should be gentler than requested",
        )
    }

    @Test
    fun `never prescribes below the hard floor even for a small person on an aggressive goal`() {
        val result = CalorieTarget.calculate(
            maintenanceKcal = 1600.0,
            bmrKcal = 1250.0,
            kgPerWeek = -1.0,
            sex = Sex.FEMALE,
        )
        assertTrue(result.targetKcal >= CalorieTarget.FLOOR_FEMALE)
    }

    @Test
    fun `never prescribes below resting metabolic rate plus margin`() {
        val result = CalorieTarget.calculate(
            maintenanceKcal = 2800.0,
            bmrKcal = 2200.0,
            kgPerWeek = -1.0,
            sex = Sex.MALE,
        )
        assertTrue(
            result.targetKcal >= 2200 * CalorieTarget.BMR_SAFETY_MARGIN,
            "target ${result.targetKcal} dipped under BMR margin",
        )
    }

    @Test
    fun `prescribes maintenance when no safe deficit exists`() {
        // Resting rate almost equal to maintenance: every safe target is above maintenance, so the
        // only honest answer is maintenance itself and a near-zero effective rate.
        val result = CalorieTarget.calculate(
            maintenanceKcal = 2000.0,
            bmrKcal = 1900.0,
            kgPerWeek = -1.0,
            sex = Sex.MALE,
        )
        assertEquals(2000, result.targetKcal)
        assertEquals(0, result.dailyDeltaKcal)
        assertEquals(TargetAdjustment.RAISED_ABOVE_BMR, result.adjustment)
        assertTrue(abs(result.effectiveKgPerWeek) < 0.01)
    }

    @Test
    fun `a surplus is passed through without clamping`() {
        val result = CalorieTarget.calculate(
            maintenanceKcal = 2500.0,
            bmrKcal = 1700.0,
            kgPerWeek = 0.25,
            sex = Sex.MALE,
        )
        assertEquals(TargetAdjustment.NONE, result.adjustment)
        assertTrue(result.targetKcal > 2500)
    }

    @Test
    fun `maintenance goal yields the maintenance number`() {
        val result = CalorieTarget.forProfile(profile(pace = GoalPace.MAINTAIN), today)
        assertEquals(result.maintenanceKcal, result.targetKcal)
        assertEquals(0, result.dailyDeltaKcal)
    }

    @Test
    fun `every goal pace produces a target above the floor`() {
        for (pace in GoalPace.entries) {
            for (sex in Sex.entries) {
                val result = CalorieTarget.forProfile(profile(sex = sex, weight = 55.0, pace = pace), today)
                val floor = if (sex == Sex.FEMALE) CalorieTarget.FLOOR_FEMALE else CalorieTarget.FLOOR_MALE
                assertTrue(
                    result.targetKcal >= floor,
                    "$pace/$sex produced ${result.targetKcal}, below floor $floor",
                )
            }
        }
    }
}

class MacroCalculatorTest {

    @Test
    fun `protein is anchored to goal weight, not current weight`() {
        val macros = MacroCalculator.calculate(targetKcal = 2000, goalWeightKg = 75.0, inDeficit = true)
        assertEquals(150, macros.proteinG)
    }

    @Test
    fun `macro calories add up to the target`() {
        val macros = MacroCalculator.calculate(targetKcal = 2200, goalWeightKg = 70.0, inDeficit = true)
        val total = macros.proteinG * 4 + macros.carbsG * 4 + macros.fatG * 9
        // Carbs are floored to whole grams, so allow a few kcal of rounding slack.
        assertTrue(abs(total - 2200) <= 4, "macros summed to $total, expected ~2200")
    }

    @Test
    fun `a very low target does not produce negative carbohydrate`() {
        val macros = MacroCalculator.calculate(targetKcal = 1200, goalWeightKg = 90.0, inDeficit = true)
        assertTrue(macros.carbsG >= 0)
    }
}

class AdaptiveTdeeTest {

    private fun records(days: Int, intake: Int, exercise: Int) =
        (0 until days).map {
            DailyEnergyRecord(LocalDate.of(2026, 1, 1).plusDays(it.toLong()), intake, exercise)
        }

    @Test
    fun `falls back to the formula when there is not enough history`() {
        val result = AdaptiveTdee.calculate(2500.0, records(10, 2000, 200), 80.0, 79.5)
        assertNull(result.measuredTdeeKcal)
        assertEquals(2500, result.tdeeKcal)
        assertEquals(TdeeConfidence.LOW, result.confidence)
    }

    @Test
    fun `recovers a known true maintenance from simulated data`() {
        // Simulate someone whose real maintenance is 2800 but whose formula says 2400.
        // Eating 2300 with no exercise is a 500/day deficit -> 0.909 kg over 14 days.
        val days = 14
        val trueDeficitPerDay = 500.0
        val lossKg = trueDeficitPerDay * days / 7700.0

        val result = AdaptiveTdee.calculate(
            formulaTdee = 2400.0,
            records = records(days, 2300, 0),
            trendWeightStartKg = 80.0,
            trendWeightEndKg = 80.0 - lossKg,
        )

        assertNotNull(result.measuredTdeeKcal)
        assertEquals(2800, result.measuredTdeeKcal!!, "measured TDEE should recover the true value")
        // The blended value should have moved off the formula toward the measured truth.
        assertTrue(
            result.tdeeKcal > 2400,
            "blended TDEE ${result.tdeeKcal} should exceed the formula's 2400",
        )
        assertTrue(result.tdeeKcal < 2800)
    }

    @Test
    fun `weights the measured value more heavily as evidence accumulates`() {
        val lossFor = { days: Int -> 500.0 * days / 7700.0 }
        val short = AdaptiveTdee.calculate(2400.0, records(14, 2300, 0), 80.0, 80.0 - lossFor(14))
        val long = AdaptiveTdee.calculate(2400.0, records(28, 2300, 0), 80.0, 80.0 - lossFor(28))

        assertTrue(
            long.tdeeKcal > short.tdeeKcal,
            "28 days (${long.tdeeKcal}) should trust the measurement more than 14 (${short.tdeeKcal})",
        )
        assertEquals(TdeeConfidence.HIGH, long.confidence)
    }

    @Test
    fun `rejects implausible results rather than acting on them`() {
        // Severe under-logging: claims 800 kcal/day while weight holds steady.
        val result = AdaptiveTdee.calculate(2400.0, records(20, 300, 0), 80.0, 80.0)
        assertNull(result.measuredTdeeKcal)
        assertEquals(2400, result.tdeeKcal)
    }

    @Test
    fun `stable weight means maintenance equals intake plus exercise`() {
        val result = AdaptiveTdee.calculate(2000.0, records(21, 2400, 300), 75.0, 75.0)
        assertEquals(2700, result.measuredTdeeKcal)
    }

    @Test
    fun `exercise calories are included in the measured maintenance`() {
        val withExercise = AdaptiveTdee.calculate(2400.0, records(21, 2000, 400), 80.0, 80.0)
        val without = AdaptiveTdee.calculate(2400.0, records(21, 2000, 0), 80.0, 80.0)
        assertEquals(400, withExercise.measuredTdeeKcal!! - without.measuredTdeeKcal!!)
    }
}
