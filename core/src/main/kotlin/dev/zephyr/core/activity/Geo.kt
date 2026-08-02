package dev.zephyr.core.activity

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class GeoPoint(
    val latitude: Double,
    val longitude: Double,
    val altitudeMetres: Double? = null,
    val timestampMillis: Long = 0L,
    val accuracyMetres: Float = 0f,
)

data class TrackStats(
    val distanceMetres: Double,
    val elevationGainMetres: Double,
    val elevationLossMetres: Double,
    val pointsUsed: Int,
)

/**
 * Geodesic maths for tracked sessions, and the filtering that keeps GPS noise out of the numbers.
 *
 * Consumer GPS reports positions a few metres off even when stationary, and barometric or
 * satellite altitude is far worse — noisy enough that naively summing every upward difference
 * over an hour invents hundreds of metres of climb that were never walked. Since elevation feeds
 * calorie burn, that fiction becomes phantom food budget. Hence the accuracy gate, the minimum
 * step distance, and the altitude threshold below.
 */
object Geo {

    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Positions less accurate than this are dropped; typical good urban fixes are 5–15 m. */
    const val MAX_ACCEPTABLE_ACCURACY_M = 30f

    /** Movement below this between fixes is treated as jitter, not travel. */
    const val MIN_STEP_DISTANCE_M = 2.0

    /** Altitude must change by at least this much before it counts as real climbing. */
    const val MIN_ELEVATION_DELTA_M = 3.0

    /** Great-circle distance in metres between two points. */
    fun distanceMetres(a: GeoPoint, b: GeoPoint): Double {
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)

        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(h), sqrt(1 - h))
    }

    /**
     * Accumulates distance and elevation over a track, discarding low-quality fixes and sub-noise
     * movement. Altitude is compared against the last *accepted* elevation rather than the previous
     * point, so a slow real climb still accumulates while jitter around a level does not.
     */
    fun summarise(points: List<GeoPoint>): TrackStats {
        val usable = points.filter { it.accuracyMetres <= MAX_ACCEPTABLE_ACCURACY_M || it.accuracyMetres == 0f }
        if (usable.size < 2) {
            return TrackStats(0.0, 0.0, 0.0, usable.size)
        }

        var distance = 0.0
        var gain = 0.0
        var loss = 0.0
        var previous = usable.first()
        var referenceAltitude = usable.first().altitudeMetres

        for (i in 1 until usable.size) {
            val current = usable[i]
            val step = distanceMetres(previous, current)
            if (step >= MIN_STEP_DISTANCE_M) {
                distance += step
                previous = current
            }

            val altitude = current.altitudeMetres
            if (altitude != null) {
                if (referenceAltitude == null) {
                    referenceAltitude = altitude
                } else {
                    val delta = altitude - referenceAltitude
                    if (abs(delta) >= MIN_ELEVATION_DELTA_M) {
                        if (delta > 0) gain += delta else loss += -delta
                        referenceAltitude = altitude
                    }
                }
            }
        }

        return TrackStats(distance, gain, loss, usable.size)
    }
}

object Pace {

    /** Seconds per kilometre. Returns null for zero distance rather than infinity. */
    fun secondsPerKm(distanceMetres: Double, durationSeconds: Long): Double? {
        if (distanceMetres <= 0 || durationSeconds <= 0) return null
        return durationSeconds / (distanceMetres / 1000.0)
    }

    /** Formats a pace as `m:ss`, the form every runner reads instinctively. */
    fun format(secondsPerKm: Double?): String {
        if (secondsPerKm == null || secondsPerKm.isInfinite() || secondsPerKm.isNaN()) return "--:--"
        val total = secondsPerKm.roundToInt()
        val minutes = total / 60
        val seconds = total % 60
        return "%d:%02d".format(minutes, seconds)
    }

    fun speedKmh(distanceMetres: Double, durationSeconds: Long): Double {
        if (durationSeconds <= 0) return 0.0
        return distanceMetres / 1000.0 / (durationSeconds / 3600.0)
    }
}
