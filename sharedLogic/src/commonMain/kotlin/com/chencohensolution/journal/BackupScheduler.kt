package com.chencohensolution.journal

import com.chencohensolution.journal.backup.BackupSettings

/** The app's background scheduling of backup runs (Android: WorkManager; iOS: the app's BackupService). */
interface BackupScheduler {
    fun settingsChanged(settings: BackupSettings)
    fun backUpNow(settings: BackupSettings)
}
