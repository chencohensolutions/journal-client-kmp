package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** The chips change the made-up settings; "Allow" gives the access at once. */
@Composable
private fun BackupOptionALab() {
    var settings by remember { mutableStateOf(labBackupSettings) }
    var access by remember { mutableStateOf(false) }
    BackupScreenA(
        labBackupUi(settings),
        BackupOptionAState(canTrashWithoutAsking = access),
        BackupActions(
            setKeepOnPhone = { settings = settings.copy(keepOnPhone = it) },
            setKeepJpegXl = { settings = settings.copy(keepJpegXlOnPhone = it) },
        ),
        allowMediaManagement = { access = true },
        back = {},
    )
}

// Fakes: the settings aren't saved; "Media management" access starts off and "Allow" gives it without the system page.
internal val backupOptionA = LabOption("a", "Like WAV / FLAC", "JPEG / JPEG XL chips under WAV / FLAC, the same shape") { BackupOptionALab() }
