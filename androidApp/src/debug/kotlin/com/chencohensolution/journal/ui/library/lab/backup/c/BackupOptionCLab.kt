package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import java.time.LocalDate
import java.time.ZoneId

// Made-up copies for option C (the server's 49,414 files: BackupLab's 48,210 photos and 1,204 videos), timed from now.

private fun todayAt(hour: Int, minute: Int) = LocalDate.now().atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun daysAgoAt(days: Long, hour: Int, minute: Int) =
    LocalDate.now().minusDays(days).atTime(hour, minute).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** E: copies as files arrive and is up to date; F: copies at 04:00 and lacks what arrived since. */
private val healthyCopies = CopiesStateC(
    serverFiles = 49_414,
    copies = listOf(
        DataCopyC(
            folder = "E:\\Journal copy\\hen", realtime = true, dailyAt = "03:30",
            copiedFiles = 49_414, missingFiles = 0, missingBytes = 0, failedFiles = 0, inCopyOnly = 2,
            freeBytes = 1_310_000_000_000, lastPass = CopyPassC(todayAt(3, 30), 120, 1_400_000_000, 0), running = null, problem = null,
        ),
        DataCopyC(
            folder = "F:\\hen", realtime = false, dailyAt = "04:00",
            copiedFiles = 49_302, missingFiles = 112, missingBytes = 1_900_000_000, failedFiles = 0, inCopyOnly = 0,
            freeBytes = 640_000_000_000, lastPass = CopyPassC(todayAt(4, 0), 486, 3_100_000_000, 0), running = null, problem = null,
        ),
    ),
    checkedAtMillis = System.currentTimeMillis() - 2 * 60_000,
)

/** E: copying with 2 files failed; F: unplugged for three days; D: on the data folder's own disk. */
private val problemCopies = CopiesStateC(
    serverFiles = 49_414,
    copies = listOf(
        DataCopyC(
            folder = "E:\\Journal copy\\hen", realtime = true, dailyAt = "03:30",
            copiedFiles = 49_377, missingFiles = 37, missingBytes = 412_000_000, failedFiles = 2, inCopyOnly = 2,
            freeBytes = 1_310_000_000_000, lastPass = CopyPassC(todayAt(3, 30), 120, 1_400_000_000, 2), running = CopyRunningC(12, 37), problem = null,
        ),
        DataCopyC(
            folder = "F:\\hen", realtime = false, dailyAt = "04:00",
            copiedFiles = 48_950, missingFiles = 464, missingBytes = 6_800_000_000, failedFiles = 0, inCopyOnly = 0,
            freeBytes = null, lastPass = CopyPassC(daysAgoAt(4, 4, 0), 210, 1_100_000_000, 0), running = null,
            problem = CopyProblemC.FOLDER_MISSING, problemSinceMillis = daysAgoAt(3, 4, 0),
        ),
        DataCopyC(
            folder = "D:\\Journal backup\\hen", realtime = true, dailyAt = "03:30",
            copiedFiles = 49_414, missingFiles = 0, missingBytes = 0, failedFiles = 0, inCopyOnly = 0,
            freeBytes = 92_000_000_000, lastPass = CopyPassC(todayAt(3, 30), 120, 1_400_000_000, 0), running = null,
            problem = CopyProblemC.SAME_DISK,
        ),
    ),
    checkedAtMillis = System.currentTimeMillis() - 2 * 60_000,
)

private val noCopies = CopiesStateC(serverFiles = 49_414, copies = emptyList(), checkedAtMillis = System.currentTimeMillis())

// Option C fakes: the copies (the server tells the app nothing about them yet: copyReport and startCopy are admin
// only); Copy now does nothing (the app can't start a pass); the times are made up from now. This phone's place is
// BackupLab's (79 waiting). A tap on a copy opens its details for real.
internal val backupOptionC = arrayOf(
    LabOption("c", "Where your files are", "One line from the phone to the server to each copy, in the top card") {
        BackupSettingsScreenC(labBackupUi(), healthyCopies, BackupActions(), CopiesActionsC(), back = {})
    },
    LabOption("c-problem", "Where your files are: problems", "Copying with 2 failed, a disk unplugged, one on the server's disk") {
        BackupSettingsScreenC(labBackupUi(), problemCopies, BackupActions(), CopiesActionsC(), back = {})
    },
)
