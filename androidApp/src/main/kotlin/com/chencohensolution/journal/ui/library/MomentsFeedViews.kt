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
import androidx.compose.ui.platform.LocalContext
import com.chencohensolution.journal.library.MediaType
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
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.stories.CropRect
import com.chencohensolution.journal.stories.FeedBlock
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FeedMoment
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.library.SoundRecording
import com.chencohensolution.journal.stories.FeedSound
import com.chencohensolution.journal.stories.Moment
import com.chencohensolution.journal.stories.MomentsFeed
import com.chencohensolution.journal.stories.NamedSet
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.stories.RevealedPhotos
import com.chencohensolution.journal.stories.StorylineDay
import java.time.LocalDate

// The Moments tab: every photo reads like a story that arranged itself (see MomentsFeed in the shared logic).
// Photos are full width at their own shape; near-duplicates are one photo you swipe. See docs/screens/moments.md.
// The Storyline tab is drawn with the same items: each photo written about, with its day above the first of a day.

internal class FeedActions(
    val openItem: (FeedSection, MediaItem) -> Unit = { _, _ -> },
    /**
     * A set's photo (or a moment's link, [NamedSet.itemIds]): the viewer on the set only, at that photo. Null: the
     * row's viewer ([openItem]), as on pages that have no set viewer.
     */
    val openSet: ((itemIds: List<String>, items: List<MediaItem>, item: MediaItem) -> Unit)? = null,
    /** A photo of the storyline; the viewer swipes through the storyline. */
    val openStoryline: (MediaItem) -> Unit = {},
    /** Long press on a photo: Write about it, Crop, Tag, Remove from storyline. */
    val itemMenu: (MediaItem) -> Unit = {},
    /** A "+6 WhatsApp" chip: that source's hidden photos of the row, in a band under it; "Hide WhatsApp" again. */
    val showOnRow: (FeedSection, MediaSource) -> Unit = { _, _ -> },
    val hideOnRow: (FeedSection, MediaSource) -> Unit = { _, _ -> },
    /** A photo of a band; the viewer swipes through the band. */
    val openRevealed: (FeedSection, RevealedPhotos, MediaItem) -> Unit = { _, _, _ -> },
    /** A band's "Always show this day". */
    val alwaysShow: (FeedSection, MediaSource) -> Unit = { _, _ -> },
    /** A day's "3 tucked away" pill (a row of Moments, or a day of the storyline): reveals them, or hides them again. */
    val toggleTucked: (days: List<LocalDay>) -> Unit = {},
)

/**
 * One item of the feed's list: a row's divider, a moment's label, or one photo or near-duplicate set; or of the
 * storyline: a day, or one photo.
 * A photo per item keeps the list lazy: a moment of a hundred photos lays out only the ones in view. Counts, towns and
 * times are worked out with the rows, off the main thread, so an item coming into view only lays them out.
 */
internal sealed interface FeedRow {
    val key: String

    /** [count]: the row's photos; [towns]: "Tel Aviv → Jaffa", empty when it has no places. */
    data class Heading(val section: FeedSection, val count: Int, val towns: String) : FeedRow {
        override val key get() = "heading-${section.key}"
    }

    /**
     * [showPlace]: a camera moment whose place differs from the camera moment above it in its row. [count]: its photos;
     * [start] and [end]: its times as [timeLabel] writes them.
     */
    data class Label(
        val moment: FeedMoment,
        val showPlace: Boolean,
        val count: Int,
        val start: String,
        val end: String
    ) : FeedRow {
        override val key get() = "label-${moment.key}"
    }

    /** [recording]: the recording it was taken during, whose offset it shows. */
    data class Photo(val section: FeedSection, val block: FeedBlock, val recording: SoundRecording? = null) : FeedRow {
        override val key get() = "photo-${block.items.first().id}"
    }

    /** A recording's bar ([RecordingBar]) where its moment starts, in place of the moment's label. */
    data class RecordingBar(val section: FeedSection, val moment: FeedMoment) : FeedRow {
        override val key get() = "recording-${moment.key}"
    }

    /** Calls one after another, or a voice message. */
    data class Sound(val section: FeedSection, val sound: FeedSound) : FeedRow {
        override val key get() = "sound-${sound.key}"
    }

    /** A band of a hidden source's photos shown on a row: its heading, rows of three, and its links. */
    data class BandTop(val section: FeedSection, val band: RevealedPhotos, val start: String, val end: String) :
        FeedRow {
        override val key get() = "band-${section.key}-${band.source}"
    }

    data class BandPhotos(val section: FeedSection, val band: RevealedPhotos, val items: List<MediaItem>) : FeedRow {
        override val key get() = "band-photos-${items.first().id}"
    }

    data class BandEnd(val section: FeedSection, val band: RevealedPhotos) : FeedRow {
        override val key get() = "band-end-${section.key}-${band.source}"
    }

    /** The storyline: the day of the photos under it, until the next one; [tucked] of its photos tucked away. */
    data class StoryDay(val day: LocalDay, val tucked: Int = 0, val revealed: Boolean = false) : FeedRow {
        override val key get() = "story-day-$day"
    }

    /** The storyline: one photo with its writing. */
    data class StoryPhoto(val item: MediaItem) : FeedRow {
        override val key get() = "story-${item.id}"
    }
}

/**
 * Photos and sets picked (a long press starts picking, taps add and remove): in Moments, the storyline, Explore's pages
 * and search, by their first photo's id; picking is on while any is.
 */
@Stable
internal class FeedSelection {
    var keys by mutableStateOf(emptySet<String>())
        private set
    val active: Boolean get() = keys.isNotEmpty()

    /** The photo the long press that started picking was on: a set's Take out takes it out. */
    var pressed by mutableStateOf<String?>(null)
        private set

    fun toggle(key: String) {
        keys = if (key in keys) keys - key else keys + key
    }

    /** A long press: picks [key], starting picking when it is off. */
    fun press(key: String, itemId: String) {
        if (!active) pressed = itemId
        toggle(key)
    }

    fun clear() {
        keys = emptySet()
        pressed = null
    }
}

/** The photos picked among [this] rows (a set's every photo), in the order shown. */
internal fun List<FeedRow>.picked(selection: FeedSelection): List<MediaItem> {
    val keys = selection.keys
    if (keys.isEmpty()) return emptyList()
    return flatMap { row ->
        when (row) {
            is FeedRow.Photo -> if (row.block.key in keys) row.block.items else emptyList()
            is FeedRow.StoryPhoto -> if (row.item.id in keys) listOf(row.item) else emptyList()
            else -> emptyList()
        }
    }.distinctBy { it.id }
}

/** The key a block is picked by: its first photo's id. */
internal val FeedBlock.key: String get() = items.first().id

/** The feed as list items; worked out once per feed, off the main thread. */
internal fun MomentsFeed.rows(): List<FeedRow> = entries.flatMap { entry ->
    when (entry) {
        is FeedEntry.Section -> {
            var place: String? = null
            val section = entry.section
            val heading = FeedRow.Heading(
                section,
                section.moments.sumOf { it.count() },
                section.places.take(MAX_TOWNS).joinToString(" → ")
            )
            listOf(heading) + section.parts.flatMap { part ->
                if (part is FeedSound) return@flatMap listOf(FeedRow.Sound(section, part))
                val moment = part as FeedMoment
                moment.recording?.let { recording ->
                    return@flatMap listOf(FeedRow.RecordingBar(section, moment)) + moment.shownBlocks.map { FeedRow.Photo(section, it, recording) }
                }
                val camera = moment.source == MediaSource.CAMERA
                val showPlace = camera && moment.place != null && moment.place != place
                if (camera) place = moment.place
                val label = FeedRow.Label(
                    moment,
                    showPlace,
                    moment.count(),
                    timeLabel(moment.startMillis),
                    timeLabel(moment.endMillis)
                )
                listOf(label) + moment.shownBlocks.map { FeedRow.Photo(section, it) }
            } + section.revealed.flatMap { band ->
                listOf(FeedRow.BandTop(section, band, timeLabel(band.startMillis), timeLabel(band.endMillis))) +
                        band.items.chunked(BAND_COLUMNS).map { FeedRow.BandPhotos(entry.section, band, it) } +
                        FeedRow.BandEnd(section, band)
            }
        }
    }
}

/** The storyline by day as list items: each day (with its tucked-away pill), then its photos shown. */
internal fun storylineDayRows(days: List<StorylineDay>): List<FeedRow> =
    days.flatMap { day -> listOf(FeedRow.StoryDay(day.day, day.tucked, day.revealed)) + day.items.map(FeedRow::StoryPhoto) }

/** Its photos, counted without listing them ([FeedMoment.items] builds a new list). */
private fun FeedMoment.count(): Int = blocks.sumOf { if (it is FeedBlock.Single) 1 else it.items.size }

/** The item of [rows] that shows [itemId], or -1: for landing from the viewer. */
internal fun List<FeedRow>.rowOf(itemId: String): Int = indexOfFirst { row ->
    (row is FeedRow.Photo && row.block.items.any { it.id == itemId }) || (row is FeedRow.BandPhotos && row.items.any { it.id == itemId }) ||
            (row is FeedRow.StoryPhoto && row.item.id == itemId)
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
    /** Long press starts picking ([FeedSelection]); null: it opens [FeedActions.itemMenu] instead (People). */
    selection: FeedSelection? = null,
    audio: FeedAudio = FeedAudio(),
    /** The latest change only took rows away ([rowsHiding]): the rest close the space once they faded. */
    hiding: Boolean = false,
) {
    val picking = selection?.active == true
    items(rows, key = { it.key }, contentType = { it::class }) { row ->
        // Rows fade in and out as photos are tucked away, revealed or brought back, and the rest slide into place.
        Box(animatedRow(hiding)) {
        when (row) {
            is FeedRow.RecordingBar -> RecordingBar(row.moment, audio, Modifier.padding(top = 16.dp))
            is FeedRow.Sound -> when (val sound = row.sound) {
                is FeedSound.Calls -> CallsRow(sound.calls, audio)
                is FeedSound.Voice -> VoiceMessageRow(sound.recording, audio)
            }
            is FeedRow.Heading -> DayDivider(row, actions)
            is FeedRow.BandTop -> BandTop(row)
            is FeedRow.BandPhotos -> BandPhotos(row.items) { actions.openRevealed(row.section, row.band, it) }
            is FeedRow.BandEnd -> BandEnd(row.band) { actions.alwaysShow(row.section, row.band.source) }
            is FeedRow.StoryDay -> StorylineDay(row) { actions.toggleTucked(listOf(row.day)) }
            is FeedRow.StoryPhoto -> Column(Modifier.padding(bottom = TILE_GAP)) {
                val note = notes[row.item.id]
                val picked = if (picking) row.item.id in selection!!.keys else null
                PhotoTitle(note?.title)
                Box(Modifier.pickedInset(picked)) {
                    FullWidthPhoto(
                        row.item,
                        video,
                        note?.crops?.moments,
                        Modifier.combinedClickable(
                            onLongClick = { if (selection != null) selection.press(row.item.id, row.item.id) else actions.itemMenu(row.item) },
                            onClick = { if (picking) selection?.toggle(row.item.id) else actions.openStoryline(row.item) }),
                    )
                    PickMark(picked, Modifier.align(Alignment.TopStart).padding(10.dp))
                }
                PhotoNoteText(note?.caption)
            }

            is FeedRow.Label -> MomentLabel(row)
            is FeedRow.Photo -> when (val block = row.block) {
                is FeedBlock.Single -> Column(Modifier.padding(bottom = TILE_GAP)) {
                    val picked = if (picking) block.key in selection!!.keys else null
                    PhotoTitle(notes[block.item.id]?.title)
                    Box(Modifier.pickedInset(picked)) {
                        FullWidthPhoto(
                            block.item,
                            video,
                            notes[block.item.id]?.crops?.moments,
                            Modifier.combinedClickable(
                                onLongClick = {
                                    val recording = row.recording
                                    when {
                                        picking -> selection!!.toggle(block.key)
                                        // During a recording: plays it from when the photo was taken.
                                        recording != null -> audio.seek(recording, block.item.takenAtMillis - recording.startMillis)
                                        selection != null -> selection.press(block.key, block.item.id)
                                        else -> actions.itemMenu(block.item)
                                    }
                                },
                                onClick = {
                                    if (picking) selection!!.toggle(block.key) else actions.openItem(
                                        row.section,
                                        block.item
                                    )
                                },
                            ),
                        )
                        PickMark(picked, Modifier.align(Alignment.TopStart).padding(10.dp))
                        row.recording?.takeIf { !picking }?.let { recording ->
                            RecordingOffset(block.item, recording, Modifier.align(Alignment.TopStart).padding(10.dp))
                        }
                    }
                    PhotoNoteText(notes[block.item.id]?.caption)
                    block.named?.let { named -> PartOfLink(named.name) { actions.openNamed(named, block, row.section) } }
                }

                is FeedBlock.Similar -> SetBlock(
                    block,
                    landing,
                    notes,
                    video,
                    Modifier.padding(bottom = TILE_GAP),
                    selected = if (picking) block.key in selection!!.keys else null,
                    onLongPress = {
                        when {
                            picking -> selection!!.toggle(block.key)
                            selection != null -> selection.press(block.key, it.id)
                            else -> actions.itemMenu(it)
                        }
                    },
                    onOpenNamed = { named -> actions.openNamed(named, block, row.section) },
                ) {
                    when {
                        picking -> selection!!.toggle(block.key)
                        actions.openSet != null -> actions.openSet.invoke(block.items.map { item -> item.id }, block.items, it)
                        else -> actions.openItem(row.section, it)
                    }
                }
            }
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
            // A row of only tucked-away photos says only its pill.
            if (count > 0 || section.tucked == 0) Text(
                (heading.towns.ifEmpty { null }?.let { "$it · " }.orEmpty()) +
                        pluralStringResource(R.plurals.library_story_items, count, count),
                color = LibraryColors.Muted,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            TuckedPillSlot(section.tucked, section.tuckedShown, { actions.toggleTucked(section.days) })
            (shownHere + section.hidden.keys).sorted().forEach { source ->
                when {
                    source in shownHere -> HiddenChip(
                        stringResource(R.string.feed_hide_source, sourceName(source)),
                        on = true
                    ) { actions.hideOnRow(section, source) }

                    source in section.hidden -> HiddenChip(
                        stringResource(
                            R.string.feed_hidden_chip,
                            section.hidden.getValue(source),
                            sourceName(source)
                        ), on = false
                    ) {
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
    val shape = RoundedCornerShape(
        topStart = if (top) r else 0.dp,
        topEnd = if (top) r else 0.dp,
        bottomStart = if (bottom) r else 0.dp,
        bottomEnd = if (bottom) r else 0.dp
    )
    return padding(horizontal = 12.dp).fillMaxWidth().background(color, shape)
}

/** A band's heading: "WhatsApp · 6" and its times, "14:02 – 17:30". */
@Composable
private fun BandTop(top: FeedRow.BandTop) {
    val band = top.band
    Row(
        Modifier.padding(top = 18.dp).band(LibraryColors.Raised, top = true)
            .padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 10.dp),
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
private fun BandEnd(band: RevealedPhotos, onAlways: () -> Unit) {
    Row(
        Modifier.padding(bottom = 6.dp).band(LibraryColors.Raised, bottom = true)
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BandLink(null, stringResource(R.string.feed_reveal_always), onAlways)
    }
}

@Composable
private fun BandLink(icon: ImageVector?, label: String, onClick: () -> Unit) {
    Row(
        Modifier.clip(RoundedCornerShape(50)).clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let {
            Icon(it, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = LibraryColors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The storyline's day above its photos, on a line like a Moments row's: "Sat, Oct 4"; under it, its tucked-away pill. */
@Composable
private fun StorylineDay(row: FeedRow.StoryDay, onTucked: () -> Unit) {
    val day = row.day
    val line = LibraryColors.Muted.copy(alpha = 0.3f)
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 28.dp, bottom = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(line))
        Text(
            dayLabel(day),
            color = LibraryColors.OnBackground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .padding(horizontal = 12.dp)
                .background(LibraryColors.Raised, RoundedCornerShape(50))
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .semantics { heading() },
        )
        Box(Modifier.weight(1f).height(1.dp).background(line))
    }
    TuckedPillSlot(row.tucked, row.revealed, onTucked)
    }
}

/**
 * The row's days, never its times: "Sat, Oct 4", or "Oct 3 – Oct 4" for a row over two days. "Today" and "Yesterday"
 * get their date too ("Today · Tue, Oct 20"), as older days show only it.
 */
@Composable
private fun dividerLabel(span: Moment): String {
    if (span.oldestDay != span.newestDay) return stringResource(
        R.string.library_moment_days,
        dayLabel(span.oldestDay),
        dayLabel(span.newestDay)
    )
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
                    Text(
                        place,
                        color = LibraryColors.OnBackground,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Text(
                time,
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(start = 22.dp)
            )
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(moment.source.icon, null, tint = LibraryColors.Muted, modifier = Modifier.size(15.dp))
                Spacer(Modifier.width(6.dp))
                Text(
                    stringResource(
                        R.string.feed_place_time,
                        stringResource(R.string.feed_source_count, sourceName(moment.source), label.count),
                        time
                    ),
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
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 8.dp),
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
    val style = TextStyle(
        color = LibraryColors.OnBackground,
        fontFamily = FontFamily.Serif,
        fontSize = 16.sp,
        lineHeight = 24.sp
    )
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 12.dp)) {
        // Measured here, not after drawing, so "more" is there in the first frame.
        val cut = remember(caption, constraints.maxWidth) {
            measurer.measure(
                caption,
                style,
                maxLines = NOTE_LINES,
                constraints = Constraints(maxWidth = constraints.maxWidth)
            ).hasVisualOverflow
        }
        Column(Modifier.fillMaxWidth().clickable(enabled = cut) { expanded = !expanded }) {
            Text(
                caption,
                style = style,
                maxLines = if (expanded) Int.MAX_VALUE else NOTE_LINES,
                overflow = TextOverflow.Ellipsis
            )
            if (cut && !expanded) {
                Text(
                    stringResource(R.string.feed_note_more),
                    color = LibraryColors.Muted,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 2.dp)
                )
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
    Box(
        modifier.fillMaxWidth().aspectRatio(frame?.ratioOn(item.ratio) ?: item.ratio).background(LibraryColors.Raised)
            .shared(SharedKey.item(item.id))
    ) {
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
            CropBox(frame, Modifier.fillMaxSize()) {
                SharpPhoto(
                    item,
                    (width / frame.w).toInt(),
                    Modifier.fillMaxSize()
                )
            }
        } else {
            SharpPhoto(
                item,
                width,
                Modifier.fillMaxSize().graphicsLayer { alpha = if (playing && showing && !failed) 0f else 1f })
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
        // Tucked away and revealed for the session.
        if (item.isTucked) TuckedMark(Modifier.align(Alignment.TopEnd).padding(10.dp))
    }
}

/**
 * [item] filling this box, which has its shape and is [width] wide in pixels: a quick image at once (MediaStore's
 * thumbnail, or a half-width HEIF for a photo only on the server), soft at full width, then the photo decoded at the
 * box's size on top once the feed slows down ([TwoStepPhoto]), sharp on any screen.
 */
@Composable
private fun SharpPhoto(item: MediaItem, width: Int, modifier: Modifier = Modifier) {
    TwoStepPhoto(item, width, (width / item.ratio).toInt(), modifier)
}

/** The feed is the width of the window: its photos' size in pixels is known without measuring them. */
@Composable
private fun feedWidth(): Int = LocalWindowInfo.current.containerSize.width

/** A moment's link from one day's part of it: the whole moment, on the part's first photo. */
private fun FeedActions.openNamed(named: NamedSet, block: FeedBlock, section: FeedSection) {
    val open = openSet ?: return openItem(section, block.items.first())
    open(named.itemIds, block.items, block.items.first())
}

/** "Part of Beach weekend ›" under one day's photos of a moment shown on each of its days. */
@Composable
private fun PartOfLink(name: String?, onClick: () -> Unit) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 6.dp).clip(RoundedCornerShape(50))
            .background(LibraryColors.Raised).clickable(onClick = onClick).padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.Stack, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(14.dp))
        Text(if (name != null) stringResource(R.string.set_part_of, name) else stringResource(R.string.set_part_of_unnamed), Modifier.padding(start = 6.dp), color = LibraryColors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Icon(LibraryIcons.ChevronRight, null, tint = LibraryColors.Muted, modifier = Modifier.padding(start = 2.dp).size(14.dp))
    }
}

/**
 * A moment's name over it, when made by hand with a name ([NamedSet]): the name, not bold, and under it its days in
 * smaller type ("Fri – Sun") (asked Nov 9, 2026).
 */
@Composable
private fun SetName(name: String, items: List<MediaItem>) {
    val first = items.first().day
    val last = items.last().day
    val days = if (first == last) formatDate(first.toLocalDate(), "EEE") else formatDate(first.toLocalDate(), "EEE") + " – " + formatDate(last.toLocalDate(), "EEE")
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        Text(name, color = LibraryColors.OnBackground, fontSize = 17.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(days, color = LibraryColors.Muted, fontSize = 12.sp, maxLines = 1)
    }
}

/**
 * A set in Moments (near-duplicates, or a moment made by hand). With highlights ([FeedBlock.Similar.highlights]),
 * those one under the other, each with its note, then the set's line ("⧉ 36 photos in this set ›"). Without, its
 * cover ([SetCover]), flipped through in place. A tap opens the viewer on the set ([onOpen], with the photo shown).
 */
@Composable
private fun SetBlock(
    block: FeedBlock.Similar,
    landing: String?,
    notes: Map<String, PhotoNote>,
    video: FeedVideo,
    modifier: Modifier = Modifier,
    /** While picking: whether this set is picked; null when not picking. */
    selected: Boolean? = null,
    onLongPress: (MediaItem) -> Unit,
    onOpenNamed: (NamedSet) -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    val named = block.named
    Column(modifier) {
        if (named != null && !named.isPartOf(block)) named.name?.let { SetName(it, block.items) }
        if (block.highlights.isEmpty()) {
            SetCover(block, landing, notes, video, selected, onLongPress, onOpen)
        } else {
            block.highlights.forEach { item ->
                val note = notes[item.id]
                PhotoTitle(note?.title)
                Box(Modifier.pickedInset(selected).padding(bottom = TILE_GAP)) {
                    FullWidthPhoto(
                        item, video, note?.crops?.moments,
                        Modifier.combinedClickable(onLongClick = { onLongPress(item) }, onClick = { onOpen(item) }),
                    )
                    PickMark(selected, Modifier.align(Alignment.TopStart).padding(10.dp))
                }
                PhotoNoteText(note?.caption)
            }
            SetLine(block.items.size) { onOpen(block.highlights.first()) }
        }
        if (named != null && named.isPartOf(block)) PartOfLink(named.name) { onOpenNamed(named) }
    }
}

/** Under a set's highlights: how many photos it has; a tap opens it. */
@Composable
private fun SetLine(count: Int, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.Stack, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(15.dp))
        Text(
            pluralStringResource(R.plurals.set_line, count, count),
            Modifier.padding(start = 8.dp).weight(1f),
            color = LibraryColors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
        )
        Icon(LibraryIcons.ChevronRight, null, tint = LibraryColors.Muted, modifier = Modifier.size(16.dp))
    }
}

/**
 * A set as one photo at its cover's shape ([FeedVideo.startPage]: where it was turned to while the feed shows, else its
 * cover), with "⧉ 36" in the corner and a thin line marking where in the set the photo shown is. Holding it, then
 * sliding sideways, flips through the set in place (a tick per photo, [FLIP_STEP_DP] each); letting go keeps it there.
 * A hold let go without sliding is the long press (picking). The small ▶ plays it in place, muted. Each photo fits
 * inside at its own shape (its Moments crop), cross-fading.
 */
@Composable
private fun SetCover(
    block: FeedBlock.Similar,
    landing: String?,
    notes: Map<String, PhotoNote>,
    video: FeedVideo,
    selected: Boolean?,
    onLongPress: (MediaItem) -> Unit,
    onOpen: (MediaItem) -> Unit,
) {
    val items = block.items
    var index by remember(block.key) { mutableStateOf(video.startPage(block).coerceIn(items.indices)) }
    val shownIndex = index.coerceIn(items.indices)
    val cover = remember(block.key) { video.startPage(block).coerceIn(items.indices) }
    var scrubbing by remember { mutableStateOf(false) }
    var playing by remember(block.key) { mutableStateOf(false) }
    val context = LocalContext.current
    val previews = LocalServerPreviews.current
    val coverRatio = items[cover].shownRatio(notes[items[cover].id]?.crops?.moments)
    val feedPixels = feedWidth()
    // The next photo is loaded at the exact size it shows at before it is switched to, so it shows sharp at once.
    Slideshow(playing, FEED_PLAY_MILLIS, items.size, shownIndex, prepare = { i ->
        val item = items[i]
        val crop = notes[item.id]?.crops?.moments?.takeIf { item.durationMillis == null }
        val ratio = item.shownRatio(crop)
        val shownWidth = if (ratio >= coverRatio) feedPixels else (feedPixels / coverRatio * ratio).toInt()
        val photoWidth = if (crop != null) (shownWidth / crop.w).toInt() else shownWidth
        if (item.type == MediaType.IMAGE) loadCover(context, previews, item, photoWidth, (photoWidth / item.ratio).toInt())
    }) { index = it }
    LaunchedEffect(landing) {
        val page = items.indexOfFirst { it.id == landing }
        if (page >= 0) index = page
    }
    // The photo shown is the one that plays, when it is a video, and where the set opens while the feed shows.
    LaunchedEffect(shownIndex, block) { video.showPage(block, items[shownIndex].id) }
    val haptic = LocalHapticFeedback.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val current by rememberUpdatedState(shownIndex)
    val longPress by rememberUpdatedState(onLongPress)
    val open by rememberUpdatedState(onOpen)
    val shown = items[shownIndex]
    val boxRatio = items[cover].shownRatio(notes[items[cover].id]?.crops?.moments)
    val width = feedWidth()
    PhotoTitle(notes[shown.id]?.title)
    Box(
        Modifier.fillMaxWidth().aspectRatio(boxRatio).pickedInset(selected).background(Color.Black)
            .pointerInput(items.size, rtl) {
                val step = FLIP_STEP_DP.dp.toPx()
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    var cancelled = false
                    val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                        while (true) {
                            val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: return@withTimeoutOrNull null
                            if (!change.pressed) return@withTimeoutOrNull change
                            if (change.isConsumed || (change.position - down.position).getDistance() > viewConfiguration.touchSlop) {
                                cancelled = true
                                return@withTimeoutOrNull null
                            }
                        }
                        null
                    }
                    if (cancelled) return@awaitEachGesture
                    if (up != null) {
                        // A tap (the Play button takes its own).
                        if (!up.isConsumed) {
                            up.consume()
                            open(items[current])
                        }
                        return@awaitEachGesture
                    }
                    // Held: sliding sideways flips; let go without sliding, it is the long press.
                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                    playing = false
                    val from = current
                    var flipping = false
                    while (true) {
                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) {
                            if (!flipping && !change.isConsumed) longPress(items[current])
                            break
                        }
                        val dx = (change.position.x - down.position.x).let { if (rtl) -it else it }
                        val dy = change.position.y - down.position.y
                        if (!flipping) {
                            if (abs(dy) > viewConfiguration.touchSlop && abs(dy) > abs(dx)) break
                            if (abs(dx) > viewConfiguration.touchSlop) flipping = true.also { scrubbing = true }
                        }
                        if (flipping) {
                            change.consume()
                            val i = (from + (dx / step).toInt()).coerceIn(0, items.size - 1)
                            if (i != current) {
                                index = i
                                haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                            }
                        }
                    }
                    scrubbing = false
                }
            },
    ) {
        Crossfade(shownIndex, Modifier.fillMaxSize(), animationSpec = tween(SET_FADE_MILLIS), label = "set cover") { i ->
            val item = items[i]
            val crop = notes[item.id]?.crops?.moments?.takeIf { item.durationMillis == null }
            val ratio = item.shownRatio(crop)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Box(Modifier.aspectRatio(ratio, matchHeightConstraintsFirst = ratio < boxRatio).shared(SharedKey.item(item.id))) {
                    PhotoOrVideo(item, video, if (ratio >= boxRatio) width else (width / boxRatio * ratio).toInt(), crop)
                }
            }
        }
        Row(
            Modifier.align(Alignment.TopEnd).padding(10.dp)
                .background(LibraryColors.BadgeScrim, RoundedCornerShape(50))
                .padding(horizontal = 9.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LibraryIcons.Stack, null, tint = LibraryColors.PhotoText, modifier = Modifier.size(13.dp))
            Text(items.size.toString(), color = LibraryColors.PhotoText, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        if (scrubbing) {
            Text(
                stringResource(R.string.set_position, shownIndex + 1, items.size),
                Modifier.align(Alignment.TopCenter).padding(top = 12.dp).background(LibraryColors.PillScrim, RoundedCornerShape(50))
                    .padding(horizontal = 10.dp, vertical = 4.dp),
                color = LibraryColors.PhotoText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            )
        }
        if (selected == null) {
            Box(
                Modifier.align(Alignment.BottomStart).padding(10.dp).size(36.dp).clip(CircleShape).background(LibraryColors.BadgeScrim)
                    .clickable(onClickLabel = stringResource(if (playing) R.string.set_stop else R.string.set_play_here)) { playing = !playing },
                contentAlignment = Alignment.Center,
            ) {
                if (playing) Text("❚❚", color = LibraryColors.PhotoText, fontSize = 12.sp)
                else Icon(LibraryIcons.Play, null, tint = LibraryColors.PhotoText, modifier = Modifier.size(14.dp))
            }
        }
        SetPositionLine(items.size, shownIndex, cover, Modifier.align(Alignment.BottomCenter).padding(start = 60.dp, end = 60.dp, bottom = 22.dp).fillMaxWidth())
        PickMark(selected, Modifier.align(Alignment.TopStart).padding(10.dp))
    }
    PhotoNoteText(notes[shown.id]?.caption)
}

@Composable
private fun VideoLength(millis: Long, modifier: Modifier = Modifier) {
    Row(
        modifier.background(LibraryColors.BadgeScrim, RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.Play, null, tint = LibraryColors.PhotoText, modifier = Modifier.size(12.dp))
        Text(
            formatDuration(millis),
            color = LibraryColors.PhotoText,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** A band of hidden photos shows this many in a row. */
private const val BAND_COLUMNS = 3

/** A note longer than this shows "more". */
private const val NOTE_LINES = 4
