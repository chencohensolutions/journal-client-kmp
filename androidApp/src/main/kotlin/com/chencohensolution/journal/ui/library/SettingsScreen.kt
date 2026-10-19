package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.ui.semantics.contentDescription
import com.chencohensolution.journal.R
import com.chencohensolution.journal.backup.BackupActivity
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.backup.UploadOrder
import com.chencohensolution.journal.backup.WaitReason
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.settings.SimilarityThreshold
import com.chencohensolution.journal.settings.TimelineGrouping

internal class SettingsActions(
    val setGroupingMode: (GroupingMode) -> Unit = {},
    val setGapHours: (Int) -> Unit = {},
    val setSimilarityPercent: (Int) -> Unit = {},
    val resetSimilarity: () -> Unit = {},
    val back: () -> Unit = {},
)

/** What the Backup section shows: the backup state and what only the app knows about the device. */
internal data class BackupUi(
    val state: BackupState,
    /** Photos of the chosen sources not on the server yet. */
    val waitingCount: Int,
    /** "Allow all the time" location: needed to check the Wi-Fi network while the app is closed. */
    val canCheckNetworkInBackground: Boolean,
)

internal class BackupActions(
    val setEnabled: (Boolean) -> Unit = {},
    val toggleSource: (MediaSource) -> Unit = {},
    val setWifiOnly: (Boolean) -> Unit = {},
    val setChargingOnly: (Boolean) -> Unit = {},
    val setOrder: (UploadOrder) -> Unit = {},
    /** Asks for location if needed, then lists the Wi-Fi network the device is on. */
    val addCurrentNetwork: () -> Unit = {},
    val removeNetwork: (String) -> Unit = {},
    val allowBackgroundLocation: () -> Unit = {},
    val backUpNow: () -> Unit = {},
)

/** The user's choices, kept on this device. See docs/screens/settings.md. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(
    settings: SettingsState,
    actions: SettingsActions,
    backup: BackupUi? = null,
    backupActions: BackupActions = BackupActions(),
) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val grouping = settings.grouping

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            if (backup != null) BackupSection(backup, backupActions)
            SectionTitle(stringResource(R.string.settings_grouping_title))
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .background(LibraryColors.Raised, RoundedCornerShape(14.dp))
                    .selectableGroup(),
            ) {
                ModeOption(
                    title = stringResource(R.string.settings_grouping_by_day),
                    body = stringResource(R.string.settings_grouping_by_day_body),
                    selected = grouping.mode == GroupingMode.BY_DAY,
                    onSelect = { actions.setGroupingMode(GroupingMode.BY_DAY) },
                )
                ModeOption(
                    title = stringResource(R.string.settings_grouping_by_gap),
                    body = stringResource(R.string.settings_grouping_by_gap_body),
                    selected = grouping.mode == GroupingMode.BY_GAP,
                    onSelect = { actions.setGroupingMode(GroupingMode.BY_GAP) },
                )
            }
            if (grouping.mode == GroupingMode.BY_GAP) {
                SectionTitle(stringResource(R.string.settings_gap_title))
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TimelineGrouping.GAP_HOURS_OPTIONS.forEach { hours ->
                        FilterChip(
                            selected = grouping.gapHours == hours,
                            onClick = { actions.setGapHours(hours) },
                            label = { Text(pluralStringResource(R.plurals.settings_gap_hours, hours, hours)) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = LibraryColors.OnBackground,
                                selectedContainerColor = LibraryColors.Inverse,
                                selectedLabelColor = LibraryColors.OnInverse,
                            ),
                        )
                    }
                }
            }
            SectionTitle(stringResource(R.string.settings_similarity_title))
            SimilarityCard(settings.similarity, actions)
        }
        TitleBar(stringResource(R.string.library_settings), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

/** Backup: on or off with its status, then what uploads, when, and in which order. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackupSection(ui: BackupUi, actions: BackupActions) {
    val settings = ui.state.settings
    SectionTitle(stringResource(R.string.settings_backup_title))
    Card {
        SwitchRow(
            title = stringResource(R.string.settings_backup_switch),
            body = stringResource(R.string.settings_backup_switch_body),
            checked = settings.enabled,
            onChange = actions.setEnabled,
        )
        HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(backupStatus(ui), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
            if (settings.enabled && ui.state.activity !is BackupActivity.Running) {
                TextButton(onClick = actions.backUpNow) { Text(stringResource(R.string.settings_backup_now), color = LibraryColors.OnBackground) }
            }
        }
    }
    if (!settings.enabled) return

    SectionTitle(stringResource(R.string.settings_backup_sources_title))
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        MediaSource.entries.forEach { source ->
            FilterChip(
                selected = source in settings.sources,
                onClick = { actions.toggleSource(source) },
                label = { Text(sourceName(source)) },
                colors = FilterChipDefaults.filterChipColors(
                    labelColor = LibraryColors.OnBackground,
                    selectedContainerColor = LibraryColors.Inverse,
                    selectedLabelColor = LibraryColors.OnInverse,
                ),
            )
        }
    }

    SectionTitle(stringResource(R.string.settings_backup_when_title))
    Card {
        SwitchRow(
            title = stringResource(R.string.settings_backup_wifi_only),
            body = stringResource(R.string.settings_backup_wifi_only_body),
            checked = settings.wifiOnly,
            onChange = actions.setWifiOnly,
        )
        SwitchRow(
            title = stringResource(R.string.settings_backup_charging_only),
            body = null,
            checked = settings.chargingOnly,
            onChange = actions.setChargingOnly,
        )
        if (settings.wifiOnly) {
            HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
            Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                Text(stringResource(R.string.settings_backup_networks), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.settings_backup_networks_body), color = LibraryColors.Muted, fontSize = 13.sp)
                settings.wifiNetworks.sorted().forEach { network ->
                    val remove = stringResource(R.string.settings_backup_remove_network, network)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(network, color = LibraryColors.OnBackground, fontSize = 15.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { actions.removeNetwork(network) }, modifier = Modifier.semantics { contentDescription = remove }) {
                            Icon(LibraryIcons.Close, contentDescription = null, tint = LibraryColors.Muted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                TextButton(onClick = actions.addCurrentNetwork) {
                    Text(stringResource(R.string.settings_backup_add_network), color = LibraryColors.OnBackground)
                }
                if (settings.wifiNetworks.isNotEmpty() && !ui.canCheckNetworkInBackground) {
                    Text(stringResource(R.string.settings_backup_background_location), color = LibraryColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                    TextButton(onClick = actions.allowBackgroundLocation) {
                        Text(stringResource(R.string.settings_backup_background_location_button), color = LibraryColors.OnBackground)
                    }
                }
            }
        }
    }

    SectionTitle(stringResource(R.string.settings_backup_order_title))
    Column(
        Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(LibraryColors.Raised, RoundedCornerShape(14.dp)).selectableGroup(),
    ) {
        ModeOption(
            title = stringResource(R.string.settings_backup_oldest),
            body = stringResource(R.string.settings_backup_oldest_body),
            selected = settings.order == UploadOrder.OLDEST_FIRST,
            onSelect = { actions.setOrder(UploadOrder.OLDEST_FIRST) },
        )
        ModeOption(
            title = stringResource(R.string.settings_backup_newest),
            body = stringResource(R.string.settings_backup_newest_body),
            selected = settings.order == UploadOrder.NEWEST_FIRST,
            onSelect = { actions.setOrder(UploadOrder.NEWEST_FIRST) },
        )
    }
}

@Composable
private fun backupStatus(ui: BackupUi): String {
    val state = ui.state
    if (!state.settings.enabled) return stringResource(R.string.settings_backup_off)
    return when (val activity = state.activity) {
        is BackupActivity.Running -> {
            val progress = activity.progress
            stringResource(R.string.settings_backup_status_running, (progress.done + 1).coerceAtMost(progress.total), progress.total)
        }
        is BackupActivity.Waiting -> stringResource(
            when (activity.reason) {
                WaitReason.WIFI_NETWORK_NOT_ALLOWED -> R.string.settings_backup_wait_network
                WaitReason.NEEDS_LOCATION -> R.string.settings_backup_wait_location
                WaitReason.NOT_REGISTERED -> R.string.settings_backup_wait_registration
                WaitReason.NO_PHOTO_ACCESS -> R.string.settings_backup_wait_access
            },
        )
        is BackupActivity.Stopped -> stringResource(R.string.settings_backup_status_stopped, activity.message)
        BackupActivity.Idle -> if (ui.waitingCount > 0) {
            pluralStringResource(R.plurals.settings_backup_status_waiting, ui.waitingCount, ui.waitingCount)
        } else {
            stringResource(R.string.settings_backup_status_done)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(LibraryColors.Raised, RoundedCornerShape(14.dp))) { content() }
}

@Composable
private fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = { onChange(!checked) }, role = Role.Switch)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (body != null) Text(body, color = LibraryColors.Muted, fontSize = 13.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors = SwitchDefaults.colors(
                checkedThumbColor = LibraryColors.OnInverse,
                checkedTrackColor = LibraryColors.Inverse,
                uncheckedThumbColor = LibraryColors.Muted,
                uncheckedTrackColor = LibraryColors.Background,
                uncheckedBorderColor = LibraryColors.Muted,
            ),
        )
    }
}

/** How alike photos must be for a "Look alike" suggestion: a slider in percent, saved when let go. */
@Composable
private fun SimilarityCard(similarity: SimilarityThreshold, actions: SettingsActions) {
    var dragging by remember(similarity.percent) { mutableFloatStateOf(similarity.percent.toFloat()) }
    val range = SimilarityThreshold.RANGE
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(LibraryColors.Raised, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_similarity_label),
                color = LibraryColors.OnBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text("${dragging.toInt()}%", color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { actions.setSimilarityPercent(dragging.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            colors = SliderDefaults.colors(
                thumbColor = LibraryColors.OnBackground,
                activeTrackColor = LibraryColors.OnBackground,
                inactiveTrackColor = LibraryColors.Muted.copy(alpha = 0.3f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        Text(stringResource(R.string.settings_similarity_body), color = LibraryColors.Muted, fontSize = 13.sp)
        if (!similarity.isDefault) {
            TextButton(onClick = actions.resetSimilarity, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.settings_similarity_reset, similarity.defaultPercent), color = LibraryColors.OnBackground)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = LibraryColors.Muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun ModeOption(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(start = 4.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = LibraryColors.OnBackground, unselectedColor = LibraryColors.Muted),
            modifier = Modifier.padding(12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = LibraryColors.Muted, fontSize = 13.sp)
        }
    }
}
