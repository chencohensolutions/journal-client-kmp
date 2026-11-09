package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.border
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.stories.CropHandle
import com.chencohensolution.journal.stories.CropKind
import com.chencohensolution.journal.stories.CropRect
import com.chencohensolution.journal.stories.PhotoCrops
import kotlin.math.roundToInt

// Crops are only for looking: the photo itself is never changed. See docs/screens/crop.md; the geometry (moving,
// pinching, dragging a corner, fitting another screen) is PhotoCrops.kt in the shared logic.

internal class CropActions(
    val close: () -> Unit = {},
    val select: (CropKind) -> Unit = {},
    /** Clears the crop of the mode shown. */
    val reset: () -> Unit = {},
    val done: () -> Unit = {},
    /** The frame moved, pinched or dragged by a handle. */
    val change: (CropRect) -> Unit = {},
)

/** This phone's screen held upright (width / height), whichever way it is held now. */
@Composable
internal fun uprightScreenRatio(): Float {
    val size = LocalWindowInfo.current.containerSize
    if (size.width <= 0 || size.height <= 0) return 9f / 19.5f
    return minOf(size.width, size.height).toFloat() / maxOf(size.width, size.height)
}

/** The shape (width / height) a crop of [kind] is locked to on a screen of [screenRatio] upright, or null for any. */
internal fun lockedRatio(kind: CropKind, screenRatio: Float): Float? = when (kind) {
    CropKind.MOMENTS -> null
    CropKind.PORTRAIT -> screenRatio
    CropKind.LANDSCAPE -> 1 / screenRatio
}

/**
 * The crop screen with its state: [saved] are the photo's crops, edited here until Done hands back the new ones
 * ([onDone]). A mode opened here is saved as shown, its suggestion too, unless it was reset; Moments' suggestion (the
 * whole photo) is no crop. Opens on [startKind].
 */
@Composable
internal fun CropEditor(item: MediaItem, saved: PhotoCrops, startKind: CropKind, onDone: (PhotoCrops) -> Unit, onClose: () -> Unit) {
    val screenRatio = uprightScreenRatio()
    var kind by rememberSaveable { mutableStateOf(startKind) }
    var drafts by rememberSaveable(stateSaver = CropsSaver) { mutableStateOf(saved) }
    // The modes opened here (and not reset since): their suggestion is kept on Done.
    var opened by rememberSaveable { mutableStateOf(listOf(startKind.name)) }
    val photoRatio = item.ratio
    fun suggestion(mode: CropKind) = when (mode) {
        CropKind.MOMENTS -> CropRect.WHOLE
        CropKind.PORTRAIT -> CropRect.largest(screenRatio, photoRatio, around = drafts.moments ?: drafts.landscape)
        CropKind.LANDSCAPE -> CropRect.largest(1 / screenRatio, photoRatio, around = drafts.moments ?: drafts.portrait)
    }
    // A saved crop made for another screen is shown fitted to this one; an unset one starts from a suggestion.
    val frame = drafts[kind]?.let { rect -> lockedRatio(kind, screenRatio)?.let { rect.fittedTo(it, photoRatio) } ?: rect }
        ?: suggestion(kind)
    val result = CropKind.entries.fold(drafts) { crops, mode ->
        if (crops[mode] != null || mode.name !in opened || mode == CropKind.MOMENTS) crops else crops.with(mode, suggestion(mode))
    }
    CropScreen(
        item,
        kind,
        frame,
        result,
        screenRatio,
        CropActions(
            close = onClose,
            select = {
                kind = it
                if (it.name !in opened) opened = opened + it.name
            },
            reset = {
                drafts = drafts.with(kind, null)
                opened = opened - kind.name
            },
            done = { if (result == saved) onClose() else onDone(result) },
            change = {
                drafts = drafts.with(kind, it)
                if (kind.name !in opened) opened = opened + kind.name
            },
        ),
    )
}

/**
 * The crop screen: [item] fitted on black with [frame] (the crop for [kind]) over it, and a switch between the three
 * crops, a dot on each one set in [saved]. [screenRatio] is this phone's screen held upright (width / height), which
 * Portrait and Landscape are locked to.
 */
@Composable
internal fun CropScreen(
    item: MediaItem,
    kind: CropKind,
    frame: CropRect,
    saved: PhotoCrops,
    screenRatio: Float,
    actions: CropActions,
) {
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = actions.close) {
                Icon(LibraryIcons.Close, stringResource(R.string.library_cancel), tint = Color.White)
            }
            Text(
                stringResource(R.string.crop_title),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = actions.reset, enabled = saved[kind] != null) {
                Text(
                    stringResource(R.string.crop_reset),
                    color = if (saved[kind] != null) Color.White else Color.White.copy(alpha = 0.35f),
                )
            }
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .height(36.dp)
                    .background(LibraryColors.Accent, RoundedCornerShape(50))
                    .clickable(onClick = actions.done)
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.crop_done), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
            CropCanvas(item, frame, lockedRatio(kind, screenRatio), actions.change)
        }

        Text(
            stringResource(
                when (kind) {
                    CropKind.MOMENTS -> R.string.crop_hint_moments
                    CropKind.PORTRAIT -> R.string.crop_hint_portrait
                    CropKind.LANDSCAPE -> R.string.crop_hint_landscape
                },
            ),
            color = Color(0xFFB8C2BF),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Text(
            stringResource(R.string.crop_untouched),
            color = Color(0xFF7E8987),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        )

        CropModes(kind, saved, screenRatio, actions.select, Modifier.padding(horizontal = 16.dp, vertical = 14.dp))
    }
}

/**
 * The crop screen's set mode (Recompose, from the viewer of a set): each photo's Moments crop, the set's strip to go
 * between its photos without leaving, the shape chips (Original, Square, 4:5, Wide) and Same frame for all (every photo
 * of the same shape gets the frame shown, so the set flips without jumping). [saved] are the photos' Moments crops;
 * Done hands back all of them ([onDone]), or just closes when nothing changed.
 */
@Composable
internal fun SetCropEditor(
    items: List<MediaItem>,
    saved: Map<String, CropRect?>,
    startId: String,
    onDone: (Map<String, CropRect?>) -> Unit,
    onClose: () -> Unit,
) {
    val photos = items.filter { it.durationMillis == null }
    if (photos.isEmpty()) return onClose()
    var index by rememberSaveable { mutableStateOf(photos.indexOfFirst { it.id == startId }.coerceAtLeast(0)) }
    var drafts by remember { mutableStateOf(photos.associate { it.id to saved[it.id] }) }
    val item = photos[index.coerceIn(photos.indices)]
    val frame = drafts[item.id] ?: CropRect.WHOLE
    fun set(rect: CropRect?) {
        drafts = drafts + (item.id to rect?.takeUnless { it.isWhole() })
    }
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    val result = photos.associate { it.id to drafts[it.id] }
    Column(Modifier.fillMaxSize().background(Color.Black).statusBarsPadding().navigationBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) {
                Icon(LibraryIcons.Close, stringResource(R.string.library_cancel), tint = Color.White)
            }
            Text(
                stringResource(R.string.set_recompose),
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { set(null) }, enabled = drafts[item.id] != null) {
                Text(stringResource(R.string.crop_reset), color = if (drafts[item.id] != null) Color.White else Color.White.copy(alpha = 0.35f))
            }
            Box(
                Modifier
                    .padding(end = 8.dp)
                    .height(36.dp)
                    .background(LibraryColors.Accent, RoundedCornerShape(50))
                    .clickable { if (photos.all { saved[it.id] == result[it.id] }) onClose() else onDone(result) }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.crop_done), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp), contentAlignment = Alignment.Center) {
            CropCanvas(item, frame, null) { set(it) }
        }
        Text(
            stringResource(R.string.recompose_hint),
            color = Color(0xFFB8C2BF),
            fontSize = 13.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
        )
        Text(
            stringResource(R.string.crop_untouched),
            color = Color(0xFF7E8987),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 4.dp),
        )
        Row(
            Modifier.fillMaxWidth().padding(top = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            listOf(
                R.string.recompose_shape_original to null,
                R.string.recompose_shape_square to 1f,
                R.string.recompose_shape_portrait to 0.8f,
                R.string.recompose_shape_wide to 16f / 9f,
            ).forEach { (label, shape) ->
                val on = if (shape == null) drafts[item.id] == null else sameShape(frame.ratioOn(item.ratio), shape)
                Box(
                    Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(50))
                        .background(if (on) Color.White else Color.White.copy(alpha = 0.14f))
                        .clickable { set(shape?.let { CropRect.largest(it, item.ratio, around = frame).copy(aspect = null) }) }
                        .padding(horizontal = 14.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(stringResource(label), color = if (on) Color.Black else Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        if (photos.size > 1) {
            androidx.compose.foundation.lazy.LazyRow(
                Modifier.fillMaxWidth().padding(top = 14.dp).height(56.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp),
            ) {
                items(photos.size, key = { photos[it].id }) { i ->
                    val photo = photos[i]
                    Box(Modifier.size(42.dp, 56.dp).clip(RoundedCornerShape(4.dp)).clickable { index = i }) {
                        SetThumb(photo, Modifier.fillMaxSize())
                        if (drafts[photo.id] != null) {
                            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 3.dp).size(5.dp).background(Color.White, CircleShape))
                        }
                        if (i == index) Box(Modifier.fillMaxSize().border(2.dp, Color.White, RoundedCornerShape(4.dp)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = {
                    val shown = drafts[item.id]
                    drafts = drafts + photos.filter { sameShape(it.ratio, item.ratio) }.associate { it.id to shown }
                    haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                }) {
                    Text("⧉  " + stringResource(R.string.recompose_same_frame), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        } else {
            Box(Modifier.height(16.dp))
        }
    }
}

private fun CropRect.isWhole(): Boolean = x < 0.001f && y < 0.001f && w > 0.999f && h > 0.999f

/**
 * The photo fitted in the space, dimmed outside [frame]. Dragging moves the frame and pinching sizes it; the handles
 * drag a corner (keeping [locked], the shape width / height, when set) or, for a free crop, a side too.
 */
@Composable
private fun CropCanvas(item: MediaItem, frame: CropRect, locked: Float?, onChange: (CropRect) -> Unit) {
    val current by rememberUpdatedState(frame)
    val change by rememberUpdatedState(onChange)
    val photoRatio = item.ratio
    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val boxRatio = maxWidth / maxHeight
        val width = if (photoRatio >= boxRatio) maxWidth else maxHeight * photoRatio
        val height = if (photoRatio >= boxRatio) maxWidth / photoRatio else maxHeight
        val density = LocalDensity.current
        val widthPx = with(density) { width.toPx() }
        val heightPx = with(density) { height.toPx() }
        Box(Modifier.size(width, height)) {
            rememberThumbnail(item, THUMB_FULL)?.let {
                Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Canvas(
                Modifier
                    .fillMaxSize()
                    .pointerInput(widthPx, heightPx) {
                        detectTransformGestures { _, pan, zoom, _ ->
                            change(current.moved(pan.x / widthPx, pan.y / heightPx).scaled(1 / zoom))
                        }
                    },
            ) { drawFrame(frame, free = locked == null) }
            val touch = 44.dp
            val half = with(density) { (touch / 2).toPx() }
            CropHandle.entries.filter { locked == null || it.isCorner }.forEach { handle ->
                val hx = when {
                    handle.left -> frame.x
                    handle.right -> frame.x + frame.w
                    else -> frame.x + frame.w / 2
                } * widthPx
                val hy = when {
                    handle.top -> frame.y
                    handle.bottom -> frame.y + frame.h
                    else -> frame.y + frame.h / 2
                } * heightPx
                Box(
                    Modifier
                        .offset { IntOffset((hx - half).roundToInt(), (hy - half).roundToInt()) }
                        .size(touch)
                        .pointerInput(handle, widthPx, heightPx, locked) {
                            detectDragGestures { pointer, drag ->
                                pointer.consume()
                                change(current.resized(handle, drag.x / widthPx, drag.y / heightPx, photoRatio, locked))
                            }
                        },
                )
            }
        }
    }
}

private fun DrawScope.drawFrame(frame: CropRect, free: Boolean) {
    val left = frame.x * size.width
    val top = frame.y * size.height
    val right = left + frame.w * size.width
    val bottom = top + frame.h * size.height
    val dim = Color.Black.copy(alpha = 0.6f)
    // Outside the frame: above, below, then the two sides between.
    drawRect(dim, Offset.Zero, Size(size.width, top))
    drawRect(dim, Offset(0f, bottom), Size(size.width, size.height - bottom))
    drawRect(dim, Offset(0f, top), Size(left, bottom - top))
    drawRect(dim, Offset(right, top), Size(size.width - right, bottom - top))

    val thirds = Color.White.copy(alpha = 0.35f)
    val line = 1.dp.toPx()
    for (i in 1..2) {
        val x = left + (right - left) * i / 3
        val y = top + (bottom - top) * i / 3
        drawLine(thirds, Offset(x, top), Offset(x, bottom), line)
        drawLine(thirds, Offset(left, y), Offset(right, y), line)
    }
    drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(1.5.dp.toPx()))

    // Corner handles; a free crop also has one in the middle of each side.
    val arm = 18.dp.toPx()
    val thick = 3.5.dp.toPx()
    listOf(Offset(left, top) to Offset(1f, 1f), Offset(right, top) to Offset(-1f, 1f), Offset(left, bottom) to Offset(1f, -1f), Offset(right, bottom) to Offset(-1f, -1f))
        .forEach { (corner, dir) ->
            drawLine(Color.White, corner, corner + Offset(arm * dir.x, 0f), thick, StrokeCap.Square)
            drawLine(Color.White, corner, corner + Offset(0f, arm * dir.y), thick, StrokeCap.Square)
        }
    if (free) {
        val half = arm / 2
        val midX = (left + right) / 2
        val midY = (top + bottom) / 2
        drawLine(Color.White, Offset(midX - half, top), Offset(midX + half, top), thick)
        drawLine(Color.White, Offset(midX - half, bottom), Offset(midX + half, bottom), thick)
        drawLine(Color.White, Offset(left, midY - half), Offset(left, midY + half), thick)
        drawLine(Color.White, Offset(right, midY - half), Offset(right, midY + half), thick)
    }
}

/** Moments (any shape), Portrait and Landscape (the screen's shape), each with its shape drawn and a dot once set. */
@Composable
private fun CropModes(kind: CropKind, saved: PhotoCrops, screenRatio: Float, onSelect: (CropKind) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().background(Color.White.copy(alpha = 0.08f), RoundedCornerShape(18.dp)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        CropKind.entries.forEach { mode ->
            val on = mode == kind
            Column(
                Modifier
                    .weight(1f)
                    .background(if (on) Color.White else Color.Transparent, RoundedCornerShape(14.dp))
                    .clickable { onSelect(mode) }
                    .semantics {
                        role = Role.Tab
                        selected = on
                    }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                val ink = if (on) Color(0xFF17201F) else Color.White
                Canvas(Modifier.size(24.dp)) { drawShapeIcon(lockedRatio(mode, screenRatio), ink) }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(
                        stringResource(
                            when (mode) {
                                CropKind.MOMENTS -> R.string.crop_mode_moments
                                CropKind.PORTRAIT -> R.string.crop_mode_portrait
                                CropKind.LANDSCAPE -> R.string.crop_mode_landscape
                            },
                        ),
                        color = ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (saved[mode] != null) Box(Modifier.size(6.dp).background(if (on) LibraryColors.Accent else Color(0xFF8FD3C7), CircleShape))
                }
            }
        }
    }
}

/** A rectangle of [ratio] (width / height); null draws a free shape: four corners only. */
private fun DrawScope.drawShapeIcon(ratio: Float?, color: Color) {
    val stroke = 1.8.dp.toPx()
    if (ratio == null) {
        val w = size.width * 0.9f
        val h = size.height * 0.68f
        val left = (size.width - w) / 2
        val top = (size.height - h) / 2
        val arm = size.width * 0.22f
        listOf(Offset(left, top) to Offset(1f, 1f), Offset(left + w, top) to Offset(-1f, 1f), Offset(left, top + h) to Offset(1f, -1f), Offset(left + w, top + h) to Offset(-1f, -1f))
            .forEach { (corner, dir) ->
                drawLine(color, corner, corner + Offset(arm * dir.x, 0f), stroke, StrokeCap.Round)
                drawLine(color, corner, corner + Offset(0f, arm * dir.y), stroke, StrokeCap.Round)
            }
        return
    }
    val w = if (ratio >= 1f) size.width else size.height * ratio
    val h = if (ratio >= 1f) size.width / ratio else size.height
    drawRoundRect(color, Offset((size.width - w) / 2, (size.height - h) / 2), Size(w, h), CornerRadius(2.dp.toPx()), style = Stroke(stroke))
}

/**
 * Shows the [frame] part of [content] filling this box, which must have the frame's shape: the content is laid out at
 * the whole photo's size and moved so the frame lands on the box.
 */
@Composable
internal fun CropBox(frame: CropRect, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier.clipToBounds()) { measurables, constraints ->
        val width = constraints.maxWidth
        val height = constraints.maxHeight
        val fullWidth = (width / frame.w).roundToInt()
        val fullHeight = (height / frame.h).roundToInt()
        val placeables = measurables.map { it.measure(Constraints.fixed(fullWidth, fullHeight)) }
        layout(width, height) {
            placeables.forEach { it.place((-frame.x * fullWidth).roundToInt(), (-frame.y * fullHeight).roundToInt()) }
        }
    }
}

/** A photo's shape (width / height), 4:3 when unknown. */
internal val MediaItem.ratio: Float
    get() = if (width > 0 && height > 0) width.toFloat() / height else 4f / 3f

/** Kept across rotation: 15 numbers, NaN for a crop not set. */
private val CropsSaver = Saver<PhotoCrops, FloatArray>(
    save = { crops ->
        CropKind.entries.flatMap { kind ->
            crops[kind]?.let { listOf(it.x, it.y, it.w, it.h, it.aspect ?: -1f) } ?: List(5) { Float.NaN }
        }.toFloatArray()
    },
    restore = { values ->
        CropKind.entries.foldIndexed(PhotoCrops()) { i, crops, kind ->
            val v = values.copyOfRange(i * 5, i * 5 + 5)
            if (v[0].isNaN()) crops else crops.with(kind, CropRect(v[0], v[1], v[2], v[3], v[4].takeIf { it > 0 }))
        }
    },
)
