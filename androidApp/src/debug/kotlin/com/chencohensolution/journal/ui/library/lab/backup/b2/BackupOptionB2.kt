package com.chencohensolution.journal.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.AudioKind
import java.text.NumberFormat

// Option b2, "one list of pills" (from b, Oct 25, 2026): What to back up is one list, photo sources and the folders of
// calls and recordings the user added side by side, not grouped; the ones backed up come first (filled), then the
// rest, and "+ Add a folder" last. Adding a folder opens the system's picker, then a dialog: what was found in it, what
// its files are (guessed from their names), and Back it up. Backed up whether or not they are hidden.

/** Settings › Backup as option b2 draws it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupScreenB2(ui: BackupUi, state: BackupOptionB2State, actions: BackupActions, pillActions: BackupOptionB2Actions, back: () -> Unit) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val settings = ui.state.settings
    val format = NumberFormat.getIntegerInstance()
    val colors = FilterChipDefaults.filterChipColors(
        labelColor = LibraryColors.OnBackground,
        iconColor = LibraryColors.OnBackground,
        selectedContainerColor = LibraryColors.Inverse,
        selectedLabelColor = LibraryColors.OnInverse,
        selectedLeadingIconColor = LibraryColors.OnInverse,
    )
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier.verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.settings_backup_switch),
                    body = stringResource(R.string.settings_backup_switch_body),
                    checked = settings.enabled,
                    onChange = actions.setEnabled,
                )
                HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                Text(backupStatus(ui), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(16.dp))
            }
            SectionTitle(stringResource(R.string.settings_backup_sources_title))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.pills.forEach { pill ->
                    val name = when (pill) {
                        is BackupOptionB2Pill.Source -> sourceName(pill.source)
                        is BackupOptionB2Pill.Folder -> pill.name
                    }
                    FilterChip(
                        selected = pill.on,
                        onClick = { pillActions.toggle(pill) },
                        label = { Text(if (pill.count > 0) "$name · ${format.format(pill.count)}" else name) },
                        // A folder of sound recordings says what its files are; photo sources need no icon.
                        leadingIcon = (pill as? BackupOptionB2Pill.Folder)?.let { folder ->
                            { Icon(kindIcon(folder.kind), null, modifier = Modifier.size(16.dp)) }
                        },
                        colors = colors,
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = pillActions.pickFolder,
                    label = { Text(stringResource(R.string.backup_b2_pick)) },
                    leadingIcon = { Icon(LibraryIcons.Plus, null, modifier = Modifier.size(16.dp)) },
                    colors = colors,
                )
            }
            Text(
                stringResource(R.string.backup_b2_note),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
        TitleBar(stringResource(R.string.settings_backup_title), back, Modifier.align(Alignment.TopCenter))
    }
    state.picked?.let { AddFolderDialog(it, pillActions) }
}

/**
 * After the system's picker: the folder's name and place, what was found in it, what its files are (the app's guess
 * chosen), and Back it up.
 */
@Composable
private fun AddFolderDialog(picked: BackupOptionB2Picked, actions: BackupOptionB2Actions) {
    var kind by remember(picked.path) { mutableStateOf(picked.guess) }
    val format = NumberFormat.getIntegerInstance()
    val size = Formatter.formatShortFileSize(LocalContext.current, picked.bytes)
    AlertDialog(
        onDismissRequest = actions.cancelAdd,
        icon = {
            Box(Modifier.size(44.dp).clip(CircleShape).background(LibraryColors.Accent), contentAlignment = Alignment.Center) {
                Icon(kindIcon(kind), null, tint = LibraryColors.PhotoText, modifier = Modifier.size(22.dp))
            }
        },
        title = { Text(stringResource(R.string.backup_b2_add_title, picked.name)) },
        text = {
            Column {
                Text(picked.path, color = LibraryColors.Muted, fontSize = 13.sp)
                Text(
                    stringResource(R.string.backup_b2_add_found, pluralStringResource(R.plurals.backup_b2_files, picked.files, format.format(picked.files)), size),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
                Text(stringResource(R.string.backup_b2_add_kind), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(bottom = 4.dp))
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
                Text(stringResource(R.string.backup_b2_add_body), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            }
        },
        confirmButton = { TextButton(onClick = { actions.add(kind) }) { Text(stringResource(R.string.backup_b2_add_confirm)) } },
        dismissButton = { TextButton(onClick = actions.cancelAdd) { Text(stringResource(R.string.backup_b2_add_cancel)) } },
    )
}

private fun kindName(kind: AudioKind): Int = when (kind) {
    AudioKind.CALL -> R.string.backup_b2_kind_calls
    AudioKind.RECORDING -> R.string.backup_b2_kind_recordings
    AudioKind.VOICE_MESSAGE -> R.string.backup_b2_kind_voice
}
