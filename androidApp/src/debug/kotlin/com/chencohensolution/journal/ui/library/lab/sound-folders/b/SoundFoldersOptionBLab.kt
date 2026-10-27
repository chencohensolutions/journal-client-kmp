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

/**
 * The owner's sound folders as the server could list them: the phone's Call, the import's "Calls" and "calls" (one
 * folder, since ids are in lower case: 953 + 567, named by the spelling most files have), Voice Recorder from the phone
 * and the import, and an app's few recordings.
 */
private val labFolders: List<ServerSoundFolder> = listOf(
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

private fun List<ServerSoundFolder>.with(id: String, change: (ServerSoundFolder) -> ServerSoundFolder) =
    map { if (it.id == id) change(it) else it }

/** The page with made-up folders; a move takes the folder's files in 16 steps of [stepMillis], as the server would report. */
@Composable
private fun Live(start: List<ServerSoundFolder>, confirming: SoundFoldersOptionBChange? = null, stepMillis: Long = 250) {
    var folders by remember { mutableStateOf(start) }
    var asking by remember { mutableStateOf(confirming) }
    val moving = folders.any { it.pending > 0 }
    LaunchedEffect(moving) {
        while (folders.any { it.pending > 0 }) {
            delay(stepMillis)
            folders = folders.map { if (it.pending > 0) it.copy(pending = (it.pending - (it.files / 16).coerceAtLeast(1)).coerceAtLeast(0)) else it }
        }
    }
    SoundFoldersScreenB(
        SoundFoldersOptionBState(SoundFoldersState.Ready(folders), asking),
        SoundFoldersOptionBActions(
            moveTo = { folder, mark -> asking = SoundFoldersOptionBChange(folder, mark) },
            confirm = {
                asking?.let { change -> folders = folders.with(change.folder.id) { it.copy(mark = change.mark, pending = it.files) } }
                asking = null
            },
            cancel = { asking = null },
        ),
    )
}

/** Voice Recorder merged into voice recordings already, as the owner might have chosen. */
private val labFoldersMarked = labFolders.with("folder:voice recorder") { it.copy(mark = SoundFolderMark.RECORDING) }

// Fakes: the server's list (journal-server-go `soundFolders`) and its marks: the folders' counts are close to the owner's
// (the second dry run), the imported counts and Voice Recorder's mark are made up, and setSoundFolder isn't called. The
// move is timed here (seconds); on the server it takes a turn per file, and Moments and Hidden collections get the moved
// recordings with the next sync. Not reachable from Settings: the "Sound folders" row under Library isn't drawn.
internal val soundFoldersOptionB = LabOption("b", "Grouped by where they go", "Calls, Voice recordings, Kept as folders; Move to… on each") {
    Live(labFoldersMarked)
}

// Fakes: as option b; opened on the question for the imported Calls, in its row.
internal val soundFoldersOptionBConfirm = LabOption("b-confirm", "Grouped: asking in the row", "\"1,520 files will become Calls\"") {
    Live(labFoldersMarked, SoundFoldersOptionBChange(labFoldersMarked.first { it.id == "folder:calls" }, SoundFolderMark.CALL))
}

// Fakes: as option b; opened while the imported Calls move into calls, slowly (about half a minute).
internal val soundFoldersOptionBMoving = LabOption("b-moving", "Grouped: moving", "\"Moving 1,140 files to Calls…\" in a band at the top") {
    Live(labFoldersMarked.with("folder:calls") { it.copy(mark = SoundFolderMark.CALL, pending = 1_140) }, stepMillis = 2000)
}
