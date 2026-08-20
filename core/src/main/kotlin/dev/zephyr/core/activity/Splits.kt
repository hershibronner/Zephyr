package dev.zephyr.core.activity

import kotlin.math.abs
import kotlin.math.roundToInt

/** One complete unit of distance within a session, plus the leftover at the end. */
data class Split(
    /** 1-based, so the first mile is "1". */
    val index: Int,
    val distanceMetres: Double,
    val durationSeconds: Long,
    val elevationGainMetres: Double,
    /** False for the final piece when it is shorter than a full unit. */
    val isComplete: Boolean,
) {
    val paceSecondsPerUnit: Double?
        get() = if (distanceMetres <= 0 || durationSeconds <= 0) {
            null
        } else {
            durationSeconds / (distanceMetres / SPLIT_UNIT_METRES)
        }
}

const val SPLIT_UNIT_METRES = 1609.344

/**
 * The per-mile breakdown — the single most useful thing a run report shows.
 *
 * A finish-line average hides the whole story: whether you went out too fast and paid for it, or
 * negative-split it properly. People read splits to answer "did I run this well", which a single
 * average can never tell them.
 */
object Splits {

    /**
     * Cuts a track into miles.
     *
     * Boundaries are interpolated rather than snapped to the nearest GPS fix. Fixes land every few
     * seconds, so snapping would misplace each boundary by up to twenty metres, and the error
     * accumulates — by mile six the splits would be describing distances that were never run.
     */
    fun perMile(points: List<GeoPoint>): List<Split> {
        val usable = points.filter { it.accuracyMetres <= MAX_ACCURACY_M || it.accuracyMetres == 0f }
        if (usable.size < 2) return emptyList()

        val splits = mutableListOf<Split>()

        var index = 1
        var splitDistance = 0.0
        var splitClimb = 0.0
        var splitStartMillis = usable.first().timestampMillis

        for (i in 1 until usable.size) {
            val previous = usable[i - 1]
            val current = usable[i]

            var segment = Geo.distanceMetres(previous, current)
            if (segment < MIN_STEP_M) continue

            val segmentMillis = (current.timestampMillis - previous.timestampMillis).coerceAtLeast(0)
            val climb = climbBetween(previous, current)

            // One segment can span a boundary — or, on a bad fix, several.
            while (splitDistance + segment >= SPLIT_UNIT_METRES) {
                val needed = SPLIT_UNIT_METRES - splitDistance
                val portion = if (segment > 0) needed / segment else 0.0
                val boundaryMillis = previous.timestampMillis + (segmentMillis * portion).toLong()

                splits += Split(
                    index = index,
                    distanceMetres = SPLIT_UNIT_METRES,
                    durationSeconds = (boundaryMillis - splitStartMillis) / 1000,
                    elevationGainMetres = splitClimb + climb * portion,
                    isComplete = true,
                )

                index += 1
                splitStartMillis = boundaryMillis
                splitDistance = 0.0
                splitClimb = 0.0
                segment -= needed
            }

            splitDistance += segment
            splitClimb += climb
        }

        // The tail: worth showing, but never presented as a full mile — a 0.3 mile piece has a
        // pace that looks either heroic or terrible and means neither.
        if (splitDistance > MIN_TAIL_M) {
            splits += Split(
                index = index,
                distanceMetres = splitDistance,
                durationSeconds = (usable.last().timestampMillis - splitStartMillis) / 1000,
                elevationGainMetres = splitClimb,
                isComplete = false,
            )
        }

        return splits
    }

    /** The quickest complete mile — the number people actually quote about a run. */
    fun fastest(splits: List<Split>): Split? =
        splits.filter { it.isComplete }.minByOrNull { it.paceSecondsPerUnit ?: Double.MAX_VALUE }

    /**
     * True when the second half was run faster than the first — a negative split, and the mark of a
     * run paced properly rather than started too hard.
     */
    fun isNegativeSplit(splits: List<Split>): Boolean {
        val complete = splits.filter { it.isComplete }
        if (complete.size < 2) return false
        val half = complete.size / 2
        val first = complete.take(half).averagePace() ?: return false
        val second = complete.drop(complete.size - half).averagePace() ?: return false
        return second < first
    }

    /**
     * How evenly the run was paced, 0..1, where 1 is metronomic. Mean absolute deviation is used
     * rather than variance so one disastrous mile does not dominate the whole score.
     */
    fun consistency(splits: List<Split>): Float? {
        val paces = splits.filter { it.isComplete }.mapNotNull { it.paceSecondsPerUnit }
        if (paces.size < 2) return null
        val mean = paces.average()
        if (mean <= 0) return null
        val deviation = paces.sumOf { abs(it - mean) } / paces.size
        return (1.0 - (deviation / mean)).coerceIn(0.0, 1.0).toFloat()
    }

    private fun List<Split>.averagePace(): Double? {
        val distance = sumOf { it.distanceMetres }
        val seconds = sumOf { it.durationSeconds }
        if (distance <= 0 || seconds <= 0) return null
        return seconds / (distance / SPLIT_UNIT_METRES)
    }

    /** Mirrors the threshold Geo uses, so climb totals agree between the summary and the splits. */
    private fun climbBetween(a: GeoPoint, b: GeoPoint): Double {
        val from = a.altitudeMetres ?: return 0.0
        val to = b.altitudeMetres ?: return 0.0
        val delta = to - from
        return if (delta > MIN_ALTITUDE_STEP_M) delta else 0.0
    }

    private const val MAX_ACCURACY_M = 30f
    private const val MIN_STEP_M = 1.0
    private const val MIN_TAIL_M = 40.0
    private const val MIN_ALTITUDE_STEP_M = 1.0
}

/**
 * A plain-language verdict on a finished session.
 *
 * The point is to answer "was that any good?" without requiring the user to interpret six numbers
 * themselves — and to answer it honestly, including when the answer is "that was a gentle one".
 */
object SessionVerdict {

    fun describe(
        type: ActivityType,
        distanceMetres: Double,
        durationSeconds: Long,
        splits: List<Split>,
        longestRecentMetres: Double?,
    ): String {
        if (durationSeconds < 60) return "Short one. Still counts."

        val miles = distanceMetres / SPLIT_UNIT_METRES
        val parts = mutableListOf<String>()

        if (longestRecentMetres != null && distanceMetres > longestRecentMetres * 1.02) {
            parts += "That's your longest ${type.label.lowercase()} in a month."
        } else if (miles >= 1) {
            parts += "${formatMiles(miles)} miles done."
        } else {
            parts += "Out the door and moving."
        }

        if (Splits.isNegativeSplit(splits)) {
            parts += "You finished faster than you started, which is how it should be done."
        } else {
            Splits.consistency(splits)?.let { steady ->
                if (steady >= 0.9f) parts += "Held a metronome pace the whole way."
                else if (steady < 0.7f) parts += "Pace wandered a fair bit — worth settling into next time."
            }
        }

        return parts.joinToString(" ")
    }

    private fun formatMiles(miles: Double): String {
        val rounded = (miles * 100).roundToInt() / 100.0
        return if (rounded % 1.0 == 0.0) rounded.toInt().toString() else rounded.toString()
    }
}
