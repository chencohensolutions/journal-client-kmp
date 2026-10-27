package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.ServerSoundFolder
import com.chencohensolution.journal.server.SoundFolderMark
import com.chencohensolution.journal.server.SoundFoldersState
import kotlinx.coroutines.delay

// Made-up data for Settings › Sound folders in the UI lab (LabScreens' "sound-folders" screen, and Settings' row there).
// Fakes: the server's list (journal-server-go `soundFolders`) and its marks: the folders' counts are close to the
// owner's (the second dry run), the imported counts and Voice Recorder's mark are made up, and setSoundFolder isn't
// called. The move is timed here (seconds); on the server it takes a turn per file, and Moments and Hidden collections
// get the moved recordings with the next sync.

/**
 * The owner's sound folders as the server could list them: the phone's Call, the import's "Calls" and "calls" (one
 * folder, since ids are in lower case: 953 + 567, named by the spelling most files have), Voice Recorder from the phone
 * and the import (merged into Voice recordings already), and an app's few recordings.
 */
internal val labSoundFolders: List<ServerSoundFolder> = listOf(
    Triple("Call", 1_623, 0),
    Triple("Calls", 953, 953),
    Triple("calls", 567, 567),
    Triple("Voice Recorder", 168, 61),
    Triple("super_recorder", 7, 7),
).groupBy { (name) -> MediaSource.folder(name).id }
    .map { (id, spellings) ->
        ServerSoundFolder(id, spellings.maxBy { it.second }.first, spellings.sumOf { it.second }, spellings.sumOf { it.third }, SoundFolderMark.NONE, 0)
    }
    .sortedByDescending { it.files }
    .with("folder:voice recorder") { it.copy(mark = SoundFolderMark.RECORDING) }

private fun List<ServerSoundFolder>.with(id: String, change: (ServerSoundFolder) -> ServerSoundFolder) =
    map { if (it.id == id) change(it) else it }

private val labImportedCalls get() = labSoundFolders.first { it.id == "folder:calls" }

/**
 * The app's [SoundFoldersScreen] with made-up folders; a move takes the folder's files in 16 steps of [stepMillis], as
 * the server would report them.
 */
@Composable
internal fun SoundFoldersLab(start: List<ServerSoundFolder>, confirmingAtStart: SoundFoldersChange? = null, stepMillis: Long = 250) {
    var folders by remember { mutableStateOf(start) }
    var asking by remember { mutableStateOf(confirmingAtStart) }
    val moving = folders.any { it.pending > 0 }
    LaunchedEffect(moving) {
        while (folders.any { it.pending > 0 }) {
            delay(stepMillis)
            folders = folders.map { if (it.pending > 0) it.copy(pending = (it.pending - (it.files / 16).coerceAtLeast(1)).coerceAtLeast(0)) else it }
        }
    }
    SoundFoldersScreen(
        SoundFoldersState.Ready(folders),
        asking,
        SoundFoldersActions(
            moveTo = { folder, mark -> asking = SoundFoldersChange(folder, mark) },
            confirm = {
                asking?.let { change -> folders = folders.with(change.folder.id) { it.copy(mark = change.mark, pending = it.files) } }
                asking = null
            },
            cancel = { asking = null },
        ),
    )
}

/** Asking, in its row, before the imported Calls become Calls. */
@Composable
internal fun SoundFoldersLabConfirm() = SoundFoldersLab(labSoundFolders, SoundFoldersChange(labImportedCalls, SoundFolderMark.CALL))

/** The imported Calls moving into Calls, slowly (about half a minute). */
@Composable
internal fun SoundFoldersLabMoving() =
    SoundFoldersLab(labSoundFolders.with("folder:calls") { it.copy(mark = SoundFolderMark.CALL, pending = 1_140) }, stepMillis = 2000)
