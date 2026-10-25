package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.StorageUsage

// The backup round's (ux/backup) sample: backup on, Camera and WhatsApp chosen, as every option starts from.

internal fun backupLabUi() = BackupUi(
    state = BackupState(BackupSettings(enabled = true, sources = setOf(MediaSource.CAMERA, MediaSource.WHATSAPP))),
    waitingCount = 12,
    canCheckNetworkInBackground = true,
    serverUsage = StorageUsage(photos = 41_230, videos = 2_118, bytes = 312_000_000_000),
    counts = mapOf(
        MediaSource.CAMERA to 18_402, MediaSource.WHATSAPP to 6_120, MediaSource.SCREENSHOTS to 2_310,
        MediaSource.folder("Telegram") to 412, MediaSource.OTHER to 96,
    ),
)
