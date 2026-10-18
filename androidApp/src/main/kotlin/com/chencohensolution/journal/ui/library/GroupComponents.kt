package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Image
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAbsoluteAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chencohensolution.journal.R
import com.chencohensolution.journal.stories.BurstSuggestion
import com.chencohensolution.journal.stories.FocusPoint
import com.chencohensolution.journal.stories.GRID_COLUMNS
import com.chencohensolution.journal.stories.PlacedTile
import com.chencohensolution.journal.stories.StoryBand
import com.chencohensolution.journal.stories.StoryDay
import com.chencohensolution.journal.stories.StoryTile
import com.chencohensolution.journal.stories.GroupView
import com.chencohensolution.journal.library.MediaItem
import java.time.Instant
import java.time.ZoneId

/** "2:09 PM", or "2:09 – 2:10 PM" when the photos span minutes. */
internal fun timeRange(items: List<MediaItem>): String {
    val zone = ZoneId.systemDefault()
    val first = Instant.ofEpochMilli(items.minOf { it.takenAtMillis }).atZone(zone)
    val last = Instant.ofEpochMilli(items.maxOf { it.takenAtMillis }).atZone(zone)
    val start = formatDate(first, "jmm")
    val end = formatDate(last, "jmm")
    return if (start == end) start else "$start – $end"
}

/** Selection mode of a story's grid: which tiles are picked ([StoryTile.key]), and how to change that. */
internal class GridSelection(val keys: Set<String>, val toggle: (String) -> Unit)

/** Arrange mode of a story's grid: tapping a tile picks it ([StoryTile.key]) to change its size or place. */
internal class GridArranging(val selectedKey: String?, val select: (String) -> Unit)

/**
 * A story's grid, day by day, each day packed onto the 6-column grid ([StoryDay.bands]): tiles are as large as the
 * owner arranged them, or automatic (full width at the photo's own ratio). With [selection], tapping a photo or
 * group toggles it; with [arranging], tapping any tile picks it. Long-pressing a tile ([StoryTile.key]) calls
 * [onLongPress].
 */
internal fun LazyListScope.storyGrids(
    days: List<StoryDay>,
    label: @Composable (StoryDay) -> String,
    onOpenItem: (MediaItem) -> Unit,
    onOpenGroup: (GroupView) -> Unit,
    onLongPress: (tileKey: String) -> Unit,
    selection: GridSelection?,
    arranging: GridArranging? = null,
) {
    days.forEach { day ->
        items(day.bands, key = { band -> "${day.day}-${band.key}" }) { band ->
            Box {
                BandGrid(band) { placed, modifier ->
                    val tile = placed.tile
                    val crop = TileCrop(placed.focus, placed.zoom)
                    val thumbnailSize = thumbnailSizeFor(placed)
                    val picked = arranging?.selectedKey == tile.key
                    Box(modifier) {
                        when (tile) {
                            is StoryTile.Group -> GroupTile(
                                tile.group,
                                Modifier.fillMaxSize(),
                                thumbnailSize,
                                crop,
                                onLongClick = if (arranging == null) ({ onLongPress(tile.key) }) else null,
                                selected = selection?.let { tile.key in it.keys },
                            ) {
                                when {
                                    arranging != null -> arranging.select(tile.key)
                                    selection != null -> selection.toggle(tile.key)
                                    else -> onOpenGroup(tile.group)
                                }
                            }
                            is StoryTile.Media -> MediaTile(
                                tile.item,
                                onClick = {
                                    when {
                                        arranging != null -> arranging.select(tile.key)
                                        selection != null -> selection.toggle(tile.key)
                                        else -> onOpenItem(tile.item)
                                    }
                                },
                                modifier = Modifier.fillMaxSize(),
                                thumbnailSize = thumbnailSize,
                                onLongClick = if (arranging == null) ({ onLongPress(tile.key) }) else null,
                                selected = selection?.let { tile.key in it.keys },
                                crop = crop,
                            )
                        }
                        if (picked) Box(Modifier.fillMaxSize().border(3.dp, LibraryColors.Warning))
                    }
                }
                if (band === day.bands.first()) DayLabelOverlay(label(day), Modifier.fillMaxWidth())
            }
        }
    }
}

/** Enough pixels for the tile: small tiles share the small thumbnails; full-width or zoomed tiles need more. */
private fun thumbnailSizeFor(tile: PlacedTile): Int = when {
    tile.size.columns >= GRID_COLUMNS || tile.zoom > 1.25f -> THUMB_FULL
    tile.size.columns >= 3 -> THUMB_LARGE
    else -> THUMB_SMALL
}

/**
 * One band of a day: square cells [GRID_COLUMNS] across, each tile spanning its size in cells; or one full-width
 * tile at its photo's own ratio ([StoryBand.aspectRatio]).
 */
@Composable
private fun BandGrid(band: StoryBand, tile: @Composable (PlacedTile, Modifier) -> Unit) {
    band.aspectRatio?.let { ratio ->
        val placed = band.tiles.single()
        key(placed.tile.key) { tile(placed, Modifier.fillMaxWidth().aspectRatio(ratio)) }
        return
    }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val cell = (maxWidth - TILE_GAP * (GRID_COLUMNS - 1)) / GRID_COLUMNS
        fun span(cells: Int) = cell * cells + TILE_GAP * (cells - 1)
        Box(Modifier.fillMaxWidth().height(span(band.rows))) {
            band.tiles.forEach { placed ->
                key(placed.tile.key) {
                    // offset follows the layout direction, so the grid mirrors in Hebrew like the rest of the screen.
                    tile(
                        placed,
                        Modifier
                            .offset(x = (cell + TILE_GAP) * placed.column, y = (cell + TILE_GAP) * placed.row)
                            .size(span(placed.size.columns), span(placed.size.rows)),
                    )
                }
            }
        }
    }
}

/**
 * How a tile crops its photo: anchored at [focus], [zoom] times closer than filling the tile. With the photo anchored
 * there, the focus point sits at the same fraction of the tile, so zooming around that fraction keeps it in view.
 */
internal class TileCrop(val focus: FocusPoint = FocusPoint.CENTER, val zoom: Float = 1f) {
    /** Absolute: the point is in the photo's own coordinates, not mirrored in RTL. */
    val alignment: Alignment get() = BiasAbsoluteAlignment(focus.x * 2 - 1, focus.y * 2 - 1)

    companion object {
        val NONE = TileCrop()
    }
}

/** For the cropped image itself; its container must clip. */
internal fun Modifier.zoomAround(crop: TileCrop): Modifier = if (crop.zoom <= 1f) {
    this
} else {
    graphicsLayer {
        scaleX = crop.zoom
        scaleY = crop.zoom
        transformOrigin = TransformOrigin(crop.focus.x, crop.focus.y)
    }
}

/**
 * A group in its story: just its cover, with the number of photos in it. [selected] is null outside selection mode;
 * in it the tile shows a check circle.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun GroupTile(
    group: GroupView,
    modifier: Modifier = Modifier,
    thumbnailSize: Int = THUMB_LARGE,
    crop: TileCrop = TileCrop.NONE,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    onClick: () -> Unit,
) {
    val thumbnail = rememberThumbnail(group.cover, thumbnailSize)
    val description = stringResource(R.string.library_group_description, group.title ?: timeRange(group.items), group.itemCount)
    Box(
        modifier
            .background(LibraryColors.Raised)
            .clipToBounds()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                contentDescription = description
                if (selected != null) this.selected = selected
            },
    ) {
        if (thumbnail != null) {
            Image(thumbnail, null, contentScale = ContentScale.Crop, alignment = crop.alignment, modifier = Modifier.fillMaxSize().zoomAround(crop))
        }
        Text(
            group.itemCount.toString(),
            color = LibraryColors.PhotoText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(6.dp)
                .background(LibraryColors.PillScrim, RoundedCornerShape(50))
                .padding(horizontal = 9.dp, vertical = 3.dp),
        )
        if (selected != null) {
            if (selected) Box(Modifier.fillMaxSize().border(3.dp, LibraryColors.PhotoText))
            SelectionCheck(selected, Modifier.align(Alignment.TopStart).padding(7.dp))
        }
    }
}

/**
 * "Look alike", "13 similar photos" (or "Taken together", "13 photos in a row"), "2:09 PM · taken within 14 seconds" (or minutes, hours,
 * days) with Group them / Not now.
 */
@Composable
internal fun BurstSuggestionCard(suggestion: BurstSuggestion, onGroup: () -> Unit, onDismiss: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(LibraryColors.Raised, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(64.dp)) {
                suggestion.items.take(3).reversed().forEachIndexed { index, item ->
                    val thumbnail = rememberThumbnail(item, THUMB_SMALL)
                    Box(
                        Modifier
                            .offset(x = (8 - index * 4).dp, y = (index * 5).dp)
                            .size(52.dp)
                            .border(2.dp, LibraryColors.Raised, RoundedCornerShape(8.dp))
                            .background(LibraryColors.Muted, RoundedCornerShape(8.dp)),
                    ) {
                        if (thumbnail != null) {
                            Image(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize().padding(2.dp))
                        }
                    }
                }
            }
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                // Why they were picked: what's in them, or when they were taken.
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        if (suggestion.byLooks) LibraryIcons.Eye else LibraryIcons.Clock,
                        contentDescription = null,
                        tint = LibraryColors.Muted,
                        modifier = Modifier.size(14.dp),
                    )
                    Text(
                        stringResource(if (suggestion.byLooks) R.string.library_burst_by_looks else R.string.library_burst_by_time),
                        color = LibraryColors.Muted,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    pluralStringResource(
                        if (suggestion.byLooks) R.plurals.library_burst_title else R.plurals.library_burst_title_time,
                        suggestion.count,
                        suggestion.count,
                    ),
                    color = LibraryColors.OnBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                // Photos that look alike can be taken hours or days apart.
                val start = timeRange(suggestion.items.take(1))
                Text(
                    when {
                        suggestion.seconds < 60 -> stringResource(R.string.library_burst_detail, start, suggestion.seconds.coerceAtLeast(1))
                        suggestion.sameDay && suggestion.seconds < 3600 ->
                            stringResource(R.string.library_burst_detail_minutes, start, (suggestion.seconds + 59) / 60)
                        suggestion.sameDay -> timeRange(suggestion.items)
                        else -> dateRange(suggestion.items.first().day, suggestion.items.last().day)
                    },
                    color = LibraryColors.Muted,
                    fontSize = 13.sp,
                )
                Text(stringResource(R.string.library_burst_question), color = LibraryColors.Muted, fontSize = 13.sp)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onGroup,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = LibraryColors.Inverse, contentColor = LibraryColors.OnInverse),
                modifier = Modifier.weight(1f).height(44.dp),
            ) { Text(stringResource(R.string.library_burst_group), fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = onDismiss,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f).height(44.dp),
            ) { Text(stringResource(R.string.library_burst_not_now), color = LibraryColors.OnBackground) }
        }
    }
}

/** Story text (from story.md) in a journal serif, with Edit; a prompt to write when it's empty. */
@Composable
internal fun WritingBlock(text: String, emptyPrompt: String, onEdit: () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(LibraryColors.Raised, RoundedCornerShape(14.dp))
            .clickable(onClick = onEdit)
            .padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 6.dp),
    ) {
        Text(
            text.ifBlank { emptyPrompt },
            color = if (text.isBlank()) LibraryColors.Muted else LibraryColors.OnBackground,
            fontFamily = FontFamily.Serif,
            fontSize = 17.sp,
            lineHeight = 25.sp,
            modifier = Modifier.padding(end = 8.dp),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.library_writing_saved_in), color = LibraryColors.Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onEdit) {
                Icon(LibraryIcons.Edit, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(16.dp))
                Text(stringResource(R.string.library_writing_edit), color = LibraryColors.OnBackground, modifier = Modifier.padding(start = 6.dp))
            }
        }
    }
}

/** Full-screen editor for story text. */
@Composable
internal fun WritingEditor(title: String, initial: String, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(initial) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = LibraryColors.Background) {
            Column(Modifier.systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(stringResource(R.string.library_cancel), color = LibraryColors.OnBackground) }
                    Text(title, color = LibraryColors.OnBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f), maxLines = 1)
                    TextButton(onClick = { onSave(text) }) {
                        Text(stringResource(R.string.library_save), color = LibraryColors.Warning, fontWeight = FontWeight.Bold)
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.library_writing_placeholder)) },
                    textStyle = androidx.compose.ui.text.TextStyle(color = LibraryColors.OnBackground, fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 27.sp),
                    modifier = Modifier.fillMaxSize().padding(16.dp).focusRequester(focus),
                )
            }
        }
    }
}

/** How long "Grouped N photos · Undo" stays, unless closed with its ✕ first. */
private const val UNDO_MILLIS = 6_000L

/**
 * Shows "Grouped N photos · Undo" with a ✕ for [UNDO_MILLIS], replacing one already showing. Returns whether Undo
 * was tapped.
 */
internal suspend fun SnackbarHostState.offerUndo(message: String, undoLabel: String): Boolean {
    currentSnackbarData?.dismiss()
    // A snackbar with an action never leaves by itself; timing out cancels it, which dismisses it.
    val result = withTimeoutOrNull(UNDO_MILLIS) {
        showSnackbar(message, actionLabel = undoLabel, withDismissAction = true, duration = SnackbarDuration.Indefinite)
    }
    return result == SnackbarResult.ActionPerformed
}
