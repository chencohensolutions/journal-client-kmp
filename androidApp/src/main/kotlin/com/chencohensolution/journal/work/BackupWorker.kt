package com.chencohensolution.journal.work

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.Manifest
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chencohensolution.journal.MainActivity
import com.chencohensolution.journal.R
import com.chencohensolution.journal.backup.BackupProgress
import com.chencohensolution.journal.backup.WaitReason
import com.chencohensolution.journal.backupController
import com.chencohensolution.journal.ui.library.transferRate

/**
 * One backup run in the background, also with the app closed. Shows its progress in a notification (as a foreground
 * service when Android allows it: always for "Back up now" from the app; a periodic run started in the background
 * gets Android's usual few minutes and continues on the next run, since uploads resume).
 */
class BackupWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var lastNotified = 0L

    override suspend fun doWork(): Result {
        val backup = backupController(applicationContext, WorkBackupScheduler(applicationContext))
        val settings = backup.currentState.settings
        if (!settings.enabled) return Result.success()
        if (settings.wifiNetworks.isNotEmpty()) {
            val network = currentWifiName(applicationContext)
            if (network == null || network !in settings.wifiNetworks) {
                backup.reportWaiting(if (network == null) WaitReason.NEEDS_LOCATION else WaitReason.WIFI_NETWORK_NOT_ALLOWED)
                return Result.success()
            }
        }

        createChannel(applicationContext)
        runCatching { setForeground(foregroundInfo(null)) } // Refused when started in the background on Android 12+.
        try {
            val outcome = backup.runOnce(::showProgress)
            // Why a run ended, for `adb logcat -s BackupWorker` (the screens show the same in Settings → Backup).
            Log.i(TAG, "run ended: $outcome, ${backup.currentState.activity}")
        } finally {
            notifications.cancel(NOTIFICATION_ID)
            val startedByNewPhotos = WorkBackupScheduler.NEW_PHOTOS_TAG in tags
            WorkBackupScheduler(applicationContext).watchForNewPhotos(
                backup.currentState.settings,
                if (startedByNewPhotos) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
            )
        }
        // Never Result.retry(): WorkManager's retry delay doubles up to 5 hours, so a server that was down for a while
        // kept backup waiting long after it was back. The next 15-minute run or new photo tries again instead.
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createChannel(applicationContext)
        return foregroundInfo(null)
    }

    private fun showProgress(progress: BackupProgress) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < NOTIFY_EVERY_MILLIS && progress.done < progress.total) return
        lastNotified = now
        if (applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            notifications.notify(NOTIFICATION_ID, notification(progress))
        }
    }

    private fun foregroundInfo(progress: BackupProgress?) =
        ForegroundInfo(NOTIFICATION_ID, notification(progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun notification(progress: BackupProgress?): Notification {
        val context = applicationContext
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, context.getString(R.string.backup_notification_stop), WorkManager.getInstance(context).createCancelPendingIntent(id))
        if (progress == null || progress.total == 0) {
            builder.setContentTitle(context.getString(R.string.backup_notification_checking)).setProgress(0, 0, true)
        } else {
            val percent = if (progress.bytesTotal > 0) (progress.bytesDone * 100 / progress.bytesTotal).toInt() else 0
            // "42% · 5.1 MB/s · IMG_1234.heic"
            val detail = listOfNotNull(
                "$percent%",
                progress.bytesPerSecond?.let { transferRate(context, it) },
                progress.currentName,
            ).joinToString(" · ")
            builder.setContentTitle(context.getString(R.string.backup_notification_title, progress.done.coerceAtMost(progress.total - 1) + 1, progress.total))
                .setContentText(detail)
                .setProgress(100, percent, false)
        }
        return builder.build()
    }

    companion object {
        private const val TAG = "BackupWorker"
        private const val CHANNEL_ID = "backup"
        private const val NOTIFICATION_ID = 1001
        private const val NOTIFY_EVERY_MILLIS = 1_000L

        fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.backup_notification_channel), NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}
