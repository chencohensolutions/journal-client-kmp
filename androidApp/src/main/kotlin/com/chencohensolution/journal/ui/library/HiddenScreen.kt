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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.HiddenCollections
import com.chencohensolution.journal.server.HiddenCollectionsController
import com.chencohensolution.journal.server.HiddenCollectionsState
import com.chencohensolution.journal.server.PrivateKinds
import com.chencohensolution.journal.server.ServerLibrary
import java.text.NumberFormat

/**
 * Settings › Private settings, behind the phone's lock (option c2 of the UI lab, Oct 27, 2026): "Show private media"
 * (this phone's), then what shows on all your devices, one list by kind with a Show switch each (on = shown), the hidden
 * ones included: apps and folders, Screenshots as chips of just the app's name, the sound kinds and Sent to you, the
 * most files first; then Nudity with its slider. The server does the hiding (see [HiddenCollections]); each switch is
 * saved there at once and nothing is kept on this phone. The logic is [HiddenCollectionsController], the list
 * [PrivateKinds]. See docs/screens/settings.md.
 */
@Composable
internal fun HiddenScreen(library: ServerLibrary, showPrivate: Boolean, setShowPrivate: (Boolean) -> Unit, back: () -> Unit) {
    val title = stringResource(R.string.settings_hidden_title)
    PhoneLocked(title, back) {
        val controller = remember(library) { HiddenCollectionsController(library) }
        DisposableEffect(controller) { onDispose { controller.close() } }
        LaunchedEffect(controller) { controller.load() }
        val state by controller.state.collectAsStateWithLifecycle()
        val actions = remember(controller) {
            HiddenActions(
                retry = controller::retry,
                show = controller::show,
                setNudityHidden = controller::setNudityHidden,
                setNudityFromPercent = controller::setNudityFromPercent,
                setShowPrivate = setShowPrivate,
            )
        }
        HiddenContent(title, state, actions, back, showPrivate)
    }
}

/** What Private settings can ask for; see [HiddenCollectionsController]. */
internal class HiddenActions(
    val retry: () -> Unit = {},
    /** A kind, or a screenshot app, shown or hidden on every device. */
    val show: (PrivateKinds.Kind, Boolean) -> Unit = { _, _ -> },
    val setNudityHidden: (Boolean) -> Unit = {},
    val setNudityFromPercent: (Int) -> Unit = {},
    /** "Show private media", kept on this phone (not on the server). */
    val setShowPrivate: (Boolean) -> Unit = {},
)

/** Draws [state]; once unlocked. Switches are ignored while a change is on its way to the server. */
@Composable
internal fun HiddenContent(title: String, state: HiddenCollectionsState, actions: HiddenActions, back: () -> Unit, showPrivate: Boolean = false) {
    val insets = barsRoom()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            // This phone's own choice (moved here from Settings, Oct 25, 2026); the rest is the server's, for every device.
            SectionTitle(stringResource(R.string.settings_private_title))
            SettingsCard {
                SwitchRow(stringResource(R.string.settings_private_switch), stringResource(R.string.settings_private_switch_body), showPrivate, actions.setShowPrivate)
            }
            SectionTitle(stringResource(R.string.private_everywhere))
            Text(
                stringResource(R.string.private_intro),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 10.dp),
            )
            when (state) {
                is HiddenCollectionsState.Ready -> {
                    if (state.notChanged) {
                        Text(
                            stringResource(R.string.hidden_not_changed),
                            color = LibraryColors.Warning,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 10.dp),
                        )
                    }
                    val show: (PrivateKinds.Kind, Boolean) -> Unit = { kind, shown -> if (!state.saving) actions.show(kind, shown) }
                    SettingsCard {
                        state.kinds.kinds.forEachIndexed { i, kind ->
                            if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                            if (kind.type == PrivateKinds.Type.SCREENSHOTS) {
                                ScreenshotsRow(kind, state.kinds.screenshotApps, show)
                            } else {
                                KindRow(kind, show)
                            }
                        }
                    }
                    SectionTitle(stringResource(R.string.hidden_nudity_title))
                    SettingsCard { NudityRows(state.collections.nudity, state.saving, actions) }
                }
                HiddenCollectionsState.Unreachable -> {
                    Column(Modifier.padding(horizontal = 32.dp, vertical = 16.dp)) {
                        Text(stringResource(R.string.hidden_unreachable), color = LibraryColors.OnBackground, fontSize = 16.sp)
                        TextButton(onClick = actions.retry) { Text(stringResource(R.string.hidden_retry), color = LibraryColors.OnBackground) }
                    }
                }
                HiddenCollectionsState.Loading -> Text(
                    stringResource(R.string.hidden_loading),
                    color = LibraryColors.Muted,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 32.dp, vertical = 16.dp).align(Alignment.CenterHorizontally),
                )
            }
        }
        TitleBar(title, back, Modifier.align(Alignment.TopCenter))
    }
}

/** A kind's Show switch, with its icon and files; off says it is hidden on all devices. */
@Composable
private fun KindRow(kind: PrivateKinds.Kind, show: (PrivateKinds.Kind, Boolean) -> Unit) {
    val body = if (kind.shown) count(kind.files) else stringResource(R.string.private_hidden, count(kind.files))
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = kind.shown, onClick = { show(kind, !kind.shown) }, role = Role.Switch)
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
private fun ScreenshotsRow(kind: PrivateKinds.Kind, apps: List<PrivateKinds.Kind>, show: (PrivateKinds.Kind, Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(iconOf(kind), null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 14.dp)) {
                Text(nameOf(kind), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.private_screenshots_body, count(kind.files)), color = LibraryColors.Muted, fontSize = 13.sp)
            }
        }
        FlowRow(
            Modifier.padding(start = 34.dp, top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            apps.forEach { app -> AppChip(app) { show(app, !app.shown) } }
        }
    }
}

/** An app's chip, just its name ("Bumble", "No app name") and its screenshots: filled with a ✓ while shown. */
@Composable
private fun AppChip(app: PrivateKinds.Kind, onClick: () -> Unit) {
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
        Text(app.name.ifEmpty { stringResource(R.string.private_no_app) }, color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(count(app.files), color = if (on) LibraryColors.OnInverseMuted else LibraryColors.Muted, fontSize = 12.sp)
    }
}

/**
 * Nudity: the Show switch (off by default), with how many files score that much (and how many aren't scanned yet), and
 * while off the score from which they are hidden, saved when let go. Ignored while a change is being saved.
 */
@Composable
private fun NudityRows(nudity: HiddenCollections.Nudity, saving: Boolean, actions: HiddenActions) {
    val counts = buildString {
        append(pluralStringResource(R.plurals.hidden_files, nudity.files, count(nudity.files)))
        if (nudity.notScanned > 0) {
            append(" · ")
            append(pluralStringResource(R.plurals.hidden_nudity_not_scanned, nudity.notScanned, count(nudity.notScanned)))
        }
    }
    // "Show": on means shown, so off (hidden from 50%) is the default.
    SwitchRow(stringResource(R.string.hidden_nudity), counts, !nudity.hidden) { if (!saving) actions.setNudityHidden(!it) }
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
            onValueChangeFinished = { if (!saving) actions.setNudityFromPercent(dragging.toInt()) else dragging = nudity.fromPercent.toFloat() },
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
private fun nameOf(kind: PrivateKinds.Kind): String = when (kind.type) {
    PrivateKinds.Type.FOLDER -> sourceName(MediaSource.folder(kind.name))
    PrivateKinds.Type.SCREENSHOTS -> sourceName(MediaSource.SCREENSHOTS)
    PrivateKinds.Type.SENT_TO_YOU -> sourceName(MediaSource.RECEIVED)
    PrivateKinds.Type.CALLS -> soundName(AudioKind.CALL)
    PrivateKinds.Type.VOICE_RECORDINGS -> soundName(AudioKind.RECORDING)
    PrivateKinds.Type.VOICE_MESSAGES -> soundName(AudioKind.VOICE_MESSAGE)
}

private fun iconOf(kind: PrivateKinds.Kind): ImageVector = when (kind.type) {
    PrivateKinds.Type.FOLDER -> MediaSource.folder(kind.name).icon
    PrivateKinds.Type.SCREENSHOTS -> MediaSource.SCREENSHOTS.icon
    PrivateKinds.Type.SENT_TO_YOU -> MediaSource.RECEIVED.icon
    PrivateKinds.Type.CALLS -> soundIcon(AudioKind.CALL)
    PrivateKinds.Type.VOICE_RECORDINGS -> soundIcon(AudioKind.RECORDING)
    PrivateKinds.Type.VOICE_MESSAGES -> soundIcon(AudioKind.VOICE_MESSAGE)
}

private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)
