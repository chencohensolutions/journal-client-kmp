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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
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
 * Settings › Private settings in groups, each a card that folds: Apps and folders, Screenshots (chips of just the app's
 * name), Sound, Sent to you and Nudity (with its slider). A folded group says how much of it is hidden ("1 of 5
 * hidden", "Hidden from 50%"). Apps and folders and Nudity start open. Show private media (this phone's) first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PrivateScreenB(state: PrivateOptionBState, actions: PrivateOptionBActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
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
            SectionTitle(stringResource(R.string.private_b_everywhere))
            Text(
                stringResource(R.string.private_b_intro),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 4.dp),
            )
            Group("apps", stringResource(R.string.private_b_apps), summary(state.apps), startOpen = true) {
                state.apps.forEachIndexed { i, kind ->
                    if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                    KindRow(kind, actions)
                }
            }
            Group("screenshots", sourceName(MediaSource.SCREENSHOTS), summary(state.screenshotApps), startOpen = false) {
                Text(
                    stringResource(R.string.private_b_screenshots_body),
                    color = LibraryColors.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp),
                )
                FlowRow(
                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    state.screenshotApps.forEach { app -> AppChip(app) { actions.set(app, !app.shown) } }
                }
            }
            Group("sound", stringResource(R.string.private_b_sound), summary(state.sounds), startOpen = false) {
                state.sounds.forEachIndexed { i, kind ->
                    if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                    KindRow(kind, actions)
                }
            }
            Group("sent", sourceName(MediaSource.RECEIVED), summary(listOf(state.sentToYou)), startOpen = false) {
                KindRow(state.sentToYou, actions)
                Text(
                    stringResource(R.string.private_b_sent_body),
                    color = LibraryColors.Muted,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                )
            }
            val nudity = state.nudity
            val nuditySummary = if (nudity.hidden) stringResource(R.string.private_b_nudity_hidden, nudity.fromPercent) else stringResource(R.string.private_b_all_shown)
            Group("nudity", stringResource(R.string.hidden_nudity_title), nuditySummary, startOpen = true) {
                NudityRows(nudity, actions)
            }
        }
        TitleBar(stringResource(R.string.settings_hidden_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

/** "1 of 5 hidden", or "All shown". */
@Composable
private fun summary(kinds: List<PrivateOptionBState.Kind>): String {
    val hidden = kinds.count { !it.shown }
    return when {
        hidden == 0 -> stringResource(R.string.private_b_all_shown)
        kinds.size == 1 -> stringResource(R.string.private_b_hidden_all)
        else -> stringResource(R.string.private_b_some_hidden, count(hidden), count(kinds.size))
    }
}

/** A group's card: its title and summary with a chevron, folding [content] away. */
@Composable
private fun Group(key: String, title: String, summary: String, startOpen: Boolean, content: @Composable () -> Unit) {
    var open by rememberSaveable(key) { mutableStateOf(startOpen) }
    Spacer(Modifier.height(12.dp))
    SettingsCard {
        Row(
            Modifier.fillMaxWidth().clickable { open = !open }.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, color = LibraryColors.OnBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Text(summary, color = LibraryColors.Muted, fontSize = 13.sp)
            }
            Icon(LibraryIcons.ChevronDown, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp).rotate(if (open) 180f else 0f))
        }
        if (open) {
            HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
            content()
        }
    }
}

/** A kind's Show switch, with its icon and files; off says it is hidden on all devices. */
@Composable
private fun KindRow(kind: PrivateOptionBState.Kind, actions: PrivateOptionBActions) {
    val body = if (kind.shown) count(kind.files) else stringResource(R.string.private_b_hidden, count(kind.files))
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

/** An app's chip, just its name ("Bumble", "No app name") and its screenshots: filled with a ✓ while shown. */
@Composable
private fun AppChip(app: PrivateOptionBState.Kind, onClick: () -> Unit) {
    val on = app.shown
    val content = if (on) LibraryColors.OnInverse else LibraryColors.OnBackground
    Row(
        Modifier
            .height(34.dp)
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
        if (on) Icon(LibraryIcons.Check, null, tint = content, modifier = Modifier.size(15.dp))
        Text(app.name.ifEmpty { stringResource(R.string.private_b_no_app) }, color = content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(count(app.files), color = if (on) LibraryColors.OnInverseMuted else LibraryColors.Muted, fontSize = 13.sp)
    }
}

/** Nudity as today: the Show switch, and while off the score it is hidden from. */
@Composable
private fun NudityRows(nudity: HiddenCollections.Nudity, actions: PrivateOptionBActions) {
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
private fun nameOf(kind: PrivateOptionBState.Kind): String = when (kind.type) {
    PrivateOptionBState.Type.FOLDER -> sourceName(MediaSource.folder(kind.name))
    PrivateOptionBState.Type.SCREENSHOTS -> sourceName(MediaSource.SCREENSHOTS)
    PrivateOptionBState.Type.SENT_TO_YOU -> sourceName(MediaSource.RECEIVED)
    PrivateOptionBState.Type.CALLS -> stringResource(R.string.feed_source_call)
    PrivateOptionBState.Type.VOICE_RECORDINGS -> stringResource(R.string.feed_source_recording)
    PrivateOptionBState.Type.VOICE_MESSAGES -> stringResource(R.string.private_b_voice_messages)
}

private fun iconOf(kind: PrivateOptionBState.Kind): ImageVector = when (kind.type) {
    PrivateOptionBState.Type.FOLDER -> MediaSource.folder(kind.name).icon
    PrivateOptionBState.Type.SCREENSHOTS -> MediaSource.SCREENSHOTS.icon
    PrivateOptionBState.Type.SENT_TO_YOU -> MediaSource.RECEIVED.icon
    PrivateOptionBState.Type.CALLS -> AudioIcons.Call
    PrivateOptionBState.Type.VOICE_RECORDINGS -> AudioIcons.Mic
    PrivateOptionBState.Type.VOICE_MESSAGES -> MediaSource.WHATSAPP.icon
}

private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)
