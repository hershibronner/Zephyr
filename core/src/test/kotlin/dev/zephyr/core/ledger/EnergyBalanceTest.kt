package dev.zephyr.core.ledger

import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnergyBalanceTest {

    private val date = LocalDate.of(2026, 4, 10)

    private fun balance(
        target: Int = 2000,
        consumed: Int = 0,
        exercise: Int = 0,
        protein: Int = 0,
        maintenance: Int = 2500,
    ) = EnergyBalance(
        date = date,
        targetKcal = target,
        consumedKcal = consumed,
        exerciseKcal = exercise,
        proteinTargetG = 150,
        macros = MacroTotals(proteinG = protein),
        maintenanceKcal = maintenance,
    )

    @Test
    fun `exercise expands the day's budget`() {
        val withRun = balance(consumed = 1500, exercise = 400)
        assertEquals(2400, withRun.adjustedTargetKcal)
        assertEquals(900, withRun.remainingKcal)
    }

    @Test
    fun `going over budget produces a negative remainder`() {
        assertEquals(-300, balance(consumed = 2300).remainingKcal)
    }

    @Test
    fun `an untouched day reads as on track rather than under-fuelled`() {
        assertEquals(BalanceState.ON_TRACK, balance(consumed = 0).state)
    }

    @Test
    fun `states reflect where the day actually stands`() {
        assertEquals(BalanceState.OVER, balance(consumed = 2400).state)
        assertEquals(BalanceState.NEARLY_THERE, balance(consumed = 1950).state)
        assertEquals(BalanceState.ON_TRACK, balance(consumed = 1500).state)
        assertEquals(BalanceState.UNDER_FUELLED, balance(consumed = 700).state)
    }

    @Test
    fun `protein progress is reported and clamped`() {
        assertEquals(0.5f, balance(protein = 75).proteinFraction, 0.001f)
        assertEquals(1f, balance(protein = 300).proteinFraction)
        assertEquals(50, balance(protein = 100).proteinRemainingG)
        assertEquals(0, balance(protein = 200).proteinRemainingG)
    }
}

class LedgerSummaryTest {

    private val start = LocalDate.of(2026, 4, 6)

    private fun week(consumed: Int, exercise: Int = 0, target: Int = 2000, maintenance: Int = 2500) =
        (0..6).map {
            EnergyBalance(
                date = start.plusDays(it.toLong()),
                targetKcal = target,
                consumedKcal = consumed,
                exerciseKcal = exercise,
                proteinTargetG = 150,
                macros = MacroTotals(proteinG = 140),
                maintenanceKcal = maintenance,
            )
        }

    @Test
    fun `an empty week reports empty rather than dividing by zero`() {
        val summary = LedgerSummary.weekly(emptyList())
        assertTrue(summary.isEmpty)
        assertEquals(0, summary.adherencePercent)
    }

    @Test
    fun `unlogged days are excluded from the averages`() {
        val mixed = week(consumed = 2000) + week(consumed = 0)
        val summary = LedgerSummary.weekly(mixed)
        assertEquals(7, summary.daysLogged)
        assertEquals(2000, summary.averageIntakeKcal)
    }

    @Test
    fun `eating to a deficit target projects real weight loss`() {
        // 2000 eaten against 2500 maintenance is 500/day, which is 3500/week, about 0.45 kg.
        val summary = LedgerSummary.weekly(week(consumed = 2000, maintenance = 2500))
        assertEquals(-0.45, summary.projectedWeeklyKg, 0.02)
    }

    @Test
    fun `eating at maintenance projects no change`() {
        val summary = LedgerSummary.weekly(week(consumed = 2500, target = 2500, maintenance = 2500))
        assertEquals(0.0, summary.projectedWeeklyKg, 0.01)
    }

    @Test
    fun `exercise deepens the projected deficit`() {
        val sedentary = LedgerSummary.weekly(week(consumed = 2000, exercise = 0))
        val active = LedgerSummary.weekly(week(consumed = 2000, exercise = 400))
        assertTrue(
            active.projectedWeeklyKg < sedentary.projectedWeeklyKg,
            "burning 400 kcal a day should project faster loss",
        )
    }

    @Test
    fun `a surplus projects gain`() {
        val summary = LedgerSummary.weekly(week(consumed = 3000, target = 3000, maintenance = 2500))
        assertTrue(summary.projectedWeeklyKg > 0)
    }

    @Test
    fun `hitting the target every day is full adherence`() {
        assertEquals(100, LedgerSummary.weekly(week(consumed = 2000)).adherencePercent)
    }

    @Test
    fun `eating under target still counts as adherent`() {
        // Under budget is not a failure to hit a number; it's a day inside the plan.
        assertEquals(100, LedgerSummary.weekly(week(consumed = 1700)).adherencePercent)
    }

    @Test
    fun `blowing past the target is not adherent`() {
        assertEquals(0, LedgerSummary.weekly(week(consumed = 3200)).adherencePercent)
    }
}
