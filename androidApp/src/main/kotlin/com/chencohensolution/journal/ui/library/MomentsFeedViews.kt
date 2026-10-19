package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.Stable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.stories.CropRect
import com.chencohensolution.journal.stories.FeedBlock
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FeedMoment
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.stories.FeedTrip
import com.chencohensolution.journal.stories.MomentsFeed
import com.chencohensolution.journal.stories.PhotoNote

// The Moments tab: every photo reads like a story that arranged itself (see MomentsFeed in the shared logic).
// Photos are full width at their own shape; near-duplicates are one photo you swipe. See docs/screens/moments.md.

internal class FeedActions(
    val openItem: (FeedSection, MediaItem) -> Unit = { _, _ -> },
    /** "+ Story" on a row: asks for a title, then makes the story. */
    val newStory: (sectionKey: String) -> Unit = {},
    val saveTrip: (FeedTrip) -> Unit = {},
    val dismissTrip: (key: String) -> Unit = {},
    /** Long press on a photo: Write about it, Crop, Add to story, Tag. */
    val itemMenu: (FeedSection, MediaItem) -> Unit = { _, _ -> },
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

/** Photos and sets picked in Moments to merge, by their first photo's id; picking is on while any is. */
@Stable
internal class FeedSelection {
    var keys by mutableStateOf(emptySet<String>())
        private set
    val active: Boolean get() = keys.isNotEmpty()

    fun toggle(key: String) {
        keys = if (key in keys) keys - key else keys + key
    }

    fun clear() {
        keys = emptySet()
    }
}

/** The key a block is picked by: its first photo's id. */
internal val FeedBlock.key: String get() = items.first().id

/** The feed as list items; worked out once per feed. */
internal fun MomentsFeed.rows(): List<FeedRow> = entries.flatMap { entry ->
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
 * video plays. A photo's note ([notes], by id) is written above it, and its Moments crop sets its shape.
 */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.feedItems(
    rows: List<FeedRow>,
    actions: FeedActions,
    landing: String?,
    video: FeedVideo = FeedVideo(),
    notes: Map<String, PhotoNote> = emptyMap(),
    selection: FeedSelection = FeedSelection(),
) {
    items(rows, key = { it.key }, contentType = { it::class }) { row ->
        when (row) {
            is FeedRow.Trip -> TripCard(row.trip, actions)
            is FeedRow.Heading -> SectionHeading(row.section) { actions.newStory(row.section.key) }
            is FeedRow.Label -> MomentLabel(row.moment)
            is FeedRow.Photo -> when (val block = row.block) {
                is FeedBlock.Single -> Column(Modifier.padding(bottom = TILE_GAP)) {
                    PhotoNoteText(notes[block.item.id]?.caption)
                    Box {
                        FullWidthPhoto(
                            block.item,
                            video,
                            notes[block.item.id]?.crops?.moments,
                            Modifier.combinedClickable(
                                onLongClick = { if (selection.active) selection.toggle(block.key) else actions.itemMenu(row.section, block.item) },
                                onClick = { if (selection.active) selection.toggle(block.key) else actions.openItem(row.section, block.item) },
                            ),
                        )
                        if (selection.active) SelectMark(block.key in selection.keys, Modifier.align(Alignment.TopStart).padding(10.dp))
                    }
                }
                is FeedBlock.Similar -> SimilarPhotos(
                    block,
                    landing,
                    notes,
                    video,
                    Modifier.padding(bottom = TILE_GAP),
                    selected = if (selection.active) block.key in selection.keys else null,
                    onLongPress = { if (selection.active) selection.toggle(block.key) else actions.itemMenu(row.section, it) },
                ) { if (selection.active) selection.toggle(block.key) else actions.openItem(row.section, it) }
            }
        }
    }
}

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

/**
 * The photo's note above it, in the serif of written text: up to four lines, then "more" (tap to read it all).
 * Nothing when it has none.
 */
@Composable
private fun PhotoNoteText(caption: String?) {
    if (caption.isNullOrBlank()) return
    var expanded by remember(caption) { mutableStateOf(false) }
    val style = TextStyle(color = LibraryColors.OnBackground, fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 24.sp)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 12.dp)) {
        // Measured here, not after drawing, so "more" is there in the first frame.
        val cut = remember(caption, constraints.maxWidth) {
            measurer.measure(caption, style, maxLines = NOTE_LINES, constraints = Constraints(maxWidth = constraints.maxWidth)).hasVisualOverflow
        }
        Column(Modifier.fillMaxWidth().clickable(enabled = cut) { expanded = !expanded }) {
            Text(caption, style = style, maxLines = if (expanded) Int.MAX_VALUE else NOTE_LINES, overflow = TextOverflow.Ellipsis)
            if (cut && !expanded) {
                Text(stringResource(R.string.feed_note_more), color = LibraryColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

/**
 * A photo at its own shape (or its Moments [crop]'s), the width of the screen; a video plays in it, muted at first, while [video] says so. The
 * photo stays composed on top of the video and only turns transparent once the video shows, so starting and stopping
 * a video doesn't rebuild it.
 */
@Composable
private fun FullWidthPhoto(item: MediaItem, video: FeedVideo, crop: CropRect?, modifier: Modifier = Modifier) {
    // Videos aren't cropped (yet).
    val frame = crop?.takeIf { item.durationMillis == null }
    Box(modifier.fillMaxWidth().aspectRatio(frame?.ratioOn(item.ratio) ?: item.ratio).background(LibraryColors.Raised).shared(SharedKey.item(item.id))) {
        PhotoOrVideo(item, video, feedWidth(), frame)
    }
}

/**
 * [item] filling its box ([width] pixels wide, at its shape or its [frame]'s): a photo, or a video's poster with its
 * length, playing in place while [video] says so.
 */
@Composable
private fun PhotoOrVideo(item: MediaItem, video: FeedVideo, width: Int, frame: CropRect? = null) {
    val playing = video.isPlaying(item)
    // Start over each time it starts playing.
    var showing by remember(item.id, playing) { mutableStateOf(false) }
    var failed by remember(item.id, playing) { mutableStateOf(false) }
    Box(Modifier.fillMaxSize()) {
        if (playing) {
            InlineVideo(
                item,
                video.muted,
                onMute = { if (!video.muted) video.toggleMute() },
                onFirstFrame = { showing = true },
                onFailed = { failed = true },
                modifier = Modifier.fillMaxSize(),
            )
        }
        if (frame != null) {
            CropBox(frame, Modifier.fillMaxSize()) { SharpPhoto(item, (width / frame.w).toInt(), Modifier.fillMaxSize()) }
        } else {
            SharpPhoto(item, width, Modifier.fillMaxSize().graphicsLayer { alpha = if (playing && showing && !failed) 0f else 1f })
        }
        if (playing && !failed) {
            MuteButton(video.muted, video.toggleMute, Modifier.align(Alignment.BottomEnd).padding(4.dp))
        } else {
            // Where the original is, as on the grids: none for a photo only on the server.
            BackupBadge(item.backup, 26.dp, Modifier.align(Alignment.BottomEnd).padding(10.dp), originalHere = item.hasOriginalHere)
        }
        item.durationMillis?.let { VideoLength(it, Modifier.align(Alignment.BottomStart).padding(10.dp)) }
    }
}

/**
 * [item] filling this box, which has its shape and is [width] wide in pixels: MediaStore's thumbnail at once (at most
 * 512 px, blurry at full width), then the photo itself decoded at the box's size on top, sharp on any screen.
 */
@Composable
private fun SharpPhoto(item: MediaItem, width: Int, modifier: Modifier = Modifier) {
    Box(modifier) {
        rememberThumbnail(item, THUMB_LARGE)?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        SizedThumbnail(item, width, (width / item.ratio).toInt(), Modifier.fillMaxSize())
    }
}

/** The feed is the width of the window: its photos' size in pixels is known without measuring them. */
@Composable
private fun feedWidth(): Int = LocalWindowInfo.current.containerSize.width

/**
 * Near-duplicates as one photo at the first one's shape; swipe for the others ("2 / 4" and dots), each with its own
 * note above. Turns to the photo the viewer closes on, so it lands on its own page.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SimilarPhotos(
    block: FeedBlock.Similar,
    landing: String?,
    notes: Map<String, PhotoNote>,
    video: FeedVideo,
    modifier: Modifier = Modifier,
    /** While picking: whether this set is picked; null when not picking. */
    selected: Boolean? = null,
    onLongPress: (MediaItem) -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    // The list item is keyed by the set's first photo, so a set that grows while photos are compared keeps its page.
    // Opens on the photo it was last turned to, also after scrolling away or a new launch.
    val pager = rememberPagerState(initialPage = remember(block.key) { video.startPage(block) }) { block.items.size }
    LaunchedEffect(landing) {
        val page = block.items.indexOfFirst { it.id == landing }
        if (page >= 0 && page != pager.currentPage) pager.scrollToPage(page)
    }
    // The page shown is the one that plays, when it is a video.
    LaunchedEffect(pager.currentPage, block) {
        video.showPage(block, block.items[pager.currentPage.coerceIn(block.items.indices)].id)
    }
    val pageWidth = feedWidth()
    Column(modifier) {
        PhotoNoteText(notes[block.items[pager.currentPage.coerceIn(block.items.indices)].id]?.caption)
        Box(Modifier.fillMaxWidth().aspectRatio(block.items.first().ratio).background(LibraryColors.Raised)) {
            // The photos beside the one shown are ready before a swipe; one only on the server is fetched only once it
            // is next to the one shown (the pager's own look-ahead gets an empty page instead), not the whole set.
            HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = TILE_GAP, beyondViewportPageCount = 1) { page ->
                val item = block.items[page]
                val fetchable = item.hasOriginalHere || kotlin.math.abs(page - pager.currentPage) <= 1
                Box(
                    Modifier.fillMaxSize().background(Color.Black).combinedClickable(onLongClick = { onLongPress(item) }, onClick = { onOpen(item) }),
                    contentAlignment = Alignment.Center,
                ) {
                    // A photo of another shape than the set's first fits inside, at its own shape.
                    // Narrower than the set's first: as tall as the page, so as wide as its shape makes it.
                    val first = block.items.first().ratio
                    val width = if (item.ratio >= first) pageWidth else (pageWidth / first * item.ratio).toInt()
                    Box(Modifier.aspectRatio(item.ratio).shared(SharedKey.item(item.id))) {
                        if (fetchable) PhotoOrVideo(item, video, width)
                    }
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
            if (selected != null) SelectMark(selected, Modifier.align(Alignment.TopStart).padding(10.dp))
        }
    }
}

internal enum class FeedItemChoice { WRITE, CROP, ADD_TO_STORY, TAG, SELECT, TAKE_OUT }

/** While picking: a filled check when picked, an empty ring when not. */
@Composable
private fun SelectMark(selected: Boolean, modifier: Modifier = Modifier) {
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
 * A photo's long-press menu over the feed: the photo and what's written about it, then Write about it, Crop, Add to
 * story and Tag. Drawn in the screen (not a popup), so it shows in previews too.
 */
@Composable
internal fun FeedItemMenu(item: MediaItem, note: String?, inSet: Boolean, onDismiss: () -> Unit, onChoose: (FeedItemChoice) -> Unit) {
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
                        note?.takeIf { it.isNotBlank() } ?: stringResource(R.string.feed_menu_no_note),
                        color = LibraryColors.OnInverseMuted,
                        fontFamily = if (note.isNullOrBlank()) null else FontFamily.Serif,
                        fontSize = 14.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            val hasNote = !note.isNullOrBlank()
            listOf(
                Triple(FeedItemChoice.WRITE, LibraryIcons.Edit, if (hasNote) R.string.feed_menu_edit_note else R.string.feed_menu_write),
                Triple(FeedItemChoice.CROP, LibraryIcons.Crop, R.string.feed_menu_crop),
                Triple(FeedItemChoice.ADD_TO_STORY, LibraryIcons.Plus, R.string.library_add_to_story),
                Triple(FeedItemChoice.TAG, LibraryIcons.Tag, R.string.library_tag),
                Triple(FeedItemChoice.SELECT, LibraryIcons.Check, R.string.feed_menu_select),
                Triple(FeedItemChoice.TAKE_OUT, LibraryIcons.Minus, R.string.feed_menu_take_out).takeIf { inSet },
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

/** A note longer than this shows "more". */
private const val NOTE_LINES = 4

/** A set longer than this shows this many dots, the last one standing for the rest. */
private const val MAX_DOTS = 8

@Composable
internal fun sourceName(source: MediaSource): String = stringResource(
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
