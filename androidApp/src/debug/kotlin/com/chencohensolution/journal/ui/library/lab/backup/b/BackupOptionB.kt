package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import java.text.NumberFormat

// Option b, "pills": sound folders are pills of their own, in a row under the photo sources, with the kind's icon
// and the count, on (filled) when backed up; a last pill "+ Add a folder" opens the system's picker. The lightest
// change: it reads like the sources above it. Backed up whether or not they are hidden.

/** Settings › Backup with sound folders, as option b draws it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BackupScreenB(ui: BackupUi, folders: List<BackupOptionBFolder>, actions: BackupActions, soundActions: BackupOptionBActions, back: () -> Unit) {
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
            SourcePills(settings.choices(ui.counts), ui.counts, { it in settings.sources }, actions.toggleSource)

            SectionTitle(stringResource(R.string.backup_b_title))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                folders.forEach { folder ->
                    FilterChip(
                        selected = folder.on,
                        onClick = { soundActions.toggle(folder) },
                        label = { Text("${folder.name} · ${format.format(folder.files)}") },
                        leadingIcon = { Icon(kindIcon(folder.kind), null, modifier = Modifier.size(16.dp)) },
                        colors = colors,
                    )
                }
                FilterChip(
                    selected = false,
                    onClick = soundActions.pickFolder,
                    label = { Text(stringResource(R.string.backup_b_pick)) },
                    leadingIcon = { Icon(LibraryIcons.Plus, null, modifier = Modifier.size(16.dp)) },
                    colors = colors,
                )
            }
            Text(
                stringResource(R.string.backup_b_hidden_note),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
        }
        TitleBar(stringResource(R.string.settings_backup_title), back, Modifier.align(Alignment.TopCenter))
    }
}
