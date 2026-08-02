package app.zephyr.fitness.data.steps

import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.core.content.ContextCompat
import app.zephyr.fitness.data.db.DailyStepsEntity
import app.zephyr.fitness.data.db.HourlyStepsEntity
import app.zephyr.fitness.data.db.StepsDao
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.roundToInt

/**
 * Reads the phone's hardware step counter.
 *
 * `TYPE_STEP_COUNTER` reports steps since the device last booted, not since midnight, and it resets
 * to zero on reboot. Turning that into a daily figure needs a stored baseline per day plus reboot
 * detection — without it, a phone restarted at lunchtime either wipes the morning's steps or, worse,
 * double-counts them. The sensor is also low-power and batched by the SoC, which is why it's used
 * instead of counting steps from GPS or an accelerometer service that would drain the battery.
 */
class StepRepository(
    private val context: Context,
    private val dao: StepsDao,
) {

    private val sensorManager: SensorManager? =
        ContextCompat.getSystemService(context, SensorManager::class.java)

    private val counter: Sensor?
        get() = sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)

    val isAvailable: Boolean get() = counter != null

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.ACTIVITY_RECOGNITION,
        ) == PackageManager.PERMISSION_GRANTED

    /**
     * Reads the counter once and folds it into today's total.
     *
     * Called from a periodic worker rather than a long-lived listener: the sensor's value is
     * cumulative, so sampling it every fifteen minutes loses nothing and costs no wakelock.
     */
    suspend fun sync(today: LocalDate = LocalDate.now(), atTime: LocalTime = LocalTime.now()) {
        val sensor = counter ?: return
        if (!hasPermission()) return

        val reading = readOnce(sensor) ?: return
        val existing = dao.forDate(today)

        val updated = when {
            // First reading of the day: anchor the baseline, count nothing yet.
            existing == null -> DailyStepsEntity(
                date = today,
                steps = 0,
                sensorBaseline = reading,
                lastSensorValue = reading,
            )

            // Counter went backwards, so the device rebooted. Re-anchor and keep the steps already
            // banked today rather than losing them or counting the new run-up twice.
            existing.lastSensorValue != null && reading < existing.lastSensorValue -> existing.copy(
                sensorBaseline = reading,
                lastSensorValue = reading,
            )

            else -> {
                val baseline = existing.sensorBaseline ?: reading
                val delta = (reading - (existing.lastSensorValue ?: baseline)).coerceAtLeast(0f)
                existing.copy(
                    steps = existing.steps + delta.roundToInt(),
                    lastSensorValue = reading,
                )
            }
        }

        dao.upsert(updated)

        val added = updated.steps - (existing?.steps ?: 0)
        if (added > 0) {
            val hour = atTime.hour
            dao.upsertHour(
                HourlyStepsEntity(today, hour, dao.stepsInHour(today, hour) + added),
            )
        }
    }

    /** Steps in the hour that just ended — the signal behind "you've been still for an hour". */
    suspend fun stepsInLastHour(now: LocalTime = LocalTime.now(), today: LocalDate = LocalDate.now()): Int =
        dao.stepsInHour(today, now.hour)

    /**
     * The step counter normally delivers its current value immediately on registration, but a
     * sleeping SoC can take a moment and some devices never report at all. A bounded wait keeps a
     * background worker from hanging on a sensor that isn't going to answer.
     */
    private suspend fun readOnce(sensor: Sensor): Float? = withTimeoutOrNull(SENSOR_TIMEOUT_MS) {
        val manager = sensorManager ?: return@withTimeoutOrNull null
        suspendCancellableCoroutine { continuation ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    manager.unregisterListener(this)
                    if (continuation.isActive) continuation.resumeWith(Result.success(event.values.firstOrNull()))
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }

            manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            continuation.invokeOnCancellation { manager.unregisterListener(listener) }
        }
    }

    private companion object {
        const val SENSOR_TIMEOUT_MS = 5_000L
    }
}
