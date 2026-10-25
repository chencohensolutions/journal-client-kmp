package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.AudioKind

/** A folder of sound recordings backup can read (option b): a pill beside the photo sources. */
internal data class BackupOptionBFolder(
    val id: String,
    /** "Call recordings": what the pill says. */
    val name: String,
    val kind: AudioKind,
    val files: Int,
    /** Backed up. */
    val on: Boolean,
)

internal class BackupOptionBActions(
    val toggle: (BackupOptionBFolder) -> Unit = {},
    /** Opens the system's folder picker, then asks what the folder holds. */
    val pickFolder: () -> Unit = {},
)
