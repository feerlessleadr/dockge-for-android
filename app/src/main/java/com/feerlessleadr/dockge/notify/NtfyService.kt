package com.feerlessleadr.dockge.notify

import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.feerlessleadr.dockge.DockgeApp

/**
 * Foreground service that keeps the process (and so the ntfy stream owned by [NtfyListener]) alive while ntfy is
 * enabled. It owns no logic of its own. START_STICKY: if Android kills it, Android starts it again.
 */
class NtfyService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val container = (application as DockgeApp).container
        // Android requires startForeground soon after startForegroundService, even if we stop right away.
        val notification = container.notifier.serviceNotification()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(AlertNotifier.SERVICE_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(AlertNotifier.SERVICE_NOTIFICATION_ID, notification)
        }
        if (!container.ntfy.settings().enabled) {
            stopSelf()
            return START_NOT_STICKY
        }
        container.ntfy.listener.apply()
        return START_STICKY
    }
}

/** Starts the service again after a reboot or an app update, when ntfy is enabled. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        ((context.applicationContext as? DockgeApp)?.container)?.ntfy?.startServiceIfEnabled()
    }
}
