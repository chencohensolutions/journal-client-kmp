package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaSource
import java.text.NumberFormat

/**
 * Settings › Show in the app as option b (one "Imported" pill, its kinds under it while on), with what Private settings
 * hides on every device said in place: a kind hidden whole is a dimmed pill with a lock that opens Private settings,
 * one hidden in part has a lock after its count, and a line under the pills names both, with "Private settings ›".
 */
@Composable
internal fun SourcesScreenB2(state: SourcesOptionB2State, actions: SourcesOptionB2Actions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_shown_title))
            Pills(state, actions)
            ImportedKinds(state, actions)
            HiddenEverywhereLine(state, actions.openPrivate)
            Column(Modifier.padding(top = 20.dp)) {
                SettingsCard {
                    SwitchRow(stringResource(R.string.settings_say_hidden), stringResource(R.string.settings_say_hidden_body), true) {}
                    HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                    SwitchRow(stringResource(R.string.settings_filter_bar), stringResource(R.string.settings_filter_bar_body), true) {}
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
 * The Moments filter bar with one "Imported" chip; what Private settings hides whole never has a chip and isn't in
 * "+N". Under it the "Showing" sheet: the same pills, kinds and line as Settings.
 */
@Composable
internal fun SourcesBarB2(state: SourcesOptionB2State, actions: SourcesOptionB2Actions) {
    Column(Modifier.fillMaxSize().background(LibraryColors.Background).padding(top = 48.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hidden = state.hiddenCount
            BarChip(if (hidden > 0) "+$hidden" else null, if (hidden > 0) null else LibraryIcons.Plus, on = false) {}
            state.fromPhones.filter { it.shown && !it.allHiddenEverywhere }.sortedByDescending { it.count }.forEach { s ->
                BarChip(sourceName(s.source), s.source.icon, count(s.count), on = true) { actions.toggleFromPhones(s.source) }
            }
            if (state.importedShown) {
                BarChip(stringResource(R.string.sources_b2_imported), BoxIcon, count(state.importedCount), on = true, keepIcon = true, onClick = actions.toggleImported)
            }
        }
        Column(
            Modifier
                .padding(top = 28.dp)
                .fillMaxWidth()
                .background(LibraryColors.Raised, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp))
                .padding(top = 24.dp, bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.explore_sheet_title),
                color = LibraryColors.OnBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
            )
            Text(
                stringResource(R.string.explore_sheet_body),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp),
            )
            Pills(state, actions)
            ImportedKinds(state, actions)
            HiddenEverywhereLine(state, actions.openPrivate)
        }
    }
}

/** The phone's sources, the most photos first, then "Imported" with its box icon and the count of what it shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pills(state: SourcesOptionB2State, actions: SourcesOptionB2Actions) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.fromPhones.sortedByDescending { it.count }.forEach { s ->
            SourcePill(s, small = false, onToggle = { actions.toggleFromPhones(s.source) }, openPrivate = actions.openPrivate)
        }
        BarChip(
            stringResource(R.string.sources_b2_imported),
            BoxIcon,
            count(state.importedKinds.filter { !it.allHiddenEverywhere }.sumOf { it.count }),
            on = state.importedShown,
            keepIcon = true,
            onClick = actions.toggleImported,
        )
    }
}

/** While Imported is on: "Of what was imported", its kinds as smaller pills in a tinted box. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportedKinds(state: SourcesOptionB2State, actions: SourcesOptionB2Actions) {
    if (!state.importedShown) return
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .background(LibraryColors.Raised, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(BoxIcon, null, tint = LibraryColors.Muted, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.sources_b2_of_imported), color = LibraryColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.importedKinds.sortedByDescending { it.count }.forEach { s ->
                SourcePill(s, small = true, onToggle = { actions.toggleImportedKind(s.source) }, openPrivate = actions.openPrivate)
            }
        }
    }
}

/**
 * A source's pill: locked and dimmed when Private settings hides it whole (tapping opens Private settings), with a
 * lock after its count when it hides part of it, else the plain pill.
 */
@Composable
private fun SourcePill(s: SourcesOptionB2State.Source, small: Boolean, onToggle: () -> Unit, openPrivate: () -> Unit) {
    when {
        s.allHiddenEverywhere -> Box(Modifier.alpha(0.5f)) {
            BarChip(sourceName(s.source), LibraryIcons.Lock, null, on = false, small = small, onClick = openPrivate)
        }
        else -> BarChip(sourceName(s.source), s.source.icon, count(s.count), on = s.shown, small = small, locked = s.hiddenEverywhere > 0, onClick = onToggle)
    }
}

/**
 * "🔒 On all your devices, Private settings hides WhatsApp and 1,640 more Screenshots." and "Private settings ›":
 * nothing when it hides none of these sources.
 */
@Composable
private fun HiddenEverywhereLine(state: SourcesOptionB2State, openPrivate: () -> Unit) {
    val all = state.fromPhones + state.importedKinds
    val whole = all.filter { it.allHiddenEverywhere }.map { it.source }.distinct()
    val part = all.filter { !it.allHiddenEverywhere && it.hiddenEverywhere > 0 }
        .groupBy { it.source }.mapValues { (_, list) -> list.sumOf { it.hiddenEverywhere } }
    if (whole.isEmpty() && part.isEmpty()) return
    val names = whole.map { sourceName(it) } +
        part.map { (source, n) -> stringResource(R.string.sources_b2_more, stringResource(R.string.explore_source_count, n, sourceName(source))) }
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
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.sources_b2_everywhere, names.joinToString(", ")), color = LibraryColors.Muted, fontSize = 13.sp)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.sources_b2_private), color = LibraryColors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Icon(LibraryIcons.ChevronRight, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)

/**
 * The filter bar's chip (FeedChrome's FilterBarChip); with [keepIcon] its icon stays when on; [small] for the kinds;
 * [locked] adds a lock after the count.
 */
@Composable
private fun BarChip(
    title: String?,
    icon: ImageVector?,
    count: String? = null,
    on: Boolean,
    keepIcon: Boolean = false,
    small: Boolean = false,
    locked: Boolean = false,
    onClick: () -> Unit,
) {
    val content = if (on) LibraryColors.OnInverse else LibraryColors.Muted
    val quiet = if (on) LibraryColors.OnInverseMuted else LibraryColors.Muted.copy(alpha = 0.7f)
    Row(
        Modifier
            .height(if (small) 28.dp else 34.dp)
            .then(
                if (on) {
                    Modifier.background(LibraryColors.Inverse, RoundedCornerShape(50))
                } else {
                    Modifier.border(1.dp, LibraryColors.Muted.copy(alpha = 0.45f), RoundedCornerShape(50))
                },
            )
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Button
                selected = on
            }
            .padding(horizontal = if (small) 10.dp else 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val size = if (small) 12.sp else 13.sp
        if (icon != null) Icon(if (on && !keepIcon) LibraryIcons.Check else icon, null, tint = content, modifier = Modifier.size(if (small) 13.dp else 15.dp))
        if (title != null) Text(title, color = content, fontSize = size, fontWeight = FontWeight.SemiBold)
        if (count != null) Text(count, color = quiet, fontSize = size)
        if (locked) Icon(LibraryIcons.Lock, null, tint = quiet, modifier = Modifier.size(if (small) 11.dp else 12.dp))
    }
}

/** An archive box: what was imported. */
private val BoxIcon: ImageVector =
    ImageVector.Builder("Box", 24.dp, 24.dp, 24f, 24f).apply {
        listOf("M3 4h18v4H3z", "M5 8v11a1 1 0 0 0 1 1h12a1 1 0 0 0 1-1V8", "M10 12h4").forEach { data ->
            addPath(
                pathData = PathParser().parsePathString(data).toNodes(),
                stroke = SolidColor(Color.Black),
                strokeLineWidth = 2f,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
    }.build()
