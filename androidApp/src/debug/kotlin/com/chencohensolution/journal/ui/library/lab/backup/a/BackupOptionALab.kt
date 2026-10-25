package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.library.AudioKind

private val callFolder = BackupOptionAFolder("tree:Recordings/Call", "Call", "Internal storage › Recordings › Call", AudioKind.CALL, 6196, 3_480_000_000, 12, on = true)
private val recorderFolder = BackupOptionAFolder("tree:Recordings/Voice Recorder", "Voice Recorder", "Internal storage › Recordings › Voice Recorder", AudioKind.RECORDING, 1204, 41_200_000_000, 0, on = false)
private val voiceNotes = BackupOptionAFolder(
    "tree:WhatsApp/Media/WhatsApp Voice Notes", "WhatsApp Voice Notes",
    "Internal storage › Android › media › com.whatsapp › WhatsApp › Media › WhatsApp Voice Notes",
    AudioKind.VOICE_MESSAGE, 14, 1_800_000, 14, on = false,
)

// Fakes: the folders, their counts and sizes (the app reads no sound files yet), finding the recorders' folders, the
// system folder picker (Add a folder does nothing), and backing sound files up (switches move here only). Can't show:
// Android asking to allow the folder, the upload of a 3-hour recording.
internal val backupOptionA = LabOption("a", "A card of folders", "Calls and recordings folders with switches, found ones to add, Add a folder") {
    var state by remember { mutableStateOf(BackupOptionAState(folders = listOf(callFolder, recorderFolder), found = listOf(voiceNotes))) }
    BackupScreenA(
        backupLabUi(),
        state,
        BackupActions(),
        BackupOptionAActions(
            setOn = { folder, on -> state = state.copy(folders = state.folders.map { if (it.id == folder.id) it.copy(on = on) else it }) },
            add = { folder -> state = state.copy(folders = state.folders + folder.copy(on = true), found = state.found - folder) },
        ),
        back = {},
    )
}
