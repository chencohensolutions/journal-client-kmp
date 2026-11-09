package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.stories.CropRect
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

// The parts of a moment set (near-duplicates or a moment made by hand) shared by the feed, the viewer and the crop
// screen: the scrubber, the slideshow, the grid sheet. Promoted from the UI lab round moment-set (Nov 9, 2026); see
// docs/screens/viewer.md and moments.md.

/** The viewer's list of a set ([Route.Viewer.list], [PageLists]): its photo ids in order, so it can be read again. */
internal fun setListKey(ids: List<String>): String = SET_LIST_PREFIX + ids.joinToString(SET_ID_SEPARATOR)

/** The ids of the set [listKey] is of; null when it isn't a set's list. */
internal fun setIdsOf(listKey: String?): List<String>? =
    listKey?.takeIf { it.startsWith(SET_LIST_PREFIX) }?.removePrefix(SET_LIST_PREFIX)?.split(SET_ID_SEPARATOR)?.filter { it.isNotEmpty() }

private const val SET_LIST_PREFIX = "set:"
private const val SET_ID_SEPARATOR = ","

/** How fast a set plays: the next photo every [millis]. */
internal enum class SlideSpeed(val millis: Long, val label: Int) {
    FAST(350, R.string.set_speed_fast),
    NORMAL(800, R.string.set_speed_normal),
    SLOW(2_000, R.string.set_speed_slow),
}

/** In the feed a set plays at this pace. */
internal const val FEED_PLAY_MILLIS = 500L

/** Swipes and flips within a set cross-fade, this long. */
internal const val SET_FADE_MILLIS = 120

/** In the feed, holding and sliding moves one photo per this many dp. */
internal const val FLIP_STEP_DP = 10

/** While [playing], the next of [count] photos every [millis], round and round. */
@Composable
internal fun Slideshow(playing: Boolean, millis: Long, count: Int, index: Int, onIndex: (Int) -> Unit) {
    val current by rememberUpdatedState(index)
    val change by rememberUpdatedState(onIndex)
    LaunchedEffect(playing, millis, count) {
        while (playing && count > 1) {
            delay(millis)
            change((current + 1) % count)
        }
    }
}

/** The screen stays on while [on] (a slideshow playing). */
@Composable
internal fun KeepScreenOn(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, on) {
        view.keepScreenOn = on
        onDispose { view.keepScreenOn = false }
    }
}

/** A photo's shape as Moments shows it: at its Moments crop, else its own. */
internal fun MediaItem.shownRatio(crop: CropRect?): Float = crop?.takeIf { durationMillis == null }?.ratioOn(ratio) ?: ratio

/** A small square of [item] (a thumbnail, cropped to fill). */
@Composable
internal fun SetThumb(item: MediaItem, modifier: Modifier = Modifier) {
    Box(modifier.clipToBounds().background(LibraryColors.Raised)) {
        rememberThumbnail(item, THUMB_SMALL)?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/**
 * Every photo of the set in one row (at most [SCRUBBER_CELLS] squeezed in, each standing for its share); sliding a
 * finger along it flips straight to the photo under it, with a tick. The photo shown stands out above the row; a dot
 * marks each highlight.
 */
@Composable
internal fun SetScrubber(items: List<MediaItem>, highlights: Set<String>, index: Int, onIndex: (Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(index)
    val change by rememberUpdatedState(onIndex)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    BoxWithConstraints(modifier.fillMaxWidth().height(60.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        val cells = items.size.coerceAtMost(SCRUBBER_CELLS)
        val cell = maxWidth / cells
        fun pick(x: Float) {
            val along = (if (rtl) widthPx - x else x) / widthPx
            val i = (along * items.size).toInt().coerceIn(0, items.size - 1)
            if (i != current) {
                change(i)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp))) {
            repeat(cells) { c ->
                val item = items[c * items.size / cells]
                Box(Modifier.width(cell).fillMaxHeight()) {
                    SetThumb(item, Modifier.fillMaxSize())
                    val stands = (c * items.size / cells until (c + 1) * items.size / cells).any { items[it].id in highlights }
                    if (stands) Box(Modifier.align(Alignment.TopCenter).padding(top = 3.dp).size(5.dp).background(Color.White, CircleShape))
                }
            }
        }
        val shown = 40.dp
        val at = (maxWidth * ((index + 0.5f) / items.size) - shown / 2).coerceIn(0.dp, maxWidth - shown)
        SetThumb(
            items[index.coerceIn(items.indices)],
            Modifier.offset(x = at).size(shown, 58.dp).clip(RoundedCornerShape(4.dp)).border(2.dp, Color.White, RoundedCornerShape(4.dp)),
        )
        Box(
            Modifier.fillMaxSize().pointerInput(items.size, rtl) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pick(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val moved = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!moved.pressed) break
                        pick(moved.position.x)
                        moved.consume()
                    }
                }
            },
        )
    }
}

/** A set with more photos squeezes this many into its scrubber. */
private const val SCRUBBER_CELLS = 48

/** Fast · Normal · Slow, the one on filled. */
@Composable
internal fun SpeedPicker(speed: SlideSpeed, onSpeed: (SlideSpeed) -> Unit) {
    Row(Modifier.background(LibraryColors.PillScrim, RoundedCornerShape(50)).padding(3.dp)) {
        SlideSpeed.entries.forEach { option ->
            val on = option == speed
            Box(
                Modifier.height(34.dp).clip(RoundedCornerShape(50)).background(if (on) Color.White else Color.Transparent)
                    .clickable { onSpeed(option) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(option.label), color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** A thin line of the set with a mark at the photo shown ([index]) and a dot at its cover ([cover]). */
@Composable
internal fun SetPositionLine(count: Int, index: Int, cover: Int?, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.height(12.dp)) {
        val step = maxWidth / maxOf(count - 1, 1)
        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.35f), RoundedCornerShape(50)))
        cover?.let { Box(Modifier.align(Alignment.CenterStart).offset(x = step * it - 2.5.dp).size(5.dp).background(Color.White.copy(alpha = 0.8f), CircleShape)) }
        Box(Modifier.align(Alignment.CenterStart).offset(x = step * index - 1.5.dp).size(3.dp, 12.dp).background(Color.White, RoundedCornerShape(50)))
    }
}

/**
 * The whole set as squares in a sheet (the viewer's grid button): a tap goes to that photo. Above them its highlights
 * in their order: holding one and dragging it sideways moves it ([onMove], its new place from 0).
 */
@Composable
internal fun SetGridSheet(
    items: List<MediaItem>,
    highlights: List<MediaItem>,
    currentId: String?,
    onPick: (MediaItem) -> Unit,
    onMove: (MediaItem, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    PageSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Text(
                pluralStringResource(R.plurals.set_photos, items.size, items.size),
                Modifier.padding(horizontal = 16.dp),
                color = LibraryColors.OnBackground, fontSize = 17.sp, fontWeight = FontWeight.Bold,
            )
            if (highlights.isNotEmpty()) {
                Text(
                    stringResource(R.string.set_highlights) + " · " + stringResource(R.string.set_highlights_hint),
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 8.dp),
                    color = LibraryColors.Muted, fontSize = 13.sp,
                )
                HighlightOrder(highlights, onPick, onMove, Modifier.padding(horizontal = 16.dp))
            }
            val ids = remember(highlights) { highlights.mapIndexed { i, item -> item.id to i + 1 }.toMap() }
            LazyVerticalGrid(
                GridCells.Fixed(4),
                Modifier.fillMaxWidth().heightIn(max = 520.dp).padding(top = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                items(items, key = { it.id }) { item ->
                    Box(Modifier.aspectRatio(1f).clickable { onPick(item) }) {
                        SetThumb(item, Modifier.fillMaxSize())
                        ids[item.id]?.let { place -> HighlightMark(place, Modifier.align(Alignment.TopStart).padding(5.dp)) }
                        if (item.id == currentId) Box(Modifier.fillMaxSize().border(3.dp, Color.White))
                    }
                }
            }
        }
    }
}

/** A highlight's place (1, 2…) in a small round mark; the first is the cover. */
@Composable
internal fun HighlightMark(place: Int, modifier: Modifier = Modifier) {
    Box(modifier.size(20.dp).background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
        Text(if (place == 1) "★" else place.toString(), color = Color.Black, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** The highlights in a row; hold one and drag it sideways to its new place. */
@Composable
private fun HighlightOrder(highlights: List<MediaItem>, onPick: (MediaItem) -> Unit, onMove: (MediaItem, Int) -> Unit, modifier: Modifier = Modifier) {
    val haptic = LocalHapticFeedback.current
    var dragging by remember { mutableStateOf<String?>(null) }
    var offset by remember { mutableFloatStateOf(0f) }
    val tile = 64.dp
    val gap = 6.dp
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap)) {
        highlights.forEachIndexed { i, item ->
            val moving = dragging == item.id
            Box(
                Modifier
                    .zIndex(if (moving) 1f else 0f)
                    .offset { IntOffset(if (moving) offset.roundToInt() else 0, 0) }
                    .size(tile)
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { onPick(item) }
                    .pointerInput(item.id, highlights.size) {
                        val stepPx = (tile + gap).toPx()
                        detectDragGesturesAfterLongPress(
                            onDragStart = {
                                dragging = item.id
                                offset = 0f
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            },
                            onDragEnd = {
                                val moved = ((if (rtl) -offset else offset) / stepPx).roundToInt()
                                val to = (i + moved).coerceIn(0, highlights.size - 1)
                                dragging = null
                                offset = 0f
                                if (to != i) onMove(item, to)
                            },
                            onDragCancel = {
                                dragging = null
                                offset = 0f
                            },
                        ) { change, amount ->
                            change.consume()
                            offset += amount.x
                        }
                    },
            ) {
                SetThumb(item, Modifier.fillMaxSize())
                HighlightMark(i + 1, Modifier.align(Alignment.TopStart).padding(4.dp))
                if (moving) Box(Modifier.fillMaxSize().border(2.dp, Color.White, RoundedCornerShape(6.dp)))
            }
        }
    }
}

/** The slideshow's controls: a thin progress line, the speeds and Stop. */
@Composable
internal fun SlideshowBar(index: Int, count: Int, speed: SlideSpeed, onSpeed: (SlideSpeed) -> Unit, onStop: () -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(16.dp)) {
        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))) {
            Box(Modifier.fillMaxWidth((index + 1f) / count).fillMaxHeight().background(Color.White, RoundedCornerShape(50)))
        }
        Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SpeedPicker(speed, onSpeed)
            Box(Modifier.weight(1f))
            Box(
                Modifier.height(40.dp).background(LibraryColors.PillScrim, RoundedCornerShape(50)).clickable(onClick = onStop).padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) { Text(stringResource(R.string.set_stop), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** How far a swipe goes before it changes the photo in a set. */
internal val SET_SWIPE = 40.dp

/** Whether [a] and [b] are the same shape, for Same frame for all. */
internal fun sameShape(a: Float, b: Float): Boolean = abs(a - b) < 0.01f
