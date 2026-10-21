package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.stories.MomentsFeed

// Around the Moments feed: the source bar, trip cards, the selection bar and the photo menu.

/** The filter bar: each source with its count, on (filled, with a check) or off (outlined). */
@Composable
internal fun FeedSourceBar(feed: MomentsFeed, onToggle: (MediaSource) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        MediaSource.entries.filter { it in feed.shown || (feed.counts[it] ?: 0) > 0 }.forEach { source ->
            val on = source in feed.shown
            val content = if (on) LibraryColors.OnInverse else LibraryColors.Muted
            Row(
                Modifier
                    .height(34.dp)
                    .then(
                        if (on) {
                            Modifier.background(LibraryColors.Inverse, RoundedCornerShape(50))
                        } else {
                            Modifier.border(1.dp, LibraryColors.Muted.copy(alpha = 0.45f), RoundedCornerShape(50))
                        },
                    )
                    .clickable { onToggle(source) }
                    .semantics {
                        role = Role.Checkbox
                        selected = on
                    }
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(if (on) LibraryIcons.Check else source.icon, null, tint = content, modifier = Modifier.size(15.dp))
                Text(sourceName(source), color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "${feed.counts[source] ?: 0}",
                    color = if (on) LibraryColors.OnInverseMuted else LibraryColors.Muted.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}

internal enum class FeedItemChoice { WRITE, CROP, TAG, REMOVE_FROM_STORYLINE, ADD_TO_STORYLINE, SELECT, TAKE_OUT }

/** While picking: a filled check when picked, an empty ring when not. */
@Composable
internal fun SelectMark(selected: Boolean, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(28.dp)
            .background(if (selected) LibraryColors.Accent else Color.Black.copy(alpha = 0.25f), CircleShape)
            .border(2.dp, Color.White, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (selected) Icon(LibraryIcons.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
    }
}

/** While picking, in place of the tabs: Cancel, "2 picked", and Merge once two or more are picked. */
@Composable
internal fun FeedSelectionBar(count: Int, onCancel: () -> Unit, onMerge: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.background(LibraryColors.Inverse, RoundedCornerShape(50)).padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TextButton(onClick = onCancel) { Text(stringResource(R.string.library_cancel), color = LibraryColors.OnInverse) }
        Text(
            pluralStringResource(R.plurals.feed_selected, count, count),
            color = LibraryColors.OnInverseMuted,
            fontSize = 14.sp,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
        val canMerge = count >= 2
        Box(
            Modifier
                .height(40.dp)
                .background(if (canMerge) LibraryColors.Accent else LibraryColors.OnInverseMuted.copy(alpha = 0.3f), RoundedCornerShape(50))
                .clickable(enabled = canMerge, onClick = onMerge)
                .padding(horizontal = 20.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(stringResource(R.string.feed_merge), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

/**
 * A photo's long-press menu over the feed: the photo and what's written about it, then Write about it, Crop, Tag,
 * Remove from (or Add to) the storyline once it has writing, and in Moments ([inMoments]) Select and, in a set
 * ([inSet]), Take out of set. Drawn in the screen (not a popup), so it shows in previews too.
 */
@Composable
internal fun FeedItemMenu(
    item: MediaItem,
    note: PhotoNote?,
    inSet: Boolean,
    onDismiss: () -> Unit,
    onChoose: (FeedItemChoice) -> Unit,
    inMoments: Boolean = true,
) {
    val written = note?.let { it.title.ifBlank { it.caption } }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = onDismiss)) {
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(LibraryColors.Inverse, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .clickable(enabled = false) {}
                .navigationBarsPadding()
                .padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 12.dp),
        ) {
            Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 10.dp).size(36.dp, 4.dp).background(LibraryColors.OnInverseMuted.copy(alpha = 0.4f), CircleShape))
            Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(56.dp).clip(RoundedCornerShape(10.dp)).background(LibraryColors.Raised)) {
                    CroppedThumbnail(item, Modifier.fillMaxSize())
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.feed_place_time, dayLabel(item.day), timeLabel(item.takenAtMillis)),
                        color = LibraryColors.OnInverse,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        written?.takeIf { it.isNotBlank() } ?: stringResource(R.string.feed_menu_no_note),
                        color = LibraryColors.OnInverseMuted,
                        fontFamily = if (written.isNullOrBlank()) null else FontFamily.Serif,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val hasNote = note?.hasWriting == true
            listOf(
                Triple(FeedItemChoice.WRITE, LibraryIcons.Edit, if (hasNote) R.string.feed_menu_edit_note else R.string.feed_menu_write),
                Triple(FeedItemChoice.CROP, LibraryIcons.Crop, R.string.feed_menu_crop),
                Triple(FeedItemChoice.TAG, LibraryIcons.Tag, R.string.library_tag),
                when {
                    note?.onStoryline == true -> Triple(FeedItemChoice.REMOVE_FROM_STORYLINE, LibraryIcons.Minus, R.string.storyline_remove)
                    hasNote -> Triple(FeedItemChoice.ADD_TO_STORYLINE, LibraryIcons.Plus, R.string.storyline_add)
                    else -> null
                },
                Triple(FeedItemChoice.SELECT, LibraryIcons.Check, R.string.feed_menu_select).takeIf { inMoments },
                Triple(FeedItemChoice.TAKE_OUT, LibraryIcons.Minus, R.string.feed_menu_take_out).takeIf { inMoments && inSet },
            ).filterNotNull().forEach { (choice, icon, label) ->
                Row(
                    Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(12.dp)).clickable { onChoose(choice) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(icon, null, tint = LibraryColors.OnInverse, modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(16.dp))
                    Text(stringResource(label), color = LibraryColors.OnInverse, fontSize = 16.sp)
                }
            }
        }
    }
}

/** Towns shown in a row's heading; a long day shows where it started. */
internal const val MAX_TOWNS = 3

@Composable
internal fun sourceName(source: MediaSource): String = stringResource(
    when (source) {
        MediaSource.CAMERA -> R.string.feed_source_camera
        MediaSource.WHATSAPP -> R.string.feed_source_whatsapp
        MediaSource.SCREENSHOTS -> R.string.feed_source_screenshots
        MediaSource.OTHER -> R.string.feed_source_other
    },
)

internal val MediaSource.icon: ImageVector
    get() = when (this) {
        MediaSource.CAMERA -> FeedIcons.Camera
        MediaSource.WHATSAPP -> FeedIcons.Chat
        MediaSource.SCREENSHOTS -> FeedIcons.Phone
        MediaSource.OTHER -> FeedIcons.Download
    }

/** Line icons for the feed, drawn like [LibraryIcons]. */
internal object FeedIcons {
    private fun icon(name: String, vararg paths: String) =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            paths.forEach { data ->
                addPath(
                    pathData = PathParser().parsePathString(data).toNodes(),
                    stroke = SolidColor(Color.Black),
                    strokeLineWidth = 2f,
                    strokeLineCap = StrokeCap.Round,
                    strokeLineJoin = StrokeJoin.Round,
                )
            }
        }.build()

    val Pin = icon("Pin", "M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21z", "M12 7.5a2 2 0 1 0 0.01 0")
    val Camera = icon("Camera", "M4 7h3l2-3h6l2 3h3a1 1 0 0 1 1 1v11a1 1 0 0 1-1 1H4a1 1 0 0 1-1-1V8a1 1 0 0 1 1-1z", "M12 9.5a3.5 3.5 0 1 0 0.01 0")
    val Phone = icon("Phone", "M8 2h8a2 2 0 0 1 2 2v16a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2z", "M11 18h2")
    val Chat = icon("Chat", "M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z")
    val Download = icon("Download", "M12 4v11", "M7 10l5 5 5-5", "M5 20h14")
}
