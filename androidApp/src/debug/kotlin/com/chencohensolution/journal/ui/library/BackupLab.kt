package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.backup.SoundFolder
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaSource
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.server.CopyPass
import com.chencohensolution.journal.server.CopyProblem
import com.chencohensolution.journal.server.CopyRunning
import com.chencohensolution.journal.server.DataCopy
import com.chencohensolution.journal.server.ServerCopiesState
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

// Made-up copies on other disks (Oct 28, 2026): the server isn't asked, Copy now does nothing, the times are from now.

private fun todayAt(hour: Int, minute: Int) =
    java.time.LocalDate.now().atTime(hour, minute).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun daysAgoAt(days: Long, hour: Int, minute: Int) =
    java.time.LocalDate.now().minusDays(days).atTime(hour, minute).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun labCopy(folder: String, realtime: Boolean, dailyAt: String) = DataCopy(
    folder = folder, realtime = realtime, dailyAt = dailyAt, sameDisk = false,
    originals = 49_414, copiedFiles = 49_414, missingFiles = 0, missingBytes = 0, inCopyOnly = 0, freeBytes = 1_310_000_000_000,
    checkedAtMillis = System.currentTimeMillis() - 2 * 60_000, checking = false, running = null,
    lastPass = CopyPass(todayAt(3, 31), 120, 1_400_000_000, 0), problem = null, problemSinceMillis = null,
)

/** E: copies as files arrive and is up to date; F: copies at 04:00 and lacks what arrived since; dana: a friend's server. */
internal val labCopies = ServerCopiesState(
    copies = listOf(
        labCopy("E:\\Journal copy\\hen", realtime = true, dailyAt = "03:30").copy(inCopyOnly = 2),
        labCopy("F:\\hen", realtime = false, dailyAt = "04:00").copy(
            copiedFiles = 49_302, missingFiles = 112, missingBytes = 1_900_000_000, freeBytes = 640_000_000_000,
            lastPass = CopyPass(todayAt(4, 2), 486, 3_100_000_000, 0),
        ),
        labCopy("dana", realtime = false, dailyAt = "02:00").copy(onPeer = true, freeBytes = 1_200_000_000_000),
    ),
    loaded = true,
)

/** E: copying with 2 files failed; F: unplugged for three days; D: on the server's own disk; dana: not answering. */
internal val labCopiesWithProblems = ServerCopiesState(
    copies = listOf(
        labCopy("E:\\Journal copy\\hen", realtime = true, dailyAt = "03:30").copy(
            copiedFiles = 49_377, missingFiles = 37, missingBytes = 412_000_000, inCopyOnly = 2,
            running = CopyRunning(System.currentTimeMillis() - 30_000, totalFiles = 37, totalBytes = 412_000_000, doneFiles = 12, doneBytes = 140_000_000),
            lastPass = CopyPass(todayAt(3, 31), 120, 1_400_000_000, 2),
        ),
        labCopy("F:\\hen", realtime = false, dailyAt = "04:00").copy(
            copiedFiles = 48_950, missingFiles = 464, missingBytes = 6_800_000_000, freeBytes = null,
            lastPass = CopyPass(daysAgoAt(4, 4, 2), 210, 1_100_000_000, 0),
            problem = CopyProblem.FOLDER_MISSING, problemSinceMillis = daysAgoAt(3, 4, 0),
        ),
        labCopy("D:\\Journal backup\\hen", realtime = true, dailyAt = "03:30").copy(sameDisk = true, freeBytes = 92_000_000_000),
        labCopy("dana", realtime = false, dailyAt = "02:00").copy(
            onPeer = true, copiedFiles = 49_120, missingFiles = 294, missingBytes = 2_300_000_000, freeBytes = null,
            lastPass = CopyPass(daysAgoAt(2, 2, 40), 301, 2_600_000_000, 0),
            problem = CopyProblem.PEER_UNREACHABLE, problemSinceMillis = daysAgoAt(1, 2, 0),
        ),
    ),
    loaded = true,
)

/** Settings › Backup with [copies], its Copies row opening their page and Back closing it. */
@androidx.compose.runtime.Composable
internal fun BackupLab(copies: ServerCopiesState, onPage: Boolean = false) {
    var page by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(onPage) }
    if (page) {
        CopiesScreen(copies, CopiesActions(), back = { page = false })
    } else {
        BackupSettingsScreen(labBackupUi().copy(copies = copies), BackupActions(), back = {}, openCopies = { page = true })
    }
}
