package com.chencohensolution.journal.ui.library

import android.text.format.Formatter
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.server.CopiesSummary
import com.chencohensolution.journal.server.CopyHealth
import com.chencohensolution.journal.server.CopyProblem
import com.chencohensolution.journal.server.DataCopy
import com.chencohensolution.journal.server.ServerCopiesState
import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Settings › Backup › Copies on other disks (Oct 28, 2026, option a of docs/ux-history/backup/2026-10-28-copies.md):
// Backup's row, and the page it opens. The server keeps plain copies of the user's files on other disks
// (journal-server-go docs/copies.md); the counts come from its last compare of each copy with the user's files.

internal class CopiesActions(
    /** Starts a full pass of one copy now. */
    val copyNow: (DataCopy) -> Unit = {},
    /** Stops the pass of one copy going now; what it copied stays. */
    val stop: (DataCopy) -> Unit = {},
)

/** Copies on other disks, a row at the end of Backup's top card: how they all are in a line, opening their page. */
@Composable
internal fun CopiesRow(copies: ServerCopiesState, open: () -> Unit) {
    val summary = copies.summary ?: return
    val warning = summary is CopiesSummary.NeedsALook
    val text = when (summary) {
        is CopiesSummary.NeedsALook -> stringResource(R.string.copies_summary_problem, summary.folder)
        is CopiesSummary.Copying -> stringResource(R.string.copies_summary_copying, summary.folder)
        CopiesSummary.Checking -> stringResource(R.string.copies_summary_checking)
        is CopiesSummary.AllUpToDate -> pluralStringResource(R.plurals.copies_summary_all, summary.count, summary.count)
        is CopiesSummary.SomeUpToDate -> stringResource(R.string.copies_summary_some, summary.upToDate, summary.count)
    }
    val color = copies.copies.maxOf { it.health }.color()
    HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
    Row(
        Modifier.fillMaxWidth().clickable(onClick = open).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.copies_title), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(text, color = if (warning) color else LibraryColors.Muted, fontSize = 13.sp)
        }
        if (warning) Box(Modifier.padding(end = 8.dp).size(10.dp).background(color, CircleShape))
        Icon(LibraryIcons.ChevronRight, contentDescription = null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
    }
}

/**
 * Settings › Backup › Copies on other disks: a line on what copies are, then a card per copy with its files in large
 * type, its bar, its state and warnings, and all that is known about it, with Copy now.
 */
@Composable
internal fun CopiesScreen(copies: ServerCopiesState, actions: CopiesActions, back: () -> Unit) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.copies_body), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 32.dp))
            if (copies.loaded && copies.copies.isEmpty()) {
                Text(stringResource(R.string.copies_none), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 32.dp))
            }
            copies.copies.forEach { copy -> SettingsCard { CopyCard(copy, copies.acting, actions) } }
            val footer = copies.error?.let { stringResource(R.string.copies_offline) }
                ?: copies.checkedAtMillis?.let { stringResource(R.string.copies_checked, whenText(it)) }
            footer?.let {
                Text(it, color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 32.dp))
            }
        }
        TitleBar(stringResource(R.string.copies_title), back, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun CopyCard(copy: DataCopy, acting: Boolean, actions: CopiesActions) {
    val format = NumberFormat.getIntegerInstance()
    val error = copy.health == CopyHealth.ERROR
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(copy.folder, color = LibraryColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f).padding(end = 8.dp))
            CopyMark(copy)
        }
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.Bottom) {
            Text(format.format(copy.heldFiles), color = LibraryColors.OnBackground, fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
            Text(
                stringResource(R.string.copies_of_files, format.format(copy.originals)),
                color = LibraryColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 6.dp, bottom = 5.dp),
            )
        }
        // While a pass copies, the bar is that pass, in bytes; otherwise how much of the user's files the copy holds.
        val running = copy.running?.takeIf { !it.counting }
        LinearProgressIndicator(
            progress = { running?.fraction ?: (copy.heldFiles.toFloat() / copy.originals.coerceAtLeast(1)) },
            color = if (error) copy.health.color() else LibraryColors.OnBackground,
            trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
            drawStopIndicator = {},
            modifier = Modifier.padding(top = 6.dp).fillMaxWidth(),
        )
        Text(copyStatus(copy), color = if (error) copy.health.color() else LibraryColors.OnBackground, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        CopyWarnings(copy)
        HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 10.dp))
        CopyDetails(copy)
        when {
            copy.running != null -> TextButton(onClick = { actions.stop(copy) }, enabled = !acting, modifier = Modifier.padding(top = 2.dp)) {
                Text(stringResource(R.string.copies_stop), color = LibraryColors.OnBackground)
            }
            copy.problem != CopyProblem.FOLDER_MISSING -> TextButton(onClick = { actions.copyNow(copy) }, enabled = !acting, modifier = Modifier.padding(top = 2.dp)) {
                Text(stringResource(R.string.copies_copy_now), color = LibraryColors.OnBackground)
            }
            else -> Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun CopyHealth.color(): Color = when (this) {
    CopyHealth.OK -> LibraryColors.OnBackground
    CopyHealth.BUSY -> LibraryColors.Muted
    CopyHealth.WARN -> LibraryColors.Warning
    CopyHealth.ERROR -> LibraryColors.Error
}

/** The copy's state in a line: can't be found, full, copying, comparing, up to date, or what it lacks. */
@Composable
private fun copyStatus(copy: DataCopy): String {
    val format = NumberFormat.getIntegerInstance()
    val running = copy.running
    return when {
        copy.problem == CopyProblem.FOLDER_MISSING -> stringResource(R.string.copies_missing, whenText(copy.problemSinceMillis ?: 0))
        copy.problem == CopyProblem.DISK_FULL -> stringResource(R.string.copies_full)
        running != null && running.counting -> stringResource(R.string.copies_counting)
        running != null -> stringResource(
            R.string.copies_copying,
            sizeText(running.doneBytes),
            sizeText(running.totalBytes),
            format.format(running.doneFiles),
            format.format(running.totalFiles),
        )
        copy.checkedAtMillis == null -> stringResource(R.string.copies_checking)
        copy.missingFiles == 0 -> stringResource(R.string.copies_up_to_date)
        else -> pluralStringResource(R.plurals.copies_to_copy, copy.missingFiles, format.format(copy.missingFiles), sizeText(copy.missingBytes))
    }
}

/** The warnings under a copy's state: files that failed, and a copy on the server's own disk. */
@Composable
private fun CopyWarnings(copy: DataCopy) {
    if (copy.failedFiles > 0) {
        Text(
            pluralStringResource(R.plurals.copies_failed, copy.failedFiles, NumberFormat.getIntegerInstance().format(copy.failedFiles)),
            color = LibraryColors.Warning,
            fontSize = 13.sp,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    if (copy.sameDisk) {
        Text(stringResource(R.string.copies_same_disk), color = LibraryColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** The rest of what is known about a copy: when it runs, its last pass, its disk, and what only it holds. */
@Composable
private fun CopyDetails(copy: DataCopy) {
    val format = NumberFormat.getIntegerInstance()
    val lines = buildList {
        add(stringResource(if (copy.realtime) R.string.copies_realtime else R.string.copies_daily, copy.dailyAt))
        add(
            copy.lastPass?.let {
                stringResource(
                    if (it.stopped) R.string.copies_last_pass_stopped else R.string.copies_last_pass,
                    whenText(it.finishedAtMillis),
                    format.format(it.files),
                    sizeText(it.bytes),
                )
            }
                ?: stringResource(R.string.copies_never),
        )
        copy.freeBytes?.let { add(stringResource(R.string.copies_free, sizeText(it))) }
        if (copy.inCopyOnly > 0) add(pluralStringResource(R.plurals.copies_in_copy_only, copy.inCopyOnly, format.format(copy.inCopyOnly)))
    }
    lines.forEach { Text(it, color = LibraryColors.Muted, fontSize = 13.sp) }
}

/** The copy's mark beside its name: a dot for what needs a look, a check when up to date; none while copying. */
@Composable
private fun CopyMark(copy: DataCopy) {
    val health = copy.health
    when {
        copy.running != null && health != CopyHealth.ERROR -> Unit
        health == CopyHealth.ERROR -> Box(Modifier.size(10.dp).background(health.color(), CircleShape))
        copy.upToDate -> Icon(LibraryIcons.Check, contentDescription = null, tint = LibraryColors.OnBackground, modifier = Modifier.size(18.dp))
        health == CopyHealth.WARN -> Box(Modifier.size(10.dp).background(health.color(), CircleShape))
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
