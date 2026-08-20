package app.zephyr.fitness.coach

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import app.zephyr.fitness.MainActivity
import app.zephyr.fitness.R
import dev.zephyr.core.nudge.Nudge
import dev.zephyr.core.nudge.NudgeAction
import dev.zephyr.core.nudge.NudgeCategory
import dev.zephyr.core.nudge.NudgePriority

/**
 * Turns a [Nudge] into an Android notification.
 *
 * One channel per [NudgeCategory], deliberately: a user who is sick of meal reminders can silence
 * exactly those and keep the reminder for the run they asked to be reminded about. Offering only a
 * single all-or-nothing channel is how an app gets muted wholesale and silently stops working.
 */
class Notifier(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    fun ensureChannels() {
        val channels = NudgeCategory.entries.map { category ->
            NotificationChannel(
                category.channelId,
                category.channelName,
                when (category) {
                    NudgeCategory.SESSION, NudgeCategory.STREAK -> NotificationManager.IMPORTANCE_DEFAULT
                    NudgeCategory.CELEBRATION -> NotificationManager.IMPORTANCE_LOW
                    else -> NotificationManager.IMPORTANCE_DEFAULT
                },
            ).apply { description = category.description }
        }

        // The tracking channel is not a nudge: it carries the ongoing notification a foreground
        // location service is required to show. Low importance so it never makes a sound mid-run,
        // and kept separate so silencing coach reminders cannot silence the recording indicator.
        val tracking = NotificationChannel(
            CHANNEL_TRACKING,
            "Session tracking",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Shows distance and time while a run, walk or hike is being recorded."
            setShowBadge(false)
        }

        manager.createNotificationChannels(channels + tracking)
    }

    fun canPost(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED

    fun post(nudge: Nudge) {
        if (!canPost()) return

        val builder = NotificationCompat.Builder(context, nudge.category.channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(nudge.title)
            .setContentText(nudge.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(nudge.body))
            .setAutoCancel(true)
            .setPriority(
                when (nudge.priority) {
                    NudgePriority.HIGH -> NotificationCompat.PRIORITY_HIGH
                    NudgePriority.LOW -> NotificationCompat.PRIORITY_LOW
                    else -> NotificationCompat.PRIORITY_DEFAULT
                },
            )
            .setContentIntent(intentFor(nudge.actions.firstOrNull() ?: NudgeAction.OPEN_TODAY, nudge.key))

        nudge.actions.take(2).forEach { action ->
            builder.addAction(0, action.label, intentFor(action, nudge.key))
        }

        manager.notify(nudge.key.hashCode(), builder.build())
    }

    private fun intentFor(action: NudgeAction, key: String): PendingIntent {
        val intent = Intent(context, MainActivity::class.java).apply {
            this.action = Intent.ACTION_VIEW
            data = Uri.parse("zephyr://${action.route}")
            putExtra(EXTRA_NUDGE_KEY, key)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        return PendingIntent.getActivity(
            context,
            (key + action.name).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    companion object {
        const val EXTRA_NUDGE_KEY = "nudge_key"

        /** Channel for the ongoing notification shown while a session is being recorded. */
        const val CHANNEL_TRACKING = "tracking"
    }
}
