package com.chencohensolution.journal.ui.library

import android.content.ContentResolver
import android.content.ContentUris
import android.graphics.Bitmap
import android.graphics.ImageDecoder
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
import com.chencohensolution.journal.library.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Thumbnail sizes in pixels; few sizes so the cache is shared between screens. */
internal const val THUMB_SMALL = 256
internal const val THUMB_LARGE = 640
internal const val THUMB_FULL = 1280

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
    val key = "${item.id}@$sizePx"
    // The state outlives a change of item, so each new key replaces the value, even with null when it can't be read.
    val bitmap by produceState(thumbnailCache.get(key), key) {
        value = thumbnailCache.get(key) ?: withContext(Dispatchers.IO) {
            runCatching { resolver.loadThumbnail(item.contentUri(), Size(sizePx, sizePx), null) }.getOrNull()
        }?.also { thumbnailCache.put(key, it) }
    }
    return bitmap?.asImageBitmap()
}

/** The full image scaled so its longer side is at most [maxSide] pixels. */
internal fun loadImage(resolver: ContentResolver, item: MediaItem, maxSide: Int): Bitmap? = runCatching {
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
    thumbnailSize: Int = THUMB_SMALL,
    badgeSize: Dp = 22.dp,
    onLongClick: (() -> Unit)? = null,
    selected: Boolean? = null,
    /** Which part of the photo stays in view when it is cropped, and how closely. */
    crop: TileCrop = TileCrop.NONE,
) {
    val thumbnail = rememberThumbnail(item, thumbnailSize)
    val description = itemDescription(item)
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
            Image(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, alignment = crop.alignment, modifier = Modifier.fillMaxSize().zoomAround(crop))
        }
        if (item.type == MediaType.VIDEO) {
            DurationChip(item.durationMillis, Modifier.align(Alignment.TopEnd).padding(5.dp))
        }
        if (selected != null) {
            if (selected) Box(Modifier.fillMaxSize().border(3.dp, LibraryColors.PhotoText))
            SelectionCheck(selected, Modifier.align(Alignment.TopStart).padding(7.dp))
        } else {
            BackupBadge(item.backup, badgeSize, Modifier.align(Alignment.BottomEnd).padding(5.dp))
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

/** The corner badge; see docs/screens/README.md for what each state means. */
@Composable
internal fun BackupBadge(status: BackupStatus, size: Dp, modifier: Modifier = Modifier) {
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
            BackupStatus.BackedUp -> Icon(LibraryIcons.Check, null, tint = Color.White, modifier = Modifier.size(inner))
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

/** "Photo, only on this device" for screen readers. */
@Composable
private fun itemDescription(item: MediaItem): String = stringResource(
    if (item.type == MediaType.VIDEO) R.string.library_video_description else R.string.library_photo_description,
    backupLabel(item.backup),
)
