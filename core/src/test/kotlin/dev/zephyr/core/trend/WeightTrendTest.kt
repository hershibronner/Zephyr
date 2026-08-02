package dev.zephyr.core.trend

import java.time.LocalDate
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WeightTrendTest {

    private val start = LocalDate.of(2026, 1, 1)

    private fun entries(vararg weights: Double) =
        weights.mapIndexed { i, w -> WeightEntry(start.plusDays(i.toLong()), w) }

    @Test
    fun `empty input produces an empty result rather than throwing`() {
        val result = WeightTrend.calculate(emptyList())
        assertTrue(result.isEmpty)
        assertNull(result.currentTrendKg)
        assertNull(result.weeklyRateKg)
    }

    @Test
    fun `trend converges toward a constant weight`() {
        val result = WeightTrend.calculate(entries(*DoubleArray(60) { 80.0 }))
        assertEquals(80.0, result.currentTrendKg!!, 0.01)
    }

    @Test
    fun `a single day of water weight barely moves the trend`() {
        // Thirty steady days, then one 2kg spike from a salty meal.
        val steady = DoubleArray(30) { 80.0 }.toMutableList()
        steady.add(82.0)
        val result = WeightTrend.calculate(entries(*steady.toDoubleArray()))

        val movement = result.currentTrendKg!! - 80.0
        assertTrue(
            movement < 0.25,
            "a single 2kg spike moved the trend by $movement kg — too reactive to be trusted",
        )
    }

    @Test
    fun `trend follows a sustained real loss`() {
        // 0.1 kg/day for 40 days is unmistakably real, not noise.
        val weights = DoubleArray(40) { 90.0 - it * 0.1 }
        val result = WeightTrend.calculate(entries(*weights))

        assertTrue(result.currentTrendKg!! < 90.0)
        assertTrue(result.currentTrendKg!! > 86.0)
        assertNotNull(result.weeklyRateKg)
        assertTrue(
            result.weeklyRateKg!! < -0.4,
            "expected a clear weekly loss, got ${result.weeklyRateKg}",
        )
    }

    @Test
    fun `gaps between weigh-ins do not create false cliffs`() {
        val sparse = listOf(
            WeightEntry(start, 85.0),
            WeightEntry(start.plusDays(14), 84.0),
            WeightEntry(start.plusDays(28), 83.0),
        )
        val result = WeightTrend.calculate(sparse)

        // A point per day is emitted even though only three readings exist.
        assertEquals(29, result.points.size)
        assertTrue(result.points.count { it.rawKg != null } == 3)
        // No day-to-day jump larger than the smoothing allows.
        val jumps = result.points.zipWithNext { a, b -> abs(b.trendKg - a.trendKg) }
        assertTrue(jumps.all { it < 0.5 }, "found an implausible single-day trend jump")
    }

    @Test
    fun `weekly rate needs at least a week of span`() {
        val result = WeightTrend.calculate(entries(80.0, 79.9, 79.8))
        assertNull(result.weeklyRateKg, "three days is not enough to claim a weekly rate")
    }

    @Test
    fun `goal projection returns null when moving away from the goal`() {
        val gaining = DoubleArray(30) { 80.0 + it * 0.05 }
        val result = WeightTrend.calculate(entries(*gaining))
        assertNull(WeightTrend.projectGoalDate(result, goalKg = 75.0, from = start.plusDays(29)))
    }

    @Test
    fun `goal projection returns a sensible date when losing steadily`() {
        val losing = DoubleArray(40) { 85.0 - it * 0.07 }
        val result = WeightTrend.calculate(entries(*losing))
        val projected = WeightTrend.projectGoalDate(result, goalKg = 80.0, from = start.plusDays(39))

        assertNotNull(projected)
        assertTrue(projected!!.isAfter(start.plusDays(39)))
    }

    @Test
    fun `goal projection returns null for a flat trend`() {
        val result = WeightTrend.calculate(entries(*DoubleArray(30) { 80.0 }))
        assertNull(WeightTrend.projectGoalDate(result, goalKg = 75.0, from = start.plusDays(29)))
    }

    @Test
    fun `implied daily imbalance matches the energy equivalent`() {
        // Half a kilo a week is 7700*0.5/7 = 550 kcal/day.
        assertEquals(-550, WeightTrend.impliedDailyImbalanceKcal(-0.5))
    }

    @Test
    fun `fitted change needs at least two readings`() {
        assertNull(WeightTrend.fittedChangeKg(emptyList()))
        assertNull(WeightTrend.fittedChangeKg(listOf(WeightEntry(start, 80.0))))
    }

    @Test
    fun `fitted change recovers a clean linear loss`() {
        val weights = (0 until 28).map { 90.0 - it * 0.0649 }
        val change = WeightTrend.fittedChangeKg(entries(*weights.toDoubleArray()))!!
        assertEquals(-0.0649 * 27, change, 0.001)
    }

    @Test
    fun `fitted change is unbiased where the smoothed endpoints are not`() {
        // The trend is seeded at the first weigh-in, so during warm-up its endpoints understate real
        // loss by roughly a third. Fed to AdaptiveTdee that reads as a lower maintenance and
        // prescribes a deeper deficit than the user asked for.
        val lossPerDay = 500.0 / 7700.0
        val weights = (0 until 28).map { 90.0 - it * lossPerDay }
        val entries = entries(*weights.toDoubleArray())

        val trueChange = weights.last() - weights.first()
        val fitted = WeightTrend.fittedChangeKg(entries)!!
        val result = WeightTrend.calculate(entries)
        val smoothed = result.points.last().trendKg - result.points.first().trendKg

        assertEquals(trueChange, fitted, 0.01)
        assertTrue(
            abs(smoothed) < abs(trueChange) * 0.8,
            "expected the smoothed endpoints to lag; they didn't, so this test proves nothing",
        )
    }

    @Test
    fun `fitted change shrugs off day-to-day water weight`() {
        val lossPerDay = 0.06
        val noise = listOf(0.8, -0.5, 0.3, -0.9, 0.6, -0.2, 0.1)
        val weights = (0 until 28).map { 90.0 - it * lossPerDay + noise[it % noise.size] }
        val fitted = WeightTrend.fittedChangeKg(entries(*weights.toDoubleArray()))!!

        assertEquals(-lossPerDay * 27, fitted, 0.25)
    }
}
