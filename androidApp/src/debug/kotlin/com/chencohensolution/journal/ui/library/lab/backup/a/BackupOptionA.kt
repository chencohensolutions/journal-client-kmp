package com.chencohensolution.journal.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.AudioKind
import java.text.NumberFormat

// Option a, "a card of folders": under What to back up, a Calls and recordings card lists the folders backup reads,
// each with what it holds and a switch; below them, the folders the app found where the phone's recorders keep
// theirs, one tap to add; then Add a folder (the system's picker). Backed up whether or not they are hidden.

/** Settings › Backup with sound folders, as option a draws it: the screen as now, with the card after the sources. */
@Composable
internal fun BackupScreenA(ui: BackupUi, sound: BackupOptionAState, actions: BackupActions, soundActions: BackupOptionAActions, back: () -> Unit) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val settings = ui.state.settings
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
            SourcePills(settings.choices(ui.counts), ui.counts, { it in settings.sources }, actions.toggleSource)

            SectionTitle(stringResource(R.string.backup_a_title))
            SettingsCard {
                sound.folders.forEachIndexed { i, folder ->
                    if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                    FolderRow(folder) {
                        Switch(
                            checked = folder.on,
                            onCheckedChange = { soundActions.setOn(folder, it) },
                            colors = SwitchDefaults.colors(checkedTrackColor = LibraryColors.OnBackground, checkedThumbColor = LibraryColors.Background),
                        )
                    }
                }
                if (sound.found.isNotEmpty()) {
                    if (sound.folders.isNotEmpty()) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                    Text(
                        stringResource(R.string.backup_a_found),
                        color = LibraryColors.Muted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                    )
                    sound.found.forEach { folder ->
                        FolderRow(folder) {
                            TextButton(onClick = { soundActions.add(folder) }) {
                                Text(stringResource(R.string.backup_a_add), color = LibraryColors.OnBackground, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                }
                HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                Row(
                    Modifier.fillMaxWidth().clickable(onClick = soundActions.pickFolder).padding(horizontal = 16.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(LibraryIcons.Plus, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.backup_a_pick), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Text(
                stringResource(R.string.backup_a_hidden_note),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
        TitleBar(stringResource(R.string.settings_backup_title), back, Modifier.align(Alignment.TopCenter))
    }
}

/** A folder: what it holds (its icon), its name and where it is, its files, size and what waits; then [end]. */
@Composable
private fun FolderRow(folder: BackupOptionAFolder, end: @Composable () -> Unit) {
    val format = NumberFormat.getIntegerInstance()
    val size = Formatter.formatShortFileSize(LocalContext.current, folder.bytes)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(36.dp).clip(CircleShape).background(LibraryColors.Accent), contentAlignment = Alignment.Center) {
            Icon(kindIcon(folder.kind), null, tint = LibraryColors.PhotoText, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                stringResource(
                    when (folder.kind) {
                        AudioKind.CALL -> R.string.backup_a_kind_calls
                        AudioKind.RECORDING -> R.string.backup_a_kind_recordings
                        AudioKind.VOICE_MESSAGE -> R.string.backup_a_kind_voice
                    },
                    folder.name,
                ),
                color = LibraryColors.OnBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(folder.path, color = LibraryColors.Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val files = pluralStringResource(R.plurals.backup_a_files, folder.files, format.format(folder.files))
            Text(
                if (folder.on && folder.waiting > 0) {
                    stringResource(R.string.backup_a_files_waiting, files, size, format.format(folder.waiting))
                } else {
                    stringResource(R.string.backup_a_files_size, files, size)
                },
                color = LibraryColors.Muted,
                fontSize = 13.sp,
            )
        }
        end()
    }
}
