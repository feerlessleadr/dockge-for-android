package com.feerlessleadr.dockge.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.feerlessleadr.dockge.MainActivity
import com.feerlessleadr.dockge.R

/** Creates the notification channels, the permanent service notification and the alert notifications. */
class AlertNotifier(private val context: Context) {
    private val manager = context.getSystemService(NotificationManager::class.java)

    init {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Dockge alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Crash loops, unhealthy containers and image updates sent through your ntfy server."
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_SERVICE, "Alert connection", NotificationManager.IMPORTANCE_MIN).apply {
                description = "The permanent notification Android requires while the app listens for alerts."
                setShowBadge(false)
            },
        )
    }

    /** The low-priority notification of the foreground service. Tapping it opens the app. */
    fun serviceNotification(): Notification =
        NotificationCompat.Builder(context, CHANNEL_SERVICE)
            .setSmallIcon(R.drawable.ic_stat_dockge)
            .setContentTitle("Dockge is listening for alerts")
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(open(null, SERVICE_REQUEST))
            .build()

    /**
     * Shows one alert. The lock screen shows only the generic [AlertKind.publicTitle]; title and text appear after
     * unlocking. [target] decides where a tap goes: a stack, an instance, or just the app.
     */
    fun post(message: NtfyMessage, kind: AlertKind, target: AlertTarget?) {
        val id = message.id.hashCode()
        val public = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_dockge)
            .setContentTitle(kind.publicTitle)
            .build()
        val title = message.title.ifBlank { kind.publicTitle }
        val notification = NotificationCompat.Builder(context, CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_dockge)
            .setContentTitle(title)
            .setContentText(message.message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message.message))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setWhen(if (message.time > 0) message.time * 1000 else System.currentTimeMillis())
            .setAutoCancel(true)
            .setContentIntent(open(target, id))
            .build()
        manager.notify(id, notification)
    }

    private fun open(target: AlertTarget?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        if (target != null) intent.data = Uri.parse(DeepLink.build(target))
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    companion object {
        const val CHANNEL_ALERTS = "alerts"
        const val CHANNEL_SERVICE = "alert_connection"
        const val SERVICE_NOTIFICATION_ID = 1
        private const val SERVICE_REQUEST = 1
    }
}
