package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.library.AudioKind

// Fakes: the folders and counts (the app reads no sound files yet), the system folder picker ("+ Add a folder" does
// nothing), and backing sound files up (pills move here only). Can't show: Android asking to allow the folder.
internal val backupOptionB = LabOption("b", "Pills", "Sound folders as pills under the sources, \"+ Add a folder\" last") {
    var folders by remember {
        mutableStateOf(
            listOf(
                BackupOptionBFolder("call", "Call recordings", AudioKind.CALL, 6196, on = true),
                BackupOptionBFolder("recorder", "Voice Recorder", AudioKind.RECORDING, 1204, on = false),
                BackupOptionBFolder("voice", "WhatsApp voice notes", AudioKind.VOICE_MESSAGE, 14, on = false),
            ),
        )
    }
    BackupScreenB(
        backupLabUi(),
        folders,
        BackupActions(),
        BackupOptionBActions(toggle = { folder -> folders = folders.map { if (it.id == folder.id) it.copy(on = !it.on) else it } }),
        back = {},
    )
}
