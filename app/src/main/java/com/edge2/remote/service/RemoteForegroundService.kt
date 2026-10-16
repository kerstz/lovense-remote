package com.edge2.remote.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.edge2.remote.MainActivity
import com.edge2.remote.R
import com.edge2.remote.prefs.Settings

/**
 * "Connected device" foreground service: keeps the process at foreground
 * importance while a toy is connected, so BLE and sharing survive in the
 * background (also needed on the stricter GrapheneOS).
 * Shows a persistent notification with a "Disconnect" button → [AppActions].
 *
 * 100% AOSP: standard NotificationManager + Service, no Google dependency.
 */
class RemoteForegroundService : Service() {

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(Settings.wrapLocale(base))
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppActions.onStop?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        runCatching { goForeground(intent?.getStringExtra(EXTRA_NAME) ?: getString(R.string.app_name)) }
            .onFailure { Log.w(TAG, "Foreground start failed", it); stopSelf() }
        // NOT_STICKY: if the process dies, the BLE link is lost anyway → no point
        // restarting an empty service. While the process lives (kept alive by
        // this service), it survives the app being swiped away.
        return START_NOT_STICKY
    }

    private fun goForeground(name: String) {
        val notif = buildNotification(name)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(name: String): Notification {
        createChannel()
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), flags)
        val stop = PendingIntent.getService(
            this, 1, Intent(this, RemoteForegroundService::class.java).setAction(ACTION_STOP), flags,
        )
        // Lock screen: only a neutral, generic version is shown (no toy name, no
        // app-specific wording) — the toy names are intimate data.
        val public = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(getString(R.string.notif_public_title))
            .build()
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(public)
            .setContentTitle(name)
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java) ?: return
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val TAG = "RemoteFgService"
        private const val CHANNEL = "edge2_status"
        private const val NOTIF_ID = 1
        private const val EXTRA_NAME = "name"
        const val ACTION_STOP = "com.edge2.remote.action.STOP"

        /** Starts/updates the service with the connected toys' names. */
        fun start(context: Context, name: String) {
            val i = Intent(context, RemoteForegroundService::class.java).putExtra(EXTRA_NAME, name)
            runCatching { ContextCompat.startForegroundService(context, i) }
                .onFailure { Log.w(TAG, "startForegroundService refused", it) }
        }

        /** Stops the service (no toy, no sharing). */
        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteForegroundService::class.java))
        }
    }
}
