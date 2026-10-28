package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** The switches change the made-up settings; "Allow" gives the access at once. */
@Composable
private fun BackupOptionB2Lab() {
    var settings by remember { mutableStateOf(labBackupSettings) }
    var access by remember { mutableStateOf(false) }
    BackupScreenB2(
        labBackupUi(settings),
        BackupOptionB2State(canTrashWithoutAsking = access),
        BackupActions(
            setKeepOnPhone = { settings = settings.copy(keepOnPhone = it) },
            setKeepJpegXl = { settings = settings.copy(keepJpegXlOnPhone = it) },
        ),
        allowMediaManagement = { access = true },
        back = {},
    )
}

// Fakes: the settings aren't saved; "Media management" access starts off and "Allow" gives it without the system page.
internal val backupOptionB2 = LabOption("b2", "One card, switches", "b with a switch per kind: FLAC for recordings, JPEG XL for photos") { BackupOptionB2Lab() }
