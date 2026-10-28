package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.server.CopyPass
import com.chencohensolution.journal.server.CopyRunning
import com.chencohensolution.journal.server.DataCopy
import com.chencohensolution.journal.server.ServerCopiesState
import kotlinx.coroutines.delay

// Made-up copies for option A: the first pass of E:\archive\hen\journal-copy, 25 minutes in, writing about 93 MB a
// second; a second copy up to date. The pass moves on its own while the page shows; Stop and Copy now do nothing.

private val passStartedAt = System.currentTimeMillis() - 25 * 60_000

private fun labCopies(nowMillis: Long): ServerCopiesState {
    val done = 140_000_000_000 + (nowMillis - passStartedAt - 25 * 60_000).coerceAtLeast(0) * 93_000
    val total = 412_000_000_000
    val doneBytes = done.coerceAtMost(total)
    val first = DataCopy(
        folder = "E:\\archive\\hen\\journal-copy", realtime = false, dailyAt = "03:30", sameDisk = false,
        originals = 114_718, copiedFiles = 0, missingFiles = 114_718, missingBytes = total, inCopyOnly = 0,
        freeBytes = 1_310_000_000_000, checkedAtMillis = passStartedAt - 60_000, checking = false,
        running = CopyRunning(passStartedAt, totalFiles = 114_718, totalBytes = total, doneFiles = (114_718 * (doneBytes.toDouble() / total)).toInt(), doneBytes = doneBytes),
        lastPass = null, problem = null, problemSinceMillis = null,
    )
    val second = first.copy(
        folder = "F:\\hen", dailyAt = "04:00", copiedFiles = 114_718, missingFiles = 0, missingBytes = 0, running = null,
        checkedAtMillis = nowMillis - 120_000, lastPass = CopyPass(nowMillis - 3_600_000, 210, 1_100_000_000, 0),
    )
    return ServerCopiesState(listOf(first, second), loaded = true)
}

/** The page with the pass moving, asked again every second as the app's watch would. */
@Composable
private fun CopiesLabA(counting: Boolean = false) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }
    var copies = labCopies(now)
    if (counting) copies = copies.copy(copies = copies.copies.mapIndexed { i, c -> if (i == 0) c.copy(running = CopyRunning(now - 40_000, counting = true)) else c })
    CopiesScreenA(copies, CopiesActions(), back = {})
}

// Option A fakes: the copy and its pass (made up, moving on its own); Stop and Copy now do nothing. The time left is
// the pass's rate since it started, which the shared logic can compute from what the server sends now.
internal val backupOptionA = arrayOf(
    LabOption("a", "Size on top", "\"140 GB of 412 GB\" large; \"34% · About 48 min left · files\", Stop at its end") { CopiesLabA() },
)
