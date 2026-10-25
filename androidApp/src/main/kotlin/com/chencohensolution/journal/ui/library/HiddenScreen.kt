package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.draw.rotate
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chencohensolution.journal.R
import com.chencohensolution.journal.server.HiddenCollections
import com.chencohensolution.journal.server.HiddenCollectionsController
import com.chencohensolution.journal.server.HiddenCollectionsState
import com.chencohensolution.journal.server.ServerLibrary
import java.text.NumberFormat

/**
 * Settings → Private media → Private settings: "Show private media" (this phone's), then what is hidden on every device: all of WhatsApp, screenshots by app, nudity and sound recordings, hidden on every device by the
 * server (see [HiddenCollections]). Behind the phone's lock. Each switch is saved on the server at once; nothing is kept
 * on this phone. The logic is [HiddenCollectionsController]. See docs/screens/settings.md.
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
                set = controller::set,
                setQuery = controller::setQuery,
                setNudityHidden = controller::setNudityHidden,
                setNudityFromPercent = controller::setNudityFromPercent,
                setShowPrivate = setShowPrivate,
            )
        }
        HiddenContent(title, state, actions, back, showPrivate)
    }
}

/** What the Hidden collections screen can ask for; see [HiddenCollectionsController]. */
internal class HiddenActions(
    val retry: () -> Unit,
    val set: (HiddenCollections.Collection, Boolean) -> Unit,
    val setQuery: (String) -> Unit,
    val setNudityHidden: (Boolean) -> Unit = {},
    val setNudityFromPercent: (Int) -> Unit = {},
    /** "Show private media", kept on this phone (not on the server). */
    val setShowPrivate: (Boolean) -> Unit = {},
)

/** Draws [state]; once unlocked. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HiddenContent(title: String, state: HiddenCollectionsState, actions: HiddenActions, back: () -> Unit, showPrivate: Boolean = false) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            // This phone's own choice (moved here from Settings, Oct 25, 2026); the rest is the server's, for every device.
            SectionTitle(stringResource(R.string.settings_private_title))
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.settings_private_switch),
                    body = stringResource(R.string.settings_private_switch_body),
                    checked = showPrivate,
                    onChange = actions.setShowPrivate,
                )
            }
            Text(
                stringResource(R.string.hidden_intro),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
            )
            when (state) {
                is HiddenCollectionsState.Ready -> {
                    if (state.notChanged) {
                        Text(
                            stringResource(R.string.hidden_not_changed),
                            color = LibraryColors.Warning,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                    }
                    val shown = state.collections
                    val set = actions.set
                    SectionTitle(stringResource(R.string.hidden_nudity_title))
                    SettingsCard { NudityRows(shown.nudity, state.saving, actions) }
                    // Sound recordings, on a server that has them: how many of each, and whether to show them (off at
                    // first: on means shown here, unlike the collections below).
                    val recordings = shown.recordings
                    val calls = shown.calls
                    val voiceMessages = shown.voiceMessages
                    if (recordings != null && calls != null && voiceMessages != null) {
                        SectionTitle(stringResource(R.string.hidden_audio_title))
                        SettingsCard {
                            ShowRow(stringResource(R.string.hidden_audio_recordings), recordings, R.plurals.hidden_audio_recordings_count, state.saving, set)
                            HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                            ShowRow(stringResource(R.string.hidden_audio_calls), calls, R.plurals.hidden_audio_calls_count, state.saving, set)
                            HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                            ShowRow(stringResource(R.string.hidden_audio_voice), voiceMessages, R.plurals.hidden_audio_voice_count, state.saving, set)
                        }
                        Text(
                            stringResource(R.string.hidden_audio_body),
                            color = LibraryColors.Muted,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp),
                        )
                    }
                    SectionTitle(stringResource(R.string.hidden_whatsapp_title))
                    SettingsCard {
                        CollectionRow(stringResource(R.string.hidden_whatsapp), shown.whatsapp, R.plurals.hidden_files, state.saving, set)
                    }
                    SectionTitle(stringResource(R.string.hidden_screenshots_title))
                    SettingsCard {
                        CollectionRow(stringResource(R.string.hidden_without_app), shown.screenshotsWithoutApp, R.plurals.hidden_screenshots, state.saving, set)
                        HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                        if (shown.screenshotApps.isEmpty()) {
                            Text(stringResource(R.string.hidden_no_apps), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
                        } else {
                            // Folded at first, showing only the apps whose screenshots are shown; open, every app
                            // (filtered by Find an app) to show or hide.
                            var appsOpen by rememberSaveable { mutableStateOf(false) }
                            val appsShown = shown.screenshotApps.filter { !it.hidden }
                            val format = NumberFormat.getIntegerInstance()
                            Row(
                                Modifier.fillMaxWidth().clickable { appsOpen = !appsOpen }.padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Column(Modifier.weight(1f)) {
                                    Text(stringResource(R.string.hidden_apps_title), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        stringResource(R.string.hidden_apps_count, format.format(appsShown.size), format.format(shown.screenshotApps.size)),
                                        color = LibraryColors.Muted,
                                        fontSize = 13.sp,
                                    )
                                }
                                Icon(
                                    LibraryIcons.ChevronDown,
                                    contentDescription = stringResource(if (appsOpen) R.string.hidden_apps_fold else R.string.hidden_apps_open),
                                    tint = LibraryColors.Muted,
                                    modifier = Modifier.size(20.dp).rotate(if (appsOpen) 180f else 0f),
                                )
                            }
                            if (appsOpen) {
                                OutlinedTextField(
                                    value = state.query,
                                    onValueChange = actions.setQuery,
                                    placeholder = { Text(stringResource(R.string.hidden_find_app)) },
                                    leadingIcon = { Icon(LibraryIcons.Search, contentDescription = null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp)) },
                                    singleLine = true,
                                    textStyle = TextStyle(color = LibraryColors.OnBackground, fontSize = 16.sp),
                                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                                    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                )
                                Text(
                                    stringResource(R.string.hidden_apps_pills),
                                    color = LibraryColors.Muted,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                                )
                            }
                            val pills = if (appsOpen) state.shownApps else appsShown
                            if (pills.isEmpty() && !appsOpen) {
                                Text(
                                    stringResource(R.string.hidden_apps_none_shown),
                                    color = LibraryColors.Muted,
                                    fontSize = 13.sp,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp),
                                )
                            } else {
                                FlowRow(
                                    Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    pills.forEach { app -> AppPill(app) { if (!state.saving) set(app, !app.hidden) } }
                                }
                            }
                        }
                    }
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

/**
 * Nudity: the "Show" switch (off by default), with how many files score that much (and how many aren't scanned yet), and while off, the score
 * from which they are hidden, saved when let go. Disabled while a change is being saved.
 */
@Composable
private fun NudityRows(nudity: HiddenCollections.Nudity, saving: Boolean, actions: HiddenActions) {
    val format = NumberFormat.getIntegerInstance()
    val counts = buildString {
        append(pluralStringResource(R.plurals.hidden_files, nudity.files, format.format(nudity.files)))
        if (nudity.notScanned > 0) {
            append(" · ")
            append(pluralStringResource(R.plurals.hidden_nudity_not_scanned, nudity.notScanned, format.format(nudity.notScanned)))
        }
    }
    SwitchRow(
        title = stringResource(R.string.hidden_nudity),
        body = counts,
        // "Show": on means shown, so off (hidden from 50%) is the default.
        checked = !nudity.hidden,
        onChange = { if (!saving) actions.setNudityHidden(!it) },
    )
    if (!nudity.hidden) return
    HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
    var dragging by remember(nudity.fromPercent) { mutableFloatStateOf(nudity.fromPercent.toFloat()) }
    val range = HiddenCollections.Nudity.RANGE
    Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.hidden_nudity_from),
                color = LibraryColors.OnBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
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

/**
 * A screenshot app as a pill you tap: filled, with a check, while its screenshots are shown; outlined while hidden.
 * The count is how many screenshots it has.
 */
@Composable
private fun AppPill(app: HiddenCollections.Collection, onClick: () -> Unit) {
    val on = !app.hidden
    val content = if (on) LibraryColors.OnInverse else LibraryColors.OnBackground
    Row(
        Modifier
            .height(36.dp)
            .then(
                if (on) {
                    Modifier.background(LibraryColors.Inverse, RoundedCornerShape(50))
                } else {
                    Modifier.border(1.dp, LibraryColors.Muted.copy(alpha = 0.45f), RoundedCornerShape(50))
                },
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
        Text(app.name, color = content, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(
            NumberFormat.getIntegerInstance().format(app.files),
            color = if (on) LibraryColors.OnInverseMuted else LibraryColors.Muted,
            fontSize = 13.sp,
        )
    }
}

/**
 * A collection's "Show" switch, with how many files it has: on means shown, the opposite of [CollectionRow]. For sound
 * recordings, hidden until the user shows them. Disabled while a change is being saved.
 */
@Composable
private fun ShowRow(
    title: String,
    collection: HiddenCollections.Collection,
    countText: Int,
    saving: Boolean,
    set: (HiddenCollections.Collection, Boolean) -> Unit,
) {
    val count = NumberFormat.getIntegerInstance().format(collection.files)
    SwitchRow(
        title = title,
        body = pluralStringResource(countText, collection.files, count),
        checked = !collection.hidden,
        onChange = { if (!saving) set(collection, !it) },
    )
}

/** A collection's switch, with how many files it has; on means hidden. Disabled while a change is being saved. */
@Composable
private fun CollectionRow(
    title: String,
    collection: HiddenCollections.Collection,
    countText: Int,
    saving: Boolean,
    set: (HiddenCollections.Collection, Boolean) -> Unit,
) {
    val count = NumberFormat.getIntegerInstance().format(collection.files)
    SwitchRow(
        title = title,
        body = pluralStringResource(countText, collection.files, count),
        checked = collection.hidden,
        onChange = { if (!saving) set(collection, it) },
    )
}
