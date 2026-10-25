package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.AudioKind
import java.text.NumberFormat

// Option c, "a page of its own": Settings › Backup gets one row, Calls and recordings ("2 folders · 7,400 files"),
// that opens this page: each folder with where it is, what it holds (Calls / Recordings / Voice messages, changeable)
// and how much is on the server, and Add a folder. After the system's picker, a short question asks what the folder
// holds, so calls are told from recordings even when the file names don't say. Backed up whether or not they are hidden.

/** Settings › Backup › Calls and recordings, as option c draws it. */
@Composable
internal fun BackupScreenC(state: BackupOptionCState, actions: BackupOptionCActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Text(
                stringResource(R.string.backup_c_intro),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            state.folders.forEach { folder ->
                Spacer(Modifier.height(12.dp))
                SettingsCard { FolderCard(folder, actions) }
            }
            Spacer(Modifier.height(16.dp))
            SettingsCard {
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = actions.pickFolder).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(LibraryIcons.Plus, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(stringResource(R.string.backup_c_pick), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.backup_c_pick_body), color = LibraryColors.Muted, fontSize = 13.sp)
                    }
                }
            }
        }
        TitleBar(stringResource(R.string.backup_c_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
    state.adding?.let { picked -> AddDialog(picked, actions) }
}

/** One folder: name and place, remove, what it holds, and how much of it is on the server. */
@Composable
private fun FolderCard(folder: BackupOptionCFolder, actions: BackupOptionCActions) {
    val format = NumberFormat.getIntegerInstance()
    Column(Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(CircleShape).background(LibraryColors.Accent), contentAlignment = Alignment.Center) {
                Icon(kindIcon(folder.kind), null, tint = LibraryColors.PhotoText, modifier = Modifier.size(18.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(folder.name, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(folder.path, color = LibraryColors.Muted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val remove = stringResource(R.string.backup_c_remove, folder.name)
            IconButton(onClick = { actions.remove(folder) }) {
                Icon(LibraryIcons.Close, remove, tint = LibraryColors.Muted, modifier = Modifier.size(18.dp))
            }
        }
        Row(Modifier.padding(top = 10.dp, end = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AudioKind.entries.forEach { kind ->
                Chip(stringResource(kindName(kind)), folder.kind == kind) { actions.setKind(folder, kind) }
            }
        }
        Text(
            stringResource(
                R.string.backup_c_progress,
                format.format(folder.backedUp),
                pluralStringResource(R.plurals.backup_c_files, folder.files, format.format(folder.files)),
            ),
            color = LibraryColors.Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 8.dp),
        )
        LinearProgressIndicator(
            progress = { folder.backedUp.toFloat() / folder.files.coerceAtLeast(1) },
            color = LibraryColors.OnBackground,
            trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 12.dp),
        )
    }
}

/** After the picker: what the folder holds, to add it. */
@Composable
private fun AddDialog(picked: BackupOptionCFolder, actions: BackupOptionCActions) {
    var kind by remember(picked.id) { mutableStateOf(picked.kind) }
    AlertDialog(
        onDismissRequest = actions.cancelAdd,
        title = { Text(stringResource(R.string.backup_c_add_title, picked.name)) },
        text = {
            Column {
                Text(picked.path, color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 8.dp))
                AudioKind.entries.forEach { option ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .selectable(selected = kind == option, role = Role.RadioButton) { kind = option }.padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = kind == option, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(kindName(option)), fontSize = 16.sp)
                    }
                }
                Text(stringResource(R.string.backup_c_add_body), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { actions.confirmAdd(kind) }) { Text(stringResource(R.string.backup_c_add_confirm)) } },
        dismissButton = { TextButton(onClick = actions.cancelAdd) { Text(stringResource(R.string.backup_c_add_cancel)) } },
    )
}

private fun kindName(kind: AudioKind): Int = when (kind) {
    AudioKind.CALL -> R.string.backup_c_kind_calls
    AudioKind.RECORDING -> R.string.backup_c_kind_recordings
    AudioKind.VOICE_MESSAGE -> R.string.backup_c_kind_voice
}
