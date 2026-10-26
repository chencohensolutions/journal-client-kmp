package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaSource
import java.text.NumberFormat

/**
 * Settings › Show in the app as a table: a row per kind with its icon and name, then a cell for "Phones" and one for
 * "Imported", each a box with its count, filled when shown; a dash where a kind has no files.
 */
@Composable
internal fun SourcesScreenC(state: SourcesOptionCState, actions: SourcesOptionCActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_shown_title))
            Table(state, actions)
            Text(
                stringResource(R.string.sources_c_note),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp),
            )
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
 * The Moments filter bar: a chip per shown cell, the most photos first, marked by where it came from (a phone or a
 * box) in place of the ✓. Under it the "Showing" sheet with the same table.
 */
@Composable
internal fun SourcesBarC(state: SourcesOptionCState, actions: SourcesOptionCActions) {
    Column(Modifier.fillMaxSize().background(LibraryColors.Background).padding(top = 48.dp)) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val hidden = state.hiddenCount
            BarChip(if (hidden > 0) "+$hidden" else null, if (hidden > 0) null else LibraryIcons.Plus, on = false) {}
            val shown = state.rows.flatMap { row ->
                listOfNotNull(
                    row.fromPhones?.takeIf { it.shown }?.let { Triple(row.source, SourcesOptionCColumn.FROM_PHONES, it.count) },
                    row.imported?.takeIf { it.shown }?.let { Triple(row.source, SourcesOptionCColumn.IMPORTED, it.count) },
                )
            }.sortedByDescending { it.third }
            shown.forEach { (source, column, n) ->
                val icon = if (column == SourcesOptionCColumn.IMPORTED) BoxIcon else LibraryIcons.Phone
                BarChip(sourceName(source), icon, count(n), on = true) { actions.toggle(source, column) }
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
            Table(state, actions, raised = false)
        }
    }
}

/** The header ("Phones", "Imported") and a row per kind. */
@Composable
private fun Table(state: SourcesOptionCState, actions: SourcesOptionCActions, raised: Boolean = true) {
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(if (raised) LibraryColors.Raised else LibraryColors.Background, RoundedCornerShape(14.dp))
            .padding(vertical = 6.dp),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f))
            Header(LibraryIcons.Phone, stringResource(R.string.sources_c_phones))
            Header(BoxIcon, stringResource(R.string.sources_c_imported))
        }
        state.rows.forEachIndexed { i, row ->
            if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.15f))
            Row(Modifier.padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(row.source.icon, null, tint = LibraryColors.Muted, modifier = Modifier.size(18.dp))
                Text(
                    sourceName(row.source),
                    color = LibraryColors.OnBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                Cell(row.fromPhones) { actions.toggle(row.source, SourcesOptionCColumn.FROM_PHONES) }
                Cell(row.imported) { actions.toggle(row.source, SourcesOptionCColumn.IMPORTED) }
            }
        }
    }
}

@Composable
private fun Header(icon: ImageVector, title: String) {
    Row(Modifier.width(CELL_WIDTH), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = LibraryColors.Muted, modifier = Modifier.size(13.dp))
        Text(title, color = LibraryColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 4.dp))
    }
}

/** A box with its count, filled with a ✓ when shown; a dash where there are no files. */
@Composable
private fun Cell(cell: SourcesOptionCState.Cell?, onClick: () -> Unit) {
    Box(Modifier.width(CELL_WIDTH).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
        if (cell == null) {
            Text("—", color = LibraryColors.Muted.copy(alpha = 0.5f), fontSize = 14.sp)
            return@Box
        }
        val content = if (cell.shown) LibraryColors.OnInverse else LibraryColors.Muted
        Row(
            Modifier
                .fillMaxWidth()
                .height(34.dp)
                .then(
                    if (cell.shown) {
                        Modifier.background(LibraryColors.Inverse, RoundedCornerShape(10.dp))
                    } else {
                        Modifier.border(1.dp, LibraryColors.Muted.copy(alpha = 0.45f), RoundedCornerShape(10.dp))
                    },
                )
                .clickable(onClick = onClick)
                .semantics {
                    role = Role.Checkbox
                    selected = cell.shown
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (cell.shown) Icon(LibraryIcons.Check, null, tint = content, modifier = Modifier.size(13.dp).padding(end = 2.dp))
            Text(count(cell.count), color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private val CELL_WIDTH = 92.dp

private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)

/** The filter bar's chip (FeedChrome's FilterBarChip), keeping its icon (where it came from) when on. */
@Composable
private fun BarChip(title: String?, icon: ImageVector?, count: String? = null, on: Boolean, onClick: () -> Unit) {
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
        if (icon != null) Icon(icon, null, tint = content, modifier = Modifier.size(15.dp))
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
