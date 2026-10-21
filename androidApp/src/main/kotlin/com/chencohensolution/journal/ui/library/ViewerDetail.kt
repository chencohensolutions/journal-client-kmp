package com.chencohensolution.journal.ui.library

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Matrix
import android.media.ExifInterface
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.geometry.Offset
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.server.ServerPreviews
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import kotlin.math.max
import kotlin.math.roundToInt

/** How long the photo must stay still before the part on screen is decoded from the original. */
private const val DETAIL_WAIT_MILLIS = 150L

/** How much around the part on screen is decoded too, so a small move still shows detail (a fraction of each side). */
private const val DETAIL_MARGIN = 0.15f

private const val DETAIL_FADE_MILLIS = 150

/** A part of the original decoded at the detail the screen shows; [rect] is where it sits, 0..1 of the upright photo. */
internal class DetailTile(val image: ImageBitmap, val rect: Rect)

/**
 * The parts of the original drawn over the zoomed photo: [over] is the newest, fading in by [overAlpha] over [under],
 * the one before it, so a new part never shows the blurrier photo beneath for a moment.
 */
@Stable
internal class DetailLayer {
    var under by mutableStateOf<DetailTile?>(null)
    var over by mutableStateOf<DetailTile?>(null)
    val overAlpha = Animatable(1f)

    /** Whether a part at full detail covers all of [part] (0..1 of the photo). */
    fun covers(part: Rect): Boolean {
        val rect = (over ?: under)?.rect ?: return false
        val e = 0.0005f
        return rect.left <= part.left + e && rect.top <= part.top + e && rect.right >= part.right - e && rect.bottom >= part.bottom - e
    }

    suspend fun show(tile: DetailTile?) {
        if (tile == null) {
            under = null
            over = null
            return
        }
        under = over
        over = tile
        overAlpha.snapTo(0f)
        overAlpha.animateTo(1f, tween(DETAIL_FADE_MILLIS))
        under = null
    }
}

/**
 * Whether the screen shows more of the photo's pixels than an image [shown] pixels on its longer side has: nothing to
 * add when it already has a pixel for each screen pixel, or every pixel the original ([originalSide]) has (past its own
 * size the original only enlarges too).
 */
internal fun PhotoZoom.wantsMoreThan(shown: Int, originalSide: Int): Boolean {
    val wanted = if (originalSide > 0) minOf(shownLongerSide, originalSide.toFloat()) else shownLongerSide
    return isZoomed && wanted > shown * 1.05f
}

/** Draws [layer]'s parts over the photo this modifies, which is drawn at the photo's full (fitted) size. */
internal fun Modifier.detailOver(layer: DetailLayer?): Modifier =
    if (layer == null) this else drawWithContent {
        drawContent()
        layer.under?.let { drawTile(it, 1f) }
        layer.over?.let { drawTile(it, layer.overAlpha.value) }
    }

private fun DrawScope.drawTile(tile: DetailTile, alpha: Float) {
    // At its exact place, not rounded to whole pixels: this is drawn before the zoom enlarges it, so a rounded pixel
    // here would put it a few screen pixels off the photo beneath.
    val left = tile.rect.left * size.width
    val top = tile.rect.top * size.height
    val scaleX = tile.rect.width * size.width / tile.image.width
    val scaleY = tile.rect.height * size.height / tile.image.height
    withTransform({
        translate(left, top)
        scale(scaleX, scaleY, pivot = Offset.Zero)
    }) {
        drawImage(tile.image, alpha = alpha)
    }
}

/**
 * While [zoom] shows the photo at more pixels than [shownSide] (the longer side of the image drawn), the part on screen
 * is decoded from the true original each time the photo stops moving, at the screen's detail; [open] opens the
 * original for that (null: it can't be read in parts, and the image drawn stays).
 */
@OptIn(FlowPreview::class)
@Composable
internal fun rememberDetailLayer(item: MediaItem, zoom: PhotoZoom?, shownSide: Int, open: suspend () -> OriginalRegions?): DetailLayer? {
    val layer = remember(item.id) { DetailLayer() }
    val originalSide = maxOf(item.width, item.height)
    val side by rememberUpdatedState(shownSide)
    if (zoom == null) return null
    LaunchedEffect(item.id, zoom) {
        var regions: OriginalRegions? = null
        var opened = false
        try {
            snapshotFlow { Triple(zoom.scale, zoom.offset, side) }.debounce(DETAIL_WAIT_MILLIS).collectLatest { (_, _, shown) ->
                if (!zoom.wantsMoreThan(shown, originalSide)) return@collectLatest layer.show(null)
                val rect = zoom.visibleRect(DETAIL_MARGIN) ?: return@collectLatest
                if (!opened) {
                    regions = withContext(Dispatchers.IO) { runCatching { open() }.getOrNull() }
                    opened = true
                }
                val from = regions ?: return@collectLatest
                // The screen pixels the part takes across: its share of the photo's shown width.
                val across = rect.width * zoom.shownWidth
                val tile = withContext(Dispatchers.IO) { from.decode(rect, across) } ?: return@collectLatest
                layer.show(tile)
            }
        } finally {
            regions?.close()
        }
    }
    return layer
}

/**
 * Tells [zoom] whether the photo is at the original's quality: fitted, or zoomed with [sharp] (the original decoded
 * for zooming) drawn and, where the screen wants more than its [shownSide] pixels, a part at full detail covering
 * the screen.
 */
@Composable
internal fun ReportQuality(zoom: PhotoZoom?, item: MediaItem, sharp: Boolean, shownSide: Int, detail: DetailLayer?) {
    if (zoom == null) return
    val isSharp by rememberUpdatedState(sharp)
    val shown by rememberUpdatedState(shownSide)
    val originalSide = maxOf(item.width, item.height)
    val quality = remember(zoom, detail) {
        derivedStateOf {
            !zoom.isZoomed || (isSharp && (!zoom.wantsMoreThan(shown, originalSide) || zoom.visibleRect(0f)?.let { detail?.covers(it) } == true))
        }
    }
    SideEffect { zoom.quality = quality }
}

/**
 * An original opened to be decoded in parts (JPEG, PNG, WebP, HEIF; not DNG or JPEG XL). Android's region decoder reads
 * the stored pixels, so the photo's EXIF turn is undone on the way in and applied to each part on the way out.
 */
internal class OriginalRegions private constructor(
    private val decoder: BitmapRegionDecoder,
    /** Degrees clockwise the stored pixels are turned to stand upright. */
    private val rotation: Int,
) {
    private val storedWidth = decoder.width
    private val storedHeight = decoder.height

    /**
     * [rect] of the upright photo (0..1), sampled down only as far as keeps [across] pixels across it; with the part
     * actually read, which is [rect] on whole pixels of the original (drawn anywhere else, it drifts off the photo).
     */
    @Synchronized
    fun decode(rect: Rect, across: Float): DetailTile? = runCatching {
        // The same part in the stored pixels' own fractions.
        val stored = when (rotation) {
            90 -> Rect(rect.top, 1 - rect.right, rect.bottom, 1 - rect.left)
            180 -> Rect(1 - rect.right, 1 - rect.bottom, 1 - rect.left, 1 - rect.top)
            270 -> Rect(1 - rect.bottom, rect.left, 1 - rect.top, rect.right)
            else -> rect
        }
        val region = android.graphics.Rect(
            (stored.left * storedWidth).toInt(), (stored.top * storedHeight).toInt(),
            (stored.right * storedWidth).roundToInt().coerceAtMost(storedWidth), (stored.bottom * storedHeight).roundToInt().coerceAtMost(storedHeight),
        )
        if (region.isEmpty) return null
        val read = Rect(
            region.left.toFloat() / storedWidth, region.top.toFloat() / storedHeight,
            region.right.toFloat() / storedWidth, region.bottom.toFloat() / storedHeight,
        )
        val upright = when (rotation) {
            90 -> Rect(1 - read.bottom, read.left, 1 - read.top, read.right)
            180 -> Rect(1 - read.right, 1 - read.bottom, 1 - read.left, 1 - read.top)
            270 -> Rect(read.top, 1 - read.right, read.bottom, 1 - read.left)
            else -> read
        }
        val partAcross = (if (rotation % 180 == 0) region.width() else region.height()).toFloat()
        var sample = 1
        while (partAcross / (sample * 2) >= across) sample *= 2
        val part = decoder.decodeRegion(region, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
        // A power of two can leave almost twice the pixels needed each way: scaled down to what the screen shows.
        val shrink = across / (if (rotation % 180 == 0) part.width else part.height)
        val matrix = Matrix().apply {
            if (shrink < 0.8f) postScale(shrink, shrink)
            if (rotation != 0) postRotate(rotation.toFloat())
        }
        val image = if (matrix.isIdentity) part else Bitmap.createBitmap(part, 0, 0, part.width, part.height, matrix, true).also { part.recycle() }
        DetailTile(image.asImageBitmap(), upright)
    }.getOrNull()

    @Synchronized
    fun close() = decoder.recycle()

    companion object {
        /**
         * Null when the file can't be opened or decoded in parts, or is mirrored (rare; the image drawn then stays).
         * [openFile] is called twice, once for the EXIF; read only, as the photo safety rules want. The decoder keeps
         * its own copy of the file in memory, so the stream is closed at once.
         */
        fun open(openFile: () -> InputStream?): OriginalRegions? {
            val orientation = runCatching {
                openFile()?.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
            }.getOrNull() ?: return null
            val rotation = when (orientation) {
                ExifInterface.ORIENTATION_NORMAL, ExifInterface.ORIENTATION_UNDEFINED -> 0
                ExifInterface.ORIENTATION_ROTATE_90 -> 90
                ExifInterface.ORIENTATION_ROTATE_180 -> 180
                ExifInterface.ORIENTATION_ROTATE_270 -> 270
                else -> return null
            }
            val decoder = runCatching { openFile()?.use { BitmapRegionDecoder.newInstance(it) } }.getOrNull() ?: return null
            return OriginalRegions(decoder, rotation)
        }
    }
}

/** [item]'s original to decode in parts: on this device, kept by the app, or downloaded into the server cache. */
internal suspend fun openOriginalRegions(resolver: ContentResolver, previews: ServerPreviews?, item: MediaItem): OriginalRegions? {
    if (item.isOnDevice) return OriginalRegions.open { resolver.openInputStream(item.contentUri()) }
    val path = item.keptOriginal ?: item.sha256?.let { previews?.cachedOriginal(it) } ?: return null
    return OriginalRegions.open { File(path).inputStream() }
}
