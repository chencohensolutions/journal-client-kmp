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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.HiddenCollections
import java.text.NumberFormat

/**
 * Settings › Private settings as where the user chooses what shows on all devices, opened without the lock: Show
 * private media (this phone's), then one card with a Show switch per kind, the most files first (Screenshots as chips
 * of just the app's name), and Nudity with its slider. Kinds hidden before this visit aren't named: the card ends with
 * "🔒 2 hidden · Unlock to see and show them"; once unlocked they are in the list with their switches off.
 */
@Composable
internal fun PrivateScreenC2(state: PrivateOptionC2State, actions: PrivateOptionC2Actions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val named = { kind: PrivateOptionC2State.Kind -> state.unlocked || !kind.hiddenBefore }
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_private_title))
            SettingsCard {
                SwitchRow(stringResource(R.string.settings_private_switch), stringResource(R.string.settings_private_switch_body), state.showPrivate, actions.setShowPrivate)
            }
            SectionTitle(stringResource(R.string.private_c2_everywhere))
            Text(
                stringResource(R.string.private_c2_intro),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 10.dp),
            )
            SettingsCard {
                val kinds = state.kinds.filter(named)
                kinds.forEachIndexed { i, kind ->
                    if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                    if (kind.type == PrivateOptionC2State.Type.SCREENSHOTS) {
                        ScreenshotsRow(kind, state.screenshotApps.filter(named), actions)
                    } else {
                        KindRow(kind, actions)
                    }
                }
                if (!state.unlocked && state.hiddenBefore > 0) {
                    HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                    LockedRow(state.hiddenBefore, actions.unlock)
                }
            }
            SectionTitle(stringResource(R.string.hidden_nudity_title))
            SettingsCard { NudityRows(state.nudity, actions) }
        }
        TitleBar(stringResource(R.string.settings_hidden_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

/** "🔒 2 hidden / Unlock to see and show them", never naming them. */
@Composable
private fun LockedRow(n: Int, unlock: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = unlock)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.Lock, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 12.dp)) {
            Text(pluralStringResource(R.plurals.private_c2_locked, n, count(n)), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.private_c2_unlock), color = LibraryColors.Muted, fontSize = 13.sp)
        }
        Icon(LibraryIcons.ChevronRight, null, tint = LibraryColors.Muted, modifier = Modifier.size(18.dp))
    }
}

/** A kind's Show switch, with its icon and files; off says it is hidden on all devices. */
@Composable
private fun KindRow(kind: PrivateOptionC2State.Kind, actions: PrivateOptionC2Actions) {
    val body = if (kind.shown) count(kind.files) else stringResource(R.string.private_c2_hidden, count(kind.files))
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = kind.shown, onClick = { actions.set(kind, !kind.shown) }, role = Role.Switch)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(iconOf(kind), null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 14.dp, end = 12.dp)) {
            Text(nameOf(kind), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = if (kind.shown) LibraryColors.Muted else LibraryColors.Warning, fontSize = 13.sp)
        }
        Switch(
            checked = kind.shown,
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

/** Screenshots: its files, then a chip per app it was taken in, just the app's name. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScreenshotsRow(kind: PrivateOptionC2State.Kind, apps: List<PrivateOptionC2State.Kind>, actions: PrivateOptionC2Actions) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(iconOf(kind), null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(nameOf(kind), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                // The named apps' screenshots only, so the count doesn't hint at hidden ones.
                Text(stringResource(R.string.private_c2_screenshots_body, count(apps.sumOf { it.files })), color = LibraryColors.Muted, fontSize = 13.sp)
            }
        }
        FlowRow(
            Modifier.padding(start = 34.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            apps.forEach { app -> AppChip(app) { actions.set(app, !app.shown) } }
        }
    }
}

/** An app's chip, just its name ("Bumble", "No app name") and its screenshots: filled with a ✓ while shown. */
@Composable
private fun AppChip(app: PrivateOptionC2State.Kind, onClick: () -> Unit) {
    val on = app.shown
    val content = if (on) LibraryColors.OnInverse else LibraryColors.OnBackground
    Row(
        Modifier
            .height(32.dp)
            .then(
                if (on) Modifier.background(LibraryColors.Inverse, RoundedCornerShape(50))
                else Modifier.border(1.dp, LibraryColors.Muted.copy(alpha = 0.45f), RoundedCornerShape(50)),
            )
            .clip(RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Checkbox
                selected = on
            }
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (on) Icon(LibraryIcons.Check, null, tint = content, modifier = Modifier.size(14.dp))
        Text(app.name.ifEmpty { stringResource(R.string.private_c2_no_app) }, color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(count(app.files), color = if (on) LibraryColors.OnInverseMuted else LibraryColors.Muted, fontSize = 12.sp)
    }
}

/** Nudity as today: the Show switch, and while off the score it is hidden from. */
@Composable
private fun NudityRows(nudity: HiddenCollections.Nudity, actions: PrivateOptionC2Actions) {
    val counts = buildString {
        append(pluralStringResource(R.plurals.hidden_files, nudity.files, count(nudity.files)))
        if (nudity.notScanned > 0) {
            append(" · ")
            append(pluralStringResource(R.plurals.hidden_nudity_not_scanned, nudity.notScanned, count(nudity.notScanned)))
        }
    }
    SwitchRow(stringResource(R.string.hidden_nudity), counts, !nudity.hidden) { actions.setNudityHidden(!it) }
    if (!nudity.hidden) return
    HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
    var dragging by remember(nudity.fromPercent) { mutableFloatStateOf(nudity.fromPercent.toFloat()) }
    val range = HiddenCollections.Nudity.RANGE
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.hidden_nudity_from), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${dragging.toInt()}%", color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { actions.setNudityFromPercent(dragging.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / 5 - 1,
            colors = SliderDefaults.colors(
                thumbColor = LibraryColors.OnBackground,
                activeTrackColor = LibraryColors.OnBackground,
                inactiveTrackColor = LibraryColors.Muted.copy(alpha = 0.3f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        Text(stringResource(R.string.hidden_nudity_from_body), color = LibraryColors.Muted, fontSize = 13.sp)
    }
}

@Composable
private fun nameOf(kind: PrivateOptionC2State.Kind): String = when (kind.type) {
    PrivateOptionC2State.Type.FOLDER -> sourceName(MediaSource.folder(kind.name))
    PrivateOptionC2State.Type.SCREENSHOTS -> sourceName(MediaSource.SCREENSHOTS)
    PrivateOptionC2State.Type.SENT_TO_YOU -> sourceName(MediaSource.RECEIVED)
    PrivateOptionC2State.Type.CALLS -> stringResource(R.string.feed_source_call)
    PrivateOptionC2State.Type.VOICE_RECORDINGS -> stringResource(R.string.feed_source_recording)
    PrivateOptionC2State.Type.VOICE_MESSAGES -> stringResource(R.string.private_c2_voice_messages)
}

private fun iconOf(kind: PrivateOptionC2State.Kind): ImageVector = when (kind.type) {
    PrivateOptionC2State.Type.FOLDER -> MediaSource.folder(kind.name).icon
    PrivateOptionC2State.Type.SCREENSHOTS -> MediaSource.SCREENSHOTS.icon
    PrivateOptionC2State.Type.SENT_TO_YOU -> MediaSource.RECEIVED.icon
    PrivateOptionC2State.Type.CALLS -> AudioIcons.Call
    PrivateOptionC2State.Type.VOICE_RECORDINGS -> AudioIcons.Mic
    PrivateOptionC2State.Type.VOICE_MESSAGES -> MediaSource.WHATSAPP.icon
}

private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)
