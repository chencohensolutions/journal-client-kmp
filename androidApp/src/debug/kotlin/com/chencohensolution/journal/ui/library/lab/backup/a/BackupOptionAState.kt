package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.AudioKind

/**
 * A folder of sound recordings on the phone that backup reads (option a): the user added it with the system's folder
 * picker (Android keeps the app's access to it), or the app found it where the phone's recorders keep theirs.
 */
internal data class BackupOptionAFolder(
    /** The folder's address the picker gave (a document tree), kept in settings. */
    val id: String,
    /** "Call", "Voice Recorder". */
    val name: String,
    /** "Internal storage › Recordings › Call". */
    val path: String,
    /** What its files are: calls, recordings or voice messages; set when it is added, changeable. */
    val kind: AudioKind,
    val files: Int,
    val bytes: Long,
    /** Its files not on the server yet. */
    val waiting: Int,
    /** Backed up (on), or kept but paused. */
    val on: Boolean,
)

/** The sound folders part of Settings › Backup in option a. */
internal data class BackupOptionAState(
    /** Folders backup reads, on or paused. */
    val folders: List<BackupOptionAFolder>,
    /** Where the phone's recorders keep files, found but not added yet: one tap adds them. */
    val found: List<BackupOptionAFolder>,
)

internal class BackupOptionAActions(
    val setOn: (BackupOptionAFolder, Boolean) -> Unit = { _, _ -> },
    val add: (BackupOptionAFolder) -> Unit = {},
    /** Opens the system's folder picker. */
    val pickFolder: () -> Unit = {},
    val remove: (BackupOptionAFolder) -> Unit = {},
)
