package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.cast.CastVideo
import com.chencohensolution.journal.cast.PhoneRect
import com.chencohensolution.journal.cast.TvZoom
import androidx.compose.ui.graphics.graphicsLayer

// The viewer while casting (docs/cast.md, docs/screens/viewer.md): the Cast button and its TV picker, the TV's screen
// on the phone as large as fits with the photo zoomed as the TV shows it (a pinch or a drag zooms both), and a video's
// controls for the TV.

/** The TV's frame on one photo while the viewer is open: [zoom] changes with each pinch or drag. */
@Stable
internal class CastZoom(photoRatio: Float, tvRatio: Float) {
    var zoom by mutableStateOf(TvZoom(photoRatio, tvRatio))
}

/** The room the photo shows in, inside an element of [size]: less [bottom] and [end] (pixels) where a set's reel is, and [top] under the header. */
internal fun castArea(size: IntSize, bottom: Float, end: Float, rtl: Boolean, top: Float = 0f): Rect =
    if (rtl) Rect(end, top, size.width.toFloat(), size.height - bottom) else Rect(0f, top, size.width - end, size.height - bottom)

/** Where a photo of [ratio] (width over height) shows fitted in [area], in the middle. */
internal fun fittedPhoto(area: Rect, ratio: Float): Rect {
    if (area.width <= 0f || area.height <= 0f || ratio <= 0f) return area
    return if (ratio >= area.width / area.height) {
        val h = area.width / ratio
        Rect(area.left, area.center.y - h / 2, area.right, area.center.y + h / 2)
    } else {
        val w = area.height * ratio
        Rect(area.center.x - w / 2, area.top, area.center.x + w / 2, area.bottom)
    }
}

/** The TV's screen on the phone: its shape as large as fits in [area] (asked Nov 10, 2026). */
internal fun tvRect(area: Rect, tvRatio: Float): Rect =
    PhoneRect.fitted(area.width, area.height, tvRatio, area.left, area.top).toRect()

/** Where the photo is drawn in [area] at [zoom]: what the TV shows fills [tvRect], the rest of the photo around it. */
internal fun castPhoto(area: Rect, zoom: TvZoom): Rect = zoom.photoOn(tvRect(area, zoom.tvRatio).toPhoneRect()).toRect()

private fun PhoneRect.toRect() = Rect(left, top, left + width, top + height)
private fun Rect.toPhoneRect() = PhoneRect(left, top, width, height)

/**
 * The photo page under it drawn where [castPhoto] puts it while casting: the phone zooms and moves with the TV. The
 * page lays the photo out fitted in its middle; this scales and moves it there.
 */
internal fun Modifier.castPlaced(zoom: TvZoom?): Modifier = if (zoom == null) this else graphicsLayer {
    val area = Rect(Offset.Zero, size)
    val fitted = fittedPhoto(area, zoom.photoRatio)
    val placed = castPhoto(area, zoom)
    if (fitted.width > 0f) {
        val k = placed.width / fitted.width
        scaleX = k
        scaleY = k
        translationX = placed.center.x - fitted.center.x
        translationY = placed.center.y - fitted.center.y
    }
}

/**
 * Pinches and drags while casting zoom and move the photo on the phone and the TV alike: two fingers zoom about their
 * middle, one finger drags the photo once it is zoomed in (at the whole photo, one finger swipes to the next photo as
 * usual). [area] is where the photo is laid out inside this element, for its size; [cast] null leaves every touch to
 * the rest.
 */
internal fun Modifier.castGestures(cast: () -> CastZoom?, area: (IntSize) -> Rect, onStart: () -> Unit = {}): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var started = false
        var dragged = 0f
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val holder = cast() ?: continue
            val zoom = holder.zoom
            val fingers = event.changes.count { it.pressed }
            if (fingers < 2 && !zoom.isZoomed) continue
            val factor = event.calculateZoom()
            val pan = event.calculatePan()
            if (!started && fingers < 2) dragged += pan.getDistance()
            if ((factor == 1f && pan == Offset.Zero) || !(started || fingers >= 2 || dragged > viewConfiguration.touchSlop)) continue
            if (!started) onStart()
            started = true
            val photo = castPhoto(area(size), zoom)
            if (photo.width <= 0f || photo.height <= 0f) continue
            val centroid = event.calculateCentroid()
            var next = zoom
            if (fingers >= 2 && factor != 1f) {
                next = next.zoomBy(factor, (centroid.x - photo.left) / photo.width, (centroid.y - photo.top) / photo.height)
            }
            // The photo follows the fingers, as when zooming without a TV: dragging it right shows more of its left.
            val moved = castPhoto(area(size), next)
            next = next.panBy(-pan.x / moved.width, -pan.y / moved.height)
            holder.zoom = next
            event.changes.forEach { if (it.positionChanged()) it.consume() }
        } while (event.changes.any { it.pressed })
    }
}

/**
 * The TV's screen on the phone: the TV's shape as large as fits ([tvRect]), outlined, "TV" in its corner, and the
 * photo outside it dimmed. Fills the same room as the photo page under it.
 */
@Composable
internal fun CastFrameOverlay(zoom: TvZoom, modifier: Modifier = Modifier) {
    val label = stringResource(R.string.cast_frame_label)
    val measurer = androidx.compose.ui.text.rememberTextMeasurer()
    // Read here: the palette is read in composition, not while drawing.
    val pill = LibraryColors.PillScrim
    Canvas(modifier) {
        val frame = tvRect(Rect(Offset.Zero, size), zoom.tvRatio)
        val dim = Color.Black.copy(alpha = 0.6f)
        drawRect(dim, Offset.Zero, Size(size.width, frame.top.coerceAtLeast(0f)))
        drawRect(dim, Offset(0f, frame.bottom), Size(size.width, (size.height - frame.bottom).coerceAtLeast(0f)))
        drawRect(dim, Offset(0f, frame.top), Size(frame.left.coerceAtLeast(0f), frame.height.coerceAtLeast(0f)))
        drawRect(dim, Offset(frame.right, frame.top), Size((size.width - frame.right).coerceAtLeast(0f), frame.height.coerceAtLeast(0f)))
        val stroke = 2.dp.toPx()
        drawRect(Color.White.copy(alpha = 0.8f), frame.topLeft, frame.size, style = Stroke(stroke))
        val text = measurer.measure(label, androidx.compose.ui.text.TextStyle(color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold))
        val pad = 6.dp.toPx()
        val at = Offset(
            (frame.left + 2 * pad).coerceIn(pad, size.width - text.size.width - pad),
            (frame.top + 2 * pad).coerceIn(pad, size.height - text.size.height - pad),
        )
        drawRoundRect(
            pill, at - Offset(pad, pad / 2), Size(text.size.width + 2 * pad, text.size.height + pad),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(text.size.height.toFloat()),
        )
        drawText(text, topLeft = at)
    }
}

/** The Cast button in the viewer's top bar: the TV picker; lit while casting. Nothing when Cast can't run here. */
@Composable
internal fun CastButton() {
    val context = LocalContext.current
    if (!remember { JournalCast.available(context) }) return
    val state by JournalCast.controller.state.collectAsState()
    var picking by remember { mutableStateOf(false) }
    IconButton(onClick = { picking = true }) {
        Icon(
            if (state.connected) LibraryIcons.CastOn else LibraryIcons.Cast,
            stringResource(if (state.connected) R.string.cast_on_tv else R.string.cast_button),
            tint = Color.White,
        )
    }
    if (picking) CastSheet(onDismiss = { picking = false })
}

/**
 * While casting, a bar under the viewer's header: Info shows the photo's note, date and place over it on the TV, and
 * Map its place on a map there (only for a photo with a place). Each stays on for the next photos until tapped again.
 */
@Composable
internal fun CastOverlayBar(hasPlace: Boolean) {
    val cast = JournalCast.controller
    val state by cast.state.collectAsState()
    if (!state.connected) return
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OverlayChip(LibraryIcons.Info, R.string.cast_info_chip, if (state.infoOnTv) R.string.cast_info_off else R.string.cast_info_on, state.infoOnTv) {
            cast.showInfo(!state.infoOnTv)
        }
        if (hasPlace || state.mapOnTv) {
            OverlayChip(LibraryIcons.Map, R.string.cast_map_chip, if (state.mapOnTv) R.string.cast_map_off else R.string.cast_map_on, state.mapOnTv) {
                cast.showMap(!state.mapOnTv)
            }
        }
    }
}

/** A pill with an icon and a word, filled in while on. */
@Composable
private fun OverlayChip(icon: androidx.compose.ui.graphics.vector.ImageVector, label: Int, description: Int, on: Boolean, onClick: () -> Unit) {
    val said = stringResource(description)
    Row(
        Modifier
            .background(if (on) LibraryColors.Accent.copy(alpha = 0.8f) else LibraryColors.PillScrim, androidx.compose.foundation.shape.CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = said }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(18.dp))
        Text(stringResource(label), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The TVs on the Wi-Fi to cast to, or the TV cast to with Stop. */
@Composable
private fun CastSheet(onDismiss: () -> Unit) {
    val context = LocalContext.current
    DisposableEffect(Unit) {
        JournalCast.lookForTvs(context)
        onDispose { JournalCast.stopLooking() }
    }
    val tvs by JournalCast.tvs.collectAsState()
    val state by JournalCast.controller.state.collectAsState()
    PageSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(if (state.connected) R.string.cast_on_tv else R.string.cast_title),
                color = LibraryColors.OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 24.dp),
            )
            Spacer(Modifier.height(12.dp))
            if (state.connected) {
                Text(
                    state.device ?: stringResource(R.string.cast_a_tv), color = LibraryColors.OnBackground, fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                Text(
                    stringResource(R.string.cast_frame_hint), color = LibraryColors.Muted, fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
                Spacer(Modifier.height(16.dp))
                SheetRow(stringResource(R.string.cast_stop), LibraryIcons.Close) {
                    JournalCast.stopCasting()
                    onDismiss()
                }
            } else if (tvs.isEmpty()) {
                Row(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(color = LibraryColors.Muted, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.cast_looking), color = LibraryColors.Muted, fontSize = 15.sp)
                }
            } else {
                tvs.forEach { tv ->
                    SheetRow(tv.name, LibraryIcons.Cast, busy = tv.connecting) {
                        JournalCast.castTo(tv)
                        onDismiss()
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetRow(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, busy: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (busy) CircularProgressIndicator(color = LibraryColors.Accent, strokeWidth = 2.dp, modifier = Modifier.size(22.dp))
        else Icon(icon, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(22.dp))
        Text(label, color = LibraryColors.OnBackground, fontSize = 16.sp)
    }
}

/** A video's controls while it plays on the TV: play or pause, and where it is (drag to go there). */
@Composable
internal fun CastVideoControls(video: CastVideo?, onPlay: () -> Unit, onPause: () -> Unit, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    var seeking by remember { mutableStateOf<Float?>(null) }
    val rtl = androidx.compose.ui.platform.LocalLayoutDirection.current == androidx.compose.ui.unit.LayoutDirection.Rtl
    fun at(x: Float, width: Int) = (x / width).coerceIn(0f, 1f).let { if (rtl) 1f - it else it }
    val duration = video?.durationMillis ?: 0L
    val fraction = seeking ?: if (duration > 0) (video!!.positionMillis.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(40.dp).clip(CircleShape).background(LibraryColors.PillScrim)
                .clickable { if (video?.playing == true) onPause() else onPlay() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (video?.playing == true) LibraryIcons.Pause else LibraryIcons.Play,
                stringResource(if (video?.playing == true) R.string.cast_pause else R.string.cast_play),
                tint = Color.White, modifier = Modifier.size(18.dp),
            )
        }
        Text(castClock(((seeking?.let { it * duration } ?: video?.positionMillis?.toFloat()) ?: 0f).toLong()), color = Color.White, fontSize = 12.sp)
        Box(
            Modifier.weight(1f).height(28.dp).pointerInput(duration, rtl) {
                if (duration <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    seeking = at(down.position.x, size.width)
                    do {
                        val event = awaitPointerEvent()
                        event.changes.firstOrNull()?.let { seeking = at(it.position.x, size.width); it.consume() }
                    } while (event.changes.any { it.pressed })
                    seeking?.let { onSeek((it * duration).toLong()) }
                    seeking = null
                }
            },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f), RoundedCornerShape(2.dp)))
            Box(Modifier.fillMaxWidth(fraction).height(3.dp).background(Color.White, RoundedCornerShape(2.dp)))
        }
        Text(castClock(duration), color = Color.White, fontSize = 12.sp)
    }
}

private fun castClock(millis: Long): String {
    val s = (millis / 1000).coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}
