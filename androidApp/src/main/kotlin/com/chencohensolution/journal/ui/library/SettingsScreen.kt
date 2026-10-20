package com.chencohensolution.journal.ui.library

import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.mutableStateOf
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
import com.chencohensolution.journal.server.PreviewFormat
import com.chencohensolution.journal.server.PreviewOptions
import com.chencohensolution.journal.server.PreviewQuality
import com.chencohensolution.journal.server.StorageUsage
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.settings.SimilarityThreshold
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.storage.FreeUpActivity
import com.chencohensolution.journal.storage.FreeUpLine
import com.chencohensolution.journal.storage.FreeUpSummary
import com.chencohensolution.journal.storage.FreeUpRule
import com.chencohensolution.journal.storage.FreeUpState
import com.chencohensolution.journal.storage.KeptReason

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
    /** What the user's originals take up on the server; null until it answered. */
    val serverUsage: StorageUsage? = null,
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

/** What the Storage section shows. */
internal data class StorageUi(
    /** Bytes the previews of server photos take up here; null while counting. */
    val previewBytes: Long?,
    val previewLimitGigabytes: Int,
    val freeUp: FreeUpState,
    /** "Media management" access: files go to the trash without a prompt each time. */
    val canTrashWithoutAsking: Boolean,
    /** Previews from the server (format, quality), or originals. */
    val previewOptions: PreviewOptions = PreviewOptions(),
)

internal class StorageActions(
    val setPreviewsEnabled: (Boolean) -> Unit = {},
    val setPreviewFormat: (PreviewFormat) -> Unit = {},
    val setPreviewQuality: (PreviewQuality) -> Unit = {},
    val setPreviewLimit: (Int) -> Unit = {},
    val clearPreviews: () -> Unit = {},
    val setRule: (FreeUpRule) -> Unit = {},
    /** Starts "Free up space" in the background. */
    val freeUp: () -> Unit = {},
    /** Opens the system page for "Media management" access, then starts "Free up space". */
    val allowTrashWithoutAsking: () -> Unit = {},
    /** One system prompt for the checked files that wait. */
    val moveWaitingToTrash: () -> Unit = {},
    /** Lets a source's photos leave the phone, or keeps them. */
    val toggleSource: (MediaSource) -> Unit = {},
    /** Opens the Free up space screen. */
    val openFreeUp: () -> Unit = {},
)

/** The user's choices, kept on this device. See docs/screens/settings.md. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(
    settings: SettingsState,
    actions: SettingsActions,
    backup: BackupUi? = null,
    backupActions: BackupActions = BackupActions(),
    storage: StorageUi? = null,
    storageActions: StorageActions = StorageActions(),
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
            if (storage != null) StorageSection(storage, storageActions)
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
        HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.settings_backup_server_usage), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(serverUsageText(ui.serverUsage), color = LibraryColors.Muted, fontSize = 13.sp)
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

/**
 * How server photos are shown (previews in a format and quality, or originals) and the space they may take here, and
 * "Free up space": a rule, what it would remove, and the last run.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StorageSection(ui: StorageUi, actions: StorageActions) {
    val context = LocalContext.current
    val options = ui.previewOptions
    SectionTitle(stringResource(R.string.settings_storage_title))
    Card {
        SwitchRow(
            title = stringResource(R.string.settings_storage_previews),
            body = stringResource(if (options.enabled) R.string.settings_storage_previews_on else R.string.settings_storage_previews_off),
            checked = options.enabled,
            onChange = actions.setPreviewsEnabled,
        )
        if (options.enabled) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 4.dp)) {
                Text(stringResource(R.string.settings_storage_format), color = LibraryColors.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(Modifier.padding(top = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewFormat.entries.forEach { format ->
                        Chip(formatName(format), options.format == format) { actions.setPreviewFormat(format) }
                    }
                }
                Text(formatHint(options.format), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                Text(
                    stringResource(R.string.settings_storage_quality),
                    color = LibraryColors.OnBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                FlowRow(Modifier.padding(top = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewQuality.entries.forEach { quality ->
                        Chip(qualityName(quality), options.quality == quality) { actions.setPreviewQuality(quality) }
                    }
                }
            }
        }
        HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f), modifier = Modifier.padding(top = 8.dp))
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.settings_storage_kept), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                ui.previewBytes?.let {
                    stringResource(
                        R.string.settings_storage_previews_used,
                        Formatter.formatShortFileSize(context, it),
                        stringResource(R.string.settings_storage_gigabytes, ui.previewLimitGigabytes),
                    )
                } ?: stringResource(R.string.settings_storage_counting),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            FlowRow(Modifier.padding(top = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsState.PREVIEW_CACHE_GB_OPTIONS.forEach { gigabytes ->
                    Chip(stringResource(R.string.settings_storage_gigabytes, gigabytes), ui.previewLimitGigabytes == gigabytes) { actions.setPreviewLimit(gigabytes) }
                }
            }
            TextButton(onClick = actions.clearPreviews) { Text(stringResource(R.string.settings_storage_clear_previews), color = LibraryColors.OnBackground) }
        }
    }

    SectionTitle(stringResource(R.string.settings_free_up_title))
    Card {
        Row(
            Modifier.fillMaxWidth().clickable(onClick = actions.openFreeUp).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_free_up_title), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(freeUpRowText(ui.freeUp), color = LibraryColors.Muted, fontSize = 13.sp)
            }
            Icon(LibraryIcons.ChevronRight, contentDescription = null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
        }
    }
}

internal const val DEFAULT_KEEP_GIGABYTES = 10

/** The Settings row ([FreeUpState.summary]): what Free up space would free, or what it is doing. */
@Composable
private fun freeUpRowText(state: FreeUpState): String {
    val context = LocalContext.current
    return when (val summary = state.summary) {
        is FreeUpSummary.Checking -> stringResource(R.string.settings_free_up_running, summary.number, summary.total)
        is FreeUpSummary.Waiting -> pluralStringResource(
            R.plurals.settings_free_up_waiting, summary.count, summary.count, Formatter.formatShortFileSize(context, summary.bytes),
        )
        FreeUpSummary.Counting -> stringResource(R.string.settings_storage_counting)
        FreeUpSummary.Nothing -> stringResource(R.string.settings_free_up_row_none)
        is FreeUpSummary.CanFree -> stringResource(R.string.settings_free_up_row_amount, Formatter.formatShortFileSize(context, summary.bytes))
    }
}

@Composable
private fun formatName(format: PreviewFormat): String = stringResource(
    when (format) {
        PreviewFormat.WEBP -> R.string.settings_storage_format_webp
        PreviewFormat.HEIF -> R.string.settings_storage_format_heif
        PreviewFormat.AVIF -> R.string.settings_storage_format_avif
        PreviewFormat.JPEG_XL -> R.string.settings_storage_format_jxl
        PreviewFormat.JPEG -> R.string.settings_storage_format_jpeg
    },
)

/** What a format is good for. */
@Composable
private fun formatHint(format: PreviewFormat): String = stringResource(
    when (format) {
        PreviewFormat.WEBP -> R.string.settings_storage_format_webp_hint
        PreviewFormat.HEIF -> R.string.settings_storage_format_heif_hint
        PreviewFormat.AVIF -> R.string.settings_storage_format_avif_hint
        PreviewFormat.JPEG_XL -> R.string.settings_storage_format_jxl_hint
        PreviewFormat.JPEG -> R.string.settings_storage_format_jpeg_hint
    },
)

@Composable
private fun qualityName(quality: PreviewQuality): String = stringResource(
    when (quality) {
        PreviewQuality.LOW -> R.string.settings_storage_quality_low
        PreviewQuality.MEDIUM -> R.string.settings_storage_quality_medium
        PreviewQuality.HIGH -> R.string.settings_storage_quality_high
        PreviewQuality.MAX -> R.string.settings_storage_quality_max
    },
)

/** "3 months", "1 year". */
@Composable
internal fun ageText(days: Int): String =
    if (days % 365 == 0) {
        pluralStringResource(R.plurals.settings_free_up_years, days / 365, days / 365)
    } else {
        pluralStringResource(R.plurals.settings_free_up_months, days / 30, days / 30)
    }

/** "1,240 photos, 38 videos". */
@Composable
internal fun photosAndVideos(photos: Int, videos: Int): String = listOfNotNull(
    pluralStringResource(R.plurals.settings_backup_server_photos, photos, photos).takeIf { photos > 0 },
    pluralStringResource(R.plurals.settings_backup_server_videos, videos, videos).takeIf { videos > 0 },
).joinToString(", ")

/** [FreeUpState.statusLines] as text: the run going on, why it stopped, or what the last one did; and what waits. */
@Composable
internal fun freeUpStatus(state: FreeUpState): String? {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val lines = state.statusLines.map { line ->
        when (line) {
            is FreeUpLine.Checking -> stringResource(R.string.settings_free_up_running, line.number, line.total) + (line.fileName?.let { " · $it" } ?: "")
            is FreeUpLine.Stopped -> stringResource(R.string.settings_free_up_stopped, line.message)
            is FreeUpLine.Removed -> pluralStringResource(R.plurals.settings_free_up_removed, line.count, line.count, size(line.bytes))
            is FreeUpLine.Kept -> pluralStringResource(
                when (line.reason) {
                    KeptReason.CHANGED_ON_PHONE -> R.plurals.settings_free_up_kept_changed
                    KeptReason.MISSING_ON_SERVER -> R.plurals.settings_free_up_kept_missing
                    KeptReason.DAMAGED_ON_SERVER -> R.plurals.settings_free_up_kept_damaged
                },
                line.count,
                line.count,
            )
            is FreeUpLine.Waiting -> pluralStringResource(R.plurals.settings_free_up_waiting, line.count, line.count, size(line.bytes))
        }
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}

@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = LibraryColors.OnBackground,
            selectedContainerColor = LibraryColors.Inverse,
            selectedLabelColor = LibraryColors.OnInverse,
        ),
    )
}

/** "4.2 GB · 1,234 photos, 56 videos". */
@Composable
private fun serverUsageText(usage: StorageUsage?): String {
    if (usage == null) return stringResource(R.string.settings_backup_server_usage_unknown)
    val size = Formatter.formatShortFileSize(LocalContext.current, usage.bytes)
    val photos = pluralStringResource(R.plurals.settings_backup_server_photos, usage.photos, usage.photos)
    if (usage.videos == 0) return "$size · $photos"
    return "$size · $photos, " + pluralStringResource(R.plurals.settings_backup_server_videos, usage.videos, usage.videos)
}

@Composable
private fun backupStatus(ui: BackupUi): String {
    val state = ui.state
    if (!state.settings.enabled) return stringResource(R.string.settings_backup_off)
    return when (val activity = state.activity) {
        is BackupActivity.Running -> {
            val progress = activity.progress
            val context = LocalContext.current
            backupProgressText(progress) + "\n" + stringResource(
                R.string.settings_backup_status_bytes,
                Formatter.formatShortFileSize(context, progress.bytesDone),
                Formatter.formatShortFileSize(context, progress.bytesTotal),
            )
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

/** A Settings card, for the screens opened from Settings. */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) = Card(content)

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
internal fun SectionTitle(text: String) {
    Text(
        text,
        color = LibraryColors.Muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
internal fun ModeOption(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
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
