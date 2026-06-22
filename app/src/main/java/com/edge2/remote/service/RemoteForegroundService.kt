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
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.edge2.remote.MainActivity
import com.edge2.remote.R

/**
 * Service premier-plan « appareil connecté » : maintient le processus en
 * importance premier-plan tant qu'un toy est connecté, pour que le BLE et le
 * partage survivent en arrière-plan (utile aussi sous GrapheneOS, plus strict).
 * Affiche une notification persistante avec un bouton « Couper » → [AppActions].
 *
 * 100 % AOSP : NotificationManager + Service standard, aucune dépendance Google.
 */
class RemoteForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            AppActions.onStop?.invoke()
            stopSelf()
            return START_NOT_STICKY
        }
        goForeground(intent?.getStringExtra(EXTRA_NAME) ?: "Lovense")
        return START_STICKY
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
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_remote)
            .setContentTitle(name)
            .setContentText(getString(R.string.notif_text))
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun createChannel() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (mgr.getNotificationChannel(CHANNEL) == null) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW),
            )
        }
    }

    companion object {
        private const val CHANNEL = "edge2_status"
        private const val NOTIF_ID = 1
        private const val EXTRA_NAME = "name"
        const val ACTION_STOP = "com.edge2.remote.action.STOP"

        /** Démarre/maj le service avec le nom du toy connecté. */
        fun start(context: Context, name: String) {
            val i = Intent(context, RemoteForegroundService::class.java).putExtra(EXTRA_NAME, name)
            ContextCompat.startForegroundService(context, i)
        }

        /** Arrête le service (toy déconnecté). */
        fun stop(context: Context) {
            context.stopService(Intent(context, RemoteForegroundService::class.java))
        }
    }
}
