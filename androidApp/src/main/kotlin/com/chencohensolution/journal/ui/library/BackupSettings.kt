package com.chencohensolution.journal.ui.library

import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.semantics.contentDescription
import com.chencohensolution.journal.R
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.chencohensolution.journal.backup.BackupActivity
import com.chencohensolution.journal.backup.BackupPill
import com.chencohensolution.journal.backup.KeepOnPhone
import com.chencohensolution.journal.backup.SoundFolder
import com.chencohensolution.journal.backup.pills
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.backup.UploadOrder
import com.chencohensolution.journal.backup.WaitReason
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.StorageUsage

// Settings › Backup, a screen of its own since Oct 24, 2026 (its row in Settings says how it goes): what it shows
// and what it can do.

/** What the Backup section shows: the backup state and what only the app knows about the device. */
internal data class BackupUi(
    val state: BackupState,
    /** Photos of the chosen sources not on the server yet. */
    val waitingCount: Int,
    /** "Allow all the time" location: needed to check the Wi-Fi network while the app is closed. */
    val canCheckNetworkInBackground: Boolean,
    /** What the user's originals take up on the server; null until it answered. */
    val serverUsage: StorageUsage? = null,
    /** The library's photos per source: What to back up has a pill for each, the most photos first. */
    val counts: Map<MediaSource, Int> = emptyMap(),
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
    val toggleFolder: (SoundFolder) -> Unit = {},
    /** Opens the system's folder picker; a folder picked opens the add dialog ([picked]). */
    val pickFolder: () -> Unit = {},
    val addFolder: (PickedFolder, AudioKind) -> Unit = { _, _ -> },
    val cancelAdd: () -> Unit = {},
    /** Stops backing up a folder (the server keeps what it sent). */
    val removeFolder: (SoundFolder) -> Unit = {},
    val setKeepOnPhone: (KeepOnPhone) -> Unit = {},
    /** A folder just picked: the add dialog is open on it. */
    val picked: State<PickedFolder?> = mutableStateOf(null),
)

/**
 * Settings › Backup: on or off with its status, then what uploads (a pill per source and per folder of calls or
 * recordings, "+ Add a folder" last), when, and in which order.
 */
@Composable
internal fun BackupSettingsScreen(ui: BackupUi, actions: BackupActions, back: () -> Unit) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            BackupSection(ui, actions)
        }
        TitleBar(stringResource(R.string.settings_backup_title), back, Modifier.align(Alignment.TopCenter))
    }
    actions.picked.value?.let { picked -> AddFolderDialog(picked, { kind -> actions.addFolder(picked, kind) }, actions.cancelAdd) }
}

/** Backup: on or off with its status, then what uploads, when, and in which order. */
@Composable
private fun BackupSection(ui: BackupUi, actions: BackupActions) {
    val settings = ui.state.settings
    SettingsCard {
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
    BackupPills(
        ui.state.pills(ui.counts),
        toggle = { pill ->
            when (pill) {
                is BackupPill.Source -> actions.toggleSource(pill.source)
                is BackupPill.Folder -> actions.toggleFolder(pill.folder)
            }
        },
        pickFolder = actions.pickFolder,
        remove = actions.removeFolder,
        showCounts = false,
    )
    if (settings.soundFolders.isNotEmpty()) {
        Text(stringResource(R.string.backup_note), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
    }
    // Always shown, as the owner asked (Oct 25, 2026), also before a sound folder is added.
    KeepOnPhoneChoice(settings.keepOnPhone, actions.setKeepOnPhone)

    SectionTitle(stringResource(R.string.settings_backup_when_title))
    SettingsCard {
        SwitchRow(
            title = stringResource(R.string.settings_backup_wifi_only),
            body = stringResource(R.string.settings_backup_wifi_only_body),
            checked = settings.wifiOnly,
            onChange = actions.setWifiOnly,
        )
        HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
        SwitchRow(
            title = stringResource(R.string.settings_backup_charging_only),
            body = stringResource(R.string.settings_backup_charging_only_body),
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
internal fun backupStatus(ui: BackupUi): String {
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
