package com.chencohensolution.journal.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

// Lab round "moment-set" (Nov 9, 2026), Android twin of iosApp/iosApp/Lab/MomentSet/: viewing a set of many photos,
// its main photo, a composition per photo, swipes that cross-fade, a slideshow, and collecting a weekend into one
// moment. Options a (set page), b (viewer with a scrubber), c (flip in the feed), d (collect). Made-up pictures of
// one run along a beach; Make main, crops and new moments live only while the option is open. English only.

// MARK: state

/** A photo of a set: its shape and its Moments crop (0..1 of the photo; null shows it whole). */
internal data class SetPhoto(
    val id: String,
    val index: Int,
    val of: Int,
    val aspect: Float,
    val crop: Rect? = null,
    /** The stand-in scene: 0 the beach, 1 the park. */
    val scene: Int = 0,
) {
    val shownAspect: Float get() = crop?.let { aspect * it.width / it.height } ?: aspect
}

/** A set as a page shows it. [mainId] is the photo it shows in Moments and opens on (the note's `front` today). */
internal data class MomentSetState(val place: String, val whenText: String, val photos: List<SetPhoto>, val mainId: String) {
    val mainIndex: Int get() = photos.indexOfFirst { it.id == mainId }.coerceAtLeast(0)
    fun withCrops(crops: Map<String, Rect>) = copy(photos = photos.map { p -> crops[p.id]?.let { p.copy(crop = if (it.isWhole) null else it) } ?: p })
}

private val Whole = Rect(0f, 0f, 1f, 1f)
private val Rect.isWhole get() = abs(left) < 0.001f && abs(top) < 0.001f && abs(width - 1) < 0.001f && abs(height - 1) < 0.001f
private fun Rect.keptInside(): Rect {
    val w = min(width, 1f)
    val h = min(height, 1f)
    val x = left.coerceIn(0f, 1f - w)
    val y = top.coerceIn(0f, 1f - h)
    return Rect(x, y, x + w, y + h)
}

internal object MomentSetLab {
    fun beach(): MomentSetState {
        val n = 36
        val photos = List(n) { SetPhoto("beach-$it", it, n, 3f / 4f) }.toMutableList()
        photos[14] = photos[14].copy(crop = Rect(0.2f, 0.38f, 0.8f, 0.86f))
        photos[22] = photos[22].copy(crop = Rect(0.25f, 0.3f, 1f, 0.8625f))
        return MomentSetState("Herzliya beach", "Sat, Nov 7 · 17:02 – 17:09", photos, "beach-14")
    }

    val parkBefore = SetPhoto("park-1", 0, 1, 4f / 3f, scene = 1)
    val parkAfter = SetPhoto("park-2", 0, 1, 3f / 4f, scene = 1)

    class Day(val title: String, val place: String, val photos: List<SetPhoto>)

    /** A weekend, newest first. */
    val days: List<Day> = listOf(
        Triple("Sun, Nov 8", "Hod HaSharon", 1) to 4,
        Triple("Sat, Nov 7", "Herzliya", 0) to 5,
        Triple("Fri, Nov 6", "Hod HaSharon", 1) to 4,
    ).mapIndexed { d, (day, count) ->
        Day(day.first, day.second, List(count) { i -> SetPhoto("day$d-$i", i, count, if (i % 4 == 1) 4f / 3f else 3f / 4f, scene = day.third) })
    }
}

// MARK: pictures

/** A set's photo at its crop (or whole), drawn into the space it is given: give it the photo's shown shape. */
@Composable
private fun SetPicture(photo: SetPhoto, modifier: Modifier = Modifier, cropped: Boolean = true) {
    Canvas(modifier.clipToBounds()) {
        val crop = if (cropped) photo.crop ?: Whole else Whole
        val full = Size(size.width / crop.width, size.height / crop.height)
        translate(-crop.left * full.width, -crop.top * full.height) { drawScene(photo, full) }
    }
}

/** Someone running along a beach at sunset, a little further each shot; scene 1 is a park. */
private fun DrawScope.drawScene(photo: SetPhoto, size: Size) {
    val w = size.width
    val h = size.height
    val s = min(w, h)
    val t = if (photo.of > 1) photo.index.toFloat() / (photo.of - 1) else 0f
    val park = photo.scene == 1
    val skyTop = if (park) Color(0xFFB8D6ED) else Color(0.98f, 0.78f - 0.12f * t, 0.60f - 0.1f * t)
    drawRect(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(skyTop, Color(0xFF8CADCC)), 0f, h * 0.6f), size = size)
    val r = s * 0.10f
    val sunY = if (park) h * 0.2f else h * (0.16f + 0.22f * t)
    drawCircle(Color.White.copy(alpha = 0.75f), r, Offset(w * 0.74f, sunY))
    if (park) {
        drawRect(Color(0xFF78A366), Offset(0f, h * 0.58f), Size(w, h * 0.42f))
        for (k in 0 until 3) {
            val x = w * (0.15f + 0.3f * k)
            drawRect(Color(0xFF664D38), Offset(x - s * 0.015f, h * 0.45f), Size(s * 0.03f, h * 0.15f))
            drawCircle(Color(0xFF4D8052), s * 0.1f, Offset(x, h * 0.32f + s * 0.1f))
        }
        return
    }
    drawRect(Color(0xFF4D809E), Offset(0f, h * 0.55f), Size(w, h * 0.16f))
    drawRect(Color(0xFFE3CCA8), Offset(0f, h * 0.70f), Size(w, h * 0.30f))
    val x = w * (0.16f + 0.68f * t)
    val feet = h * 0.80f - if (photo.index % 2 == 1) h * 0.05f else 0f
    drawOval(Color.Black.copy(alpha = 0.15f), Offset(x - s * 0.06f, h * 0.80f), Size(s * 0.12f, s * 0.022f))
    drawRoundRect(Color(0xFFC7403A), Offset(x - s * 0.04f, feet - s * 0.17f), Size(s * 0.08f, s * 0.15f), CornerRadius(s * 0.03f))
    drawCircle(Color(0xFF5C3D2E), s * 0.035f, Offset(x, feet - s * 0.21f))
}

/** A square tile at the photo's crop, a star on the main one, a crop mark on a recomposed one. */
@Composable
private fun SetTile(photo: SetPhoto, isMain: Boolean, modifier: Modifier = Modifier, current: Boolean = false) {
    Box(modifier.aspectRatio(1f).clipToBounds()) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val side = maxWidth
            val (w, h) = if (photo.shownAspect >= 1f) side * photo.shownAspect to side else side to side / photo.shownAspect
            SetPicture(photo, Modifier.align(Alignment.Center).size(w, h).wrapUnbounded())
        }
        if (isMain) Badge("★", LibraryColors.Paper, LibraryColors.PaperInk, Modifier.align(Alignment.TopStart).padding(5.dp))
        if (photo.crop != null) Badge("⌗", LibraryColors.BadgeScrim, LibraryColors.PhotoText, Modifier.align(Alignment.BottomEnd).padding(5.dp))
        if (current) Box(Modifier.fillMaxSize().border(3.dp, Color.White))
    }
}

private fun Modifier.wrapUnbounded(): Modifier = this.then(
    Modifier.layout { measurable, constraints ->
        val placeable = measurable.measure(constraints.copy(maxWidth = androidx.compose.ui.unit.Constraints.Infinity, maxHeight = androidx.compose.ui.unit.Constraints.Infinity))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place((constraints.maxWidth - placeable.width) / 2, (constraints.maxHeight - placeable.height) / 2)
        }
    },
)

@Composable
private fun Badge(text: String, background: Color, color: Color, modifier: Modifier = Modifier) {
    Box(modifier.size(20.dp).background(background, CircleShape), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }
}

/** The largest size of [aspect] inside [maxW] × [maxH]. */
private fun fitted(aspect: Float, maxW: Dp, maxH: Dp): Pair<Dp, Dp> =
    if (maxW / maxH > aspect) maxH * aspect to maxH else maxW to maxW / aspect

// MARK: cross-fade, slideshow, scrubber

/** The set's photos one at a time on black, fitted at their crop; a swipe changes the photo with a 0.12 s cross-fade. */
@Composable
private fun SetFadePhoto(photos: List<SetPhoto>, index: Int, onIndex: (Int) -> Unit, onTap: () -> Unit) {
    val current by rememberUpdatedState(index)
    var dragged by remember { mutableStateOf(0f) }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }) }
            .pointerInput(photos.size) {
                detectHorizontalDragGestures(
                    onDragStart = { dragged = 0f },
                    onDragEnd = {
                        if (abs(dragged) > 40.dp.toPx()) {
                            val next = current + if (dragged < 0) 1 else -1
                            if (next in photos.indices) onIndex(next)
                        }
                    },
                ) { change, amount ->
                    change.consume()
                    dragged += amount
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val maxW = maxWidth
        val maxH = maxHeight
        Crossfade(index, animationSpec = tween(120), label = "set photo") { i ->
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                val (w, h) = fitted(photos[i].shownAspect, maxW, maxH)
                SetPicture(photos[i], Modifier.size(w, h))
            }
        }
    }
}

/** While [playing], the next photo every [seconds], round and round. */
@Composable
private fun Slideshow(playing: Boolean, seconds: Float, count: Int, index: Int, onIndex: (Int) -> Unit) {
    val current by rememberUpdatedState(index)
    LaunchedEffect(playing, seconds) {
        while (playing) {
            delay((seconds * 1000).toLong())
            onIndex((current + 1) % count)
        }
    }
}

@Composable
private fun SpeedPicker(seconds: Float, onSeconds: (Float) -> Unit) {
    Row(Modifier.background(LibraryColors.PillScrim, RoundedCornerShape(50)).padding(3.dp)) {
        listOf("Fast" to 0.35f, "Normal" to 0.8f, "Slow" to 2f).forEach { (title, value) ->
            val on = abs(seconds - value) < 0.01f
            Box(
                Modifier.height(32.dp).clip(RoundedCornerShape(50)).background(if (on) LibraryColors.Paper else Color.Transparent)
                    .clickable { onSeconds(value) }.padding(horizontal = 14.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(title, color = if (on) LibraryColors.PaperInk else LibraryColors.PhotoText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/** Every photo in one row; sliding a finger along it flips straight to the photo under it, with a tick. */
@Composable
private fun Scrubber(photos: List<SetPhoto>, mainId: String, index: Int, onIndex: (Int) -> Unit) {
    val haptic = LocalHapticFeedback.current
    val current by rememberUpdatedState(index)
    BoxWithConstraints(Modifier.fillMaxWidth().height(60.dp)) {
        val widthPx = constraints.maxWidth.toFloat()
        val cell = maxWidth / photos.size
        fun pick(x: Float) {
            val i = (x / widthPx * photos.size).toInt().coerceIn(0, photos.size - 1)
            if (i != current) {
                onIndex(i)
                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            }
        }
        Row(Modifier.padding(top = 10.dp).fillMaxWidth().height(40.dp).clip(RoundedCornerShape(6.dp))) {
            photos.forEach { p ->
                Box(Modifier.width(cell).fillMaxHeight()) {
                    SetPicture(p, Modifier.fillMaxSize(), cropped = false)
                    if (p.id == mainId) Box(Modifier.align(Alignment.TopCenter).padding(top = 3.dp).size(5.dp).background(LibraryColors.Paper, CircleShape))
                }
            }
        }
        val shown = 40.dp
        val x = (cell * index + cell / 2 - shown / 2).coerceIn(0.dp, maxWidth - shown)
        SetPicture(
            photos[index],
            Modifier.offset(x = x).size(shown, 58.dp).clip(RoundedCornerShape(4.dp)).border(2.dp, Color.White, RoundedCornerShape(4.dp)),
            cropped = false,
        )
        Box(
            Modifier.fillMaxSize().pointerInput(photos.size) {
                awaitEachGesture {
                    val down = awaitFirstDown()
                    pick(down.position.x)
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull() ?: break
                        if (!change.pressed) break
                        pick(change.position.x)
                        change.consume()
                    }
                }
            },
        )
    }
}

// MARK: viewer

/** The set full screen: cross-fading swipes, Make main, a big Play, Recompose; with [strip], the scrubber and a grid. */
@Composable
private fun SetViewer(
    set: MomentSetState,
    onSet: (MomentSetState) -> Unit,
    start: Int,
    strip: Boolean,
    startPlaying: Boolean = false,
    startRecomposing: Boolean = false,
    onClose: () -> Unit,
) {
    var index by remember { mutableIntStateOf(start) }
    var playing by remember { mutableStateOf(startPlaying) }
    var seconds by remember { mutableStateOf(0.8f) }
    var recomposing by remember { mutableStateOf(startRecomposing) }
    var grid by remember { mutableStateOf(false) }
    Slideshow(playing, seconds, set.photos.size, index) { index = it }
    BackHandler(onBack = onClose)
    val photo = set.photos[index]
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        SetFadePhoto(set.photos, index, { index = it }) { if (playing) playing = false }
        if (!playing) {
            Row(
                Modifier.fillMaxWidth().background(LibraryColors.LabelFade).statusBarsPadding().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconText("✕", onClose)
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(set.place, color = LibraryColors.PhotoText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("${index + 1} of ${set.photos.size}", color = LibraryColors.PhotoTextMuted, fontSize = 12.sp)
                }
                if (strip) IconText("▦") { grid = true } else Spacer(Modifier.size(48.dp))
            }
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(LibraryColors.PhotoFade).navigationBarsPadding()
                    .padding(top = 24.dp, bottom = 8.dp),
            ) {
                if (strip) Box(Modifier.padding(horizontal = 16.dp)) { Scrubber(set.photos, set.mainId, index) { index = it } }
                Spacer(Modifier.height(14.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                    val isMain = photo.id == set.mainId
                    BarButton(if (isMain) "★" else "☆", if (isMain) "Main" else "Make main") { onSet(set.copy(mainId = photo.id)) }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(64.dp).background(LibraryColors.Paper, CircleShape).clickable { playing = true },
                        contentAlignment = Alignment.Center,
                    ) { Text("▶", color = LibraryColors.PaperInk, fontSize = 26.sp) }
                    Spacer(Modifier.weight(1f))
                    BarButton("⌗", "Recompose") { recomposing = true }
                }
            }
        } else {
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding().padding(16.dp)) {
                Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(50))) {
                    Box(Modifier.fillMaxWidth((index + 1f) / set.photos.size).fillMaxHeight().background(Color.White, RoundedCornerShape(50)))
                }
                Spacer(Modifier.height(12.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SpeedPicker(seconds) { seconds = it }
                    Spacer(Modifier.weight(1f))
                    Box(
                        Modifier.size(48.dp).background(LibraryColors.PillScrim, CircleShape).clickable { playing = false },
                        contentAlignment = Alignment.Center,
                    ) { Text("❚❚", color = LibraryColors.PhotoText, fontSize = 15.sp) }
                }
            }
        }
        if (grid) {
            BackHandler { grid = false }
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)).clickable { grid = false })
            LazyVerticalGrid(
                GridCells.Fixed(4),
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().fillMaxHeight(0.6f)
                    .background(LibraryColors.Background, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)).padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(set.photos, key = { _, p -> p.id }) { i, p ->
                    SetTile(p, p.id == set.mainId, Modifier.clickable { index = i; grid = false }, current = i == index)
                }
            }
        }
        if (recomposing) {
            SetRecompose(set.photos, index, onDone = { onSet(set.withCrops(it)) }) { recomposing = false }
        }
    }
}

@Composable
private fun IconText(glyph: String, onClick: () -> Unit) {
    Box(Modifier.size(48.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(glyph, color = LibraryColors.PhotoText, fontSize = 20.sp)
    }
}

@Composable
private fun BarButton(glyph: String, title: String, onClick: () -> Unit) {
    Column(Modifier.width(92.dp).clickable(onClick = onClick), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(glyph, color = LibraryColors.PhotoText, fontSize = 22.sp)
        Text(title, color = LibraryColors.PhotoText, fontSize = 12.sp, fontWeight = FontWeight.Medium)
    }
}

// MARK: recompose

/** A frame per photo (drag moves it, pinch sizes it, shapes set it), the set's strip, and Same frame for all. */
@Composable
private fun SetRecompose(photos: List<SetPhoto>, start: Int, onDone: (Map<String, Rect>) -> Unit, onClose: () -> Unit) {
    var index by remember { mutableIntStateOf(start) }
    var frames by remember { mutableStateOf(photos.associate { it.id to (it.crop ?: Whole) }) }
    val haptic = LocalHapticFeedback.current
    BackHandler(onBack = onClose)
    val photo = photos[index]
    val frame = frames[photo.id] ?: Whole
    fun setFrame(r: Rect) { frames = frames + (photo.id to r.keptInside()) }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconText("✕", onClose)
            Text("Recompose", Modifier.weight(1f), color = LibraryColors.PhotoText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            Text("Done", Modifier.clickable { onDone(frames); onClose() }.padding(12.dp), color = LibraryColors.PhotoText, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
            val (w, h) = fitted(photo.aspect, maxWidth, maxHeight)
            val density = LocalDensity.current
            val wPx = with(density) { w.toPx() }
            val hPx = with(density) { h.toPx() }
            val currentFrame by rememberUpdatedState(frame)
            Box(
                Modifier.size(w, h).pointerInput(photo.id) {
                    detectTransformGestures { _, pan, zoom, _ ->
                        val f = currentFrame
                        val most = min(1f / f.width, 1f / f.height)
                        val k = zoom.coerceIn(0.2f / f.width, most)
                        val nw = f.width * k
                        val nh = f.height * k
                        val cx = f.center.x + pan.x / wPx
                        val cy = f.center.y + pan.y / hPx
                        setFrame(Rect(cx - nw / 2, cy - nh / 2, cx + nw / 2, cy + nh / 2))
                    }
                },
            ) {
                SetPicture(photo, Modifier.fillMaxSize(), cropped = false)
                Canvas(Modifier.fillMaxSize()) {
                    val box = Rect(frame.left * size.width, frame.top * size.height, frame.right * size.width, frame.bottom * size.height)
                    val dim = Path().apply {
                        fillType = PathFillType.EvenOdd
                        addRect(Rect(Offset.Zero, size))
                        addRect(box)
                    }
                    drawPath(dim, Color.Black.copy(alpha = 0.6f))
                    for (k in 1..2) {
                        val x = box.left + box.width * k / 3
                        val y = box.top + box.height * k / 3
                        drawLine(Color.White.copy(alpha = 0.45f), Offset(x, box.top), Offset(x, box.bottom), 1f)
                        drawLine(Color.White.copy(alpha = 0.45f), Offset(box.left, y), Offset(box.right, y), 1f)
                    }
                    drawRect(Color.White, box.topLeft, box.size, style = Stroke(2.dp.toPx()))
                }
            }
        }
        Text("The photo itself isn’t changed.", Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp),
            color = LibraryColors.PhotoTextMuted, fontSize = 13.sp)
        Row(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("Original" to null, "Square" to 1f, "4:5" to 0.8f, "Wide" to 16f / 9f).forEach { (title, aspect) ->
                Box(
                    Modifier.height(32.dp).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(50)).clip(RoundedCornerShape(50))
                        .clickable {
                            if (aspect == null) setFrame(Whole) else {
                                val ratio = aspect / photo.aspect
                                val (sw, sh) = if (ratio <= 1f) ratio to 1f else 1f to 1f / ratio
                                setFrame(Rect(frame.center.x - sw / 2, frame.center.y - sh / 2, frame.center.x + sw / 2, frame.center.y + sh / 2))
                            }
                        }.padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(title, color = LibraryColors.PhotoText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
        LazyRow(Modifier.height(52.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp)) {
            itemsIndexed(photos, key = { _, p -> p.id }) { i, p ->
                Box(Modifier.size(40.dp, 52.dp).clip(RoundedCornerShape(3.dp)).clickable { index = i }) {
                    SetPicture(p, Modifier.fillMaxSize(), cropped = false)
                    if (i == index) Box(Modifier.fillMaxSize().border(2.dp, Color.White, RoundedCornerShape(3.dp)))
                    if (!(frames[p.id] ?: Whole).isWhole) {
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(5.dp).background(Color.White, CircleShape))
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp)) {
            Text("Reset", Modifier.clickable { setFrame(Whole) }, color = LibraryColors.PhotoText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.weight(1f))
            Text(
                "⧉ Same frame for all",
                Modifier.clickable {
                    frames = frames + photos.filter { it.aspect == photo.aspect }.associate { it.id to frame }
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                },
                color = LibraryColors.PhotoText, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

// MARK: option a, set page

/**
 * Option a, "Set page": tapping a set opens its page, the main photo large with Play, then every photo in a grid
 * (main starred, recomposed marked). A tile opens the viewer; holding one gives Make main, Recompose, Take out.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MomentSetScreenA(start: MomentSetState, viewerAt: Int? = null) {
    var set by remember { mutableStateOf(start) }
    var viewer by remember { mutableStateOf(viewerAt) }
    var viewerPlaying by remember { mutableStateOf(false) }
    var recompose by remember { mutableStateOf<Int?>(null) }
    var menuFor by remember { mutableStateOf<Int?>(null) }
    val main = set.photos[set.mainIndex]
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyVerticalGrid(
            GridCells.Fixed(3),
            Modifier.fillMaxSize(),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.statusBarsPadding().padding(start = 16.dp, end = 16.dp, top = 36.dp, bottom = 14.dp)) {
                    Text("‹", color = LibraryColors.OnBackground, fontSize = 30.sp)
                    Text(set.place, color = LibraryColors.OnBackground, fontSize = 26.sp, fontWeight = FontWeight.Bold)
                    Text(set.whenText, color = LibraryColors.Muted, fontSize = 14.sp)
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.fillMaxWidth().aspectRatio(main.shownAspect).clickable { viewerPlaying = false; viewer = set.mainIndex }) {
                    SetPicture(main, Modifier.fillMaxSize())
                    Row(
                        Modifier.align(Alignment.BottomStart).padding(12.dp).height(26.dp).background(LibraryColors.Paper, RoundedCornerShape(50))
                            .padding(horizontal = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) { Text("★ Main", color = LibraryColors.PaperInk, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                    Box(
                        Modifier.align(Alignment.BottomEnd).padding(12.dp).size(52.dp).background(LibraryColors.Paper, CircleShape)
                            .clickable { viewerPlaying = true; viewer = set.mainIndex },
                        contentAlignment = Alignment.Center,
                    ) { Text("▶", color = LibraryColors.PaperInk, fontSize = 20.sp) }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${set.photos.size} photos", color = LibraryColors.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    Text("Hold a photo for more", color = LibraryColors.Muted, fontSize = 12.sp)
                }
            }
            itemsIndexed(set.photos, key = { _, p -> p.id }) { i, p ->
                Box {
                    SetTile(
                        p, p.id == set.mainId,
                        Modifier.combinedClickable(onLongClick = { menuFor = i }) { viewerPlaying = false; viewer = i },
                    )
                    DropdownMenu(expanded = menuFor == i, onDismissRequest = { menuFor = null }) {
                        DropdownMenuItem(text = { Text("☆  Make main") }, onClick = { set = set.copy(mainId = p.id); menuFor = null })
                        DropdownMenuItem(text = { Text("⌗  Recompose") }, onClick = { recompose = i; menuFor = null })
                        DropdownMenuItem(text = { Text("⊖  Take out of this set") }, onClick = {
                            if (p.id != set.mainId) set = set.copy(photos = set.photos.filterNot { it.id == p.id })
                            menuFor = null
                        })
                    }
                }
            }
            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.navigationBarsPadding().height(32.dp)) }
        }
        viewer?.let { at ->
            SetViewer(set, { set = it }, at, strip = false, startPlaying = viewerPlaying) { viewer = null }
        }
        recompose?.let { at ->
            SetRecompose(set.photos, at, onDone = { set = set.withCrops(it) }) { recompose = null }
        }
    }
}

// MARK: option b, viewer with a scrubber

/** Option b: a set opens straight in the viewer on its main photo, with the scrubber of all its photos and a grid. */
@Composable
internal fun MomentSetScreenB(start: MomentSetState, at: Int? = null, playing: Boolean = false, recomposing: Boolean = false) {
    var set by remember { mutableStateOf(start) }
    SetViewer(set, { set = it }, at ?: set.mainIndex, strip = true, startPlaying = playing, startRecomposing = recomposing) {}
}

// MARK: option c, flip in the feed

/**
 * Option c, "Flip in the feed": the set stays one photo in Moments at its main photo, with its count and a line
 * showing where in the set it is. Hold and slide to flip through it there; the small Play plays it in place; a tap
 * opens option b's viewer.
 */
@Composable
internal fun MomentSetScreenC(start: MomentSetState) {
    var set by remember { mutableStateOf(start) }
    var shown by remember { mutableIntStateOf(start.mainIndex) }
    var scrubFrom by remember { mutableStateOf<Int?>(null) }
    var dragged by remember { mutableStateOf(0f) }
    var playing by remember { mutableStateOf(false) }
    var viewer by remember { mutableStateOf(false) }
    val haptic = LocalHapticFeedback.current
    Slideshow(playing, 0.5f, set.photos.size, shown) { shown = it }
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(Modifier.fillMaxSize()) {
            item {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(top = 40.dp, bottom = 4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Sat, Nov 7", Modifier.background(LibraryColors.Raised, RoundedCornerShape(50)).padding(horizontal = 12.dp, vertical = 4.dp),
                        color = LibraryColors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("Hod HaSharon → Herzliya · 38 photos", Modifier.padding(top = 6.dp), color = LibraryColors.Muted, fontSize = 12.sp)
                }
            }
            item { FeedLabel("Hod HaSharon park", "15:40") }
            item { SetPicture(MomentSetLab.parkBefore, Modifier.fillMaxWidth().aspectRatio(MomentSetLab.parkBefore.aspect)) }
            item { FeedLabel("Herzliya beach", "17:02 – 17:09") }
            item {
                val main = set.photos[set.mainIndex]
                val density = LocalDensity.current
                BoxWithConstraints(
                    Modifier.fillMaxWidth().aspectRatio(main.shownAspect).background(Color.Black)
                        .pointerInput(Unit) { detectTapGestures(onTap = { playing = false; viewer = true }) }
                        .pointerInput(set.photos.size) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    scrubFrom = shown; dragged = 0f; playing = false
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDragEnd = { scrubFrom = null },
                                onDragCancel = { scrubFrom = null },
                            ) { change, amount ->
                                change.consume()
                                dragged += amount.x
                                val from = scrubFrom ?: return@detectDragGesturesAfterLongPress
                                val i = (from + (dragged / with(density) { 10.dp.toPx() }).toInt()).coerceIn(0, set.photos.size - 1)
                                if (i != shown) {
                                    shown = i
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                }
                            }
                        },
                ) {
                    val maxW = maxWidth
                    val maxH = maxHeight
                    Crossfade(shown, animationSpec = tween(120), label = "feed set") { i ->
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val (w, h) = fitted(set.photos[i].shownAspect, maxW, maxH)
                            SetPicture(set.photos[i], Modifier.size(w, h))
                        }
                    }
                    Text("⧉ ${set.photos.size}", Modifier.align(Alignment.TopEnd).padding(10.dp).background(LibraryColors.BadgeScrim, RoundedCornerShape(50))
                        .padding(horizontal = 9.dp, vertical = 4.dp), color = LibraryColors.PhotoText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    if (scrubFrom != null) {
                        Text("${shown + 1} of ${set.photos.size}", Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
                            .background(LibraryColors.PillScrim, RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp),
                            color = LibraryColors.PhotoText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Box(
                        Modifier.align(Alignment.BottomStart).padding(10.dp).size(36.dp).background(LibraryColors.BadgeScrim, CircleShape)
                            .clickable { playing = !playing },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (playing) "❚❚" else "▶", color = LibraryColors.PhotoText, fontSize = 13.sp) }
                    // Where in the set: a thin line, a dot at the main photo, a mark at the one shown.
                    val lineW = maxW - 120.dp
                    val step = lineW / max(set.photos.size - 1, 1)
                    Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 22.dp).width(lineW).height(12.dp)) {
                        Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = 0.35f)))
                        Box(Modifier.align(Alignment.CenterStart).offset(x = step * set.mainIndex - 2.5.dp).size(5.dp).background(Color.White.copy(alpha = 0.8f), CircleShape))
                        Box(Modifier.align(Alignment.CenterStart).offset(x = step * shown - 1.5.dp).size(3.dp, 12.dp).background(Color.White, RoundedCornerShape(50)))
                    }
                }
            }
            item { Text("Hold and slide to flip", Modifier.padding(start = 16.dp, top = 6.dp), color = LibraryColors.Muted, fontSize = 12.sp) }
            item { FeedLabel(null, "17:30") }
            item { SetPicture(MomentSetLab.parkAfter, Modifier.fillMaxWidth().aspectRatio(MomentSetLab.parkAfter.aspect)) }
            item { Spacer(Modifier.navigationBarsPadding().height(32.dp)) }
        }
        if (viewer) SetViewer(set, { set = it }, shown, strip = true) { viewer = false }
    }
}

@Composable
private fun FeedLabel(place: String?, time: String) {
    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (place != null) Text("📍 $place  ", color = LibraryColors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Text(time, color = LibraryColors.Muted, fontSize = 13.sp)
    }
}

// MARK: option d, collect a weekend

internal enum class CollectStep { PICKING, NAMING, MADE }

/**
 * Option d, "Collect a weekend": photos from neighbouring days made into one moment. Picking in Moments as today,
 * the picks in a tray that counts the days; Make a moment asks for a name, the main photo and the day it shows on,
 * then opens its page (option a's).
 */
@Composable
internal fun MomentSetScreenD(startStep: CollectStep = CollectStep.PICKING) {
    val all = remember { MomentSetLab.days.flatMapIndexed { d, day -> day.photos.map { d to it } } }
    var picked by remember { mutableStateOf(all.filter { it.second.index % 2 == 0 }.take(5)) }
    var step by remember { mutableStateOf(startStep) }
    var name by remember { mutableStateOf("Beach weekend") }
    var mainId by remember { mutableStateOf<String?>(null) }
    var showOn by remember { mutableIntStateOf(0) }
    val haptic = LocalHapticFeedback.current
    fun toggle(entry: Pair<Int, SetPhoto>) {
        picked = if (picked.any { it.second.id == entry.second.id }) picked.filterNot { it.second.id == entry.second.id } else picked + entry
        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }
    if (step == CollectStep.MADE) {
        val days = picked.map { it.first }.toSet()
        val first = MomentSetLab.days[days.max()].title
        val last = MomentSetLab.days[days.min()].title
        MomentSetScreenA(
            MomentSetState(
                name, if (days.size == 1) first else "$first – $last", picked.map { it.second },
                mainId ?: picked.first().second.id,
            ),
        )
        return
    }
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(Modifier.fillMaxSize()) {
            item { Spacer(Modifier.statusBarsPadding().height(40.dp)) }
            MomentSetLab.days.forEachIndexed { d, day ->
                item {
                    Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(day.title, color = LibraryColors.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        Text("  ${day.place}", color = LibraryColors.Muted, fontSize = 13.sp)
                    }
                }
                items(day.photos, key = { it.id }) { p ->
                    val on = picked.any { it.second.id == p.id }
                    Box(Modifier.fillMaxWidth().aspectRatio(p.aspect).padding(bottom = 2.dp).clickable { toggle(d to p) }) {
                        SetPicture(p, Modifier.fillMaxSize())
                        if (on) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)))
                        Box(
                            Modifier.align(Alignment.TopEnd).padding(8.dp).size(24.dp)
                                .background(if (on) LibraryColors.Accent else Color.Black.copy(alpha = 0.25f), CircleShape)
                                .border(1.5.dp, Color.White, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (on) Text("✓", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            item { Spacer(Modifier.height(170.dp)) }
        }
        if (picked.isNotEmpty()) {
            val days = picked.map { it.first }.toSet().size
            Column(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(10.dp).fillMaxWidth()
                    .background(LibraryColors.Inverse, RoundedCornerShape(20.dp)).padding(vertical = 12.dp),
            ) {
                LazyRow(Modifier.height(44.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp)) {
                    items(picked, key = { it.second.id }) { entry ->
                        SetTile(entry.second, false, Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).clickable { toggle(entry) })
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Cancel", Modifier.clickable { picked = emptyList() }, color = LibraryColors.OnInverse, fontSize = 14.sp)
                    Text(
                        "${picked.size} photos · $days ${if (days == 1) "day" else "days"}", Modifier.weight(1f),
                        color = LibraryColors.OnInverse, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                    Text(
                        "Make a moment",
                        Modifier.background(LibraryColors.Accent, RoundedCornerShape(50)).clickable(enabled = picked.size >= 2) { step = CollectStep.NAMING }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
        if (step == CollectStep.NAMING) {
            BackHandler { step = CollectStep.PICKING }
            val main = mainId ?: picked.firstOrNull()?.second?.id
            val first = MomentSetLab.days[picked.maxOfOrNull { it.first } ?: 0].title
            val last = MomentSetLab.days[picked.minOfOrNull { it.first } ?: 0].title
            Column(
                Modifier.fillMaxSize().background(LibraryColors.Background).statusBarsPadding().navigationBarsPadding().padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Text("New moment", color = LibraryColors.OnBackground, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
                Text("Main photo", color = LibraryColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(picked, key = { it.second.id }) { (_, p) ->
                        SetTile(p, p.id == main, Modifier.size(64.dp).clip(RoundedCornerShape(6.dp)).clickable { mainId = p.id })
                    }
                }
                Text("Show it in Moments on", color = LibraryColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Row(Modifier.fillMaxWidth().background(LibraryColors.Raised, RoundedCornerShape(10.dp)).padding(3.dp)) {
                    listOf(first, last, "Every day it has").forEachIndexed { i, title ->
                        Box(
                            Modifier.weight(1f).height(34.dp).clip(RoundedCornerShape(8.dp))
                                .background(if (showOn == i) LibraryColors.Accent else Color.Transparent).clickable { showOn = i },
                            contentAlignment = Alignment.Center,
                        ) { Text(title, color = if (showOn == i) Color.White else LibraryColors.OnBackground, fontSize = 13.sp) }
                    }
                }
                Text(
                    if (showOn == 2) "Each day shows its own photos, with a link to the whole moment." else "Its photos leave their days and show here together.",
                    color = LibraryColors.Muted, fontSize = 12.sp,
                )
                Spacer(Modifier.weight(1f))
                Box(
                    Modifier.fillMaxWidth().height(50.dp).background(LibraryColors.Accent, RoundedCornerShape(12.dp)).clickable { step = CollectStep.MADE },
                    contentAlignment = Alignment.Center,
                ) { Text("Make it", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold) }
            }
        }
    }
}
