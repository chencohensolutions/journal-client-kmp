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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * Settings › Show in the app with one more pill, "Imported", after the phone's folders. While it is on, a tinted box
 * under the pills holds its kinds, "Of what was imported", each shown or hidden.
 */
@Composable
internal fun SourcesScreenB(state: SourcesOptionBState, actions: SourcesOptionBActions) {
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
 * The Moments filter bar with one "Imported" chip (the imported files shown, together); tapping it hides them all.
 * Under it the "Showing" sheet: the same pills and kinds as Settings.
 */
@Composable
internal fun SourcesBarB(state: SourcesOptionBState, actions: SourcesOptionBActions) {
    Column(Modifier.fillMaxSize().background(LibraryColors.Background).padding(top = 48.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hidden = state.hiddenCount
            BarChip(if (hidden > 0) "+$hidden" else null, if (hidden > 0) null else LibraryIcons.Plus, on = false) {}
            state.fromPhones.filter { it.shown }.sortedByDescending { it.count }.forEach { s ->
                BarChip(sourceName(s.source), s.source.icon, count(s.count), on = true) { actions.toggleFromPhones(s.source) }
            }
            if (state.importedShown) {
                BarChip(stringResource(R.string.sources_b_imported), BoxIcon, count(state.importedCount), on = true, keepIcon = true, onClick = actions.toggleImported)
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
        }
    }
}

/** The phone's sources, the most photos first, then "Imported" with its box icon and the count of what it shows. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pills(state: SourcesOptionBState, actions: SourcesOptionBActions) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.fromPhones.sortedByDescending { it.count }.forEach { s ->
            BarChip(sourceName(s.source), s.source.icon, count(s.count), on = s.shown) { actions.toggleFromPhones(s.source) }
        }
        BarChip(
            stringResource(R.string.sources_b_imported),
            BoxIcon,
            count(state.importedKinds.sumOf { it.count }),
            on = state.importedShown,
            keepIcon = true,
            onClick = actions.toggleImported,
        )
    }
}

/** While Imported is on: "Of what was imported", its kinds as smaller pills in a tinted box. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportedKinds(state: SourcesOptionBState, actions: SourcesOptionBActions) {
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
            Text(stringResource(R.string.sources_b_of_imported), color = LibraryColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            state.importedKinds.sortedByDescending { it.count }.forEach { s ->
                BarChip(sourceName(s.source), s.source.icon, count(s.count), on = s.shown, small = true) { actions.toggleImportedKind(s.source) }
            }
        }
    }
}

private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)

/** The filter bar's chip (FeedChrome's FilterBarChip); with [keepIcon] its icon stays when on; [small] for the kinds. */
@Composable
private fun BarChip(
    title: String?,
    icon: ImageVector?,
    count: String? = null,
    on: Boolean,
    keepIcon: Boolean = false,
    small: Boolean = false,
    onClick: () -> Unit,
) {
    val content = if (on) LibraryColors.OnInverse else LibraryColors.Muted
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
        if (count != null) Text(count, color = if (on) LibraryColors.OnInverseMuted else LibraryColors.Muted.copy(alpha = 0.7f), fontSize = size)
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
