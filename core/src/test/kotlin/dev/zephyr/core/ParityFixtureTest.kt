package dev.zephyr.core

import dev.zephyr.core.activity.ActivityCalories
import dev.zephyr.core.activity.ActivityType
import dev.zephyr.core.energy.AdaptiveTdee
import dev.zephyr.core.energy.BasalMetabolicRate
import dev.zephyr.core.energy.CalorieTarget
import dev.zephyr.core.energy.DailyEnergyRecord
import dev.zephyr.core.energy.MacroCalculator
import dev.zephyr.core.ledger.EnergyBalance
import dev.zephyr.core.ledger.LedgerSummary
import dev.zephyr.core.ledger.MacroTotals
import dev.zephyr.core.model.Sex
import dev.zephyr.core.plan.FitnessBaseline
import dev.zephyr.core.plan.PlannedSlot
import dev.zephyr.core.plan.ProgressionEngine
import dev.zephyr.core.plan.WeeklyTemplate
import dev.zephyr.core.steps.DailySteps
import dev.zephyr.core.steps.StepGoalEngine
import dev.zephyr.core.steps.StepPace
import dev.zephyr.core.streak.DayOutcome
import dev.zephyr.core.streak.StreakCalculator
import dev.zephyr.core.trend.WeightEntry
import dev.zephyr.core.trend.WeightTrend
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Emits `prototype/parity-fixtures.json`: the answers this Kotlin implementation gives for a spread
 * of inputs.
 *
 * The web prototype re-implements these same rules in JavaScript so the design can be used for real
 * before it is committed to Compose. Two implementations of one set of rules normally drift, and a
 * prototype that quietly disagrees with the shipping app is worse than no prototype — it produces
 * confident decisions about numbers the user will never actually see.
 *
 * So Kotlin is the source of truth and writes down its answers here; `prototype/parity.test.mjs`
 * replays every case through the JavaScript port and fails on any disagreement. Changing a formula
 * on one side without the other turns the check red.
 */
class ParityFixtureTest {

    private fun d(x: Double) = String.format(Locale.ROOT, "%.6f", x)
    private fun q(s: String) = "\"$s\""

    @Test
    fun `write parity fixtures`() {
        val cases = mutableListOf<String>()

        // --- BMR ---------------------------------------------------------------
        listOf(
            Triple(80.0, 180.0, 30) to Sex.MALE,
            Triple(65.0, 165.0, 30) to Sex.FEMALE,
            Triple(92.0, 183.0, 34) to Sex.MALE,
            Triple(70.0, 175.0, 35) to Sex.UNSPECIFIED,
            Triple(30.0, 100.0, 120) to Sex.FEMALE,
        ).forEach { (t, sex) ->
            val (w, h, a) = t
            cases += """{"fn":"bmr","args":[${d(w)},${d(h)},$a,${q(sex.name)}],"expect":${d(BasalMetabolicRate.calculate(w, h, a, sex))}}"""
        }

        // --- Calorie target ----------------------------------------------------
        listOf(
            listOf(2600.0, 1800.0, -0.5) to Sex.MALE,
            listOf(2000.0, 1400.0, -1.0) to Sex.MALE,
            listOf(1600.0, 1250.0, -1.0) to Sex.FEMALE,
            listOf(2800.0, 2200.0, -1.0) to Sex.MALE,
            listOf(2000.0, 2050.0, -1.0) to Sex.MALE,
            listOf(2500.0, 1700.0, 0.25) to Sex.MALE,
            listOf(2620.0, 1899.0, -0.5) to Sex.MALE,
            listOf(1559.0, 1299.0, -0.5) to Sex.FEMALE,
            listOf(2340.0, 1800.0, -0.5) to Sex.MALE,
            listOf(2400.0, 1600.0, 0.0) to Sex.FEMALE,
        ).forEach { (v, sex) ->
            val r = CalorieTarget.calculate(v[0], v[1], v[2], sex)
            cases += """{"fn":"calorieTarget","args":[${d(v[0])},${d(v[1])},${d(v[2])},${q(sex.name)}],"expect":{"maintenanceKcal":${r.maintenanceKcal},"targetKcal":${r.targetKcal},"dailyDeltaKcal":${r.dailyDeltaKcal},"adjustment":${q(r.adjustment.name)},"effectiveKgPerWeek":${d(r.effectiveKgPerWeek)}}}"""
        }

        // --- Macros ------------------------------------------------------------
        listOf(
            Triple(2070, 82.0, true),
            Triple(2000, 75.0, true),
            Triple(2200, 70.0, true),
            Triple(1200, 90.0, true),
            Triple(2800, 80.0, false),
        ).forEach { (kcal, goal, deficit) ->
            val m = MacroCalculator.calculate(kcal, goal, deficit)
            cases += """{"fn":"macros","args":[$kcal,${d(goal)},$deficit],"expect":{"proteinG":${m.proteinG},"fatG":${m.fatG},"carbsG":${m.carbsG}}}"""
        }

        // --- Weight trend ------------------------------------------------------
        val start = LocalDate.of(2026, 1, 1)
        listOf(
            "steady" to DoubleArray(30) { 80.0 }.toList(),
            "losing" to (0 until 40).map { 90.0 - it * 0.1 },
            "spike" to (DoubleArray(30) { 80.0 }.toList() + 82.0),
            "noisy" to (0 until 35).map { 85.0 - it * 0.05 + (if (it % 3 == 0) 0.9 else -0.4) },
        ).forEach { (name, weights) ->
            val entries = weights.mapIndexed { i, w -> WeightEntry(start.plusDays(i.toLong()), w) }
            val r = WeightTrend.calculate(entries)
            val series = entries.joinToString(",") { """{"date":${q(it.date.toString())},"weightKg":${d(it.weightKg)}}""" }
            cases += """{"fn":"weightTrend","name":${q(name)},"args":[[$series]],"expect":{"currentTrendKg":${d(r.currentTrendKg!!)},"weeklyRateKg":${r.weeklyRateKg?.let { d(it) } ?: "null"},"points":${r.points.size}}}"""
        }

        // --- Adaptive TDEE -----------------------------------------------------
        fun records(days: Int, intake: Int, exercise: Int) =
            (0 until days).map { DailyEnergyRecord(start.plusDays(it.toLong()), intake, exercise) }

        listOf(
            Triple(2400.0, records(14, 2300, 0), 80.0 to 80.0 - 500.0 * 14 / 7700.0),
            Triple(2400.0, records(28, 2300, 0), 80.0 to 80.0 - 500.0 * 28 / 7700.0),
            Triple(2000.0, records(21, 2400, 300), 75.0 to 75.0),
            Triple(2400.0, records(20, 300, 0), 80.0 to 80.0),
            Triple(2500.0, records(10, 2000, 200), 80.0 to 79.5),
        ).forEach { (formula, recs, weights) ->
            val r = AdaptiveTdee.calculate(formula, recs, weights.first, weights.second)
            val recJson = recs.joinToString(",") { """{"intakeKcal":${it.intakeKcal},"exerciseKcal":${it.exerciseKcal}}""" }
            cases += """{"fn":"adaptiveTdee","args":[${d(formula)},[$recJson],${d(weights.first)},${d(weights.second)}],"expect":{"tdeeKcal":${r.tdeeKcal},"measuredTdeeKcal":${r.measuredTdeeKcal ?: "null"},"confidence":${q(r.confidence.name)},"daysOfData":${r.daysOfData}}}"""
        }

        // --- Activity burn -----------------------------------------------------
        listOf(
            listOf(ActivityType.RUN, 3600.0, 10000.0, 0.0, 80.0),
            listOf(ActivityType.HIKE, 7200.0, 8000.0, 800.0, 80.0),
            listOf(ActivityType.HIKE, 7200.0, 8000.0, 0.0, 80.0),
            listOf(ActivityType.WALK, 1800.0, 2500.0, 0.0, 92.0),
            listOf(ActivityType.RUN, 1500.0, 5000.0, 40.0, 92.0),
            listOf(ActivityType.STRENGTH, 2700.0, 0.0, 0.0, 92.0),
            listOf(ActivityType.CYCLE, 3600.0, 25000.0, 200.0, 75.0),
        ).forEach { v ->
            val type = v[0] as ActivityType
            val dur = (v[1] as Double).toLong()
            val dist = v[2] as Double
            val elev = v[3] as Double
            val kg = v[4] as Double
            val b = ActivityCalories.estimate(type, dur, dist, elev, kg)
            cases += """{"fn":"activityBurn","args":[${q(type.name)},$dur,${d(dist)},${d(elev)},${d(kg)}],"expect":{"kcal":${b.kcal},"restingKcal":${b.restingKcal},"netKcal":${b.netKcal}}}"""
        }

        // --- Step goal ---------------------------------------------------------
        listOf(
            listOf(3000, 3200, 2800, 3100, 2900, 3300, 3000) to null,
            listOf(12000, 13000, 11500, 12500, 12000, 13500, 12200) to null,
            listOf(4000, 4200, 3800, 4100, 3900, 4300, 4000) to 12000,
            listOf(4000, 4000, 4000, 4000, 4000, 4000, 40000) to null,
            listOf(100, 50, 80, 120, 90, 70, 60) to null,
        ).forEach { (steps, current) ->
            val history = steps.mapIndexed { i, s -> DailySteps(start.minusDays(i.toLong()), s) }
            val r = StepGoalEngine.suggest(history, current)
            val json = history.joinToString(",") { """{"date":${q(it.date.toString())},"steps":${it.steps}}""" }
            cases += """{"fn":"suggestStepGoal","args":[[$json],${current ?: "null"}],"expect":{"goal":${r.goal},"baselineMedian":${r.baselineMedian}}}"""
        }

        // --- Step pace ---------------------------------------------------------
        listOf(
            listOf(400, 8000, 8, 0),
            listOf(1200, 8000, 16, 0),
            listOf(7000, 8000, 14, 0),
            listOf(16000, 8000, 20, 0),
            listOf(6432, 8000, 14, 20),
            listOf(0, 8000, 3, 30),
        ).forEach { v ->
            val s = StepPace.status(v[0], v[1], LocalTime.of(v[2], v[3]))
            cases += """{"fn":"stepStatus","args":[${v[0]},${v[1]},${v[2]},${v[3]}],"expect":{"expectedByNow":${s.expectedByNow},"deficit":${s.deficit},"onTrack":${s.onTrack},"projectedEndOfDay":${s.projectedEndOfDay},"remaining":${s.remaining},"fractionOfGoal":${d(s.fractionOfGoal.toDouble())}}}"""
        }

        // --- Streak ------------------------------------------------------------
        val today = LocalDate.of(2026, 5, 20)
        fun outcome(off: Long, logged: Boolean = true, within: Boolean = true, steps: Boolean = false, session: Boolean = false) =
            DayOutcome(today.minusDays(off), logged, within, steps, session)

        listOf(
            "consecutive" to (0L..4L).map { outcome(it) },
            "gap" to listOf(outcome(0), outcome(1), outcome(5), outcome(6), outcome(7), outcome(8), outcome(9)),
            "atRisk" to (1L..6L).map { outcome(it) },
            "abandoned" to (5L..10L).map { outcome(it) },
            "savedBySteps" to listOf(outcome(0, within = false, steps = true), outcome(1), outcome(2)),
        ).forEach { (name, outcomes) ->
            val r = StreakCalculator.calculate(outcomes, today)
            val json = outcomes.joinToString(",") {
                """{"date":${q(it.date.toString())},"loggedFood":${it.loggedFood},"withinCalorieTarget":${it.withinCalorieTarget},"hitStepGoal":${it.hitStepGoal},"completedSession":${it.completedSession}}"""
            }
            cases += """{"fn":"calculateStreak","name":${q(name)},"args":[[$json],${q(today.toString())}],"expect":{"current":${r.current},"longest":${r.longest},"atRisk":${r.atRisk}}}"""
        }

        // --- Training progression ---------------------------------------------
        val template = WeeklyTemplate(
            listOf(
                PlannedSlot(1, DayOfWeek.MONDAY, ActivityType.STRENGTH, LocalTime.of(18, 0)),
                PlannedSlot(2, DayOfWeek.TUESDAY, ActivityType.RUN, LocalTime.of(7, 0)),
                PlannedSlot(3, DayOfWeek.THURSDAY, ActivityType.STRENGTH, LocalTime.of(18, 0)),
                PlannedSlot(4, DayOfWeek.FRIDAY, ActivityType.RUN, LocalTime.of(7, 0)),
                PlannedSlot(5, DayOfWeek.SATURDAY, ActivityType.HIKE, LocalTime.of(9, 0)),
            ),
        )
        val weekStart = LocalDate.of(2026, 6, 1)
        for (week in 0..5) {
            for (base in listOf(null, 5000.0)) {
                val p = ProgressionEngine.prescriptionsForWeek(
                    template, weekStart, week,
                    FitnessBaseline(longestRunMetres = base, longestHikeMinutes = null),
                )
                val json = p.joinToString(",") {
                    """{"slotId":${it.slot.id},"date":${q(it.date.toString())},"distance":${it.targetDistanceMetres?.let { v -> d(v) } ?: "null"},"minutes":${it.targetDurationMinutes ?: "null"},"isDeload":${it.isDeload},"headline":${q(it.headline)}}"""
                }
                cases += """{"fn":"prescriptionsForWeek","args":[${q(weekStart.toString())},$week,${base?.let { d(it) } ?: "null"}],"expect":[$json]}"""
            }
        }

        // --- Weekly summary ----------------------------------------------------
        listOf(
            listOf(2000, 0, 2000, 2500),
            listOf(2500, 0, 2500, 2500),
            listOf(2000, 400, 2000, 2500),
            listOf(3000, 0, 3000, 2500),
            listOf(1700, 120, 2000, 2500),
        ).forEach { v ->
            val week = (0..6).map { i ->
                EnergyBalance(
                    date = start.plusDays(i.toLong()),
                    targetKcal = v[2], consumedKcal = v[0], exerciseKcal = v[1],
                    proteinTargetG = 150, macros = MacroTotals(proteinG = 140),
                    maintenanceKcal = v[3],
                )
            }
            val s = LedgerSummary.weekly(week)
            cases += """{"fn":"weeklySummary","args":[${v[0]},${v[1]},${v[2]},${v[3]}],"expect":{"daysLogged":${s.daysLogged},"averageIntakeKcal":${s.averageIntakeKcal},"averageDeltaKcal":${s.averageDeltaKcal},"adherencePercent":${s.adherencePercent},"projectedWeeklyKg":${d(s.projectedWeeklyKg)}}}"""
        }

        val json = "{\n  \"generatedBy\": \"ParityFixtureTest\",\n  \"cases\": [\n    " +
            cases.joinToString(",\n    ") + "\n  ]\n}\n"

        val out = File("../prototype/parity-fixtures.json")
        out.parentFile?.mkdirs()
        out.writeText(json)

        assertTrue(cases.size > 60, "expected a broad fixture set, got ${cases.size}")
    }
}
