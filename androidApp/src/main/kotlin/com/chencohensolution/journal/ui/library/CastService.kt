package com.chencohensolution.journal.ui.library

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
import androidx.core.app.ServiceCompat
import com.chencohensolution.journal.R

/**
 * Runs while casting, with a notification ("Showing on <TV>", Stop). It keeps the app counted as in the foreground
 * when the owner switches apps or the screen goes off, so the Wi-Fi locks in [JournalCast] still hold and the TV can
 * keep reaching [CastServer] (a low-latency Wi-Fi lock does nothing for an app in the background).
 */
class CastService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            JournalCast.stopCasting()
            stopSelf()
            return START_NOT_STICKY
        }
        val tv = intent?.getStringExtra(EXTRA_TV)
        val notification = notification(this, tv)
        val started = runCatching {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification,
                if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK else 0,
            )
        }
        if (started.isFailure) {
            Log.w(TAG, "can't stay in the foreground", started.exceptionOrNull())
            stopSelf()
        }
        return START_NOT_STICKY
    }

    companion object {
        private const val TAG = "CastService"
        private const val CHANNEL_ID = "cast"
        private const val NOTIFICATION_ID = 7301
        private const val ACTION_STOP = "com.chencohensolution.journal.cast.STOP"
        private const val EXTRA_TV = "tv"

        /** Starts it as casting begins (the app is in front then, as Android requires). */
        fun start(context: Context, tv: String?) {
            runCatching {
                context.startForegroundService(Intent(context, CastService::class.java).putExtra(EXTRA_TV, tv))
            }.onFailure { Log.w(TAG, "not started", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, CastService::class.java))
        }

        private fun notification(context: Context, tv: String?): Notification {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(CHANNEL_ID) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, context.getString(R.string.cast_notification_channel), NotificationManager.IMPORTANCE_LOW),
                )
            }
            val open = context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
                PendingIntent.getActivity(context, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            }
            val stop = PendingIntent.getService(
                context, 1, Intent(context, CastService::class.java).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_slideshow)
                .setContentTitle(if (tv != null) context.getString(R.string.cast_notification_on, tv) else context.getString(R.string.cast_title))
                .setContentText(context.getString(R.string.cast_notification_text))
                .setContentIntent(open)
                .addAction(0, context.getString(R.string.cast_stop), stop)
                .setOngoing(true)
                .setSilent(true)
                .build()
        }
    }
}
