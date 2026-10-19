package com.chencohensolution.journal.work

import android.content.Context
import android.provider.MediaStore
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.chencohensolution.journal.BackupScheduler
import com.chencohensolution.journal.backup.BackupSettings
import java.util.concurrent.TimeUnit

/**
 * Backup runs, kept by WorkManager across restarts and reboots: every 15 minutes (Android's shortest), soon after new
 * photos appear, and right away for "Back up now". All of them wait for the conditions in Settings (Wi-Fi, charging).
 */
class WorkBackupScheduler(context: Context) : BackupScheduler {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    override fun settingsChanged(settings: BackupSettings) {
        if (!settings.enabled) {
            listOf(PERIODIC, NEW_PHOTOS, NOW).forEach(workManager::cancelUniqueWork)
            return
        }
        val periodic = PeriodicWorkRequestBuilder<BackupWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints(settings))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
            .build()
        workManager.enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.UPDATE, periodic)
        watchForNewPhotos(settings, ExistingWorkPolicy.REPLACE)
    }

    override fun backUpNow(settings: BackupSettings) {
        // Started from the app, so it may run as a foreground service. Expedited work can't wait for charging.
        val now = OneTimeWorkRequestBuilder<BackupWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(networkType(settings)).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        workManager.enqueueUniqueWork(NOW, ExistingWorkPolicy.KEEP, now)
    }

    /**
     * A run when MediaStore changes (a photo taken). A content trigger fires once, so the run it started asks for the
     * next one when it ends ([ExistingWorkPolicy.APPEND_OR_REPLACE]); other runs only make sure one is waiting.
     */
    fun watchForNewPhotos(settings: BackupSettings, policy: ExistingWorkPolicy = ExistingWorkPolicy.KEEP) {
        if (!settings.enabled) return
        val trigger = Constraints.Builder()
            .setRequiredNetworkType(networkType(settings))
            .setRequiresCharging(settings.chargingOnly)
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            .addContentUriTrigger(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true)
            .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(NEW_PHOTOS, policy, OneTimeWorkRequestBuilder<BackupWorker>().setConstraints(trigger).addTag(NEW_PHOTOS_TAG).build())
    }

    private fun constraints(settings: BackupSettings) = Constraints.Builder()
        .setRequiredNetworkType(networkType(settings))
        .setRequiresCharging(settings.chargingOnly)
        .build()

    private fun networkType(settings: BackupSettings) = if (settings.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED

    companion object {
        /** Marks the run started by new photos, which asks for the next one when it ends. */
        const val NEW_PHOTOS_TAG = "new-photos"
        private const val PERIODIC = "backup-periodic"
        private const val NEW_PHOTOS = "backup-new-photos"
        private const val NOW = "backup-now"
    }
}
