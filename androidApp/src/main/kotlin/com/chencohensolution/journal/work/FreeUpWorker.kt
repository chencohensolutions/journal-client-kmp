package com.chencohensolution.journal.work

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.text.format.Formatter
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.chencohensolution.journal.MainActivity
import com.chencohensolution.journal.R
import com.chencohensolution.journal.backupController
import com.chencohensolution.journal.freeUpController
import com.chencohensolution.journal.storage.FreeUpController
import com.chencohensolution.journal.storage.FreeUpProgress
import com.chencohensolution.journal.ui.library.AndroidPhotoTrash

/**
 * One "Free up space" run in the background (Settings → Storage on this phone), also with the app closed, with its
 * progress in a notification and a Stop action. Files that pass the checks go to the trash as it goes when the app has
 * "Media management" access; the others wait, and a notification at the end opens the app to confirm them at once.
 */
class FreeUpWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    private val notifications = context.getSystemService(NotificationManager::class.java)
    private var lastNotified = 0L

    override suspend fun doWork(): Result {
        val freeUp = freeUp(applicationContext)
        createChannel(applicationContext)
        runCatching { setForeground(foregroundInfo(null)) }
        try {
            val result = freeUp.runOnce(AndroidPhotoTrash(applicationContext), ::showProgress)
            Log.i(TAG, "run ended: $result, ${freeUp.currentState.activity}")
        } finally {
            notifications.cancel(PROGRESS_ID)
        }
        val waiting = freeUp.currentState.waitingCount
        if (waiting > 0 && canNotify()) notifications.notify(CONFIRM_ID, confirmNotification(waiting, freeUp.currentState.waitingBytes))
        return Result.success()
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        createChannel(applicationContext)
        return foregroundInfo(null)
    }

    private fun canNotify() =
        applicationContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun showProgress(progress: FreeUpProgress) {
        val now = System.currentTimeMillis()
        if (now - lastNotified < NOTIFY_EVERY_MILLIS && progress.done < progress.total) return
        lastNotified = now
        if (canNotify()) notifications.notify(PROGRESS_ID, progressNotification(progress))
    }

    private fun foregroundInfo(progress: FreeUpProgress?) =
        ForegroundInfo(PROGRESS_ID, progressNotification(progress), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        applicationContext, 0, Intent(applicationContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE,
    )

    private fun progressNotification(progress: FreeUpProgress?): Notification {
        val context = applicationContext
        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(openApp())
            .addAction(0, context.getString(R.string.backup_notification_stop), WorkManager.getInstance(context).createCancelPendingIntent(id))
        if (progress == null || progress.total == 0) {
            builder.setContentTitle(context.getString(R.string.free_up_notification_starting)).setProgress(0, 0, true)
        } else {
            val percent = if (progress.bytesTotal > 0) (progress.bytesDone * 100 / progress.bytesTotal).toInt() else 0
            builder.setContentTitle(context.getString(R.string.free_up_notification_title, progress.done.coerceAtMost(progress.total - 1) + 1, progress.total))
                .setContentText(progress.currentName)
                .setProgress(100, percent, false)
        }
        return builder.build()
    }

    private fun confirmNotification(count: Int, bytes: Long): Notification {
        val context = applicationContext
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_delete)
            .setContentTitle(context.resources.getQuantityString(R.plurals.free_up_notification_confirm, count, count))
            .setContentText(context.getString(R.string.free_up_notification_confirm_body, Formatter.formatShortFileSize(context, bytes)))
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
    }

    companion object {
        private const val TAG = "FreeUpWorker"
        private const val CHANNEL_ID = "free-up"
        private const val PROGRESS_ID = 1002
        private const val CONFIRM_ID = 1003
        private const val NOTIFY_EVERY_MILLIS = 1_000L
        private const val WORK = "free-up-space"

        /** Starts a run now (once a network is there); a run already going continues. */
        fun start(context: Context) {
            val request = OneTimeWorkRequestBuilder<FreeUpWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(WORK, ExistingWorkPolicy.KEEP, request)
        }

        /** The confirmation notification is gone once the user answered in the app. */
        fun dismissConfirmation(context: Context) {
            context.getSystemService(NotificationManager::class.java).cancel(CONFIRM_ID)
        }

        private fun createChannel(context: Context) {
            val channel = NotificationChannel(CHANNEL_ID, context.getString(R.string.free_up_notification_channel), NotificationManager.IMPORTANCE_LOW)
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }
}

/** The process's "Free up space", wired to backup and to this worker. */
fun freeUp(context: Context): FreeUpController {
    val app = context.applicationContext
    return freeUpController(app, backupController(app, WorkBackupScheduler(app))) { FreeUpWorker.start(app) }
}
