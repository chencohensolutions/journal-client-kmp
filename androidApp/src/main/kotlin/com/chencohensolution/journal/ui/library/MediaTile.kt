package com.chencohensolution.journal.ui.library

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.awxkee.jxlcoder.JxlCoder
import android.graphics.ImageDecoder
import android.media.ThumbnailUtils
import java.io.File
import android.net.Uri
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.server.ServerPreviews
import com.chencohensolution.journal.library.MediaType
import kotlin.math.ceil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.withContext

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
 * [item] cropped to fill this box, at exactly the box's size in pixels so it is drawn without scaling (times the
 * crop's zoom, which then enlarges it); empty while it loads.
 */
@Composable
internal fun CroppedThumbnail(item: MediaItem, modifier: Modifier = Modifier, crop: TileCrop = TileCrop.NONE) {
    BoxWithConstraints(modifier) {
        val thumbnail = if (constraints.hasBoundedWidth && constraints.hasBoundedHeight) {
            val zoom = maxOf(crop.zoom, 1f)
            rememberCoverThumbnail(item, (constraints.maxWidth * zoom).toInt(), (constraints.maxHeight * zoom).toInt())
        } else {
            rememberThumbnail(item, THUMB_SMALL)
        }
        thumbnail?.let {
            Image(it, contentDescription = null, contentScale = ContentScale.Crop, alignment = crop.alignment, modifier = Modifier.fillMaxSize().zoomAround(crop))
        }
    }
}

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
    val resolver = LocalContext.current.contentResolver
    val previews = LocalServerPreviews.current
    val key = "${item.id}@${width}x$height"
    val bitmap by produceState(thumbnailCache.get(key), key) {
        value = thumbnailCache.get(key) ?: withContext(Dispatchers.IO) {
            val kept = item.keptOriginal
            if (kept != null) {
                withContext(fullDecodes) { decodeKept(kept, item) { w, h -> coverDimensions(w, h, width, height) } }
            } else if (!item.isOnDevice) {
                // The server's preview at the size that covers the box, so the caller crops and zooms it as a local one.
                val (w, h) = coverSize(item, width, height)
                serverBitmap(previews, item, w, h) { ow, oh -> coverDimensions(ow, oh, width, height) }
            } else if (item.type == MediaType.VIDEO || coverLonger(item, width, height) <= STORE_THUMBNAIL_MAX) {
                val size = Size(STORE_THUMBNAIL_MAX, STORE_THUMBNAIL_MAX)
                runCatching { resolver.loadThumbnail(item.contentUri(), size, null) }.getOrNull()?.let { loaded ->
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
        }?.also { thumbnailCache.put(key, it) }
    }
    return bitmap?.asImageBitmap()
}

/** Decodes of whole photos, which take far longer than MediaStore's thumbnails, run this many at a time. */
@OptIn(ExperimentalCoroutinesApi::class)
private val fullDecodes = Dispatchers.IO.limitedParallelism(3)

/** The gap between photo tiles in a grid. */
internal val TILE_GAP = 2.dp

/** Where photos that are only on the server come from; null in screenshot tests. */
internal val LocalServerPreviews = staticCompositionLocalOf<ServerPreviews?> { null }

/** Previews, originals and videos of server photos kept on this phone, under one limit; null in screenshot tests. */
internal val LocalPhoneStorage = staticCompositionLocalOf<com.chencohensolution.journal.storage.PhoneStorage?> { null }

/** Android's decoders for what [ServerPreviews] keeps: an original fitted by [fromOriginal], or preview bytes. */
private fun decoders(item: MediaItem, fromOriginal: (Int, Int) -> Pair<Int, Int>?) = ServerPreviews.Decoders(
    original = { path -> withContext(fullDecodes) { decodeKept(path, item, fromOriginal) } },
    preview = { bytes -> decodePreview(bytes) },
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

/** The item's thumbnail at [sizePx], or null while it loads (or when the file can't be read). */
@Composable
internal fun rememberThumbnail(item: MediaItem, sizePx: Int): ImageBitmap? {
    LocalThumbnailSource.current?.let { source -> return remember(source, item, sizePx) { source.thumbnail(item, sizePx) } }
    val resolver = LocalContext.current.contentResolver
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
            } else {
                runCatching { resolver.loadThumbnail(item.contentUri(), Size(sizePx, sizePx), null) }.getOrNull()
            }
        }?.also { thumbnailCache.put(key, it) }
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

/** An original the app downloaded and kept, decoded to the size [target] picks (null: as it is); a video's frame. */
private fun decodeKept(path: String, item: MediaItem, target: (Int, Int) -> Pair<Int, Int>?): Bitmap? = runCatching {
    val file = File(path)
    if (item.type == MediaType.VIDEO) {
        val size = target(item.width.coerceAtLeast(1), item.height.coerceAtLeast(1)) ?: (item.width to item.height)
        ThumbnailUtils.createVideoThumbnail(file, Size(size.first.coerceAtLeast(1), size.second.coerceAtLeast(1)), null)
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

/**
 * A frame of [item] (a video on this device, or its kept original) up to [maxSide] pixels, as WebP: what it shows once
 * it is only on the server. Null when it can't be read.
 */
internal fun videoPoster(resolver: ContentResolver, item: MediaItem, maxSide: Int): Triple<ByteArray, Int, Int>? = runCatching {
    val kept = item.keptOriginal
    val frame = when {
        item.isOnDevice -> resolver.loadThumbnail(item.contentUri(), Size(maxSide, maxSide), null)
        kept != null -> ThumbnailUtils.createVideoThumbnail(File(kept), Size(maxSide, maxSide), null)
        else -> return null
    }
    val bytes = java.io.ByteArrayOutputStream().also { frame.compress(Bitmap.CompressFormat.WEBP_LOSSY, 90, it) }.toByteArray()
    Triple(bytes, frame.width, frame.height)
}.getOrNull()

/** What a player opens: the library's file, or the original the app kept; null for one only on the server. */
internal fun MediaItem.playableUri(): Uri? {
    val kept = keptOriginal
    return when {
        isOnDevice -> contentUri()
        kept != null -> Uri.fromFile(File(kept))
        else -> null
    }
}

private fun loadImage(resolver: ContentResolver, item: MediaItem, maxSide: Int): Bitmap? = runCatching {
    ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, item.contentUri())) { decoder, info, _ ->
        val longer = maxOf(info.size.width, info.size.height)
        if (longer > maxSide) {
            val scale = maxSide.toFloat() / longer
            decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
        }
    }
}.getOrNull()

/**
 * A square-cropped photo or video with its video length and backup badge. [selected] is null outside selection
 * mode; in it the tile shows a check circle instead of its badges.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun MediaTile(
    item: MediaItem,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badgeSize: Dp = 22.dp,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    /** Which part of the photo stays in view when it is cropped, and how closely. */
    crop: TileCrop = TileCrop.NONE,
) {
    val description = itemDescription(item)
    Box(
        modifier
            .shared(SharedKey.item(item.id))
            .background(LibraryColors.Raised)
            .clipToBounds()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics {
                contentDescription = description
                if (selected != null) this.selected = selected
            },
    ) {
        CroppedThumbnail(item, Modifier.fillMaxSize(), crop)
        if (item.type == MediaType.VIDEO) {
            DurationChip(item.durationMillis, Modifier.align(Alignment.TopEnd).padding(5.dp))
        }
        if (selected != null) {
            if (selected) Box(Modifier.fillMaxSize().border(3.dp, LibraryColors.PhotoText))
            SelectionCheck(selected, Modifier.align(Alignment.TopStart).padding(7.dp))
        } else {
            BackupBadge(item.backup, badgeSize, Modifier.align(Alignment.BottomEnd).padding(5.dp), originalHere = item.hasOriginalHere)
        }
    }
}

@Composable
internal fun SelectionCheck(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(24.dp)
            .background(if (selected) LibraryColors.Accent else LibraryColors.BadgeScrim, CircleShape)
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(LibraryIcons.Check, null, tint = Color.White, modifier = Modifier.size(14.dp))
    }
}

/** Marks a video: a play icon, plus its length when known. */
@Composable
internal fun DurationChip(durationMillis: Long?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(LibraryColors.BadgeScrim, RoundedCornerShape(50))
            .padding(start = 4.dp, end = if (durationMillis != null) 6.dp else 4.dp, top = 2.dp, bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.size(9.dp))
        if (durationMillis != null) {
            Text(formatDuration(durationMillis), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * The corner badge: a phone when the original is on this phone and on the server, the upload state while it is only
 * here, nothing when it is only on the server ([originalHere] false). See docs/screens/README.md.
 */
@Composable
internal fun BackupBadge(status: BackupStatus, size: Dp, modifier: Modifier = Modifier, originalHere: Boolean = true) {
    if (!originalHere && status == BackupStatus.BackedUp) return
    val background = when (status) {
        is BackupStatus.Uploading -> LibraryColors.Accent
        BackupStatus.BackedUp -> LibraryColors.Accent.copy(alpha = 0.85f)
        is BackupStatus.Failed -> LibraryColors.Error
        else -> LibraryColors.BadgeScrim
    }
    Box(modifier.size(size).background(background, CircleShape), contentAlignment = Alignment.Center) {
        val inner = size * 0.6f
        when (status) {
            BackupStatus.NotBackedUp -> Icon(LibraryIcons.CloudOff, null, tint = Color.White, modifier = Modifier.size(inner))
            BackupStatus.BackedUp -> Icon(LibraryIcons.Phone, null, tint = Color.White, modifier = Modifier.size(inner))
            BackupStatus.Queued -> Canvas(Modifier.size(inner)) {
                drawCircle(
                    Color.White,
                    style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.5.dp.toPx(), 2.5.dp.toPx()))),
                )
            }
            is BackupStatus.Uploading -> Canvas(Modifier.size(inner)) {
                val stroke = 2.5.dp.toPx()
                drawCircle(Color.White.copy(alpha = 0.35f), style = Stroke(stroke))
                drawArc(Color.White, -90f, 360f * status.progress.coerceIn(0f, 1f), useCenter = false, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            is BackupStatus.Failed -> Text("!", color = Color.White, fontSize = (size.value * 0.6f).sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun backupLabel(status: BackupStatus): String = when (status) {
    BackupStatus.NotBackedUp -> stringResource(R.string.library_backup_not_backed_up)
    BackupStatus.Queued -> stringResource(R.string.library_backup_queued)
    is BackupStatus.Uploading -> stringResource(R.string.library_backup_uploading)
    BackupStatus.BackedUp -> stringResource(R.string.library_backup_done)
    is BackupStatus.Failed -> stringResource(R.string.library_backup_failed, status.reason)
}

/** Where the original is: on this phone and the server, only on one of them, or on its way. */
@Composable
internal fun originalLabel(item: MediaItem): String = when {
    item.backup != BackupStatus.BackedUp -> backupLabel(item.backup)
    !item.hasOriginalHere -> stringResource(R.string.library_original_server_only)
    !item.isOnDevice -> stringResource(R.string.library_original_kept)
    else -> stringResource(R.string.library_original_both)
}

/** "Photo, only on this device" for screen readers. */
@Composable
private fun itemDescription(item: MediaItem): String = stringResource(
    if (item.type == MediaType.VIDEO) R.string.library_video_description else R.string.library_photo_description,
    originalLabel(item),
)
