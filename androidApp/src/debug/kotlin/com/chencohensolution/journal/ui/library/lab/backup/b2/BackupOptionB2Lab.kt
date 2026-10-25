package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaSource

/** Backed up first, then the rest, each the most files first. */
private fun ordered(pills: List<BackupOptionB2Pill>) = pills.sortedWith(compareByDescending<BackupOptionB2Pill> { it.on }.thenByDescending { it.count })

private val startPills = ordered(
    listOf(
        BackupOptionB2Pill.Source(MediaSource.CAMERA, 18_402, on = true),
        BackupOptionB2Pill.Source(MediaSource.WHATSAPP, 6_120, on = true),
        BackupOptionB2Pill.Source(MediaSource.SCREENSHOTS, 2_310, on = false),
        BackupOptionB2Pill.Source(MediaSource.folder("Telegram"), 412, on = false),
        BackupOptionB2Pill.Source(MediaSource.OTHER, 96, on = false),
        BackupOptionB2Pill.Folder("tree:Call", "Call recordings", "Internal storage › Recordings › Call", AudioKind.CALL, 6_196, on = true),
        BackupOptionB2Pill.Folder("tree:Voice Recorder", "Voice Recorder", "Internal storage › Recordings › Voice Recorder", AudioKind.RECORDING, 1_204, on = false),
    ),
)

private val pickedFolder = BackupOptionB2Picked(
    name = "WhatsApp Voice Notes",
    path = "Internal storage › Android › media › com.whatsapp › WhatsApp › Media › WhatsApp Voice Notes",
    files = 14,
    bytes = 1_800_000,
    guess = AudioKind.VOICE_MESSAGE,
)

@Composable
private fun BackupOptionB2Lab(adding: Boolean) {
    var state by remember { mutableStateOf(BackupOptionB2State(startPills, picked = pickedFolder.takeIf { adding })) }
    BackupScreenB2(
        backupLabUi(),
        state,
        BackupActions(),
        BackupOptionB2Actions(
            // Turning one on or off moves it to its part of the list at once.
            toggle = { pill ->
                val flipped = when (pill) {
                    is BackupOptionB2Pill.Source -> pill.copy(on = !pill.on)
                    is BackupOptionB2Pill.Folder -> pill.copy(on = !pill.on)
                }
                state = state.copy(pills = ordered(state.pills.map { if (it.key == pill.key) flipped else it }))
            },
            // The picker is faked: it always comes back with WhatsApp's voice notes.
            pickFolder = { state = state.copy(picked = pickedFolder) },
            add = { kind ->
                val folder = BackupOptionB2Pill.Folder("tree:" + pickedFolder.name, pickedFolder.name, pickedFolder.path, kind, pickedFolder.files, on = true)
                state = BackupOptionB2State(ordered(state.pills + folder))
            },
            cancelAdd = { state = state.copy(picked = null) },
        ),
        back = {},
    )
}

// Fakes: the folders and every count (the app reads no sound files yet), the system folder picker ("+ Add a folder"
// always "picks" WhatsApp's voice notes), the guess of what a folder holds, and backing sound files up (pills move here
// only). Can't show: Android asking to allow the folder.
internal val backupOptionB2 = LabOption("b2", "One list of pills", "Photo sources and sound folders in one list, backed up first, \"+ Add a folder\" last") {
    BackupOptionB2Lab(adding = false)
}

// The same as b2, just after picking a folder: the add dialog.
internal val backupOptionB2Adding = LabOption("b2-add", "One list of pills, adding", "The add dialog: what was found, what the files are, Back it up") {
    BackupOptionB2Lab(adding = true)
}
