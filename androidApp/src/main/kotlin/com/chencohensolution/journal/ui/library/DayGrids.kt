package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.backup.BackupProgress
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LibraryFilter
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.stories.StoriesState

// The photo grid of a tag's page: each day under its name, a 2×2 photo first when it has three or more.

/** One row of the grid: a large photo with two small ones beside it, or up to three small ones. */
private sealed interface GridRow {
    val key: String
    val items: List<MediaItem>

    data class Hero(val day: LocalDay, val large: MediaItem, val side: List<MediaItem>) : GridRow {
        override val key get() = "hero-${large.id}"
        override val items get() = listOf(large) + side
    }

    data class Regular(val day: LocalDay, override val items: List<MediaItem>) : GridRow {
        override val key get() = "row-${items.first().id}"
    }
}

/** Days with three or more items open with a 2×2 photo; the rest fill rows of three. */
private fun gridRows(day: DayGroup): List<GridRow> {
    val items = day.items
    if (items.size < 3) return listOf(GridRow.Regular(day.day, items))
    return listOf(GridRow.Hero(day.day, items[0], items.subList(1, 3))) +
        items.drop(3).chunked(3).map { GridRow.Regular(day.day, it) }
}

/**
 * Every photo and video the sources setting shows, edge to edge, newest first. The backup pill still counts every
 * photo not backed up: hiding doesn't change what is backed up. See docs/screens/all-photos.md.
 */
/**
 * What [dayGrids] needs of the list it is in: its large tiles' first images loaded ahead of the scroll, and whether the
 * list has slowed down enough for sharp images ([LocalScrollSettled]).
 */
@Composable
internal fun rememberDayGridsLoading(list: LazyListState, days: List<DayGroup>): State<Boolean> {
    val rows = remember(days) { days.flatMap(::gridRows) }
    PrefetchFirstImages(list, rows, { it.key }) { row -> if (row is GridRow.Hero) listOf(row.large) else emptyList() }
    return rememberScrollSettled(list)
}

/** The ids in each row of [dayGrids], in order. */
internal fun dayGridRows(days: List<DayGroup>): List<Set<String>> =
    days.flatMap(::gridRows).map { row -> row.items.mapTo(mutableSetOf()) { it.id } }

/**
 * The photo grid of [days], each day under its name: a tag's page (All photos had it too, until Oct 24, 2026).
 * [label] names a day; by default "Today", "Tue, Oct 7"….
 */
internal fun LazyListScope.dayGrids(
    days: List<DayGroup>,
    label: @Composable (DayGroup) -> String = { dayLabel(it.day) },
    onOpen: (MediaItem) -> Unit,
) {
    days.forEach { day ->
        val rows = gridRows(day)
        items(rows, key = { it.key }, contentType = { it::class }) { row ->
            Box {
                when (row) {
                    is GridRow.Hero -> HeroRow(row, onOpen)
                    is GridRow.Regular -> RegularRow(row.items, onOpen)
                }
                if (row === rows.first()) DayLabelOverlay(label(day), Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun HeroRow(row: GridRow.Hero, onOpen: (MediaItem) -> Unit) {
    Row(Modifier.fillMaxWidth().aspectRatio(1.5f), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        MediaTile(row.large, { onOpen(row.large) }, Modifier.weight(2f).fillMaxHeight())
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            row.side.forEach { item ->
                MediaTile(item, { onOpen(item) }, Modifier.weight(1f).fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RegularRow(items: List<MediaItem>, onOpen: (MediaItem) -> Unit) {
    Row(Modifier.fillMaxWidth().aspectRatio(3f), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
        items.forEach { item ->
            MediaTile(item, { onOpen(item) }, Modifier.weight(1f).fillMaxHeight())
        }
        repeat(3 - items.size) { Spacer(Modifier.weight(1f)) }
    }
}
