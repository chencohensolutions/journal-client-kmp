package com.chencohensolution.journal.ui.library

// The server's copies of this user's data folder (journal-server-go/docs/copies.md), as the server would tell the app
// once it can: the app doesn't ask yet, since copies are only in the admin schema (copyReport, startCopy).

/** The user's copies, and what they are compared with. */
internal data class CopiesStateB(
    /** Originals in the user's data folder now: what a copy that is up to date holds. */
    val serverFiles: Int,
    /** In config.json's order; empty when the user has none. */
    val copies: List<DataCopyB>,
    /** When the server compared them; null until it answered. */
    val checkedAtMillis: Long?,
)

/** One copy folder: how many of the data folder's originals it holds, and how its passes go. */
internal data class DataCopyB(
    /** The folder as config.json names it, "E:\Journal copy\hen". */
    val folder: String,
    /** Copies each file a few seconds after it arrives, besides the daily pass. */
    val realtime: Boolean,
    /** The daily full pass, in the server's local time ("03:30"). */
    val dailyAt: String,
    /** Originals in the copy, of [CopiesStateB.serverFiles]. */
    val copiedFiles: Int,
    /** Originals the copy lacks now, and what they take up. */
    val missingFiles: Int,
    val missingBytes: Long,
    /** Files the last pass couldn't copy; they are tried again at the next. */
    val failedFiles: Int,
    /** Originals still in the copy that are gone from the data folder (kept: nothing is deleted from a copy). */
    val inCopyOnly: Int,
    /** Free space on the copy's disk; null when the folder can't be found. */
    val freeBytes: Long?,
    val lastPass: CopyPassB?,
    /** A pass going now; null otherwise. */
    val running: CopyRunningB?,
    val problem: CopyProblemB?,
    /** Since when [problem] holds. */
    val problemSinceMillis: Long? = null,
)

internal data class CopyPassB(val atMillis: Long, val files: Int, val bytes: Long, val failed: Int)

internal data class CopyRunningB(val done: Int, val total: Int)

internal enum class CopyProblemB {
    /** The folder isn't there: an unplugged disk, or a network share that is down. The pass stops. */
    FOLDER_MISSING,
    /** Less than the next file plus 1 GB free on the copy's disk. The pass stops. */
    DISK_FULL,
    /** On the data folder's own disk: copying goes on, but a failing disk would take both. */
    SAME_DISK,
}

internal class CopiesActionsB(
    /** Starts a full pass of one copy now. */
    val copyNow: (DataCopyB) -> Unit = {},
)
