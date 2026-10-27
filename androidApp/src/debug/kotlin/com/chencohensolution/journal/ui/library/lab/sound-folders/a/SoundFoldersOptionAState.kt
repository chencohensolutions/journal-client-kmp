package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.server.ServerSoundFolder
import com.chencohensolution.journal.server.SoundFolderMark
import com.chencohensolution.journal.server.SoundFoldersState

/**
 * Option a's state: the shared logic's [SoundFoldersState] (SoundFoldersController), and the choice that waits for its
 * confirmation ("1,623 files will become Calls"), which the screen's route would hold.
 */
internal data class SoundFoldersOptionAState(
    val folders: SoundFoldersState,
    val confirming: SoundFoldersOptionAChange? = null,
)

/** A folder and the choice tapped for it, asked about before it is sent. */
internal data class SoundFoldersOptionAChange(val folder: ServerSoundFolder, val mark: SoundFolderMark)

internal class SoundFoldersOptionAActions(
    /** A choice tapped on a folder's row: asks first. */
    val choose: (ServerSoundFolder, SoundFolderMark) -> Unit = { _, _ -> },
    /** "Move": sends the choice being asked about. */
    val confirm: () -> Unit = {},
    val cancel: () -> Unit = {},
    val retry: () -> Unit = {},
    val back: () -> Unit = {},
)
