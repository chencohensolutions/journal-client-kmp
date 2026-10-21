package com.chencohensolution.journal.ui.library

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.chencohensolution.journal.stories.CropRect
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt
import com.chencohensolution.journal.library.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The zoom of the photo the viewer shows: [scale] times its fitted size (1 is fitted), moved by [offset] pixels from the
 * centre of the screen. Zoomed in at most twice past the photo's own pixels (and never less than filling the screen).
 * [restoring] is where it was left last time ([zoomedPart]); [resume] glides there once the photo has opened.
 */
@Stable
internal class PhotoZoom(private val scope: CoroutineScope, private var restoring: CropRect? = null) {
    var scale by mutableFloatStateOf(1f)
        private set
    var offset by mutableStateOf(Offset.Zero)
        private set

    /** The screen, and the photo's fitted size in it; set by [layout]. */
    private var viewport = Size.Zero
    private var fitted = Size.Zero

    /** The longer side of the photo's own pixels (the original's, not the decoded image's). */
    private var pixelSide = 0

    private var animation: kotlinx.coroutines.Job? = null

    val isZoomed: Boolean get() = scale > 1.01f

    /**
     * The smallest picture of the whole photo its page has ready (the preview it opened with, else the image shown),
     * for the minimap: set by the page, so the minimap never depends on what happens to be cached.
     */
    var picture by mutableStateOf<ImageBitmap?>(null)

    /** The photo page's check of whether what is on screen is at the original's quality ([ReportQuality]). */
    var quality by mutableStateOf<State<Boolean>?>(null)

    /** Read straight from the page's check, so it is right on the very frame the zoom changes: the minimap is grey until it is true. */
    val atOriginalQuality: Boolean get() = quality?.value ?: false

    fun layout(viewport: Size, photoRatio: Float, pixelSide: Int) {
        this.viewport = viewport
        this.pixelSide = pixelSide
        fitted = if (viewport.width / viewport.height > photoRatio) {
            Size(viewport.height * photoRatio, viewport.height)
        } else {
            Size(viewport.width, viewport.width / photoRatio)
        }
        if (resumeWaiting) {
            resumeWaiting = false
            resume(resumeFrom)
        }
    }

    /** Whether it has a zoom from last time still to [resume] to. */
    val hasSaved: Boolean get() = restoring != null

    private var resumeWaiting = false
    private var resumeFrom: CropRect? = null

    /**
     * From what the photo shows now (fitted, or its crop [from]: the same view, drawn as a zoom of the whole photo),
     * glides to where it was left last time. Waits for the first layout when there hasn't been one.
     */
    fun resume(from: CropRect?) {
        val part = restoring ?: return
        if (fitted.isEmpty()) {
            resumeWaiting = true
            resumeFrom = from
            return
        }
        restoring = null
        if (from != null) {
            val start = scaleShowing(from)
            scale = start
            offset = clamp(offsetFor(middleOf(from), start), start)
        }
        glideTo(part)
    }

    /** Fitted, filling the screen, and the original's own size: what a double-tap steps through and a pinch snaps to. */
    private val levels: List<Float> get() {
        if (fitted.isEmpty()) return listOf(1f)
        val fill = max(viewport.width / fitted.width, viewport.height / fitted.height)
        val original = if (pixelSide > 0) pixelSide / max(fitted.width, fitted.height) else 1f
        // A small photo's own size can be below fitted or near filling: those steps are left out.
        return listOf(1f, fill, original).filter { it >= 1f && it <= maxScale }.sorted()
            .fold(emptyList()) { kept, level -> if (kept.isNotEmpty() && level < kept.last() * 1.05f) kept else kept + level }
    }

    private val maxScale: Float get() {
        if (fitted.isEmpty()) return 1f
        val original = if (pixelSide > 0) pixelSide / max(fitted.width, fitted.height) else 1f
        val fill = max(viewport.width / fitted.width, viewport.height / fitted.height)
        return max(max(2 * original, fill), 1f)
    }

    /** Pinching and dragging: [zoom] times bigger around [focus] (from the screen's centre), moved by [pan]. */
    fun transform(focus: Offset, zoom: Float, pan: Offset) {
        animation?.cancel()
        // Below fitted while pinching is allowed (it springs back in [settle]); past the maximum is not.
        val next = (scale * zoom).coerceIn(0.7f, maxScale)
        offset = clamp(focus - (focus - offset) * (next / scale) + pan, next)
        scale = next
    }

    /**
     * Fingers lifted: back to fitted when pinched smaller than that; after a pinch ([pinched]), to fitted, filling the
     * screen or the original's size when within [SNAP] of one, around [focus] (where the fingers were).
     */
    fun settle(pinched: Boolean, focus: Offset) {
        if (scale < 1f) return zoomTo(1f)
        if (!pinched) return
        val near = levels.minByOrNull { abs(ln(scale / it)) } ?: return
        if (abs(ln(scale / near)) < ln(SNAP) && abs(scale - near) > 0.001f) zoomTo(near, focus)
    }

    /** Double-tap: the next of fitted → filling the screen → the original's size around [focus], then back to fitted. */
    fun step(focus: Offset) {
        val next = levels.firstOrNull { it > scale * 1.02f }
        if (next == null) zoomTo(1f) else zoomTo(next, focus)
    }

    /** Animates to [target] times the fitted size, keeping the point under [focus] (from the screen's centre) where it is. */
    private fun zoomTo(target: Float, focus: Offset = Offset.Zero) {
        val targetOffset = clamp(focus - (focus - offset) * (target / scale), target)
        val from = scale
        val fromOffset = offset
        animation?.cancel()
        animation = scope.launch {
            val progress = Animatable(0f)
            progress.animateTo(1f, tween(ZOOM_MILLIS)) {
                scale = from + (target - from) * value
                offset = fromOffset + (targetOffset - fromOffset) * value
            }
        }
    }

    /** How many screen pixels the photo's longer side takes now. */
    val shownLongerSide: Float get() = max(fitted.width, fitted.height) * scale

    /** How many screen pixels the photo's width takes now. */
    val shownWidth: Float get() = fitted.width * scale

    /**
     * The part of the photo on screen now, widened by [margin] of its size on each side, as fractions (0..1) of the
     * photo's width and height; null before the first layout.
     */
    fun visibleRect(margin: Float): Rect? {
        if (fitted.isEmpty() || viewport.isEmpty()) return null
        // A screen point p shows the fitted photo's point c + (p - c - offset) / scale, c the screen's centre.
        val centre = Offset(viewport.width / 2, viewport.height / 2)
        val photoLeft = centre.x - fitted.width / 2
        val photoTop = centre.y - fitted.height / 2
        fun across(x: Float) = (centre.x + (x - centre.x - offset.x) / scale - photoLeft) / fitted.width
        fun down(y: Float) = (centre.y + (y - centre.y - offset.y) / scale - photoTop) / fitted.height
        val left = across(0f)
        val right = across(viewport.width)
        val top = down(0f)
        val bottom = down(viewport.height)
        val dx = (right - left) * margin
        val dy = (bottom - top) * margin
        return Rect(
            (left - dx).coerceIn(0f, 1f), (top - dy).coerceIn(0f, 1f), (right + dx).coerceIn(0f, 1f), (bottom + dy).coerceIn(0f, 1f),
        ).takeIf { it.width > 0f && it.height > 0f }
    }

    /** What to keep in the photo's note when it is left: the part on screen (null when fitted, or not laid out yet). */
    val zoomedPart: CropRect? get() {
        restoring?.let { return it }
        if (!isZoomed) return null
        val part = visibleRect(0f) ?: return null
        return CropRect(part.left, part.top, part.width, part.height, viewport.width / viewport.height)
    }

    /**
     * Animates until [part] fills the screen as far as it can, centred on it (another screen keeps its middle): the
     * scale changes evenly in steps of doubling, not of size, and the middle slides with it, so it reads as one move.
     */
    private fun glideTo(part: CropRect) {
        if (fitted.isEmpty() || part.w <= 0f || part.h <= 0f) return
        val fromScale = scale
        val fromMiddle = middleNow()
        val toScale = scaleShowing(part)
        val toMiddle = middleOf(part)
        animation?.cancel()
        animation = scope.launch {
            val progress = Animatable(0f)
            progress.animateTo(1f, tween(RESUME_MILLIS, easing = FastOutSlowInEasing)) {
                val now = fromScale * (toScale / fromScale).pow(value)
                scale = now
                offset = clamp(offsetFor(fromMiddle + (toMiddle - fromMiddle) * value, now), now)
            }
        }
    }

    /** The zoom at which [part] fills the screen as far as it can. */
    private fun scaleShowing(part: CropRect): Float =
        min(viewport.width / (part.w * fitted.width), viewport.height / (part.h * fitted.height)).coerceIn(1f, maxScale)

    /** The middle of [part], as fractions of the photo. */
    private fun middleOf(part: CropRect) = Offset(part.x + part.w / 2, part.y + part.h / 2)

    /** The point of the photo (fractions) at the screen's centre now. */
    private fun middleNow(): Offset = Offset(0.5f - offset.x / scale / fitted.width, 0.5f - offset.y / scale / fitted.height)

    /** The offset that puts [middle] (fractions of the photo) at the screen's centre at [scale]. */
    private fun offsetFor(middle: Offset, scale: Float) =
        Offset((0.5f - middle.x) * fitted.width * scale, (0.5f - middle.y) * fitted.height * scale)

    /** Within the photo's edges: a side smaller than the screen stays centred. */
    private fun clamp(offset: Offset, scale: Float): Offset {
        val maxX = max(0f, (fitted.width * scale - viewport.width) / 2)
        val maxY = max(0f, (fitted.height * scale - viewport.height) / 2)
        return Offset(offset.x.coerceIn(-maxX, maxX), offset.y.coerceIn(-maxY, maxY))
    }
}

private const val ZOOM_MILLIS = 250

/** The glide from the opened photo to where it was zoomed last time. */
private const val RESUME_MILLIS = 550

/** A pinch let go within this factor of fitted, filling the screen or the original's size goes to it. */
private const val SNAP = 1.15f

/**
 * The photo laid out as [zoom] shows it: fitted in the screen at its shape ([ratio]), times the zoom, moved by its
 * offset from the centre. A layout rather than a drawing effect, so the photo's bounds are what is seen: the flight
 * back to its tile on closing starts there. Read while laying out, so a pinch relays it without recomposing.
 */
internal fun Modifier.zoomedLayout(zoom: PhotoZoom, ratio: Float): Modifier = layout { measurable, constraints ->
    val width = constraints.maxWidth
    val height = constraints.maxHeight
    val fittedWidth = if (width.toFloat() / height > ratio) height * ratio else width.toFloat()
    val fittedHeight = fittedWidth / ratio
    val w = (fittedWidth * zoom.scale).roundToInt().coerceAtLeast(1)
    val h = (fittedHeight * zoom.scale).roundToInt().coerceAtLeast(1)
    val photo = measurable.measure(Constraints.fixed(w, h))
    layout(width, height) {
        photo.place(((width - w) / 2f + zoom.offset.x).roundToInt(), ((height - h) / 2f + zoom.offset.y).roundToInt())
    }
}

/**
 * Pinching (two fingers) and, while zoomed, dragging with one: seen before the pager, which then neither swipes nor
 * takes a tap. One finger moves the photo only once it has gone further than a tap may (the touch slop), so a tap on
 * a zoomed photo still shows the bars. [onStart] is called when a gesture starts changing the zoom.
 */
internal fun Modifier.zoomGestures(zoom: () -> PhotoZoom?, onStart: () -> Unit): Modifier = pointerInput(Unit) {
    awaitEachGesture {
        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        var started = false
        var pinched = false
        var dragged = 0f
        val centre = Offset(size.width / 2f, size.height / 2f)
        var focus = Offset.Zero
        do {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val current = zoom() ?: continue
            val fingers = event.changes.count { it.pressed }
            if (fingers >= 2 || current.isZoomed) {
                val change = event.calculateZoom()
                val pan = event.calculatePan()
                if (!started && fingers < 2) dragged += pan.getDistance()
                if ((change != 1f || pan != Offset.Zero) && (started || fingers >= 2 || dragged > viewConfiguration.touchSlop)) {
                    if (!started) onStart()
                    started = true
                    if (fingers >= 2 && change != 1f) pinched = true
                    focus = event.calculateCentroid() - centre
                    current.transform(focus, change, pan)
                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                }
            }
        } while (event.changes.any { it.pressed })
        zoom()?.settle(pinched, focus)
    }
}

/**
 * Where the screen is on the whole photo while zoomed: [image] small, with the part on screen ([part], 0..1) outlined
 * and the rest dimmed; grey until the photo on screen is at the original's quality ([sharp]), its colour then fading
 * in. Its longer side is [side].
 */
@Composable
internal fun ZoomMinimap(image: ImageBitmap, part: Rect, sharp: Boolean, modifier: Modifier = Modifier, side: Dp = 96.dp) {
    val colour by animateFloatAsState(if (sharp) 1f else 0f, tween(MINIMAP_COLOUR_MILLIS), label = "minimap colour")
    val ratio = image.width.toFloat() / image.height
    val size = if (ratio >= 1f) DpSize(side, side / ratio) else DpSize(side * ratio, side)
    Canvas(
        modifier
            .size(size)
            .shadow(6.dp, RoundedCornerShape(6.dp))
            .clip(RoundedCornerShape(6.dp))
            .background(Color.Black),
    ) {
        val whole = IntSize(this.size.width.roundToInt(), this.size.height.roundToInt())
        if (colour < 1f) drawImage(image, dstSize = whole, filterQuality = FilterQuality.Medium, colorFilter = GREY)
        if (colour > 0f) drawImage(image, dstSize = whole, filterQuality = FilterQuality.Medium, alpha = colour)
        val left = part.left * this.size.width
        val top = part.top * this.size.height
        val right = part.right * this.size.width
        val bottom = part.bottom * this.size.height
        val dim = Color.Black.copy(alpha = 0.5f)
        // The rest dimmed, in four bands around the part.
        drawRect(dim, Offset.Zero, Size(this.size.width, top))
        drawRect(dim, Offset(0f, bottom), Size(this.size.width, this.size.height - bottom))
        drawRect(dim, Offset(0f, top), Size(left, bottom - top))
        drawRect(dim, Offset(right, top), Size(this.size.width - right, bottom - top))
        drawRect(Color.White, Offset(left, top), Size(right - left, bottom - top), style = Stroke(1.5.dp.toPx()))
    }
}

private const val MINIMAP_COLOUR_MILLIS = 300

private val GREY = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

/** Most pixels of an original decoded for zooming: under Android's limit for drawing a bitmap (100 MB). */
private const val ZOOM_MAX_PIXELS = 24_000_000L

/** Longest side of an original decoded for zooming (larger textures fail on some GPUs). */
private const val ZOOM_MAX_SIDE = 8192

/** The longer side [item]'s original is decoded at for zooming: its own, unless that is too many pixels to draw. */
internal fun zoomDecodeSide(item: MediaItem): Int {
    val pixels = item.width.toLong() * item.height
    val longer = max(item.width, item.height)
    if (pixels <= 0) return ZOOM_MAX_SIDE
    val side = if (pixels <= ZOOM_MAX_PIXELS) longer else (longer * sqrt(ZOOM_MAX_PIXELS.toDouble() / pixels)).toInt()
    return min(side, ZOOM_MAX_SIDE)
}
