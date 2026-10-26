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
 * Settings › Show in the app in two groups of pills, "From your phones" and "Imported", each with a line on what it
 * holds; imported pills carry a box icon. Then the switches and notes as before.
 */
@Composable
internal fun SourcesScreenA(state: SourcesOptionAState, actions: SourcesOptionAActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            GroupTitle(stringResource(R.string.sources_a_from_phones), stringResource(R.string.sources_a_from_phones_body))
            Pills(state.fromPhones, imported = false, actions.toggleFromPhones)
            GroupTitle(stringResource(R.string.sources_a_imported), stringResource(R.string.sources_a_imported_body))
            Pills(state.imported, imported = true, actions.toggleImported)
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
 * The Moments filter bar: "+N", the shown sources from phones, a divider and "Imported", then the shown imported ones
 * with the box icon. Under it, the "Showing" sheet ("+N" opens it) with the same two groups.
 */
@Composable
internal fun SourcesBarA(state: SourcesOptionAState, actions: SourcesOptionAActions) {
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
            val imported = state.imported.filter { it.shown }.sortedByDescending { it.count }
            if (imported.isNotEmpty()) {
                Box(Modifier.width(1.dp).height(22.dp).background(LibraryColors.Muted.copy(alpha = 0.4f)))
                Text(stringResource(R.string.sources_a_imported), color = LibraryColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
            imported.forEach { s ->
                BarChip(sourceName(s.source), BoxIcon, count(s.count), on = true, keepIcon = true) { actions.toggleImported(s.source) }
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
            Text(stringResource(R.string.explore_sheet_body), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 24.dp))
            GroupTitle(stringResource(R.string.sources_a_from_phones), null)
            Pills(state.fromPhones, imported = false, actions.toggleFromPhones)
            GroupTitle(stringResource(R.string.sources_a_imported), null)
            Pills(state.imported, imported = true, actions.toggleImported)
        }
    }
}

@Composable
private fun GroupTitle(title: String, body: String?) {
    Column(Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 8.dp)) {
        Text(title, color = LibraryColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
        if (body != null) Text(body, color = LibraryColors.Muted.copy(alpha = 0.8f), fontSize = 12.sp)
    }
}

/** A pill per source, the most photos first; imported ones keep the box icon. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pills(sources: List<SourcesOptionAState.Source>, imported: Boolean, onToggle: (MediaSource) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.sortedByDescending { it.count }.forEach { s ->
            BarChip(sourceName(s.source), if (imported) BoxIcon else s.source.icon, count(s.count), on = s.shown, keepIcon = imported) {
                onToggle(s.source)
            }
        }
    }
}

private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)

/** The filter bar's chip (FeedChrome's FilterBarChip); with [keepIcon] its icon stays when on, in place of the ✓. */
@Composable
private fun BarChip(title: String?, icon: ImageVector?, count: String? = null, on: Boolean, keepIcon: Boolean = false, onClick: () -> Unit) {
    val content = if (on) LibraryColors.OnInverse else LibraryColors.Muted
    Row(
        Modifier
            .height(34.dp)
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
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(if (on && !keepIcon) LibraryIcons.Check else icon, null, tint = content, modifier = Modifier.size(15.dp))
        if (title != null) Text(title, color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        if (count != null) Text(count, color = if (on) LibraryColors.OnInverseMuted else LibraryColors.Muted.copy(alpha = 0.7f), fontSize = 13.sp)
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
