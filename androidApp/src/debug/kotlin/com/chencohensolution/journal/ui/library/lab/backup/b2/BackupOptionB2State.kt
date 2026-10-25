package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaSource

/**
 * One pill of What to back up (option b2): a photo source, or a folder of sound recordings the user added. One list
 * for both, the ones backed up first.
 */
internal sealed interface BackupOptionB2Pill {
    val key: String
    val count: Int
    val on: Boolean

    data class Source(val source: MediaSource, override val count: Int, override val on: Boolean) : BackupOptionB2Pill {
        override val key: String get() = source.id
    }

    /** A folder added with the system's picker; [name] is what the pill says, [kind] what its files are. */
    data class Folder(override val key: String, val name: String, val path: String, val kind: AudioKind, override val count: Int, override val on: Boolean) : BackupOptionB2Pill
}

/** What the add dialog shows about a folder just picked, before it is backed up. */
internal data class BackupOptionB2Picked(
    val name: String,
    /** "Internal storage › Recordings › Call". */
    val path: String,
    /** Sound files found in it, and their size. */
    val files: Int,
    val bytes: Long,
    /** What the app guesses its files are, from their names and the folder's (Samsung's "Call recording …" are calls). */
    val guess: AudioKind,
)

internal data class BackupOptionB2State(
    /** Backed up first, then the rest; each part the most files first. */
    val pills: List<BackupOptionB2Pill>,
    /** A folder just picked: the add dialog is open on it. */
    val picked: BackupOptionB2Picked? = null,
)

internal class BackupOptionB2Actions(
    val toggle: (BackupOptionB2Pill) -> Unit = {},
    /** Opens the system's folder picker; a folder picked opens the add dialog. */
    val pickFolder: () -> Unit = {},
    val add: (AudioKind) -> Unit = {},
    val cancelAdd: () -> Unit = {},
)
