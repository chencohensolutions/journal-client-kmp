package com.chencohensolution.journal.ui.library

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.awxkee.jxlcoder.JxlCoder
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import java.io.File
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.contentDescription
import com.chencohensolution.journal.backup.JpegXl
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.people.Face
import com.chencohensolution.journal.people.faceCrop
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.PreviewOptions
import com.chencohensolution.journal.server.ServerPreviews
import com.chencohensolution.journal.library.MediaType
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext

// Loading and caching the pictures tiles and pages draw: device thumbnails, server previews, kept originals, video posters.

/** Thumbnail sizes in pixels; few sizes so the cache is shared between screens. */
internal const val THUMB_SMALL = 256

internal const val THUMB_LARGE = 640

internal const val THUMB_FULL = 1280

/** MediaStore hands out thumbnails no larger than this on their longer side, whatever size is asked for. */
private const val STORE_THUMBNAIL_MAX = 512

/**
 * The longer side [item] has once scaled to cover [width] × [height] pixels: a 4:3 photo in a square tile is a third
 * longer than the tile.
 */
private fun coverLonger(item: MediaItem, width: Int, height: Int): Float {
    val ratio = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 4f / 3f
    return maxOf(ratio, 1f) * maxOf(width / ratio, height.toFloat())
}

/**
 * The size of a [sourceWidth] × [sourceHeight] image scaled down to exactly cover [width] × [height]: the side that fits
 * the box matches it, the other is rounded up so it still covers. Null when the image is no larger than that already.
 */
private fun coverDimensions(sourceWidth: Int, sourceHeight: Int, width: Int, height: Int): Pair<Int, Int>? {
    val byWidth = width.toFloat() / sourceWidth
    val byHeight = height.toFloat() / sourceHeight
    return when {
        maxOf(byWidth, byHeight) >= 1f -> null
        byWidth >= byHeight -> width to maxOf(ceil(sourceHeight * byWidth).toInt(), height)
        else -> maxOf(ceil(sourceWidth * byHeight).toInt(), width) to height
    }
}

/**
 * [item] cropped to fill this box, at exactly the box's size in pixels so it is drawn without scaling; empty while it
 * loads. A box larger than MediaStore's thumbnails (a grid's large tile) loads in two steps, as [TwoStepPhoto].
 */
@Composable
internal fun CroppedThumbnail(item: MediaItem, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier) {
        val bounded = constraints.hasBoundedWidth && constraints.hasBoundedHeight
        val (width, height) = constraints.maxWidth to constraints.maxHeight
        if (bounded && coverLonger(item, width, height) > STORE_THUMBNAIL_MAX) {
            TwoStepPhoto(item, width, height, Modifier.fillMaxSize())
        } else {
            val thumbnail = if (bounded) rememberCoverThumbnail(item, width, height) else rememberThumbnail(item, THUMB_SMALL)
            thumbnail?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }
}

/**
 * [item] cropped to fill a [width] × [height] pixel box in two steps: a quick image at once (MediaStore's thumbnail,
 * or for a photo only on the server its [first image][rememberFirstImage]), soft when the box is large, then the sharp
 * one on top once the list has slowed down ([LocalScrollSettled]). A fast scroll decodes only the quick ones.
 */
@Composable
internal fun TwoStepPhoto(item: MediaItem, width: Int, height: Int, modifier: Modifier = Modifier) {
    Box(modifier) {
        val quick = if (item.hasOriginalHere) rememberThumbnail(item, THUMB_LARGE) else rememberFirstImage(item)
        quick?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        // First images off in Settings: the sharp one at once, as there is nothing to show meanwhile.
        val noQuick = !item.hasOriginalHere && LocalServerPreviews.current?.currentOptions?.firstWidthPercent == 0
        if (noQuick || sharpAllowed(item, width, height)) SizedThumbnail(item, width, height, Modifier.fillMaxSize())
    }
}

/**
 * Whether [item]'s sharp image at [width] × [height] may load: once the list has slowed down, and from then on even if
 * it speeds up again; at once when it is already in memory.
 */
@Composable
private fun sharpAllowed(item: MediaItem, width: Int, height: Int): Boolean {
    val settled by LocalScrollSettled.current
    // Not state: it only turns true while [settled] is read, which recomposes this anyway.
    val started = remember(item.id, width, height) { booleanArrayOf(thumbnailCache.get("${item.id}@${width}x$height") != null) }
    if (settled) started[0] = true
    return started[0]
}

/**
 * Whether the list on screen moves slowly enough for sharp images to be worth decoding ([rememberScrollSettled]);
 * always where there is no list to watch (the viewer, screenshot tests).
 */
internal val LocalScrollSettled = staticCompositionLocalOf<State<Boolean>> { AlwaysSettled }

private object AlwaysSettled : State<Boolean> {
    override val value = true
}

/** A list moving slower than this many screens a second counts as settled. */
private const val SETTLED_SCREENS_PER_SECOND = 1f

/** ...for this long: a fling slowing down past it for a frame isn't settled yet. */
private const val SETTLED_NANOS = 100_000_000L

/**
 * Whether [list] is still or moving slowly (under [SETTLED_SCREENS_PER_SECOND] for [SETTLED_NANOS]), for
 * [LocalScrollSettled]. The speed is how far the first item on screen moved since the frame before.
 */
@Composable
internal fun rememberScrollSettled(list: LazyListState): State<Boolean> {
    val settled = remember { mutableStateOf(true) }
    val screen = LocalWindowInfo.current.containerSize.height
    LaunchedEffect(list, screen) {
        snapshotFlow { list.isScrollInProgress }.collectLatest { scrolling ->
            if (!scrolling) {
                settled.value = true
                return@collectLatest
            }
            val limit = screen * SETTLED_SCREENS_PER_SECOND
            var anchor = list.layoutInfo.visibleItemsInfo.firstOrNull()?.let { it.key to it.offset }
            var last = withFrameNanos { it }
            var slowSince: Long? = null
            while (true) {
                val now = withFrameNanos { it }
                val items = list.layoutInfo.visibleItemsInfo
                // Gone from the screen in one frame: fast.
                val moved = anchor?.let { (key, offset) -> items.firstOrNull { it.key == key }?.let { abs(it.offset - offset) } }
                val fast = moved == null || moved * 1e9f / (now - last).coerceAtLeast(1) > limit
                if (fast) {
                    slowSince = null
                    settled.value = false
                } else {
                    val since = slowSince ?: now.also { slowSince = it }
                    if (now - since >= SETTLED_NANOS) settled.value = true
                }
                anchor = items.firstOrNull()?.let { it.key to it.offset }
                last = now
            }
        }
    }
    return settled
}

/**
 * What a photo only on the server shows first, before its sharp image and in its place while the list moves fast:
 * [ServerPreviews.firstImage], by default a HEIF at half the window's width (Settings → Advanced; the same size on
 * every screen, so one file serves them all). Null while it loads, when there is none, or when it is off.
 */
@Composable
private fun rememberFirstImage(item: MediaItem): ImageBitmap? {
    val window = LocalWindowInfo.current.containerSize.width
    LocalThumbnailSource.current?.let { source -> return remember(source, item) { source.thumbnail(item, window / 2) } }
    val previews = LocalServerPreviews.current
    val key = previews?.let { firstImageKey(item, it.currentOptions) }
    val bitmap by produceState(key?.let(thumbnailCache::get), key) {
        value = loadFirstImage(previews, item, window)
    }
    return bitmap?.asImageBitmap()
}

/** In memory by its format and size too, so a change in Settings shows at once. */
private fun firstImageKey(item: MediaItem, options: PreviewOptions) = "${item.id}@first-${options.firstFormat}-${options.firstWidthPercent}"

/** [rememberFirstImage] for [item] in a window [windowWidth] pixels wide, kept in memory; also to load it ahead. */
internal suspend fun loadFirstImage(previews: ServerPreviews?, item: MediaItem, windowWidth: Int): Bitmap? {
    if (previews == null || windowWidth <= 0) return null
    val options = previews.currentOptions
    if (options.firstWidthPercent <= 0) return null
    val key = firstImageKey(item, options)
    thumbnailCache.get(key)?.let { return it }
    val sha = item.sha256 ?: return null
    // The photo's shape at a part of the window's width: what a full-width photo is drawn at, scaled down.
    val width = (windowWidth * options.firstWidthPercent / 100).coerceAtLeast(1)
    val height = if (item.width > 0 && item.height > 0) ceil(width.toFloat() * item.height / item.width).toInt() else width
    val bytes = previews.firstImage(sha, width, height, isVideo = item.type == MediaType.VIDEO) ?: return null
    return withContext(firstLoads) { decodeFirstImage(bytes, width, height) }?.also { thumbnailCache.put(key, it) }
}

/**
 * A first image straight into graphics memory, fitted in [width] × [height] (a larger preview kept stands in offline).
 * HEIF through ImageDecoder, which uses the phone's video decoder where it has one.
 */
private fun decodeFirstImage(bytes: ByteArray, width: Int, height: Int): Bitmap? {
    if (JxlCoder.isJXL(bytes)) return decodePreview(bytes)
    return runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(bytes))) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_HARDWARE
            val scale = minOf(width.toFloat() / info.size.width, height.toFloat() / info.size.height)
            if (scale < 1f) {
                decoder.setTargetSize((info.size.width * scale).toInt().coerceAtLeast(1), (info.size.height * scale).toInt().coerceAtLeast(1))
            }
        }
    }.getOrNull()
}

/**
 * Loads the first images of [rows] ahead of [list] while it moves, in the direction it moves: the next [FIRST_AHEAD]
 * rows past the screen. [key] is a row's list item key (other items, banners, are skipped); [items] the photos a row
 * shows full size. Only photos that are only on the server; the others have MediaStore's thumbnails.
 */
@Composable
internal fun <R> PrefetchFirstImages(
    list: LazyListState,
    rows: List<R>,
    key: (R) -> Any,
    ahead: Int = FIRST_AHEAD,
    items: (R) -> List<MediaItem>,
) {
    if (LocalThumbnailSource.current != null) return
    val previews = LocalServerPreviews.current ?: return
    val window = LocalWindowInfo.current.containerSize.width
    LaunchedEffect(list, rows, window, ahead) {
        val byKey = withContext(Dispatchers.Default) { rows.withIndex().associate { (index, row) -> key(row) to index } }
        val rowOf = { listKey: Any -> byKey[listKey] }
        var lastFirst = list.firstVisibleItemIndex
        snapshotFlow { list.layoutInfo.visibleItemsInfo.let { it.firstOrNull()?.key to it.lastOrNull()?.key } }
            .collectLatest { (firstKey, lastKey) ->
                val down = list.firstVisibleItemIndex >= lastFirst
                lastFirst = list.firstVisibleItemIndex
                val edge = (if (down) lastKey else firstKey)?.let(rowOf) ?: return@collectLatest
                val next = if (down) (edge + 1..minOf(edge + ahead, rows.lastIndex)) else (edge - 1 downTo maxOf(edge - ahead, 0))
                for (index in next) {
                    for (item in items(rows[index])) {
                        // One at a time; the one under way finishes when the scroll moves on, to be there next time.
                        if (!item.hasOriginalHere) withContext(NonCancellable) { loadFirstImage(previews, item, window) }
                    }
                }
            }
    }
}

/** How many rows past the screen [PrefetchFirstImages] loads, unless told otherwise. */
private const val FIRST_AHEAD = 20

/**
 * [item] cropped to fill this box, which the caller knows to be [width] × [height] pixels: like [CroppedThumbnail]
 * without measuring the box first (BoxWithConstraints composes its content a second time, which costs a frame in a
 * fast scroll).
 */
@Composable
internal fun SizedThumbnail(item: MediaItem, width: Int, height: Int, modifier: Modifier = Modifier) {
    rememberCoverThumbnail(item, width, height)?.let {
        Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = modifier)
    }
}

/**
 * [item] scaled to exactly cover [width] × [height] pixels, to be cropped to it. Up to MediaStore's thumbnail size it is
 * that thumbnail scaled down; larger, the photo itself decoded straight to that size (a video has only its thumbnail).
 * Only the result is cached.
 */
@Composable
private fun rememberCoverThumbnail(item: MediaItem, width: Int, height: Int): ImageBitmap? {
    if (width <= 0 || height <= 0) return null
    LocalThumbnailSource.current?.let { source ->
        return remember(source, item, width, height) { source.thumbnail(item, maxOf(width, height)) }
    }
    val context = LocalContext.current
    val previews = LocalServerPreviews.current
    val key = "${item.id}@${width}x$height"
    val bitmap by produceState(thumbnailCache.get(key), key) {
        if (value == null) value = loadCover(context, previews, item, width, height)
    }
    return bitmap?.asImageBitmap()
}

/**
 * [item] cropped to cover a [width] × [height] pixel box, as [SizedThumbnail] draws it: from memory, else loaded and
 * kept there. Called ahead by the feed's in-place play, so the next photo shows sharp at once (asked Nov 9, 2026).
 */
internal suspend fun loadCover(context: Context, previews: ServerPreviews?, item: MediaItem, width: Int, height: Int): Bitmap? {
    if (width <= 0 || height <= 0) return null
    val resolver = context.contentResolver
    val key = "${item.id}@${width}x$height"
    return thumbnailCache.get(key) ?: withContext(Dispatchers.IO) {
        val kept = item.keptOriginal
        if (kept != null) {
            withContext(fullDecodes) { decodeKept(kept, item) { w, h -> coverDimensions(w, h, width, height) } }
        } else if (item.isOnDevice && item.isJxl) {
            withContext(fullDecodes) { decodeJxl(readDevice(resolver, item)) { w, h -> coverDimensions(w, h, width, height) } }
        } else if (!item.isOnDevice) {
            // The server's preview at the size that covers the box, so the caller crops and zooms it as a local one.
            val (w, h) = coverSize(item, width, height)
            serverBitmap(previews, item, w, h) { ow, oh -> coverDimensions(ow, oh, width, height) }
        } else if (item.type == MediaType.VIDEO || coverLonger(item, width, height) <= STORE_THUMBNAIL_MAX) {
            val size = Size(STORE_THUMBNAIL_MAX, STORE_THUMBNAIL_MAX)
            deviceThumbnail(context, item, size)?.let { loaded ->
                coverDimensions(loaded.width, loaded.height, width, height)
                    ?.let { (w, h) -> Bitmap.createScaledBitmap(loaded, w, h, true) }
                    ?: loaded
            }
        } else {
            // A few at a time: a fast scroll asks for many, and those scrolled past are cancelled before they start.
            withContext(fullDecodes) {
                runCatching {
                    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.contentUri())) { decoder, info, _ ->
                        coverDimensions(info.size.width, info.size.height, width, height)?.let { (w, h) -> decoder.setTargetSize(w, h) }
                    }
                }.getOrNull()
            }
        }
    }?.forDrawing()?.also { thumbnailCache.put(key, it) }
}

/**
 * This bitmap in graphics memory, copied there off the main thread. A software bitmap is otherwise copied by the
 * render thread the first frame it is drawn: 5–8 ms for a full-width photo, a dropped frame or two just as it scrolls
 * into view. Cached bitmaps are only drawn, never read, so they can live there.
 */
private suspend fun Bitmap.forDrawing(): Bitmap =
    if (config == Bitmap.Config.HARDWARE) this else withContext(Dispatchers.IO) { copy(Bitmap.Config.HARDWARE, false) } ?: this

/** Decodes of whole photos, which take far longer than MediaStore's thumbnails, run this many at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
private val fullDecodes = Dispatchers.IO.limitedParallelism(2)

/** Sharp server previews decode this many at a time, so a fast scroll can't start dozens that slow each other down. */
@OptIn(ExperimentalCoroutinesApi::class)
private val previewDecodes = Dispatchers.IO.limitedParallelism(3)

/**
 * First images decode in their own lane, never behind sharp decodes; few at a time, as a phone has only a few hardware
 * HEVC decoders.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val firstLoads = Dispatchers.IO.limitedParallelism(3)

/** Where photos that are only on the server come from; null in screenshot tests. */
internal val LocalServerPreviews = staticCompositionLocalOf<ServerPreviews?> { null }

/** Previews, originals and videos of server photos kept on this phone, under one limit; null in screenshot tests. */
internal val LocalPhoneStorage = staticCompositionLocalOf<com.chencohensolution.journal.storage.PhoneStorage?> { null }

/** Android's decoders for what [ServerPreviews] keeps: an original fitted by [fromOriginal], or preview bytes. */
private fun decoders(item: MediaItem, fromOriginal: (Int, Int) -> Pair<Int, Int>?) = ServerPreviews.Decoders(
    original = { path -> withContext(fullDecodes) { decodeKept(path, item, fromOriginal) } },
    preview = { bytes -> withContext(previewDecodes) { decodePreview(bytes) } },
)

/**
 * A server-only photo or video at [width] × [height] pixels, chosen by [ServerPreviews.tile] (original, exact preview,
 * or the original downloaded with previews off); an original is decoded to the size [fromOriginal] picks.
 */
private suspend fun serverBitmap(
    previews: ServerPreviews?,
    item: MediaItem,
    width: Int,
    height: Int,
    fromOriginal: (Int, Int) -> Pair<Int, Int>?,
): Bitmap? {
    val sha = item.sha256 ?: return null
    return previews?.tile(sha, item.type == MediaType.VIDEO, item.fileName, width, height, decoders(item, fromOriginal))
}

/**
 * The best image of a server-only photo already on this phone, asking the server for nothing: the largest one decoded
 * in memory (a tile just seen), else the largest preview kept on disk.
 */
internal suspend fun cachedServerImage(previews: ServerPreviews?, item: MediaItem): Bitmap? {
    val inMemory = thumbnailCache.snapshot().filterKeys { it.startsWith("${item.id}@") }.values.maxByOrNull { it.width.toLong() * it.height }
    if (inMemory != null) return inMemory
    val sha = item.sha256 ?: return null
    return previews?.largestPreview(sha)?.let(::decodePreview)
}

/** [cachedServerImage] for a composable: what is already on this phone, never fetched. */
@Composable
internal fun rememberCachedServerImage(item: MediaItem): ImageBitmap? {
    val previews = LocalServerPreviews.current
    val bitmap by produceState<Bitmap?>(null, item.id) { value = withContext(Dispatchers.IO) { cachedServerImage(previews, item) } }
    return bitmap?.asImageBitmap()
}

/** A server-only photo opened on its own, fitted in [maxSide]: [ServerPreviews.viewer] (its original, downloaded). */
internal suspend fun serverOriginal(previews: ServerPreviews?, item: MediaItem, maxSide: Int): Bitmap? {
    val sha = item.sha256 ?: return null
    return previews?.viewer(sha, item.fileName, maxSide, decoders(item) { w, h -> fitInside(w, h, maxSide) })
}

/** A preview from the server, in any format it makes; JPEG XL with the app's own decoder (Android has none). */
internal fun decodePreview(bytes: ByteArray): Bitmap? = runCatching {
    if (JxlCoder.isJXL(bytes)) JxlCoder.decode(bytes) else BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

/** The photo's own shape scaled to cover [width] × [height]; the box itself when the shape is unknown. */
private fun coverSize(item: MediaItem, width: Int, height: Int): Pair<Int, Int> {
    if (item.width <= 0 || item.height <= 0) return width to height
    val scale = maxOf(width.toFloat() / item.width, height.toFloat() / item.height)
    return kotlin.math.ceil(item.width * scale).toInt() to kotlin.math.ceil(item.height * scale).toInt()
}

/** The MediaStore URI of a photo on this device; never call it for one that is only on the server. */
internal fun MediaItem.contentUri(): Uri = ContentUris.withAppendedId(
    if (type == MediaType.VIDEO) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
    id.toLong(),
)

/** Decoded thumbnails, up to 1/8 of the app's memory. */
private val thumbnailCache = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
    override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
}

/**
 * Draws thumbnails instead of the photo library, for screenshot tests (see docs/previews.md). Null in the app: then
 * thumbnails come from MediaStore.
 */
internal fun interface ThumbnailSource {
    fun thumbnail(item: MediaItem, sizePx: Int): ImageBitmap?
}

internal val LocalThumbnailSource = staticCompositionLocalOf<ThumbnailSource?> { null }

/** Draws faces instead of the server's, for screenshot tests and the UI lab. Null in the app. */
internal fun interface FaceSource {
    fun face(face: Face, sizePx: Int): ImageBitmap?
}

internal val LocalFaceSource = staticCompositionLocalOf<FaceSource?> { null }

/**
 * The server's list, for the size of a face's photo (its crop is made square on it), read from the database by sha256;
 * null in screenshot tests.
 */
internal val LocalServerLibrary = staticCompositionLocalOf<ServerLibrary?> { null }

/** [face] as a round avatar [sizePx] across: a crop of its photo's preview, fetched from the server and kept. */
@Composable
internal fun rememberFace(face: Face, sizePx: Int): ImageBitmap? {
    LocalFaceSource.current?.let { source -> return remember(source, face, sizePx) { source.face(face, sizePx) } }
    val previews = LocalServerPreviews.current ?: return null
    val server = LocalServerLibrary.current
    val key = "face:${face.id}@$sizePx"
    val bitmap by produceState(thumbnailCache.get(key), key) {
        value = thumbnailCache.get(key) ?: withContext(Dispatchers.IO) {
            val photo = server?.file(face.sha256)
            previews.face(face.sha256, faceCrop(face, photo?.width ?: 0, photo?.height ?: 0), sizePx)
                ?.let { bytes -> withContext(previewDecodes) { decodePreview(bytes) } }
        }?.forDrawing()?.also { thumbnailCache.put(key, it) }
    }
    return bitmap?.asImageBitmap()
}

/** The item's thumbnail at [sizePx], or null while it loads (or when the file can't be read). */
@Composable
internal fun rememberThumbnail(item: MediaItem, sizePx: Int): ImageBitmap? {
    LocalThumbnailSource.current?.let { source -> return remember(source, item, sizePx) { source.thumbnail(item, sizePx) } }
    val context = LocalContext.current
    val resolver = context.contentResolver
    val previews = LocalServerPreviews.current
    val key = "${item.id}@$sizePx"
    // The state outlives a change of item, so each new key replaces the value, even with null when it can't be read.
    val bitmap by produceState(thumbnailCache.get(key), key) {
        value = thumbnailCache.get(key) ?: withContext(Dispatchers.IO) {
            val kept = item.keptOriginal
            if (kept != null) {
                decodeKept(kept, item) { w, h -> fitInside(w, h, sizePx) }
            } else if (!item.isOnDevice) {
                serverBitmap(previews, item, sizePx, sizePx) { w, h -> fitInside(w, h, sizePx) }
            } else if (item.isJxl) {
                withContext(fullDecodes) { decodeJxl(readDevice(resolver, item)) { w, h -> fitInside(w, h, sizePx) } }
            } else {
                deviceThumbnail(context, item, Size(sizePx, sizePx))
            }
        }?.forDrawing()?.also { thumbnailCache.put(key, it) }
    }
    return bitmap?.asImageBitmap()
}

/** The full image scaled so its longer side is at most [maxSide] pixels; for a server-only photo, the server's preview. */
internal suspend fun loadImage(resolver: ContentResolver, previews: ServerPreviews?, item: MediaItem, maxSide: Int): Bitmap? {
    val kept = item.keptOriginal
    return when {
        item.isOnDevice -> loadImage(resolver, item, maxSide)
        kept != null -> decodeKept(kept, item) { w, h -> fitInside(w, h, maxSide) }
        else -> serverBitmap(previews, item, maxSide, maxSide) { w, h -> fitInside(w, h, maxSide) }
    }
}

/**
 * A photo or video on this phone fitted in [size]: the system's thumbnail of a photo; a video's very first frame
 * (the system's thumbnail of a video is a frame it picks, often not the first).
 */
private fun deviceThumbnail(context: Context, item: MediaItem, size: Size): Bitmap? =
    if (item.type == MediaType.VIDEO) {
        firstVideoFrame(size.width, size.height) { it.setDataSource(context, item.contentUri()) }
    } else {
        runCatching { context.contentResolver.loadThumbnail(item.contentUri(), size, null) }.getOrNull()
    }

/**
 * A video's very first frame, as a player shows it first (rotated upright), fitted in [width] × [height], decoded by
 * the phone's own decoder (its hardware one where it has one). [open] gives the retriever the video. Null when it can't.
 */
internal fun firstVideoFrame(width: Int, height: Int, open: (MediaMetadataRetriever) -> Unit): Bitmap? = runCatching {
    MediaMetadataRetriever().use { retriever ->
        open(retriever)
        retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }
}.getOrNull()

/** An original the app downloaded and kept, decoded to the size [target] picks (null: as it is); a video's first frame. */
private fun decodeKept(path: String, item: MediaItem, target: (Int, Int) -> Pair<Int, Int>?): Bitmap? = runCatching {
    val file = File(path)
    if (item.type == MediaType.VIDEO) {
        val size = target(item.width.coerceAtLeast(1), item.height.coerceAtLeast(1)) ?: (item.width to item.height)
        firstVideoFrame(size.first, size.second) { it.setDataSource(file.path) }
    } else if (item.isJxl || file.extension.equals("jxl", ignoreCase = true)) {
        decodeJxl(file.readBytes(), target)
    } else {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
            target(info.size.width, info.size.height)?.let { (w, h) -> decoder.setTargetSize(w, h) }
        }
    }
}.getOrNull()

/** Scaled so the longer side is at most [maxSide]; null when it already is. */
private fun fitInside(width: Int, height: Int, maxSide: Int): Pair<Int, Int>? {
    val longer = maxOf(width, height)
    if (longer <= maxSide) return null
    val scale = maxSide.toFloat() / longer
    return (width * scale).toInt().coerceAtLeast(1) to (height * scale).toInt().coerceAtLeast(1)
}

/** What a player opens: the library's file, or the original the app kept; null for one only on the server. */
internal fun MediaItem.playableUri(): Uri? {
    val kept = keptOriginal
    return when {
        isOnDevice -> contentUri()
        kept != null -> Uri.fromFile(File(kept))
        else -> null
    }
}

/** A JPEG XL photo, by its type or name: Android has no decoder or thumbnails for it, so [decodeJxl] reads it. */
private val MediaItem.isJxl: Boolean
    get() = type == MediaType.IMAGE && (mimeType.equals("image/jxl", ignoreCase = true) || fileName?.endsWith(".jxl", ignoreCase = true) == true)

private fun readDevice(resolver: ContentResolver, item: MediaItem): ByteArray? =
    runCatching { resolver.openInputStream(item.contentUri())?.use { it.readBytes() } }.getOrNull()

/**
 * A JPEG XL photo at the size [target] picks (null: as it is). One packed from a JPEG gives that JPEG back, which
 * ImageDecoder reads (turned as its EXIF says, and fast); any other goes through the app's JPEG XL decoder.
 */
private fun decodeJxl(bytes: ByteArray?, target: (Int, Int) -> Pair<Int, Int>?): Bitmap? = runCatching {
    if (bytes == null) return null
    val jpeg = JpegXl.unpack(bytes) ?: return decodePreview(bytes)
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(ByteBuffer.wrap(jpeg))) { decoder, info, _ ->
        target(info.size.width, info.size.height)?.let { (w, h) -> decoder.setTargetSize(w, h) }
    }
}.getOrNull()

private fun loadImage(resolver: ContentResolver, item: MediaItem, maxSide: Int): Bitmap? = if (item.isJxl) {
    decodeJxl(readDevice(resolver, item)) { w, h -> fitInside(w, h, maxSide) }
} else runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.contentUri())) { decoder, info, _ ->
        val longer = maxOf(info.size.width, info.size.height)
        if (longer > maxSide) {
            val scale = maxSide.toFloat() / longer
            decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
        }
    }
}.getOrNull()
