package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.chencohensolution.journal.R
import com.chencohensolution.journal.stories.BurstSuggestion
import com.chencohensolution.journal.stories.StoryDay
import com.chencohensolution.journal.stories.StoryTile
import com.chencohensolution.journal.stories.GroupView
import com.chencohensolution.journal.library.MediaItem
import java.time.Instant
import java.time.ZoneId

private val GAP = 2.dp

/** "2:09 PM", or "2:09 – 2:10 PM" when the photos span minutes. */
internal fun timeRange(items: List<MediaItem>): String {
    val zone = ZoneId.systemDefault()
    val first = Instant.ofEpochMilli(items.minOf { it.takenAtMillis }).atZone(zone)
    val last = Instant.ofEpochMilli(items.maxOf { it.takenAtMillis }).atZone(zone)
    val start = formatDate(first, "jmm")
    val end = formatDate(last, "jmm")
    return if (start == end) start else "$start – $end"
}

/** Selection mode of a story's grid: which photos are picked, and how to change that. */
internal class GridSelection(val ids: Set<String>, val toggle: (String) -> Unit)

/**
 * A story's grid, day by day: a group is a large tile; the first tile of a day with three or more tiles is large
 * too; the rest fill rows of three. With [selection], tapping a photo toggles it; groups can't be picked.
 */
internal fun LazyListScope.storyGrids(
    days: List<StoryDay>,
    label: @Composable (StoryDay) -> String,
    onOpenItem: (MediaItem) -> Unit,
    onOpenGroup: (GroupView) -> Unit,
    onLongPress: (MediaItem) -> Unit,
    selection: GridSelection?,
) {
    days.forEach { day ->
        val rows = storyRows(day.tiles)
        items(rows, key = { row -> "${day.day}-${row.key}" }) { row ->
            Box {
                val tile: @Composable (StoryTile, Modifier, Boolean) -> Unit = { tile, modifier, large ->
                    when (tile) {
                        is StoryTile.Group -> GroupTile(tile.group, modifier, large) { if (selection == null) onOpenGroup(tile.group) }
                        is StoryTile.Media -> MediaTile(
                            tile.item,
                            onClick = { if (selection != null) selection.toggle(tile.item.id) else onOpenItem(tile.item) },
                            modifier = modifier,
                            thumbnailSize = if (large) THUMB_LARGE else THUMB_SMALL,
                            onLongClick = { onLongPress(tile.item) },
                            selected = selection?.let { tile.item.id in it.ids },
                        )
                    }
                }
                when (row) {
                    is StoryRow.Hero -> Row(Modifier.fillMaxWidth().aspectRatio(1.5f), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        tile(row.large, Modifier.weight(2f).fillMaxHeight(), true)
                        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(GAP)) {
                            row.side.forEach { tile(it, Modifier.weight(1f).fillMaxWidth(), false) }
                            repeat(2 - row.side.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                    is StoryRow.Regular -> Row(Modifier.fillMaxWidth().aspectRatio(3f), horizontalArrangement = Arrangement.spacedBy(GAP)) {
                        row.tiles.forEach { tile(it, Modifier.weight(1f).fillMaxHeight(), false) }
                        repeat(3 - row.tiles.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                if (row === rows.first()) DayLabelOverlay(label(day), Modifier.fillMaxWidth())
            }
        }
    }
}

private sealed interface StoryRow {
    val key: String

    data class Hero(val large: StoryTile, val side: List<StoryTile>) : StoryRow {
        override val key get() = "hero-${large.key}"
    }

    data class Regular(val tiles: List<StoryTile>) : StoryRow {
        override val key get() = "row-${tiles.first().key}"
    }
}

private val StoryTile.key: String
    get() = when (this) {
        is StoryTile.Media -> item.id
        is StoryTile.Group -> "group-${group.id}"
    }

private fun storyRows(tiles: List<StoryTile>): List<StoryRow> {
    val rows = mutableListOf<StoryRow>()
    var index = 0
    while (index < tiles.size) {
        val tile = tiles[index]
        val large = tile is StoryTile.Group || (index == 0 && tiles.size >= 3)
        if (large) {
            // Up to two photos beside it; another group starts its own row.
            val side = tiles.drop(index + 1).take(2).takeWhile { it is StoryTile.Media }
            rows += StoryRow.Hero(tile, side)
            index += 1 + side.size
        } else {
            val run = tiles.drop(index).take(3).takeWhile { it is StoryTile.Media }
            rows += StoryRow.Regular(run)
            index += run.size
        }
    }
    return rows
}

/** A group in its story: the cover with a stack behind it, the title or time, and "⧉ 13". */
@Composable
internal fun GroupTile(group: GroupView, modifier: Modifier = Modifier, large: Boolean = true, onClick: () -> Unit) {
    val thumbnail = rememberThumbnail(group.cover, if (large) THUMB_LARGE else THUMB_SMALL)
    val description = stringResource(R.string.library_group_description, group.title ?: timeRange(group.items), group.itemCount)
    Box(modifier.clickable(onClick = onClick).semantics { contentDescription = description }) {
        // The stack: two cards peeking out behind the cover.
        Box(Modifier.fillMaxSize().padding(start = 8.dp, bottom = 8.dp).offset(x = 2.dp, y = (-4).dp).background(LibraryColors.Muted.copy(alpha = 0.35f), RoundedCornerShape(3.dp)))
        Box(Modifier.fillMaxSize().padding(start = 4.dp, bottom = 4.dp, end = 2.dp).background(LibraryColors.Muted.copy(alpha = 0.55f), RoundedCornerShape(3.dp)))
        Box(
            Modifier
                .fillMaxSize()
                .padding(top = 4.dp, end = 6.dp)
                .background(LibraryColors.Raised, RoundedCornerShape(3.dp)),
        ) {
            if (thumbnail != null) {
                Image(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .background(LibraryColors.PhotoFade)
                    .padding(start = 10.dp, end = 8.dp, top = 32.dp, bottom = 8.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Column(Modifier.weight(1f)) {
                    group.title?.let {
                        Text(it, color = LibraryColors.PhotoText, fontSize = if (large) 17.sp else 13.sp, fontWeight = FontWeight.Bold, maxLines = 2)
                    }
                    if (large || group.title == null) {
                        Text(timeRange(group.items), color = LibraryColors.PhotoTextMuted, fontSize = 12.sp)
                    }
                }
                StackCount(group.itemCount)
            }
        }
    }
}

@Composable
private fun StackCount(count: Int) {
    Row(
        Modifier
            .background(LibraryColors.PillScrim, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.Stack, null, tint = LibraryColors.PhotoText, modifier = Modifier.size(13.dp))
        Text(count.toString(), color = LibraryColors.PhotoText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    }
}

/** "13 similar photos · 2:09 PM · taken within 14 seconds" with Group them / Not now. */
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
                Text(
                    pluralStringResource(R.plurals.library_burst_title, suggestion.count, suggestion.count),
                    color = LibraryColors.OnBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.library_burst_detail, timeRange(suggestion.items.take(1)), suggestion.seconds.coerceAtLeast(1)),
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
