package com.chencohensolution.journal.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.backup.BackupPill
import com.chencohensolution.journal.backup.FolderLook
import com.chencohensolution.journal.backup.KeepOnPhone
import com.chencohensolution.journal.backup.SoundFolder
import com.chencohensolution.journal.library.AudioKind
import java.text.NumberFormat

// The list of pills Settings › Backup and Free up space share (UI lab round ux/backup/2026-10-25, option b2): photo
// sources and the folders of calls and recordings the user picked, side by side, not grouped; the ones that are on
// come first (filled), then the rest. Backup ends it with "+ Add a folder", which opens the system's folder picker and
// then AddFolderDialog: what was found in it, what its files are (guessed from their names), and Back it up.

/** A folder just picked with the system's picker, before it is backed up. */
internal data class PickedFolder(val folder: SoundFolder, val look: FolderLook)

/**
 * The pills: a tap turns one on or off ([toggle]; a folder the app can no longer read is picked again instead); a long
 * press on a folder asks to stop backing it up ([remove], when given). [pickFolder], when given, ends the list with
 * "+ Add a folder". [showCounts] adds each pill's files (Free up space; Settings › Backup shows names only, asked
 * Oct 27, 2026), [showBytes] their bytes (Free up space).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupPills(
    pills: List<BackupPill>,
    toggle: (BackupPill) -> Unit,
    pickFolder: (() -> Unit)? = null,
    remove: ((SoundFolder) -> Unit)? = null,
    showCounts: Boolean = true,
    showBytes: Boolean = false,
) {
    val format = NumberFormat.getIntegerInstance()
    val context = LocalContext.current
    var removing by remember { mutableStateOf<SoundFolder?>(null) }
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        pills.forEach { pill ->
            val name = when (pill) {
                is BackupPill.Source -> sourceName(pill.source)
                is BackupPill.Folder -> pill.folder.name
            }
            val bytes = (pill as? BackupPill.Source)?.bytes ?: (pill as? BackupPill.Folder)?.bytes
            val label = buildString {
                append(name)
                if (showCounts && pill.count > 0) append(" · ").append(format.format(pill.count))
                if (showBytes && bytes != null && bytes > 0) append(" · ").append(Formatter.formatShortFileSize(context, bytes))
            }
            val folder = pill as? BackupPill.Folder
            Pill(
                label = if (folder?.lost == true) stringResource(R.string.backup_folder_lost, name) else label,
                selected = pill.on && folder?.lost != true,
                // A folder of sound recordings says what its files are; photo sources need no icon.
                icon = folder?.let { kindIcon(it.folder.kind) },
                warning = folder?.lost == true,
                onClick = { if (folder?.lost == true && pickFolder != null) pickFolder() else toggle(pill) },
                onLongClick = folder?.takeIf { remove != null }?.let { { removing = it.folder } },
            )
        }
        if (pickFolder != null) {
            Pill(stringResource(R.string.backup_pick), selected = false, icon = LibraryIcons.Plus, onClick = pickFolder)
        }
    }
    removing?.let { folder ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.backup_remove_title, folder.name)) },
            text = { Text(stringResource(R.string.backup_remove_body)) },
            confirmButton = {
                TextButton(onClick = {
                    removing = null
                    remove?.invoke(folder)
                }) { Text(stringResource(R.string.backup_remove_confirm)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.backup_add_cancel)) } },
        )
    }
}

/** A pill in the look of the app's filter chips, with a long press. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Pill(label: String, selected: Boolean, icon: ImageVector?, warning: Boolean = false, onClick: () -> Unit, onLongClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(8.dp)
    val content = when {
        selected -> LibraryColors.OnInverse
        warning -> LibraryColors.Warning
        else -> LibraryColors.OnBackground
    }
    Row(
        Modifier
            .height(32.dp)
            .clip(shape)
            .then(if (selected) Modifier.background(LibraryColors.Inverse) else Modifier.border(1.dp, LibraryColors.Muted.copy(alpha = 0.5f), shape))
            .combinedClickable(role = Role.Checkbox, onClick = onClick, onLongClick = onLongClick)
            .padding(start = if (icon != null) 8.dp else 12.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = content, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(label, color = content, fontSize = 14.sp, fontWeight = FontWeight.Medium)
    }
}

/** Under Backup's pills, once a sound folder is added: WAVs go as FLAC, and which of the two stays on the phone. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KeepOnPhoneChoice(keep: KeepOnPhone, setKeep: (KeepOnPhone) -> Unit) {
    Column(Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) {
        Text(stringResource(R.string.backup_wav_note), color = LibraryColors.Muted, fontSize = 13.sp)
        Text(
            stringResource(R.string.backup_keep_title),
            color = LibraryColors.OnBackground,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Chip(stringResource(R.string.backup_keep_wav), keep == KeepOnPhone.WAV) { setKeep(KeepOnPhone.WAV) }
            Chip(stringResource(R.string.backup_keep_flac), keep == KeepOnPhone.FLAC) { setKeep(KeepOnPhone.FLAC) }
        }
        Text(
            stringResource(if (keep == KeepOnPhone.WAV) R.string.backup_keep_wav_body else R.string.backup_keep_flac_body),
            color = LibraryColors.Muted,
            fontSize = 13.sp,
        )
    }
}

/**
 * After the system's picker: the folder's name and place, what was found in it, what its files are (the app's guess
 * chosen), and Back it up.
 */
@Composable
internal fun AddFolderDialog(picked: PickedFolder, add: (AudioKind) -> Unit, cancel: () -> Unit) {
    var kind by remember(picked.folder.treeUri) { mutableStateOf(picked.look.guess) }
    val format = NumberFormat.getIntegerInstance()
    val size = Formatter.formatShortFileSize(LocalContext.current, picked.look.bytes)
    AlertDialog(
        onDismissRequest = cancel,
        icon = {
            Box(Modifier.size(44.dp).clip(CircleShape).background(LibraryColors.Accent), contentAlignment = Alignment.Center) {
                Icon(kindIcon(kind), null, tint = LibraryColors.PhotoText, modifier = Modifier.size(22.dp))
            }
        },
        title = { Text(stringResource(R.string.backup_add_title, picked.folder.name)) },
        text = {
            Column {
                Text(picked.folder.path, color = LibraryColors.Muted, fontSize = 13.sp)
                Text(
                    stringResource(R.string.backup_add_found, pluralStringResource(R.plurals.backup_files, picked.look.files, format.format(picked.look.files)), size),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                Text(stringResource(R.string.backup_add_kind), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
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
                Text(stringResource(R.string.backup_add_body), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { add(kind) }) { Text(stringResource(R.string.backup_add_confirm)) } },
        dismissButton = { TextButton(onClick = cancel) { Text(stringResource(R.string.backup_add_cancel)) } },
    )
}

private fun kindName(kind: AudioKind): Int = when (kind) {
    AudioKind.CALL -> R.string.backup_kind_calls
    AudioKind.RECORDING -> R.string.backup_kind_recordings
    AudioKind.VOICE_MESSAGE -> R.string.backup_kind_voice
}
