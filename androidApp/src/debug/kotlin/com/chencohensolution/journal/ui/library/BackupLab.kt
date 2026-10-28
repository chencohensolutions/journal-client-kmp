package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.backup.SoundFolder
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.StorageUsage

// Made-up data for Settings › Backup in the UI lab (LabScreens' "backup" screen): backup on, Camera and a Voice
// Recorder folder chosen, the server's totals made up. Nothing uploads, and no setting is saved.

internal val labBackupSettings = BackupSettings(
    enabled = true,
    sources = setOf(MediaSource.CAMERA),
    soundFolders = listOf(SoundFolder("content://lab/voice", "Voice Recorder", "Recordings › Voice Recorder", AudioKind.RECORDING)),
)

internal fun labBackupUi(settings: BackupSettings = labBackupSettings) = BackupUi(
    BackupState(settings),
    waitingCount = 79,
    canCheckNetworkInBackground = true,
    serverUsage = StorageUsage(photos = 48_210, videos = 1_204, bytes = 214_300_000_000),
    counts = mapOf(MediaSource.CAMERA to 41_502, MediaSource.WHATSAPP to 18_458),
)
