package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.stories.FeedBlock
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FeedMoment
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.stories.FeedTrip
import com.chencohensolution.journal.stories.UnsortedFeed

// The Unsorted tab: unsorted photos read like a story that arranged itself (see UnsortedFeed in the shared logic).
// Photos are full width at their own shape; near-duplicates are one photo you swipe. See docs/screens/unsorted.md.

internal class FeedActions(
    val openItem: (FeedSection, MediaItem) -> Unit = { _, _ -> },
    /** "+ Story" on a row: asks for a title, then makes the story. */
    val newStory: (sectionKey: String) -> Unit = {},
    val saveTrip: (FeedTrip) -> Unit = {},
    val dismissTrip: (key: String) -> Unit = {},
)

/**
 * One item of the feed's list: a trip card, a row's heading, a moment's label, or one photo or near-duplicate set.
 * A photo per item keeps the list lazy: a moment of a hundred photos lays out only the ones in view.
 */
internal sealed interface FeedRow {
    val key: String

    data class Trip(val trip: FeedTrip) : FeedRow {
        override val key get() = trip.key
    }

    data class Heading(val section: FeedSection) : FeedRow {
        override val key get() = "heading-${section.key}"
    }

    data class Label(val moment: FeedMoment) : FeedRow {
        override val key get() = "label-${moment.key}"
    }

    data class Photo(val section: FeedSection, val block: FeedBlock) : FeedRow {
        override val key get() = "photo-${block.items.first().id}"
    }
}

/** The feed as list items; worked out once per feed. */
internal fun UnsortedFeed.rows(): List<FeedRow> = entries.flatMap { entry ->
    when (entry) {
        is FeedEntry.Trip -> listOf(FeedRow.Trip(entry.trip))
        is FeedEntry.Section -> listOf(FeedRow.Heading(entry.section)) + entry.section.moments.flatMap { moment ->
            listOf(FeedRow.Label(moment)) + moment.blocks.map { FeedRow.Photo(entry.section, it) }
        }
    }
}

/** The item of [rows] that shows [itemId], or -1: for landing from the viewer. */
internal fun List<FeedRow>.rowOf(itemId: String): Int = indexOfFirst { row -> row is FeedRow.Photo && row.block.items.any { it.id == itemId } }

/**
 * The feed's [rows], for the home screen's list. [landing] is the photo the viewer above shows; [video] says which
 * video plays.
 */
internal fun LazyListScope.feedItems(rows: List<FeedRow>, actions: FeedActions, landing: String?, video: FeedVideo = FeedVideo()) {
    items(rows, key = { it.key }, contentType = { it::class }) { row ->
        when (row) {
            is FeedRow.Trip -> TripCard(row.trip, actions)
            is FeedRow.Heading -> SectionHeading(row.section) { actions.newStory(row.section.key) }
            is FeedRow.Label -> MomentLabel(row.moment)
            is FeedRow.Photo -> when (val block = row.block) {
                is FeedBlock.Single -> FullWidthPhoto(
                    block.item,
                    video,
                    Modifier.padding(bottom = TILE_GAP).clickable { actions.openItem(row.section, block.item) },
                )
                is FeedBlock.Similar -> SimilarPhotos(block, landing, Modifier.padding(bottom = TILE_GAP)) { actions.openItem(row.section, it) }
            }
        }
    }
}

/** The filter bar: each source with its count, on (filled, with a check) or off (outlined). */
@Composable
internal fun FeedSourceBar(feed: UnsortedFeed, onToggle: (MediaSource) -> Unit, modifier: Modifier = Modifier) {
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

/** "Today", the towns visited ("Tel Aviv → Jaffa · 21 photos") and "+ Story". */
@Composable
private fun SectionHeading(section: FeedSection, onNewStory: () -> Unit) {
    val count = section.moments.sumOf { it.items.size }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                momentLabel(section.span),
                color = LibraryColors.OnBackground,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                (section.places.take(MAX_TOWNS).joinToString(" → ").ifEmpty { null }?.let { "$it · " }.orEmpty()) +
                    pluralStringResource(R.plurals.library_story_items, count, count),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        TextButton(
            onClick = onNewStory,
            shape = RoundedCornerShape(50),
            modifier = Modifier.height(36.dp).border(1.dp, LibraryColors.OnBackground.copy(alpha = 0.28f), RoundedCornerShape(50)),
        ) {
            Icon(LibraryIcons.Plus, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(14.dp))
            Text(
                stringResource(R.string.library_story),
                color = LibraryColors.OnBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 4.dp),
            )
        }
    }
}

/** A moment's label above its photos: "Jaffa Port · 18:10 – 19:05", or "WhatsApp · 6 · 14:02 – 17:30". */
@Composable
private fun MomentLabel(moment: FeedMoment) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (moment.source == MediaSource.CAMERA) FeedIcons.Pin else moment.source.icon, null, tint = LibraryColors.Muted, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            momentHeading(moment),
            color = LibraryColors.Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun momentHeading(moment: FeedMoment): String {
    val start = timeLabel(moment.startMillis)
    val end = timeLabel(moment.endMillis)
    val time = if (start == end) start else stringResource(R.string.feed_time_range, start, end)
    val what = if (moment.source == MediaSource.CAMERA) {
        moment.place
    } else {
        stringResource(R.string.feed_source_count, sourceName(moment.source), moment.items.size)
    }
    return what?.let { stringResource(R.string.feed_place_time, it, time) } ?: time
}

/** A photo at its own shape, the width of the screen; a video plays in it, muted at first, while [video] says so. */
@Composable
private fun FullWidthPhoto(item: MediaItem, video: FeedVideo, modifier: Modifier = Modifier) {
    val playing = item.id == video.playingId
    Box(modifier.fillMaxWidth().aspectRatio(item.ratio).background(LibraryColors.Raised).shared(SharedKey.item(item.id))) {
        if (playing) {
            InlineVideo(
                item,
                video.muted,
                onMute = { if (!video.muted) video.toggleMute() },
                poster = { SharpPhoto(item, Modifier.fillMaxSize()) },
            ) {
                Box(Modifier.fillMaxSize()) { MuteButton(video.muted, video.toggleMute, Modifier.align(Alignment.BottomEnd).padding(4.dp)) }
            }
        } else {
            SharpPhoto(item, Modifier.fillMaxSize())
        }
        item.durationMillis?.let { VideoLength(it, Modifier.align(Alignment.BottomStart).padding(10.dp)) }
    }
}

/**
 * [item] filling this box, which has its shape: MediaStore's thumbnail at once (at most 512 px, blurry at full width),
 * then the photo itself decoded at the box's size in pixels on top, sharp on any screen.
 */
@Composable
private fun SharpPhoto(item: MediaItem, modifier: Modifier = Modifier) {
    Box(modifier) {
        rememberThumbnail(item, THUMB_LARGE)?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        CroppedThumbnail(item, Modifier.fillMaxSize())
    }
}

/**
 * Near-duplicates as one photo at the first one's shape; swipe for the others ("2 / 4" and dots). Turns to the photo
 * the viewer closes on, so it lands on its own page.
 */
@Composable
private fun SimilarPhotos(block: FeedBlock.Similar, landing: String?, modifier: Modifier = Modifier, onOpen: (MediaItem) -> Unit) {
    // The list item is keyed by the set's first photo, so a set that grows while photos are compared keeps its page.
    val pager = rememberPagerState { block.items.size }
    LaunchedEffect(landing) {
        val page = block.items.indexOfFirst { it.id == landing }
        if (page >= 0 && page != pager.currentPage) pager.scrollToPage(page)
    }
    Box(modifier.fillMaxWidth().aspectRatio(block.items.first().ratio).background(LibraryColors.Raised)) {
        HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = TILE_GAP) { page ->
            val item = block.items[page]
            Box(Modifier.fillMaxSize().background(Color.Black).clickable { onOpen(item) }, contentAlignment = Alignment.Center) {
                // A photo of another shape than the set's first fits inside, at its own shape.
                SharpPhoto(item, Modifier.aspectRatio(item.ratio).shared(SharedKey.item(item.id)))
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(10.dp).background(LibraryColors.BadgeScrim, RoundedCornerShape(50)).padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LibraryIcons.Stack, null, tint = LibraryColors.PhotoText, modifier = Modifier.size(13.dp))
            Text(
                stringResource(R.string.feed_similar_position, pager.currentPage + 1, block.items.size),
                color = LibraryColors.PhotoText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
        Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            repeat(block.items.size.coerceAtMost(MAX_DOTS)) { i ->
                val current = i == pager.currentPage.coerceAtMost(MAX_DOTS - 1)
                Box(Modifier.size(if (current) 7.dp else 6.dp).background(Color.White.copy(alpha = if (current) 1f else 0.5f), CircleShape))
            }
        }
    }
}

/** Days away from home, offered as one story; "Not now" doesn't offer it again. */
@Composable
private fun TripCard(trip: FeedTrip, actions: FeedActions) {
    Column(
        Modifier
            .padding(start = 12.dp, end = 12.dp, top = 24.dp)
            .fillMaxWidth()
            .background(LibraryColors.Inverse, RoundedCornerShape(18.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(52.dp).background(LibraryColors.Raised, RoundedCornerShape(10.dp))) {
                CroppedThumbnail(trip.cover, Modifier.fillMaxSize())
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.feed_trip_kicker), color = LibraryColors.OnInverseMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    trip.place ?: stringResource(R.string.feed_trip_untitled),
                    color = LibraryColors.OnInverse,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    listOf(
                        dateRange(trip.firstDay, trip.lastDay),
                        pluralStringResource(R.plurals.library_story_days, trip.days, trip.days),
                        pluralStringResource(R.plurals.library_story_items, trip.items.size, trip.items.size),
                    ).joinToString(" · "),
                    color = LibraryColors.OnInverseMuted,
                    fontSize = 13.sp,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.weight(1f).height(40.dp).background(LibraryColors.Accent, RoundedCornerShape(50)).clickable { actions.saveTrip(trip) },
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.feed_trip_save), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
            Box(
                Modifier
                    .height(40.dp)
                    .border(1.dp, LibraryColors.OnInverseMuted.copy(alpha = 0.5f), RoundedCornerShape(50))
                    .clickable { actions.dismissTrip(trip.key) }
                    .padding(horizontal = 18.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(stringResource(R.string.library_burst_not_now), color = LibraryColors.OnInverse, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

@Composable
private fun VideoLength(millis: Long, modifier: Modifier = Modifier) {
    Row(
        modifier.background(LibraryColors.BadgeScrim, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.Play, null, tint = LibraryColors.PhotoText, modifier = Modifier.size(12.dp))
        Text(formatDuration(millis), color = LibraryColors.PhotoText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Towns shown in a row's heading; a long day shows where it started. */
private const val MAX_TOWNS = 3

/** A set longer than this shows this many dots, the last one standing for the rest. */
private const val MAX_DOTS = 8

private val MediaItem.ratio: Float
    get() = if (width > 0 && height > 0) width.toFloat() / height else 4f / 3f

@Composable
private fun sourceName(source: MediaSource): String = stringResource(
    when (source) {
        MediaSource.CAMERA -> R.string.feed_source_camera
        MediaSource.WHATSAPP -> R.string.feed_source_whatsapp
        MediaSource.SCREENSHOTS -> R.string.feed_source_screenshots
        MediaSource.OTHER -> R.string.feed_source_other
    },
)

private val MediaSource.icon: ImageVector
    get() = when (this) {
        MediaSource.CAMERA -> FeedIcons.Camera
        MediaSource.WHATSAPP -> FeedIcons.Chat
        MediaSource.SCREENSHOTS -> FeedIcons.Phone
        MediaSource.OTHER -> FeedIcons.Download
    }

/** Line icons for the feed, drawn like [LibraryIcons]. */
private object FeedIcons {
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
