package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.stories.StoryView
import com.chencohensolution.journal.stories.photo

/** What a story card on the journal shows; see [cardState]. */
internal data class StoryCardState(
    /** Optional: an untitled story shows only its dates and count above its photos. */
    val title: String?,
    /** "Oct 8 – 16 · 9 days · 36 photos". */
    val summary: String,
    /** The story's main photos, in the story's order; a group counts as its cover. */
    val highlights: List<MediaItem>,
    val photoCount: Int,
    val notBackedUpCount: Int,
)

/** A story's main photos are its first tiles, in its order, a group as its cover. */
@Composable
internal fun cardState(story: StoryView, count: Int = 6) = StoryCardState(
    title = story.title.takeIf { story.hasTitle },
    summary = storySummary(story),
    highlights = story.tiles.map { it.photo }.take(count),
    photoCount = story.itemCount,
    notBackedUpCount = story.notBackedUpCount,
)

/** A story on the journal: its title (when it has one) and dates above a mosaic of its main photos. */
@Composable
internal fun StoryCard(card: StoryCardState, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = card.title ?: card.summary, onClick = onOpen)
            .padding(bottom = 22.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                card.title?.let {
                    Text(
                        it,
                        color = LibraryColors.OnBackground,
                        fontSize = 22.sp,
                        lineHeight = 26.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.semantics { heading() },
                    )
                }
                Text(
                    card.summary,
                    color = if (card.title == null) LibraryColors.OnBackground else LibraryColors.Muted,
                    fontSize = if (card.title == null) 15.sp else 13.sp,
                    fontWeight = if (card.title == null) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
            if (card.notBackedUpCount > 0) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(LibraryIcons.CloudOff, null, tint = LibraryColors.Muted, modifier = Modifier.size(14.dp))
                    Text(card.notBackedUpCount.toString(), color = LibraryColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        HighlightMosaic(card.highlights, more = card.photoCount - card.highlights.size)
    }
}

/**
 * One photo full width; two side by side; a large one with two beside it; with six, a row of three under them. The
 * last tile says how many more photos the story has.
 */
@Composable
private fun HighlightMosaic(items: List<MediaItem>, more: Int) {
    val last = items.lastOrNull()
    val tile: @Composable (MediaItem, Modifier) -> Unit = { item, modifier ->
        Highlight(item, modifier, more = if (item == last) more else 0)
    }
    when {
        items.isEmpty() -> Box(Modifier.fillMaxWidth().aspectRatio(3f).background(LibraryColors.Raised))
        items.size == 1 -> tile(items[0], Modifier.fillMaxWidth().aspectRatio(1.5f))
        items.size == 2 -> Row(Modifier.fillMaxWidth().aspectRatio(2f), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            items.forEach { tile(it, Modifier.weight(1f).fillMaxHeight()) }
        }
        else -> Column(verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
            Row(Modifier.fillMaxWidth().aspectRatio(1.5f), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                tile(items[0], Modifier.weight(2f).fillMaxHeight())
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    tile(items[1], Modifier.weight(1f).fillMaxWidth())
                    tile(items[2], Modifier.weight(1f).fillMaxWidth())
                }
            }
            if (items.size >= 6) {
                Row(Modifier.fillMaxWidth().aspectRatio(3f), horizontalArrangement = Arrangement.spacedBy(TILE_GAP)) {
                    items.subList(3, 6).forEach { tile(it, Modifier.weight(1f).fillMaxHeight()) }
                }
            }
        }
    }
}

/** A photo cropped to its tile; with [more], "+12" over it. */
@Composable
private fun Highlight(item: MediaItem, modifier: Modifier, more: Int) {
    Box(modifier.background(LibraryColors.Raised)) {
        CroppedThumbnail(item, Modifier.fillMaxSize())
        if (more > 0) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.38f)), contentAlignment = Alignment.Center) {
                Text("+$more", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}
