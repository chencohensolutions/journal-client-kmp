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

import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// UI lab, Settings › Backup option B: the screen as the app has it (BackupSettings.kt), with the server's copies of
// the user's data on other disks (journal-server-go/docs/copies.md). The copy keeps BackupUi, BackupActions and
// backupStatus from the app.

/**
 * Settings › Backup: on or off with its status, then what uploads (a pill per source and per folder of calls or
 * recordings, "+ Add a folder" last), when, and in which order.
 */
@Composable
internal fun BackupSettingsScreenB(ui: BackupUi, copies: CopiesStateB, actions: BackupActions, copiesActions: CopiesActionsB, back: () -> Unit) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
       ) {
            BackupSection(ui, actions) { CopiesSection(copies, copiesActions) }
        }
        TitleBar(stringResource(R.string.settings_backup_title), back, Modifier.align(Alignment.TopCenter))
    }
    actions.picked.value?.let { picked -> AddFolderDialog(picked, { kind -> actions.addFolder(picked, kind) }, actions.cancelAdd) }
}

/** Backup: on or off with its status, then what uploads, when, and in which order. */
@Composable
private fun BackupSection(ui: BackupUi, actions: BackupActions, copies: @Composable () -> Unit) {
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
    copies()
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
    // Always shown, as the owner asked (Oct 25, 2026), also before a sound folder is added; one card with a switch per
    // kind since Oct 28, 2026 (docs/ux-history/backup/2026-10-28-keep-on-phone.md).
    KeepOnPhoneCard(ui, actions)

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

/**
 * Keep on this phone, under Backup's pills: a switch for each kind backup sends smaller, recordings kept as FLAC in
 * place of the WAV and photos as JPEG XL in place of the JPEG, both off by default, each saying what it does. While
 * JPEG XL is kept without "Media management" access, a row asks for it. The server always keeps FLAC and JPEG XL.
 */
@Composable
private fun KeepOnPhoneCard(ui: BackupUi, actions: BackupActions) {
    val settings = ui.state.settings
    SectionTitle(stringResource(R.string.backup_keep_title))
    SettingsCard {
        val flac = settings.keepOnPhone == KeepOnPhone.FLAC
        SwitchRow(
            title = stringResource(R.string.backup_keep_flac),
            body = stringResource(if (flac) R.string.backup_keep_flac_on else R.string.backup_keep_flac_off),
            checked = flac,
            onChange = { actions.setKeepOnPhone(if (it) KeepOnPhone.FLAC else KeepOnPhone.WAV) },
        )
        HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
        SwitchRow(
            title = stringResource(R.string.backup_keep_jxl),
            body = stringResource(if (settings.keepJpegXlOnPhone) R.string.backup_keep_jxl_on else R.string.backup_keep_jxl_off),
            checked = settings.keepJpegXlOnPhone,
            onChange = actions.setKeepJpegXl,
        )
        if (settings.keepJpegXlOnPhone && !ui.canTrashWithoutAsking) {
            HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.backup_keep_needs_access), color = LibraryColors.Warning, fontSize = 13.sp, modifier = Modifier.weight(1f))
                TextButton(onClick = actions.allowMediaManagement) { Text(stringResource(R.string.backup_keep_allow), color = LibraryColors.OnBackground) }
            }
        }
    }
    Text(stringResource(R.string.backup_keep_note), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
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

// What every copy shows, in this option's own words (strings_backup_x).

/** How much a copy's state matters: it picks the color of its line, its bar and its mark. */
private enum class ToneB { OK, BUSY, WARN, ERROR }

@Composable
private fun ToneB.color() = when (this) {
    ToneB.OK -> LibraryColors.OnBackground
    ToneB.BUSY -> LibraryColors.Muted
    ToneB.WARN -> LibraryColors.Warning
    ToneB.ERROR -> LibraryColors.Error
}

/** The copy's state in a line: can't be found, full, copying, up to date, or what it lacks until its next pass. */
@Composable
private fun copyStatus(copy: DataCopyB): Pair<String, ToneB> {
    val format = NumberFormat.getIntegerInstance()
    val running = copy.running
    return when {
        copy.problem == CopyProblemB.FOLDER_MISSING ->
            stringResource(R.string.backup_b_missing, whenText(copy.problemSinceMillis ?: 0)) to ToneB.ERROR
        copy.problem == CopyProblemB.DISK_FULL -> stringResource(R.string.backup_b_full) to ToneB.ERROR
        running != null -> stringResource(R.string.backup_b_copying, format.format(running.done), format.format(running.total)) to ToneB.BUSY
        copy.missingFiles == 0 -> stringResource(R.string.backup_b_up_to_date) to ToneB.OK
        else -> pluralStringResource(
            R.plurals.backup_b_to_copy, copy.missingFiles, format.format(copy.missingFiles), sizeText(copy.missingBytes),
        ) to ToneB.BUSY
    }
}

/** The worst of the copy's state and its warnings (files that failed, the data folder's own disk). */
private fun DataCopyB.tone(): ToneB = when {
    problem == CopyProblemB.FOLDER_MISSING || problem == CopyProblemB.DISK_FULL -> ToneB.ERROR
    failedFiles > 0 || problem == CopyProblemB.SAME_DISK -> ToneB.WARN
    running != null || missingFiles > 0 -> ToneB.BUSY
    else -> ToneB.OK
}

/** The warnings under a copy's state: files that failed, and a copy on the data folder's own disk. */
@Composable
private fun CopyWarnings(copy: DataCopyB) {
    val format = NumberFormat.getIntegerInstance()
    if (copy.failedFiles > 0) {
        Text(
            pluralStringResource(R.plurals.backup_b_failed, copy.failedFiles, format.format(copy.failedFiles)),
            color = LibraryColors.Warning,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (copy.problem == CopyProblemB.SAME_DISK) {
        Text(stringResource(R.string.backup_b_same_disk), color = LibraryColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The bar of how many of the server's files the copy holds. */
@Composable
private fun CopyBar(copy: DataCopyB, serverFiles: Int, modifier: Modifier = Modifier) {
    val tone = copy.tone()
    LinearProgressIndicator(
        progress = { copy.copiedFiles.toFloat() / serverFiles.coerceAtLeast(1) },
        color = if (tone == ToneB.ERROR) tone.color() else LibraryColors.OnBackground,
        trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
        drawStopIndicator = {},
        modifier = modifier.fillMaxWidth(),
    )
}

/** "49,377 of 49,414 files". */
@Composable
private fun filesOfText(copy: DataCopyB, serverFiles: Int): String {
    val format = NumberFormat.getIntegerInstance()
    return stringResource(R.string.backup_b_files_of, format.format(copy.copiedFiles), format.format(serverFiles))
}

/** "As files arrive, and daily at 03:30" or "Daily at 04:00". */
@Composable
private fun scheduleText(copy: DataCopyB) =
    stringResource(if (copy.realtime) R.string.backup_b_realtime else R.string.backup_b_daily, copy.dailyAt)

/** "Last pass 03:30 · 120 files, 1.4 GB". */
@Composable
private fun lastPassText(copy: DataCopyB): String {
    val pass = copy.lastPass ?: return stringResource(R.string.backup_b_never)
    return stringResource(R.string.backup_b_last_pass, whenText(pass.atMillis), NumberFormat.getIntegerInstance().format(pass.files), sizeText(pass.bytes))
}

/** The rest of what is known about a copy: when it runs, its last pass, its disk, and what only it holds. */
@Composable
private fun CopyDetails(copy: DataCopyB) {
    val lines = buildList {
        add(scheduleText(copy))
        add(lastPassText(copy))
        copy.freeBytes?.let { add(stringResource(R.string.backup_b_free, sizeText(it))) }
        if (copy.inCopyOnly > 0) {
            add(pluralStringResource(R.plurals.backup_b_in_copy_only, copy.inCopyOnly, NumberFormat.getIntegerInstance().format(copy.inCopyOnly)))
        }
    }
    lines.forEach { Text(it, color = LibraryColors.Muted, fontSize = 13.sp) }
}

/** The copy's mark beside its name: a check when up to date, a spinner while copying, a dot for what needs a look. */
@Composable
private fun CopyMark(copy: DataCopyB) {
    val tone = copy.tone()
    when {
        copy.running != null && tone != ToneB.ERROR ->
            CircularProgressIndicator(color = LibraryColors.Muted, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
        tone == ToneB.ERROR -> Box(Modifier.size(10.dp).background(tone.color(), CircleShape))
        copy.missingFiles == 0 -> Icon(LibraryIcons.Check, contentDescription = null, tint = LibraryColors.OnBackground, modifier = Modifier.size(18.dp))
        tone == ToneB.WARN -> Box(Modifier.size(10.dp).background(tone.color(), CircleShape))
        else -> Unit
    }
}

/** A time today ("03:30"), or the day of another ("Oct 25"). */
@Composable
private fun whenText(millis: Long): String {
    val time = Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault())
    return if (time.toLocalDate() == LocalDate.now()) formatDate(time, "Hm") else formatDate(time, "MMMd")
}

@Composable
private fun sizeText(bytes: Long) = Formatter.formatShortFileSize(LocalContext.current, bytes)

/**
 * Copies on other disks, right under the top card and also with backup off: a card per copy with a bar of how many
 * of the server's files it holds and its state in a line; a tap shows when it runs, its last pass and its disk.
 */
@Composable
private fun CopiesSection(copies: CopiesStateB, actions: CopiesActionsB) {
    SectionTitle(stringResource(R.string.backup_b_copies_title))
    if (copies.copies.isEmpty()) {
        Text(stringResource(R.string.backup_b_copies_none), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        copies.copies.forEach { copy -> SettingsCard { CopyCard(copy, copies.serverFiles, actions) } }
    }
    copies.checkedAtMillis?.let {
        Text(stringResource(R.string.backup_b_checked, whenText(it)), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp))
    }
}

@Composable
private fun CopyCard(copy: DataCopyB, serverFiles: Int, actions: CopiesActionsB) {
    var open by rememberSaveable(copy.folder) { mutableStateOf(false) }
    val (status, tone) = copyStatus(copy)
    Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (open) 4.dp else 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                copy.folder,
                color = LibraryColors.OnBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(end = 8.dp),
            )
            CopyMark(copy)
        }
        Text(filesOfText(copy, serverFiles), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
        CopyBar(copy, serverFiles, Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(status, color = if (tone == ToneB.ERROR) tone.color() else LibraryColors.OnBackground, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(
                if (open) LibraryIcons.ChevronDown else LibraryIcons.ChevronRight,
                contentDescription = null,
                tint = LibraryColors.Muted,
                modifier = Modifier.size(18.dp),
            )
        }
        CopyWarnings(copy)
        if (open) {
            Column(Modifier.padding(top = 8.dp)) { CopyDetails(copy) }
            if (copy.running == null && copy.problem != CopyProblemB.FOLDER_MISSING) {
                TextButton(onClick = { actions.copyNow(copy) }, modifier = Modifier.padding(top = 2.dp)) {
                    Text(stringResource(R.string.backup_b_copy_now), color = LibraryColors.OnBackground)
                }
            } else {
                Spacer(Modifier.height(10.dp))
            }
        }
    }
}
