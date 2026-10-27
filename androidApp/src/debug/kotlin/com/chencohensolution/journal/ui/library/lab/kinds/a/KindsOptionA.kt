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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
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
 * Settings › Show in the app as a list of kinds: a row per kind with its icon, name, files and a Show switch, and
 * "12 not backed up" under the count where some of this phone's files aren't on the server yet (tapping it opens
 * Backup). The sound kinds are a short second card. A kind Private settings hides on every device isn't listed; one
 * quiet line at the bottom says some are, with "Private settings ›".
 */
@Composable
internal fun KindsScreenA(state: KindsOptionAState, actions: KindsOptionAActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Text(
                stringResource(R.string.kinds_a_intro),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
            )
            SectionTitle(stringResource(R.string.kinds_a_photos))
            SettingsCard {
                state.kinds.forEachIndexed { i, kind ->
                    if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                    KindRow(sourceName(kind.source), kind.source.icon, kind.files, kind.notBackedUp, kind.shown, { actions.toggle(kind.source) }, actions.openBackup)
                }
            }
            if (state.sounds.isNotEmpty()) {
                SectionTitle(stringResource(R.string.kinds_a_sound))
                SettingsCard {
                    state.sounds.forEachIndexed { i, kind ->
                        if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                        KindRow(soundName(kind.sound), soundIcon(kind.sound), kind.files, kind.notBackedUp, kind.shown, { actions.toggleSound(kind.sound) }, actions.openBackup)
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

/** A kind's row: icon, name, files, the "not backed up" note when some aren't, and its Show switch. */
@Composable
private fun KindRow(name: String, icon: ImageVector, files: Int, notBackedUp: Int, shown: Boolean, onToggle: () -> Unit, openBackup: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = shown, onClick = onToggle, role = Role.Switch)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 12.dp)) {
            Text(name, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(count(files), color = LibraryColors.Muted, fontSize = 13.sp)
            if (notBackedUp > 0) {
                Row(
                    Modifier.padding(top = 2.dp).clickable(role = Role.Button, onClick = openBackup),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(LibraryIcons.CloudUp, null, tint = LibraryColors.Warning, modifier = Modifier.size(14.dp))
                    Text(stringResource(R.string.kinds_a_not_backed_up, count(notBackedUp)), color = LibraryColors.Warning, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Switch(
            checked = shown,
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

/** "🔒 Some kinds are hidden on all your devices · Private settings ›", never naming them. */
@Composable
private fun HiddenEverywhereLine(openPrivate: () -> Unit) {
    val text = buildAnnotatedString {
        append(stringResource(R.string.kinds_a_hidden_everywhere))
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
private fun soundName(sound: KindsOptionAState.Sound): String = when (sound) {
    KindsOptionAState.Sound.CALLS -> stringResource(R.string.feed_source_call)
    KindsOptionAState.Sound.VOICE_RECORDINGS -> stringResource(R.string.feed_source_recording)
    KindsOptionAState.Sound.VOICE_MESSAGES -> stringResource(R.string.kinds_a_voice_messages)
}

private fun soundIcon(sound: KindsOptionAState.Sound): ImageVector = when (sound) {
    KindsOptionAState.Sound.CALLS -> AudioIcons.Call
    KindsOptionAState.Sound.VOICE_RECORDINGS -> AudioIcons.Mic
    KindsOptionAState.Sound.VOICE_MESSAGES -> MediaSource.WHATSAPP.icon
}

private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)
