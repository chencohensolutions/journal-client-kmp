package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.library.AudioKind

private val callFolder = BackupOptionCFolder("call", "Call", "Internal storage › Recordings › Call", AudioKind.CALL, 6196, 5810)
private val recorderFolder = BackupOptionCFolder("recorder", "Voice Recorder", "Internal storage › Recordings › Voice Recorder", AudioKind.RECORDING, 1204, 1204)
private val picked = BackupOptionCFolder(
    "notes", "WhatsApp Voice Notes", "Internal storage › Android › media › com.whatsapp › WhatsApp › Media › WhatsApp Voice Notes",
    AudioKind.VOICE_MESSAGE, 14, 0,
)

@Composable
private fun BackupOptionCLab(adding: Boolean) {
    var state by remember { mutableStateOf(BackupOptionCState(listOf(callFolder, recorderFolder), adding = picked.takeIf { adding })) }
    BackupScreenC(
        state,
        BackupOptionCActions(
            // The picker is faked: it always comes back with WhatsApp's voice notes.
            pickFolder = { state = state.copy(adding = picked) },
            confirmAdd = { kind -> state = state.copy(folders = state.folders + picked.copy(kind = kind), adding = null) },
            cancelAdd = { state = state.copy(adding = null) },
            setKind = { folder, kind -> state = state.copy(folders = state.folders.map { if (it.id == folder.id) it.copy(kind = kind) else it }) },
            remove = { folder -> state = state.copy(folders = state.folders - folder) },
        ),
    )
}

// Fakes: the folders, counts and how much is on the server (the app reads no sound files yet), the system folder
// picker (Add a folder always "picks" WhatsApp's voice notes), and backing sound files up. Can't show: Android asking
// to allow the folder. The page opens from one row in Settings › Backup ("Calls and recordings · 2 folders"), not mocked.
internal val backupOptionC = LabOption("c", "A page of its own", "Calls and recordings on their own page: what each folder holds, progress, Add a folder") {
    BackupOptionCLab(adding = false)
}

// The same as c, just after picking a folder: the question of what it holds.
internal val backupOptionCAdding = LabOption("c-add", "A page of its own, adding", "After the picker: what does the folder hold?") {
    BackupOptionCLab(adding = true)
}
