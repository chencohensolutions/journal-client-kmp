package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.settings.KindsStatus
import java.text.NumberFormat

/**
 * Settings › Show in the app as a status page (option c3 of the UI lab, Oct 27, 2026), with no switches: a line at the
 * top ("87,343 files" and "15 not backed up ›", or "All backed up"), then a line per kind with its total in a column at
 * the end and "12 not backed up ›" in orange under its name where some of this phone's files aren't on the server yet
 * (tapping it opens Backup); the sound kinds are a second card. Kinds Private settings hides on every device aren't
 * named: one line counts them, with "Private settings ›" (left out until Private settings was opened in this run). A note
 * says hiding on this phone is the Moments filter bar's. Drawn inside Settings' page.
 */
@Composable
internal fun ShowInAppPage(status: KindsStatus, openBackup: () -> Unit, openPrivate: () -> Unit) {
    Summary(status, openBackup)
    if (status.kinds.isNotEmpty()) {
        SectionTitle(stringResource(R.string.kinds_photos))
        SettingsCard {
            status.kinds.forEachIndexed { i, kind ->
                if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                KindRow(sourceName(kind.source), kind.source.icon, kind.files, kind.notBackedUp, openBackup)
            }
        }
    }
    if (status.sounds.isNotEmpty()) {
        SectionTitle(stringResource(R.string.kinds_sound))
        SettingsCard {
            status.sounds.forEachIndexed { i, kind ->
                if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                KindRow(soundName(kind.kind), soundIcon(kind.kind), kind.files, kind.notBackedUp, openBackup)
            }
        }
    }
    val hidden = status.hiddenEverywhere
    if (hidden != null && hidden > 0) HiddenEverywhereLine(hidden, openPrivate)
    Text(
        stringResource(R.string.kinds_how_to_hide),
        color = LibraryColors.Muted,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp),
    )
}

/** "87,343 files" and "15 not backed up ›" (or "All backed up") under the title. */
@Composable
private fun Summary(status: KindsStatus, openBackup: () -> Unit) {
    Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp)) {
        Text(stringResource(R.string.kinds_files, count(status.files)), color = LibraryColors.OnBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (status.notBackedUp > 0) {
            NotBackedUp(status.notBackedUp, openBackup, 15.sp)
        } else {
            Text(stringResource(R.string.kinds_all_backed_up), color = LibraryColors.Muted, fontSize = 15.sp)
        }
    }
}

/**
 * A kind's line: icon and name, its total in a column at the end, and the orange "not backed up" note under the name
 * when some of its files aren't on the server yet.
 */
@Composable
private fun KindRow(name: String, icon: ImageVector, files: Int, notBackedUp: Int, openBackup: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 12.dp)) {
            Text(name, color = LibraryColors.OnBackground, fontSize = 16.sp)
            if (notBackedUp > 0) NotBackedUp(notBackedUp, openBackup, 12.sp)
        }
        Text(
            count(files),
            color = LibraryColors.OnBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
            modifier = Modifier.widthIn(min = 72.dp),
        )
    }
}

/** "☁ 12 not backed up ›" in orange; opens Backup. */
@Composable
private fun NotBackedUp(n: Int, openBackup: () -> Unit, size: TextUnit) {
    Row(
        Modifier.padding(top = 2.dp).clickable(role = Role.Button, onClick = openBackup),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(LibraryIcons.CloudUp, null, tint = LibraryColors.Warning, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.kinds_not_backed_up, count(n)) + " ›", color = LibraryColors.Warning, fontSize = size, fontWeight = FontWeight.SemiBold)
    }
}

/** "🔒 2 kinds hidden on all your devices · Private settings ›", never naming them. */
@Composable
private fun HiddenEverywhereLine(n: Int, openPrivate: () -> Unit) {
    val text = buildAnnotatedString {
        append(pluralStringResource(R.plurals.kinds_hidden_everywhere, n, count(n)))
        append(" · ")
        withStyle(SpanStyle(color = LibraryColors.OnBackground, fontWeight = FontWeight.Bold)) {
            append(stringResource(R.string.settings_hidden_title))
            append(" ›")
        }
    }
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = openPrivate)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(LibraryIcons.Lock, null, tint = LibraryColors.Muted, modifier = Modifier.padding(top = 2.dp).size(15.dp))
        Text(text, color = LibraryColors.Muted, fontSize = 13.sp)
    }
}

/** A sound kind's name: Calls, Voice recordings, Voice messages. */
@Composable
internal fun soundName(kind: AudioKind): String = when (kind) {
    AudioKind.CALL -> stringResource(R.string.feed_source_call)
    AudioKind.RECORDING -> stringResource(R.string.feed_source_recording)
    AudioKind.VOICE_MESSAGE -> stringResource(R.string.kinds_voice_messages)
}

internal fun soundIcon(kind: AudioKind): ImageVector = when (kind) {
    AudioKind.CALL -> AudioIcons.Call
    AudioKind.RECORDING -> AudioIcons.Mic
    AudioKind.VOICE_MESSAGE -> MediaSource.WHATSAPP.icon
}

/**
 * Show in the app's row on Settings' first page: "87,343 files · 15 not backed up", or "… · All backed up".
 */
@Composable
internal fun showInAppSummary(status: KindsStatus): String = stringResource(
    R.string.feed_place_time,
    stringResource(R.string.kinds_files, count(status.files)),
    if (status.notBackedUp > 0) stringResource(R.string.kinds_not_backed_up, count(status.notBackedUp)) else stringResource(R.string.kinds_all_backed_up),
)

private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)
