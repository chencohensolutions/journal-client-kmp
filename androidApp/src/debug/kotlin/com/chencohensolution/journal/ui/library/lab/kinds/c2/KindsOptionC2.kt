package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaSource
import java.text.NumberFormat

/**
 * Settings › Show in the app as a status page, with no switches: a line at the top ("88,801 files · 15 not backed
 * up"), then a row per kind with its files, how many are on this phone, "12 not backed up" in orange where some aren't
 * (tapping it opens Backup), and its state on the right: Shown, or Hidden on this phone (hidden with the filter bar in
 * Moments). The sound kinds are a second card. Kinds Private settings hides on every device aren't named: one line
 * counts them, with "Private settings ›".
 */
@Composable
internal fun KindsScreenC2(state: KindsOptionC2State, actions: KindsOptionC2Actions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Summary(state, actions.openBackup)
            SectionTitle(stringResource(R.string.kinds_c2_photos))
            SettingsCard {
                state.kinds.forEachIndexed { i, kind ->
                    if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                    KindRow(sourceName(kind.source), kind.source.icon, kind.files, kind.onPhone, kind.notBackedUp, kind.hiddenHere, actions.openBackup)
                }
            }
            if (state.sounds.isNotEmpty()) {
                SectionTitle(stringResource(R.string.kinds_c2_sound))
                SettingsCard {
                    state.sounds.forEachIndexed { i, kind ->
                        if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                        KindRow(soundName(kind.sound), soundIcon(kind.sound), kind.files, kind.onPhone, kind.notBackedUp, kind.hiddenHere, actions.openBackup)
                    }
                }
            }
            if (state.hiddenEverywhere > 0) HiddenEverywhereLine(state.hiddenEverywhere, actions.openPrivate)
            Text(
                stringResource(R.string.kinds_c2_how_to_hide),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp),
            )
        }
        TitleBar(stringResource(R.string.settings_shown_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

/** "88,801 files" and "15 not backed up ›" (or "All backed up") under the title. */
@Composable
private fun Summary(state: KindsOptionC2State, openBackup: () -> Unit) {
    Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp)) {
        Text(stringResource(R.string.kinds_c2_files, count(state.files)), color = LibraryColors.OnBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        if (state.notBackedUp > 0) {
            NotBackedUp(state.notBackedUp, openBackup, 15.sp)
        } else {
            Text(stringResource(R.string.kinds_c2_all_backed_up), color = LibraryColors.Muted, fontSize = 15.sp)
        }
    }
}

/** A kind's row: icon, name, "79,000 files · 41,200 on this phone" and the orange "not backed up" note; whether it is hidden on this phone isn't shown. */
@Composable
private fun KindRow(name: String, icon: ImageVector, files: Int, onPhone: Int, notBackedUp: Int, hiddenHere: Boolean, openBackup: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 12.dp)) {
            Text(name, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.kinds_c2_files, count(files)), color = LibraryColors.Muted, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Icon(LibraryIcons.Phone, null, tint = LibraryColors.Muted, modifier = Modifier.size(13.dp))
                Text(stringResource(R.string.kinds_c2_on_phone, count(onPhone)), color = LibraryColors.Muted, fontSize = 13.sp)
            }
            if (notBackedUp > 0) NotBackedUp(notBackedUp, openBackup, 13.sp)
        }
    }
}

/** "☁ 12 not backed up ›" in orange; opens Backup. */
@Composable
private fun NotBackedUp(n: Int, openBackup: () -> Unit, size: androidx.compose.ui.unit.TextUnit) {
    Row(
        Modifier.padding(top = 2.dp).clickable(role = Role.Button, onClick = openBackup),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(LibraryIcons.CloudUp, null, tint = LibraryColors.Warning, modifier = Modifier.size(14.dp))
        Text(stringResource(R.string.kinds_c2_not_backed_up, count(n)) + " ›", color = LibraryColors.Warning, fontSize = size, fontWeight = FontWeight.SemiBold)
    }
}

/** "🔒 2 kinds hidden on all your devices · Private settings ›", never naming them. */
@Composable
private fun HiddenEverywhereLine(n: Int, openPrivate: () -> Unit) {
    val text = buildAnnotatedString {
        append(pluralStringResource(R.plurals.kinds_c2_hidden_everywhere, n, count(n)))
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

@Composable
private fun soundName(sound: KindsOptionC2State.Sound): String = when (sound) {
    KindsOptionC2State.Sound.CALLS -> stringResource(R.string.feed_source_call)
    KindsOptionC2State.Sound.VOICE_RECORDINGS -> stringResource(R.string.feed_source_recording)
    KindsOptionC2State.Sound.VOICE_MESSAGES -> stringResource(R.string.kinds_c2_voice_messages)
}

private fun soundIcon(sound: KindsOptionC2State.Sound): ImageVector = when (sound) {
    KindsOptionC2State.Sound.CALLS -> AudioIcons.Call
    KindsOptionC2State.Sound.VOICE_RECORDINGS -> AudioIcons.Mic
    KindsOptionC2State.Sound.VOICE_MESSAGES -> MediaSource.WHATSAPP.icon
}

private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)
