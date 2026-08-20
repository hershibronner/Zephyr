package app.zephyr.fitness.tracking

import dev.zephyr.core.activity.ActivityCalories
import dev.zephyr.core.activity.ActivityType
import dev.zephyr.core.activity.Geo
import dev.zephyr.core.activity.GeoPoint
import dev.zephyr.core.activity.Pace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class TrackingPhase { IDLE, RUNNING, PAUSED }

/**
 * Everything the Move screen draws, recomputed from the points as they land.
 */
data class TrackingState(
    val phase: TrackingPhase = TrackingPhase.IDLE,
    val type: ActivityType = ActivityType.RUN,
    val points: List<GeoPoint> = emptyList(),
    val distanceMetres: Double = 0.0,
    val elevationGainMetres: Double = 0.0,
    val elevationLossMetres: Double = 0.0,
    /** Moving time, so standing at a crossing does not wreck the average pace. */
    val elapsedSeconds: Long = 0,
    val kcal: Int = 0,
    val netKcal: Int = 0,
    val startedAtMillis: Long = 0,
    /** Pace over the last stretch rather than the whole session, which is what runners watch. */
    val currentPaceSecondsPerKm: Double? = null,
    val gpsAccuracyMetres: Float? = null,
) {
    val isActive: Boolean get() = phase != TrackingPhase.IDLE
    val averagePaceSecondsPerKm: Double? get() = Pace.secondsPerKm(distanceMetres, elapsedSeconds)
}

/**
 * The single source of truth for a session in progress.
 *
 * It is a process singleton held by [app.zephyr.fitness.AppContainer] and written only by
 * [TrackingService]. The UI observes it and never owns it — so rotating the phone, backgrounding
 * the app, or the Activity being destroyed outright cannot lose a run that is still being recorded.
 * That separation is the whole reason a tracker can be trusted with an hour of someone's time.
 */
class TrackingRepository {

    private val _state = MutableStateFlow(TrackingState())
    val state: StateFlow<TrackingState> = _state.asStateFlow()

    /** Body weight is needed for the calorie model; set when a session starts. */
    private var weightKg: Double = 75.0

    /** Wall-clock millis accumulated while running, excluding pauses. */
    private var movingMillis: Long = 0
    private var lastResumeMillis: Long = 0

    fun start(type: ActivityType, weightKg: Double) {
        this.weightKg = weightKg
        movingMillis = 0
        lastResumeMillis = System.currentTimeMillis()
        _state.value = TrackingState(
            phase = TrackingPhase.RUNNING,
            type = type,
            startedAtMillis = lastResumeMillis,
        )
    }

    fun pause() {
        if (_state.value.phase != TrackingPhase.RUNNING) return
        movingMillis += System.currentTimeMillis() - lastResumeMillis
        _state.value = _state.value.copy(phase = TrackingPhase.PAUSED)
    }

    fun resume() {
        if (_state.value.phase != TrackingPhase.PAUSED) return
        lastResumeMillis = System.currentTimeMillis()
        _state.value = _state.value.copy(phase = TrackingPhase.RUNNING)
    }

    /** Returns the finished session's state so it can be saved, then clears itself. */
    fun finish(): TrackingState {
        tick()
        val finished = _state.value
        _state.value = TrackingState()
        movingMillis = 0
        lastResumeMillis = 0
        return finished
    }

    fun discard() {
        _state.value = TrackingState()
        movingMillis = 0
        lastResumeMillis = 0
    }

    /**
     * Adds a fix. Points arriving while paused are dropped rather than stored — otherwise a walk to
     * the car during a pause would be drawn into the route and paid for in calories.
     */
    fun addPoint(point: GeoPoint) {
        val current = _state.value
        if (current.phase != TrackingPhase.RUNNING) return

        val points = current.points + point
        val stats = Geo.summarise(points)
        val elapsed = elapsedSecondsNow()
        val burn = ActivityCalories.estimate(
            type = current.type,
            durationSeconds = elapsed,
            distanceMetres = stats.distanceMetres,
            elevationGainMetres = stats.elevationGainMetres,
            weightKg = weightKg,
        )

        _state.value = current.copy(
            points = points,
            distanceMetres = stats.distanceMetres,
            elevationGainMetres = stats.elevationGainMetres,
            elevationLossMetres = stats.elevationLossMetres,
            elapsedSeconds = elapsed,
            kcal = burn.kcal,
            netKcal = burn.netKcal,
            currentPaceSecondsPerKm = recentPace(points),
            gpsAccuracyMetres = point.accuracyMetres.takeIf { it > 0f },
        )
    }

    /** Advances the clock without a new fix, so the duration keeps counting between GPS updates. */
    fun tick() {
        val current = _state.value
        if (current.phase != TrackingPhase.RUNNING) return
        _state.value = current.copy(elapsedSeconds = elapsedSecondsNow())
    }

    private fun elapsedSecondsNow(): Long {
        val running = if (_state.value.phase == TrackingPhase.RUNNING) {
            System.currentTimeMillis() - lastResumeMillis
        } else {
            0
        }
        return (movingMillis + running) / 1000
    }

    /**
     * Pace over the last ~30 seconds of fixes. A whole-session average barely moves once a run is
     * long, which makes it useless for the thing people actually use live pace for: deciding whether
     * to speed up right now.
     */
    private fun recentPace(points: List<GeoPoint>): Double? {
        if (points.size < 2) return null
        val newest = points.last()
        val window = points.filter { newest.timestampMillis - it.timestampMillis <= RECENT_PACE_WINDOW_MS }
        if (window.size < 2) return null

        val seconds = (newest.timestampMillis - window.first().timestampMillis) / 1000
        if (seconds <= 0) return null
        return Pace.secondsPerKm(Geo.summarise(window).distanceMetres, seconds)
    }

    private companion object {
        const val RECENT_PACE_WINDOW_MS = 30_000L
    }
}
