package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import com.chencohensolution.journal.stories.Moment
import com.chencohensolution.journal.stories.MomentsFeed
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.stories.RevealedPhotos
import java.time.LocalDate

// The Moments tab: every photo reads like a story that arranged itself (see MomentsFeed in the shared logic).
// Photos are full width at their own shape; near-duplicates are one photo you swipe. See docs/screens/moments.md.

internal class FeedActions(
    val openItem: (FeedSection, MediaItem) -> Unit = { _, _ -> },
    val saveTrip: (FeedTrip) -> Unit = {},
    val dismissTrip: (key: String) -> Unit = {},
    /** Long press on a photo: Write about it, Crop, Add to story, Tag. */
    val itemMenu: (FeedSection, MediaItem) -> Unit = { _, _ -> },
    /** A "+6 WhatsApp" chip: that source's hidden photos of the row, in a band under it; "Hide WhatsApp" again. */
    val showOnRow: (FeedSection, MediaSource) -> Unit = { _, _ -> },
    val hideOnRow: (FeedSection, MediaSource) -> Unit = { _, _ -> },
    /** A photo of a band; the viewer swipes through the band. */
    val openRevealed: (FeedSection, RevealedPhotos, MediaItem) -> Unit = { _, _, _ -> },
    /** A band's "Add to a story" and "Always show this day". */
    val addRevealedToStory: (RevealedPhotos) -> Unit = {},
    val alwaysShow: (FeedSection, MediaSource) -> Unit = { _, _ -> },
)

/**
 * One item of the feed's list: a trip card, a row's divider, a moment's label, or one photo or near-duplicate set.
 * A photo per item keeps the list lazy: a moment of a hundred photos lays out only the ones in view. Counts, towns and
 * times are worked out with the rows, off the main thread, so an item coming into view only lays them out.
 */
internal sealed interface FeedRow {
    val key: String

    data class Trip(val trip: FeedTrip) : FeedRow {
        override val key get() = trip.key
    }

    /** [count]: the row's photos; [towns]: "Tel Aviv → Jaffa", empty when it has no places. */
    data class Heading(val section: FeedSection, val count: Int, val towns: String) : FeedRow {
        override val key get() = "heading-${section.key}"
    }

    /**
     * [showPlace]: a camera moment whose place differs from the camera moment above it in its row. [count]: its photos;
     * [start] and [end]: its times as [timeLabel] writes them.
     */
    data class Label(val moment: FeedMoment, val showPlace: Boolean, val count: Int, val start: String, val end: String) : FeedRow {
        override val key get() = "label-${moment.key}"
    }

    data class Photo(val section: FeedSection, val block: FeedBlock) : FeedRow {
        override val key get() = "photo-${block.items.first().id}"
    }

    /** A band of a hidden source's photos shown on a row: its heading, rows of three, and its links. */
    data class BandTop(val section: FeedSection, val band: RevealedPhotos, val start: String, val end: String) : FeedRow {
        override val key get() = "band-${section.key}-${band.source}"
    }

    data class BandPhotos(val section: FeedSection, val band: RevealedPhotos, val items: List<MediaItem>) : FeedRow {
        override val key get() = "band-photos-${items.first().id}"
    }

    data class BandEnd(val section: FeedSection, val band: RevealedPhotos) : FeedRow {
        override val key get() = "band-end-${section.key}-${band.source}"
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

/** The feed as list items; worked out once per feed, off the main thread. */
internal fun MomentsFeed.rows(): List<FeedRow> = entries.flatMap { entry ->
    when (entry) {
        is FeedEntry.Trip -> listOf(FeedRow.Trip(entry.trip))
        is FeedEntry.Section -> {
            var place: String? = null
            val section = entry.section
            val heading = FeedRow.Heading(section, section.moments.sumOf { it.count() }, section.places.take(MAX_TOWNS).joinToString(" → "))
            listOf(heading) + section.moments.flatMap { moment ->
                val camera = moment.source == MediaSource.CAMERA
                val showPlace = camera && moment.place != null && moment.place != place
                if (camera) place = moment.place
                val label = FeedRow.Label(moment, showPlace, moment.count(), timeLabel(moment.startMillis), timeLabel(moment.endMillis))
                listOf(label) + moment.shownBlocks.map { FeedRow.Photo(section, it) }
            } + section.revealed.flatMap { band ->
                listOf(FeedRow.BandTop(section, band, timeLabel(band.startMillis), timeLabel(band.endMillis))) +
                    band.items.chunked(BAND_COLUMNS).map { FeedRow.BandPhotos(entry.section, band, it) } +
                    FeedRow.BandEnd(section, band)
            }
        }
    }
}

/** Its photos, counted without listing them ([FeedMoment.items] builds a new list). */
private fun FeedMoment.count(): Int = blocks.sumOf { if (it is FeedBlock.Single) 1 else it.items.size }

/** The item of [rows] that shows [itemId], or -1: for landing from the viewer. */
internal fun List<FeedRow>.rowOf(itemId: String): Int = indexOfFirst { row ->
    (row is FeedRow.Photo && row.block.items.any { it.id == itemId }) || (row is FeedRow.BandPhotos && row.items.any { it.id == itemId })
}

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
            is FeedRow.Heading -> DayDivider(row, actions)
            is FeedRow.BandTop -> BandTop(row)
            is FeedRow.BandPhotos -> BandPhotos(row.items) { actions.openRevealed(row.section, row.band, it) }
            is FeedRow.BandEnd -> BandEnd(row.band, onAdd = { actions.addRevealedToStory(row.band) }) { actions.alwaysShow(row.section, row.band.source) }
            is FeedRow.Label -> MomentLabel(row)
            is FeedRow.Photo -> when (val block = row.block) {
                is FeedBlock.Single -> Column(Modifier.padding(bottom = TILE_GAP)) {
                    PhotoTitle(notes[block.item.id]?.title)
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
                    PhotoNoteText(notes[block.item.id]?.caption)
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

/**
 * The line between rows, "Today · Tue, Oct 20" on it, then the towns visited and the count ("Tel Aviv → Jaffa · 21
 * photos"), and a chip per source it hides ("+6 WhatsApp"), or "Hide WhatsApp" once shown.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayDivider(heading: FeedRow.Heading, actions: FeedActions) {
    val section = heading.section
    val count = heading.count
    val line = LibraryColors.Muted.copy(alpha = 0.3f)
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).background(line))
            Text(
                dividerLabel(section.span),
                color = LibraryColors.OnBackground,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .padding(horizontal = 12.dp)
                    .background(LibraryColors.Raised, RoundedCornerShape(50))
                    .padding(horizontal = 14.dp, vertical = 6.dp)
                    .semantics { heading() },
            )
            Box(Modifier.weight(1f).height(1.dp).background(line))
        }
        val shownHere = section.revealed.map { it.source }
        FlowRow(
            Modifier.padding(top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            itemVerticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                (heading.towns.ifEmpty { null }?.let { "$it · " }.orEmpty()) +
                    pluralStringResource(R.plurals.library_story_items, count, count),
                color = LibraryColors.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            MediaSource.entries.forEach { source ->
                when {
                    source in shownHere -> HiddenChip(stringResource(R.string.feed_hide_source, sourceName(source)), on = true) { actions.hideOnRow(section, source) }
                    source in section.hidden -> HiddenChip(stringResource(R.string.feed_hidden_chip, section.hidden.getValue(source), sourceName(source)), on = false) {
                        actions.showOnRow(section, source)
                    }
                }
            }
        }
    }
}

/** "+6 WhatsApp" (outlined) or "Hide WhatsApp" (filled), small, in a row's divider. */
@Composable
private fun HiddenChip(label: String, on: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (on) LibraryColors.OnInverse else LibraryColors.OnBackground,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .then(
                if (on) {
                    Modifier.background(LibraryColors.Inverse, RoundedCornerShape(50))
                } else {
                    Modifier.border(1.dp, LibraryColors.Muted.copy(alpha = 0.5f), RoundedCornerShape(50))
                },
            )
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 4.dp),
    )
}

/** The band's background, a little lighter than the page, with round corners at its [top] and [bottom]. */
private fun Modifier.band(color: Color, top: Boolean = false, bottom: Boolean = false): Modifier {
    val r = 16.dp
    val shape = RoundedCornerShape(topStart = if (top) r else 0.dp, topEnd = if (top) r else 0.dp, bottomStart = if (bottom) r else 0.dp, bottomEnd = if (bottom) r else 0.dp)
    return padding(horizontal = 12.dp).fillMaxWidth().background(color, shape)
}

/** A band's heading: "WhatsApp · 6" and its times, "14:02 – 17:30". */
@Composable
private fun BandTop(top: FeedRow.BandTop) {
    val band = top.band
    Row(
        Modifier.padding(top = 18.dp).band(LibraryColors.Raised, top = true).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(band.source.icon, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            stringResource(R.string.feed_source_count, sourceName(band.source), band.items.size),
            color = LibraryColors.OnBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(timeRange(top.start, top.end), color = LibraryColors.Muted, fontSize = 13.sp)
    }
}

/** Up to three of a band's photos, square. */
@Composable
private fun BandPhotos(items: List<MediaItem>, onOpen: (MediaItem) -> Unit) {
    Row(
        Modifier.band(LibraryColors.Raised).padding(start = 8.dp, end = 8.dp, bottom = 3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items.forEach { item ->
            MediaTile(item, { onOpen(item) }, Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(4.dp)))
        }
        repeat(BAND_COLUMNS - items.size) { Spacer(Modifier.weight(1f)) }
    }
}

/** A band's links: "Add to a story" and "Always show this day". */
@Composable
private fun BandEnd(band: RevealedPhotos, onAdd: () -> Unit, onAlways: () -> Unit) {
    Row(
        Modifier.padding(bottom = 6.dp).band(LibraryColors.Raised, bottom = true).padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BandLink(LibraryIcons.Plus, stringResource(R.string.feed_reveal_add), onAdd)
        BandLink(null, stringResource(R.string.feed_reveal_always), onAlways)
    }
}

@Composable
private fun BandLink(icon: ImageVector?, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = LibraryColors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * The row's days, never its times: "Sat, Oct 4", or "Oct 3 – Oct 4" for a row over two days. "Today" and "Yesterday"
 * get their date too ("Today · Tue, Oct 20"), as older days show only it.
 */
@Composable
private fun dividerLabel(span: Moment): String {
    if (span.oldestDay != span.newestDay) return stringResource(R.string.library_moment_days, dayLabel(span.oldestDay), dayLabel(span.newestDay))
    val label = dayLabel(span.newestDay)
    val date = span.newestDay.toLocalDate()
    val recent = !date.isBefore(LocalDate.now().minusDays(1))
    return if (recent) stringResource(R.string.feed_place_time, label, formatDate(date, "EEEMMMd")) else label
}

/**
 * A moment's label above its photos. Camera: its place ("📍 Jaffa Port") where it differs from the camera moment above
 * ([showPlace]), then the time ("18:10 – 19:05"). Other sources: "WhatsApp · 6 · 14:02 – 17:30".
 */
@Composable
private fun MomentLabel(label: FeedRow.Label) {
    val moment = label.moment
    val time = timeRange(label.start, label.end)
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 8.dp)) {
        if (moment.source == MediaSource.CAMERA) {
            moment.place?.takeIf { label.showPlace }?.let { place ->
                Row(Modifier.padding(bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(FeedIcons.Pin, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(place, color = LibraryColors.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(time, color = LibraryColors.Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 22.dp))
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(moment.source.icon, null, tint = LibraryColors.Muted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(R.string.feed_place_time, stringResource(R.string.feed_source_count, sourceName(moment.source), label.count), time),
                    color = LibraryColors.Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** "18:10 – 19:05", or "18:10" when it starts and ends in one minute. */
@Composable
private fun timeRange(start: String, end: String): String =
    if (start == end) start else stringResource(R.string.feed_time_range, start, end)

/** The title of the photo's writing, above it. Nothing when it has none. */
@Composable
private fun PhotoTitle(title: String?) {
    if (title.isNullOrBlank()) return
    Text(
        title,
        color = LibraryColors.OnBackground,
        fontSize = 19.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 10.dp),
    )
}

/**
 * The paragraph of the photo's writing, under it, in the serif of written text: up to four lines, then "more" (tap to
 * read it all). Nothing when it has none.
 */
@Composable
private fun PhotoNoteText(caption: String?) {
    if (caption.isNullOrBlank()) return
    var expanded by remember(caption) { mutableStateOf(false) }
    val style = TextStyle(color = LibraryColors.OnBackground, fontFamily = FontFamily.Serif, fontSize = 16.sp, lineHeight = 24.sp)
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 8.dp)) {
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
            BackupBadge(
                item.backup,
                26.dp,
                Modifier.align(Alignment.BottomEnd).padding(10.dp),
                originalHere = item.hasOriginalHere,
                showOnPhone = LocalShowOnPhoneBadge.current,
            )
        }
        item.durationMillis?.let { VideoLength(it, Modifier.align(Alignment.BottomStart).padding(10.dp)) }
    }
}

/**
 * [item] filling this box, which has its shape and is [width] wide in pixels: MediaStore's thumbnail at once (at most
 * 512 px, blurry at full width), then the photo itself decoded at the box's size on top, sharp on any screen. A photo
 * only on the server gets only the box's size: the thumbnail under it would be one more preview to ask for and keep.
 */
@Composable
private fun SharpPhoto(item: MediaItem, width: Int, modifier: Modifier = Modifier) {
    Box(modifier) {
        if (item.hasOriginalHere) {
            rememberThumbnail(item, THUMB_LARGE)?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        }
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
    val shown = notes[block.items[pager.currentPage.coerceIn(block.items.indices)].id]
    Column(modifier) {
        PhotoTitle(shown?.title)
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
        PhotoNoteText(shown?.caption)
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

/** A band of hidden photos shows this many in a row. */
private const val BAND_COLUMNS = 3

/** A note longer than this shows "more". */
private const val NOTE_LINES = 4

/** A set longer than this shows this many dots, the last one standing for the rest. */
private const val MAX_DOTS = 8
