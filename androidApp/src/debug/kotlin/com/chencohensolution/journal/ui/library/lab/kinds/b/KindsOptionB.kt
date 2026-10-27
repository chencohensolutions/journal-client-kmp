package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaSource
import java.text.NumberFormat

/**
 * Settings › Show in the app as pills, as today, one per kind with its count, filled with a ✓ when shown; a kind with
 * files not on the server yet carries a small "12 not backed up" badge (tapping it opens Backup). The sound kinds are a
 * second, shorter row of pills. A kind Private settings hides on every device isn't among them; one quiet line at the
 * bottom says some are, with "Private settings ›".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KindsScreenB(state: KindsOptionBState, actions: KindsOptionBActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Text(
                stringResource(R.string.kinds_b_intro),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
            )
            SectionTitle(stringResource(R.string.kinds_b_photos))
            FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                state.kinds.forEach { kind ->
                    KindPill(sourceName(kind.source), kind.source.icon, kind.files, kind.notBackedUp, kind.shown, { actions.toggle(kind.source) }, actions.openBackup)
                }
            }
            if (state.sounds.isNotEmpty()) {
                SectionTitle(stringResource(R.string.kinds_b_sound))
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.sounds.forEach { kind ->
                        KindPill(soundName(kind.sound), soundIcon(kind.sound), kind.files, kind.notBackedUp, kind.shown, { actions.toggleSound(kind.sound) }, actions.openBackup)
                    }
                }
            }
            if (state.someHiddenEverywhere) HiddenEverywhereLine(actions.openPrivate)
            Column(Modifier.padding(top = 20.dp)) {
                SettingsCard {
                    SwitchRow(stringResource(R.string.settings_say_hidden), stringResource(R.string.settings_say_hidden_body), state.sayWhatsHidden, actions.setSayWhatsHidden)
                    HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                    SwitchRow(stringResource(R.string.settings_filter_bar), stringResource(R.string.settings_filter_bar_body), state.showFilterBar, actions.setShowFilterBar)
                }
            }
            listOf(R.string.settings_shown_where, R.string.settings_shown_stories).forEach { note ->
                Text(stringResource(note), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp))
            }
        }
        TitleBar(stringResource(R.string.settings_shown_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

/**
 * A kind's pill (the filter bar's chip, copied): filled with a ✓ when shown, outlined when not; with files not on the
 * server yet, a small badge after the count, "12 not backed up", that opens Backup.
 */
@Composable
private fun KindPill(name: String, icon: ImageVector, files: Int, notBackedUp: Int, shown: Boolean, onToggle: () -> Unit, openBackup: () -> Unit) {
    val content = if (shown) LibraryColors.OnInverse else LibraryColors.Muted
    val quiet = if (shown) LibraryColors.OnInverseMuted else LibraryColors.Muted.copy(alpha = 0.7f)
    Row(
        Modifier
            .height(34.dp)
            .then(
                if (shown) {
                    Modifier.background(LibraryColors.Inverse, RoundedCornerShape(50))
                } else {
                    Modifier.border(1.dp, LibraryColors.Muted.copy(alpha = 0.45f), RoundedCornerShape(50))
                },
            )
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onToggle)
            .semantics {
                role = Role.Checkbox
                selected = shown
            }
            .padding(start = 12.dp, end = if (notBackedUp > 0) 5.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (shown) LibraryIcons.Check else icon, null, tint = content, modifier = Modifier.size(15.dp))
        Text(name, color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(count(files), color = quiet, fontSize = 13.sp)
        if (notBackedUp > 0) {
            Row(
                Modifier
                    .height(24.dp)
                    .clip(RoundedCornerShape(50))
                    .background(LibraryColors.Warning)
                    .clickable(role = Role.Button, onClick = openBackup)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp),
            ) {
                Icon(LibraryIcons.CloudUp, null, tint = LibraryColors.Background, modifier = Modifier.size(12.dp))
                Text(
                    stringResource(R.string.kinds_b_not_backed_up, count(notBackedUp)),
                    color = LibraryColors.Background,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}

/** "🔒 Some kinds are hidden on all your devices · Private settings ›", never naming them. */
@Composable
private fun HiddenEverywhereLine(openPrivate: () -> Unit) {
    val text = buildAnnotatedString {
        append(stringResource(R.string.kinds_b_hidden_everywhere))
        append(" · ")
        withStyle(SpanStyle(color = LibraryColors.OnBackground, fontWeight = FontWeight.Bold)) {
            append(stringResource(R.string.settings_hidden_title))
            append(" ›")
        }
    }
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 16.dp)
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
private fun soundName(sound: KindsOptionBState.Sound): String = when (sound) {
    KindsOptionBState.Sound.CALLS -> stringResource(R.string.feed_source_call)
    KindsOptionBState.Sound.VOICE_RECORDINGS -> stringResource(R.string.feed_source_recording)
    KindsOptionBState.Sound.VOICE_MESSAGES -> stringResource(R.string.kinds_b_voice_messages)
}

private fun soundIcon(sound: KindsOptionBState.Sound): ImageVector = when (sound) {
    KindsOptionBState.Sound.CALLS -> AudioIcons.Call
    KindsOptionBState.Sound.VOICE_RECORDINGS -> AudioIcons.Mic
    KindsOptionBState.Sound.VOICE_MESSAGES -> MediaSource.WHATSAPP.icon
}

private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)
