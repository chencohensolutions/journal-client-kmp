package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** The chips change the made-up settings; "Allow" gives the access at once. */
@Composable
private fun BackupOptionBLab() {
    var settings by remember { mutableStateOf(labBackupSettings) }
    var access by remember { mutableStateOf(false) }
    BackupScreenB(
        labBackupUi(settings),
        BackupOptionBState(canTrashWithoutAsking = access),
        BackupActions(
            setKeepOnPhone = { settings = settings.copy(keepOnPhone = it) },
            setKeepJpegXl = { settings = settings.copy(keepJpegXlOnPhone = it) },
        ),
        allowMediaManagement = { access = true },
        back = {},
    )
}

// Fakes: the settings aren't saved; "Media management" access starts off and "Allow" gives it without the system page.
internal val backupOptionB = LabOption("b", "One card", "Keep on this phone as a card: recordings and photos, chips beside each") { BackupOptionBLab() }
