package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** The choices change the made-up settings; "Allow" gives the access at once. */
@Composable
private fun BackupOptionCLab() {
    var settings by remember { mutableStateOf(labBackupSettings) }
    var access by remember { mutableStateOf(false) }
    BackupScreenC(
        labBackupUi(settings),
        BackupOptionCState(canTrashWithoutAsking = access, savingsBytes = 4_100_000_000),
        BackupActions(
            setKeepOnPhone = { settings = settings.copy(keepOnPhone = it) },
            setKeepJpegXl = { settings = settings.copy(keepJpegXlOnPhone = it) },
        ),
        allowMediaManagement = { access = true },
        back = {},
    )
}

// Fakes: the space given back (4.1 GB, made up: nothing works it out yet); the settings aren't saved; "Media
// management" access starts off and "Allow" gives it without the system page.
internal val backupOptionC = LabOption("c", "Originals or smaller copies", "One choice for both kinds, with the space it gives back; or each apart") { BackupOptionCLab() }
