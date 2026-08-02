package dev.zephyr.core.activity

import kotlin.math.max
import kotlin.math.roundToInt

enum class ActivityType(val label: String) {
    RUN("Run"),
    HIKE("Hike"),
    WALK("Walk"),
    CYCLE("Cycle"),
    STRENGTH("Strength"),
    OTHER("Other"),
}

data class ActivityBurn(
    val kcal: Int,
    val met: Double,
    /** Calories the user would have burned anyway just being alive for that time. */
    val restingKcal: Int,
) {
    /** Burn above resting — the only part that legitimately expands the day's food budget. */
    val netKcal: Int get() = max(kcal - restingKcal, 0)
}

/**
 * MET-based energy expenditure: `kcal/min = MET · 3.5 · kg / 200`.
 *
 * Two details matter more than the equation itself.
 *
 * First, grade. A MET value for running assumes flat ground, so a hike that gains 600 m gets scored
 * like a gentle walk unless the climb is accounted for — which is why hikers wonder where their
 * calories went. Vertical work is added explicitly from elevation gain.
 *
 * Second, [ActivityBurn.netKcal]. Gross MET calories include the resting metabolism the user is
 * already budgeting for in their TDEE. Adding gross exercise calories to a TDEE-derived target
 * double-counts that baseline and hands back calories that were never earned — a systematic
 * overfeed of a few hundred kcal a week, enough to erase a modest deficit entirely.
 */
object ActivityCalories {

    private const val RESTING_MET = 1.0

    /** Efficiency of vertical work: roughly 5 kcal per kg per 100 m climbed at ~25% efficiency. */
    private const val KCAL_PER_KG_PER_METRE_CLIMB = 0.0091

    fun estimate(
        type: ActivityType,
        durationSeconds: Long,
        distanceMetres: Double,
        elevationGainMetres: Double,
        weightKg: Double,
    ): ActivityBurn {
        val minutes = durationSeconds / 60.0
        if (minutes <= 0) return ActivityBurn(0, 0.0, 0)

        val speedKmh = if (distanceMetres > 0) distanceMetres / 1000.0 / (minutes / 60.0) else 0.0
        val met = metFor(type, speedKmh)

        val baseKcal = met * 3.5 * weightKg / 200.0 * minutes
        val climbKcal = max(elevationGainMetres, 0.0) * weightKg * KCAL_PER_KG_PER_METRE_CLIMB
        val restingKcal = RESTING_MET * 3.5 * weightKg / 200.0 * minutes

        return ActivityBurn(
            kcal = (baseKcal + climbKcal).roundToInt(),
            met = met,
            restingKcal = restingKcal.roundToInt(),
        )
    }

    /**
     * MET lookup by speed, interpolated from the Compendium of Physical Activities. Speeds outside
     * the tabulated range clamp to the nearest entry rather than extrapolating into fiction.
     */
    fun metFor(type: ActivityType, speedKmh: Double): Double = when (type) {
        ActivityType.RUN -> interpolate(RUN_METS, speedKmh)
        ActivityType.WALK -> interpolate(WALK_METS, speedKmh)
        // Hiking METs are for the flat component only; the climb is paid for separately above.
        ActivityType.HIKE -> interpolate(HIKE_METS, speedKmh)
        ActivityType.CYCLE -> interpolate(CYCLE_METS, speedKmh)
        ActivityType.STRENGTH -> 5.0
        ActivityType.OTHER -> 4.0
    }

    private val RUN_METS = listOf(
        6.4 to 6.0, 8.0 to 8.3, 9.7 to 9.8, 11.3 to 11.0,
        12.9 to 11.8, 14.5 to 12.8, 16.1 to 14.5, 17.7 to 16.0, 19.3 to 19.0,
    )

    private val WALK_METS = listOf(
        3.2 to 2.0, 4.0 to 2.8, 4.8 to 3.5, 5.6 to 4.3, 6.4 to 5.0, 7.2 to 7.0,
    )

    private val HIKE_METS = listOf(
        3.0 to 4.5, 4.0 to 5.3, 5.0 to 6.0, 6.0 to 6.8,
    )

    private val CYCLE_METS = listOf(
        16.0 to 4.0, 19.0 to 6.8, 22.5 to 8.0, 25.5 to 10.0, 30.0 to 12.0,
    )

    private fun interpolate(table: List<Pair<Double, Double>>, speed: Double): Double {
        if (speed <= table.first().first) return table.first().second
        if (speed >= table.last().first) return table.last().second
        for (i in 0 until table.size - 1) {
            val (s1, m1) = table[i]
            val (s2, m2) = table[i + 1]
            if (speed in s1..s2) {
                val t = (speed - s1) / (s2 - s1)
                return m1 + t * (m2 - m1)
            }
        }
        return table.last().second
    }
}
