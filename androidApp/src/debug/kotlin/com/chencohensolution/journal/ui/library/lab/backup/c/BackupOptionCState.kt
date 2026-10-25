package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.AudioKind

/** A folder of sound recordings backup reads (option c), shown on its own page. */
internal data class BackupOptionCFolder(
    val id: String,
    val name: String,
    /** "Internal storage › Recordings › Call". */
    val path: String,
    val kind: AudioKind,
    val files: Int,
    /** Its files on the server already. */
    val backedUp: Int,
)

/** Settings › Backup › Calls and recordings (option c). */
internal data class BackupOptionCState(
    val folders: List<BackupOptionCFolder>,
    /** A folder just picked, asking what it holds before it is added; null otherwise. */
    val adding: BackupOptionCFolder? = null,
)

internal class BackupOptionCActions(
    val pickFolder: () -> Unit = {},
    /** What the picked folder holds: it is added and backed up from now on. */
    val confirmAdd: (AudioKind) -> Unit = {},
    val cancelAdd: () -> Unit = {},
    val setKind: (BackupOptionCFolder, AudioKind) -> Unit = { _, _ -> },
    val remove: (BackupOptionCFolder) -> Unit = {},
    val back: () -> Unit = {},
)
