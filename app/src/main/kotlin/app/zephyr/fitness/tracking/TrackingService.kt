package app.zephyr.fitness.tracking

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import app.zephyr.fitness.MainActivity
import app.zephyr.fitness.R
import app.zephyr.fitness.ZephyrApplication
import app.zephyr.fitness.coach.Notifier
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.zephyr.core.activity.ActivityType
import dev.zephyr.core.activity.Pace
import dev.zephyr.core.activity.GeoPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Records a run, walk or hike while the app is in the background or the screen is off.
 *
 * This has to be a foreground service. Android will freeze a backgrounded process within minutes
 * and stop delivering location to it, so a tracker built on an Activity or a plain coroutine
 * silently stops recording the moment the user pockets the phone — which is precisely when a run
 * starts. The persistent notification is the price of the guarantee, so it is made useful: live
 * distance and duration, and a tap back into the session.
 */
class TrackingService : Service() {

    private lateinit var repository: TrackingRepository
    private lateinit var client: FusedLocationProviderClient

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var tickJob: Job? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.locations.forEach { location ->
                repository.addPoint(
                    GeoPoint(
                        latitude = location.latitude,
                        longitude = location.longitude,
                        altitudeMetres = if (location.hasAltitude()) location.altitude else null,
                        timestampMillis = location.time,
                        accuracyMetres = if (location.hasAccuracy()) location.accuracy else 0f,
                    ),
                )
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val container = (application as ZephyrApplication).container
        repository = container.trackingRepository
        client = LocationServices.getFusedLocationProviderClient(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val type = runCatching {
                    ActivityType.valueOf(intent.getStringExtra(EXTRA_TYPE) ?: ActivityType.RUN.name)
                }.getOrDefault(ActivityType.RUN)
                val weight = intent.getDoubleExtra(EXTRA_WEIGHT_KG, 75.0)
                startTracking(type, weight)
            }

            ACTION_PAUSE -> {
                repository.pause()
                stopLocationUpdates()
                updateNotification()
            }

            ACTION_RESUME -> {
                repository.resume()
                startLocationUpdates()
                updateNotification()
            }

            ACTION_STOP -> {
                stopLocationUpdates()
                stopForegroundCompat()
                stopSelf()
            }
        }
        // Recreating the service without its intent would leave a zombie notification and no
        // recording, so the system is told not to bother.
        return START_NOT_STICKY
    }

    private fun startTracking(type: ActivityType, weightKg: Double) {
        repository.start(type, weightKg)
        startForegroundCompat()
        startLocationUpdates()

        // The duration must advance between GPS fixes, otherwise a runner waiting for signal watches
        // a frozen clock and assumes the app has died.
        tickJob?.cancel()
        tickJob = scope.launch {
            while (isActive) {
                delay(1_000)
                repository.tick()
                if (System.currentTimeMillis() % NOTIFICATION_REFRESH_MS < 1_000) updateNotification()
            }
        }
    }

    private fun startLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, UPDATE_INTERVAL_MS)
            .setMinUpdateIntervalMillis(FASTEST_INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .build()

        // The caller checks the permission before starting the service; if it was revoked in between,
        // the request throws and there is nothing useful to do but stop cleanly.
        runCatching { client.requestLocationUpdates(request, callback, mainLooper) }
            .onFailure { stopSelf() }
    }

    private fun stopLocationUpdates() {
        runCatching { client.removeLocationUpdates(callback) }
    }

    override fun onDestroy() {
        tickJob?.cancel()
        scope.cancel()
        stopLocationUpdates()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundCompat() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun updateNotification() {
        val manager = getSystemService(NotificationManager::class.java)
        manager?.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val state = repository.state.value
        val miles = state.distanceMetres / 1609.344
        val minutes = state.elapsedSeconds / 60
        val seconds = state.elapsedSeconds % 60

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val title = if (state.phase == TrackingPhase.PAUSED) {
            "${state.type.label} paused"
        } else {
            "${state.type.label} in progress"
        }

        return NotificationCompat.Builder(this, Notifier.CHANNEL_TRACKING)
            .setContentTitle(title)
            .setContentText(
                String.format(
                    Locale.US,
                    "%.2f mi · %d:%02d · %s /mi",
                    miles,
                    minutes,
                    seconds,
                    Pace.format(state.averagePaceSecondsPerKm?.let { it * 1.609344 }),
                ),
            )
            .setSmallIcon(R.drawable.ic_notification)
            .setContentIntent(open)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        const val ACTION_START = "app.zephyr.fitness.TRACK_START"
        const val ACTION_PAUSE = "app.zephyr.fitness.TRACK_PAUSE"
        const val ACTION_RESUME = "app.zephyr.fitness.TRACK_RESUME"
        const val ACTION_STOP = "app.zephyr.fitness.TRACK_STOP"

        private const val EXTRA_TYPE = "type"
        private const val EXTRA_WEIGHT_KG = "weightKg"

        private const val NOTIFICATION_ID = 4201
        private const val UPDATE_INTERVAL_MS = 2_000L
        private const val FASTEST_INTERVAL_MS = 1_000L
        private const val MIN_DISTANCE_M = 3f
        private const val NOTIFICATION_REFRESH_MS = 5_000L

        fun start(context: Context, type: ActivityType, weightKg: Double) {
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_TYPE, type.name)
                .putExtra(EXTRA_WEIGHT_KG, weightKg)
            context.startForegroundService(intent)
        }

        fun send(context: Context, action: String) {
            context.startService(Intent(context, TrackingService::class.java).setAction(action))
        }
    }
}
