package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.PathEffect
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

/** The gap between photo tiles in a grid. */
internal val TILE_GAP = 2.dp

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
        CroppedThumbnail(item, Modifier.fillMaxSize())
        if (item.type == MediaType.VIDEO) {
            DurationChip(item.durationMillis, Modifier.align(Alignment.TopEnd).padding(5.dp))
        }
        if (selected != null) {
            if (selected) Box(Modifier.fillMaxSize().border(3.dp, LibraryColors.PhotoText))
            SelectionCheck(selected, Modifier.align(Alignment.TopStart).padding(7.dp))
        } else {
            BackupBadge(
                item.backup,
                badgeSize,
                Modifier.align(Alignment.BottomEnd).padding(5.dp),
                originalHere = item.hasOriginalHere,
                showOnPhone = LocalShowOnPhoneBadge.current,
            )
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

/** Settings → Badges, "Show the phone badge", for the tiles and Moments; off unless provided. */
internal val LocalShowOnPhoneBadge = staticCompositionLocalOf { false }

/**
 * The corner badge: a phone when the original is on this phone and on the server (only if [showOnPhone]), the upload
 * state while it is only here, nothing when it is only on the server ([originalHere] false). See
 * docs/screens/README.md.
 */
@Composable
internal fun BackupBadge(
    status: BackupStatus,
    size: Dp,
    modifier: Modifier = Modifier,
    originalHere: Boolean = true,
    showOnPhone: Boolean = true,
) {
    if ((!originalHere || !showOnPhone) && status == BackupStatus.BackedUp) return
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
