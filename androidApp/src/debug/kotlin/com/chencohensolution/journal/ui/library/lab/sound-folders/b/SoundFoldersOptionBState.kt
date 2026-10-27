package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.server.ServerSoundFolder
import com.chencohensolution.journal.server.SoundFolderMark
import com.chencohensolution.journal.server.SoundFoldersState

/**
 * Option b's state: the shared logic's [SoundFoldersState] (SoundFoldersController), and the move asked about in its
 * folder's row ("953 files will become Calls", Move or Cancel), which the screen's route would hold.
 */
internal data class SoundFoldersOptionBState(
    val folders: SoundFoldersState,
    val confirming: SoundFoldersOptionBChange? = null,
)

/** A folder and where "Move to…" was asked to take it. */
internal data class SoundFoldersOptionBChange(val folder: ServerSoundFolder, val mark: SoundFolderMark)

internal class SoundFoldersOptionBActions(
    /** A destination picked in a folder's "Move to…" menu: asks in the row first. */
    val moveTo: (ServerSoundFolder, SoundFolderMark) -> Unit = { _, _ -> },
    val confirm: () -> Unit = {},
    val cancel: () -> Unit = {},
    val retry: () -> Unit = {},
    val back: () -> Unit = {},
)
