package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.LocatedRow
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.SoundRecording
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.places.distanceMeters
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.similarity.PhotoLooks

/*
 * The Moments tab: every photo reads like a story that arranged itself: each row of the journal (a day, or a run of photos; see
 * the grouping setting) split into moments by time, place and source, with near-duplicates shown as one photo you
 * swipe. Sound recordings the user shows are in it too: a recording with the photos taken while it ran, calls folded,
 * voice messages. Nothing here is saved: it is worked out again from the library.
 */

/** One photo, or near-duplicates (a burst, or photos that look alike) shown as one photo you flip through. */
sealed interface FeedBlock {
    val items: List<MediaItem>

    /** The moment made by hand this is of ([StoriesController.makeMoment]): all of it, or one day's part ([NamedSet.isPartOf]). */
    val named: NamedSet?

    data class Single(val item: MediaItem, override val named: NamedSet? = null) : FeedBlock {
        override val items: List<MediaItem> get() = listOf(item)
    }

    data class Similar(
        override val items: List<MediaItem>,
        /**
         * Its photos marked highlights ([PhotoNote.highlight]), in their order: what Moments shows of it, one under the
         * other, above the set's line. Empty: it shows its cover ([StoriesState.frontOf]).
         */
        val highlights: List<MediaItem> = emptyList(),
        override val named: NamedSet? = null,
    ) : FeedBlock
}

/**
 * A set made into a moment by hand: its [name], the days that show it ([showOn]) and all its photos ([itemIds], oldest
 * first), for opening the whole set from one day's part of it.
 */
data class NamedSet(val setId: String, val name: String, val showOn: ShowOn, val itemIds: List<String>) {
    /** Whether [block] is only one day's photos of it ([ShowOn.EACH]): it shows a link "Part of <name> ›". */
    fun isPartOf(block: FeedBlock): Boolean = block.items.size < itemIds.size
}

/**
 * Photos from one source taken close together, in one place; oldest first. With a [recording], the camera photos
 * taken while it ran (maybe none), one moment however long it ran.
 */
data class FeedMoment(
    val source: MediaSource,
    val blocks: List<FeedBlock>,
    /** The most precise name of where most of them were taken, once looked up. */
    val place: String?,
    val recording: SoundRecording? = null,
) {
    val items: List<MediaItem> get() = blocks.flatMap { it.items }
    val key: String get() = recording?.let { SOUND_PREFIX + it.sha256 } ?: blocks.first().items.first().id
    val startMillis: Long get() = recording?.startMillis ?: blocks.first().items.first().takenAtMillis
    val endMillis: Long get() = recording?.endMillis ?: blocks.last().items.last().takenAtMillis

    /** Its blocks newest first, as shown: the feed reads back in time all the way down. */
    val shownBlocks: List<FeedBlock> get() = blocks.asReversed()
}

/** Calls and voice messages of a row of Moments (recordings are moments: [FeedMoment.recording]). */
sealed interface FeedSound {
    val key: String
    val startMillis: Long
    val endMillis: Long

    /** Calls one after another (less than [MOMENT_GAP_MILLIS] apart), newest first: one row, folded until opened. */
    data class Calls(val calls: List<SoundRecording>) : FeedSound {
        override val key: String get() = SOUND_PREFIX + calls.last().sha256
        override val startMillis: Long get() = calls.last().startMillis
        override val endMillis: Long get() = calls.maxOf { it.endMillis }
    }

    /** A WhatsApp voice message. */
    data class Voice(val recording: SoundRecording) : FeedSound {
        override val key: String get() = SOUND_PREFIX + recording.sha256
        override val startMillis: Long get() = recording.startMillis
        override val endMillis: Long get() = recording.endMillis
    }
}

/** One row of the journal ([span]: a day, or a run of photos), its moments newest first. */
data class FeedSection(
    val span: Moment,
    val moments: List<FeedMoment>,
    /** The towns its camera photos were taken in, in the order visited. */
    val places: List<String>,
    /**
     * Photos of its days left out by the sources setting, per source in [MediaSource] order: the "+6 WhatsApp" chips.
     * Empty while "Say what's hidden" is off; a source shown for this row ([revealed]) isn't counted.
     */
    val hidden: Map<MediaSource, Int> = emptyMap(),
    /** Hidden photos shown for this row only (tapping a chip), per source, each newest first: the tinted bands. */
    val revealed: List<RevealedPhotos> = emptyList(),
    /** Its calls and voice messages, newest first; drawn among the moments by time ([parts]). */
    val sounds: List<FeedSound> = emptyList(),
    /**
     * How many of its photos are tucked away (revealed or not): its "3 tucked away" pill. A row may have nothing else,
     * when every photo of its days is tucked away.
     */
    val tucked: Int = 0,
    /** Whether its tucked photos show, in place among its moments (its days are revealed for the session). */
    val tuckedShown: Boolean = false,
) {
    val key: String get() = span.key

    /** Its days, newest first: what "Always show this day" keeps showing. */
    val days: List<LocalDay> get() = span.items.map { it.day }.distinct()

    /** Its moments and sounds as drawn, newest first: each a [FeedMoment] or a [FeedSound]. */
    val parts: List<Any> get() = (moments + sounds).sortedByDescending {
        when (it) {
            is FeedMoment -> it.startMillis
            is FeedSound -> it.startMillis
            else -> 0L
        }
    }

    /** Every recording, call and voice message on it. */
    val recordings: List<SoundRecording>
        get() = moments.mapNotNull { it.recording } + sounds.flatMap {
            when (it) {
                is FeedSound.Calls -> it.calls
                is FeedSound.Voice -> listOf(it.recording)
            }
        }

    /** In the order shown: moments newest first, each newest first ([FeedMoment.shownBlocks]). */
    val items: List<MediaItem> get() = moments.flatMap { moment -> moment.shownBlocks.flatMap { it.items } }
}

/** A hidden source's photos of one row, shown there until hidden again; newest first. */
data class RevealedPhotos(val source: MediaSource, val items: List<MediaItem>) {
    val startMillis: Long get() = items.last().takenAtMillis
    val endMillis: Long get() = items.first().takenAtMillis
}

/** A hidden source shown on one row of the feed ([FeedSection.key]), until hidden again; not saved. */
data class FeedReveal(val section: String, val source: MediaSource)

sealed interface FeedEntry {
    data class Section(val section: FeedSection) : FeedEntry
}

/** The Moments tab: every photo and video of the [shown] sources, newest first. */
data class MomentsFeed(
    val entries: List<FeedEntry> = emptyList(),
    /** Photos per source, shown or not, in [MediaSource] order. */
    val counts: Map<MediaSource, Int> = emptyMap(),
    /** Of the sources [choices] offers, those on. */
    val shown: Set<MediaSource> = ShownSources().shownOf(MediaSource.FIXED),
    /**
     * Every source the filter bar's "+N" sheet offers, the most photos first: those with photos, the fixed ones and
     * those hidden, so a hidden folder with no photos left can be shown again ([ShownSources.choices]).
     */
    val sheet: List<MediaSource> = MediaSource.byCount(counts),
    /** The whole feed's timeline when [entries] are only its first rows (read from the database: FeedPager). */
    val givenTimeline: FeedTimeline? = null,
    /** Whether what the server's import placed shows ([ShownSources.importedShown]). */
    val importedShown: Boolean = true,
    /** The kinds under Imported in the sheet, the most photos first; empty while nothing imported has photos. */
    val importedSheet: List<MediaSource> = MediaSource.importedChoices(counts),
) {
    // Compared by identity, like StoriesState: screens key on it, and a field-by-field comparison is slow.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()

    fun section(key: String): FeedSection? =
        entries.firstNotNullOfOrNull { (it as? FeedEntry.Section)?.section?.takeIf { section -> section.key == key } }

    /** The sources its filter bar offers: the fixed ones and those with photos, in order. */
    val choices: List<MediaSource> get() = MediaSource.choices(counts.keys)

    /**
     * The filter bar's chips: the phones' sources shown that have photos, the most first (asked Oct 24, 2026); what
     * was imported is one chip after them ([importedCount]).
     */
    val bar: List<MediaSource> get() = sheet.filter { it in shown && (counts[it] ?: 0) > 0 }

    /** The filter bar's Imported chip: the imported photos shown; 0 while Imported is off or none show. */
    val importedCount: Int get() = if (!importedShown) 0 else importedSheet.filter { it in shown }.sumOf { counts[it] ?: 0 }

    /** Whether anything imported has photos, shown or not: the sheet and Settings offer Imported. */
    val hasImported: Boolean get() = importedSheet.isNotEmpty()

    /** The filter bar's "+N": the phones' sources hidden that have photos, and Imported while it is off. */
    val hiddenCount: Int get() = sheet.count { it !in shown && (counts[it] ?: 0) > 0 } + if (hasImported && !importedShown) 1 else 0

    /** Its months and days, for going to a date: worked out once, when first asked for. */
    val timeline: FeedTimeline by lazy { givenTimeline ?: FeedTimeline.of(this) }
}

/** A new camera moment starts after this long without a photo… */
internal const val MOMENT_GAP_MILLIS = 2 * 3_600_000L

/** …or after this long and [MOMENT_MOVE_METERS] away, so a walk stays one moment and a drive to another town doesn't. */
internal const val MOMENT_MOVE_GAP_MILLIS = 20 * 60_000L
internal const val MOMENT_MOVE_METERS = 1_000.0

/** Photos taken this close together are one burst, shown as one set you swipe. */
internal const val BURST_GAP_MILLIS = 10_000L

/** Videos taken this close together (in one moment) are one set you swipe. */
internal const val VIDEO_SET_METERS = 150.0

/** Home is only guessed from this many located photos or more. */
internal const val HOME_MIN_PHOTOS = 20

/** Keys of the feed's sound recordings, and of the stand-ins that place them in rows ([soundMarks]). */
const val SOUND_PREFIX = "sound:"
private const val SOUND_END_PREFIX = "sound-end:"

/**
 * The feed of [items] (the whole library, newest first): only what [shown] shows, split into rows ([grouping]),
 * each into moments ([splitMoments]) of blocks ([blocksOf]). Photos in [alone] (cropped, or taken out of a set) are never in
 * a near-duplicate set; photos merged by hand ([sets]: photo id to set id) are one set, where its oldest photo is.
 * Each row counts the photos it hides ([FeedSection.hidden]) and shows those of the sources in [revealed].
 *
 * [sounds] (the server's sound recordings the user shows) join the rows of their time: a recording takes the camera
 * photos taken while it ran into one moment ([FeedMoment.recording]), even across the gap that splits camera moments;
 * calls one after another fold into one row; voice messages show while WhatsApp does. They don't count as photos.
 */
internal fun buildFeed(
    items: List<MediaItem>,
    shown: ShownSources,
    grouping: TimelineGrouping,
    places: Places = Places.NONE,
    looks: PhotoLooks = PhotoLooks.NONE,
    alone: Set<String> = emptySet(),
    sets: Map<String, String> = emptyMap(),
    revealed: Set<FeedReveal> = emptySet(),
    sounds: List<SoundRecording> = emptyList(),
    /** The days whose tucked-away photos show ([TuckedSession.days]); the others' are only counted ([FeedSection.tucked]). */
    tuckedShown: Set<LocalDay> = emptySet(),
    /**
     * Where the named sets of [sets] show (set id to [ShowOn]); a set not in it shows where its oldest photo is. With
     * [ShowOn.EACH] each day's photos of the set are a set of their own on that day.
     */
    placements: Map<String, ShowOn> = emptyMap(),
): MomentsFeed {
    val counts = sourceCounts(items)
    val (visible, hiddenBySource) = items.partition(shown::shows)
    // A tucked photo of a hidden source is neither counted on the source's chip nor shown in its band.
    val hidden = hiddenBySource.filterNot { it.isTucked }
    // Tucked away and not revealed: in its row's span (so revealing never moves rows), but not among its moments.
    fun tuckedAway(item: MediaItem) = item.isTucked && item.day !in tuckedShown
    // Each set merged by hand, oldest first, by the photo it shows at (its oldest; its newest when shown on its last
    // day); its other photos show only in it. A set shown on each of its days is a set per day.
    val merged = visible.filter { it.id in sets && !tuckedAway(it) }
        .groupBy { item -> sets.getValue(item.id).let { set -> if (placements[set] == ShowOn.EACH) "$set@${item.day}" else set } }
        .map { (key, members) -> key.substringBefore("@") to members.sortedBy { it.takenAtMillis } }
        .associate { (set, members) -> (if (placements[set] == ShowOn.LAST) members.last() else members.first()).id to members }
    val inLaterPlace = merged.flatMap { (at, members) -> members.filter { it.id != at } }.map { it.id }.toSet()
    val shownSounds = sounds.filter { it.kind != AudioKind.VOICE_MESSAGE || shown.shows(it.day, it.source) }.associateBy { it.sha256 }
    val marks = soundMarks(shownSounds.values, grouping)
    val rowItems = if (marks.isEmpty()) visible else (visible + marks).sortedByDescending { it.takenAtMillis }
    val sections = groupMoments(rowItems.filter { it.id !in inLaterPlace }, grouping).mapNotNull { span ->
        val spanPhotos = span.items.filter { it.type != MediaType.AUDIO }
        val tucked = spanPhotos.count { it.isTucked }
        val photos = if (tucked == 0) spanPhotos else spanPhotos.filterNot(::tuckedAway)
        // A sound belongs to the row its start is in.
        val spanSounds = span.items.filter { it.id.startsWith(SOUND_PREFIX) }.mapNotNull { shownSounds[it.id.removePrefix(SOUND_PREFIX)] }
        val taken = HashSet<String>()
        val recordings = spanSounds.filter { it.kind == AudioKind.RECORDING }.sortedBy { it.startMillis }.map { recording ->
            val during = photos.filter {
                it.source.kind == MediaSource.CAMERA && it.id !in taken && it.takenAtMillis in recording.startMillis..recording.endMillis
            }.sortedBy { it.takenAtMillis }
            during.forEach { taken += it.id }
            FeedMoment(MediaSource.CAMERA, blocksOf(during, looks, alone, merged, places), mostCommon(during) { places.nameOf(it)?.label }, recording)
        }
        val moments = (photos.filter { it.id !in taken }.groupBy { it.source }
            .flatMap { (source, items) -> splitMoments(source, items.reversed(), places) }
            .map { items -> FeedMoment(items.first().source, blocksOf(items, looks, alone, merged, places), mostCommon(items) { places.nameOf(it)?.label }) } + recordings)
            .sortedByDescending { it.startMillis }
        val soundRows = callRuns(spanSounds.filter { it.kind == AudioKind.CALL }) +
            spanSounds.filter { it.kind == AudioKind.VOICE_MESSAGE }.map(FeedSound::Voice)
        // A row made only of where a recording ended (it started in the row before) shows nothing.
        // A row of only tucked photos stays, for its pill.
        if (moments.isEmpty() && soundRows.isEmpty() && tucked == 0) return@mapNotNull null
        val towns = moments.reversed()
            .filter { it.source.kind == MediaSource.CAMERA }
            .flatMap { moment -> moment.items.mapNotNull { places.nameOf(it)?.town } }
        FeedSection(
            span, moments, towns.distinct(), sounds = soundRows.sortedByDescending { it.startMillis },
            tucked = tucked, tuckedShown = tucked > 0 && spanPhotos.any { it.isTucked && it.day in tuckedShown },
        )
    }.withHidden(hidden, shown.sayWhatsHidden, revealed)
    return MomentsFeed(
        sections.map(FeedEntry::Section),
        counts,
        shown.shownAmong(counts),
        shown.choices(counts),
        importedShown = shown.importedShown,
        importedSheet = shown.importedChoices(counts),
    )
}

/**
 * The feed with its sets' highlights and names from [notes] ([FeedBlock.Similar.highlights], [FeedBlock.named]): laid
 * over the rows however they were made (in memory or read from the database, which keeps only their ids). The feed
 * itself when no note has either.
 */
internal fun MomentsFeed.withSets(notes: Map<String, PhotoNote>): MomentsFeed {
    val named = namedSetsIn(notes)
    if (named.isEmpty() && notes.values.none { it.highlight != null }) return this
    fun nameOf(block: FeedBlock): NamedSet? = block.items.firstNotNullOfOrNull { item -> notes[item.id]?.feedSet?.let(named::get) }
    fun laid(block: FeedBlock): FeedBlock = when (block) {
        is FeedBlock.Single -> nameOf(block)?.let { block.copy(named = it) } ?: block
        is FeedBlock.Similar -> {
            val highlights = highlightsOf(block.items, notes)
            val name = nameOf(block)
            if (highlights.isEmpty() && name == null) block else block.copy(highlights = highlights, named = name)
        }
    }
    return copy(
        entries = entries.map { entry ->
            when (entry) {
                is FeedEntry.Section -> FeedEntry.Section(
                    entry.section.copy(moments = entry.section.moments.map { moment -> moment.copy(blocks = moment.blocks.map(::laid)) }),
                )
            }
        },
    )
}

/** Of [items], the highlights ([PhotoNote.highlight]) in their order. */
internal fun highlightsOf(items: List<MediaItem>, notes: Map<String, PhotoNote>): List<MediaItem> =
    items.mapNotNull { item -> notes[item.id]?.highlight?.let { item to it } }.sortedWith(compareBy({ it.second }, { it.first.takenAtMillis })).map { it.first }

/** The named sets ([PhotoNote.setInfo] on any of a set's photos), by set id; their photos oldest first. */
internal fun namedSetsIn(notes: Map<String, PhotoNote>): Map<String, NamedSet> {
    val members = notes.values.filter { it.feedSet != null }.groupBy { it.feedSet!! }
    return members.mapNotNull { (set, notes) ->
        val info = notes.sortedBy { it.highlight ?: Int.MAX_VALUE }.firstNotNullOfOrNull { it.setInfo } ?: return@mapNotNull null
        set to NamedSet(set, info.name, info.showOn, notes.sortedBy { it.takenAtMillis }.map { it.deviceId })
    }.toMap()
}

/** Where each named set shows (set id to [ShowOn]), for [buildFeed]. */
internal fun placementsIn(notes: Map<String, PhotoNote>): Map<String, ShowOn> = namedSetsIn(notes).mapValues { it.value.showOn }

/**
 * [hidden] photos (newest first) counted on, or for [revealed] sources shown on, the row of their day nearest to
 * them in time; days without a row of shown photos say nothing.
 */
private fun List<FeedSection>.withHidden(hidden: List<MediaItem>, say: Boolean, revealed: Set<FeedReveal>): List<FeedSection> {
    if (hidden.isEmpty()) return this
    val byDay = HashMap<LocalDay, MutableList<Int>>()
    forEachIndexed { i, section -> section.days.forEach { byDay.getOrPut(it) { mutableListOf() } += i } }
    val perSection = HashMap<Int, MutableList<MediaItem>>()
    hidden.forEach { item ->
        val rows = byDay[item.day] ?: return@forEach
        val row = rows.minBy { i ->
            val span = this[i].span
            maxOf(span.startMillis - item.takenAtMillis, item.takenAtMillis - span.endMillis, 0)
        }
        perSection.getOrPut(row) { mutableListOf() } += item
    }
    return mapIndexed { i, section ->
        val items = perSection[i] ?: return@mapIndexed section
        val bySource = items.groupBy { it.source }
        val shownHere = bySource.keys.filter { FeedReveal(section.key, it) in revealed }.toSet()
        section.copy(
            hidden = if (say) {
                sourceCounts(items.filter { it.source !in shownHere })
            } else {
                emptyMap()
            },
            revealed = shownHere.sorted().map { RevealedPhotos(it, bySource.getValue(it)) },
        )
    }
}

private fun Places.nameOf(item: MediaItem) = location(item)?.let(::name)

/**
 * Stand-ins that place [sounds] in the rows ([groupMoments] takes photos): one where each starts, and with
 * [GroupingMode.BY_GAP] more along it to where it ends, closer than the gap, so a long recording isn't cut into two
 * rows. Never shown.
 */
private fun soundMarks(sounds: Collection<SoundRecording>, grouping: TimelineGrouping): List<MediaItem> = sounds.flatMap { sound ->
    fun mark(id: String, at: Long) = MediaItem(id, MediaType.AUDIO, at, sound.day, sound.durationMillis, 0, 0, null, null, source = sound.source, isOnDevice = false)
    val start = mark(SOUND_PREFIX + sound.sha256, sound.startMillis)
    if (grouping.mode != GroupingMode.BY_GAP || sound.durationMillis <= 0) return@flatMap listOf(start)
    val step = (grouping.gapMillis / 2).coerceAtLeast(MINUTE_MILLIS)
    val along = generateSequence(sound.startMillis + step) { it + step }.takeWhile { it < sound.endMillis } + sound.endMillis
    listOf(start) + along.mapIndexed { i, t -> mark("$SOUND_END_PREFIX${sound.sha256}/$i", t) }
}

private const val MINUTE_MILLIS = 60_000L

/** Calls (any order) as rows: those less than [MOMENT_GAP_MILLIS] apart are one row. */
private fun callRuns(calls: List<SoundRecording>): List<FeedSound.Calls> {
    val runs = mutableListOf<MutableList<SoundRecording>>()
    calls.sortedBy { it.startMillis }.forEach { call ->
        val run = runs.lastOrNull()
        if (run != null && call.startMillis - run.maxOf { it.endMillis } <= MOMENT_GAP_MILLIS) run += call else runs += mutableListOf(call)
    }
    return runs.map { FeedSound.Calls(it.sortedByDescending { call -> call.startMillis }) }
}

/**
 * Splits one source's photos of a row (oldest first) into moments. Camera photos split after [MOMENT_GAP_MILLIS]
 * without a photo, or after [MOMENT_MOVE_GAP_MILLIS] when the next photo is [MOMENT_MOVE_METERS] from the last
 * located one; photos from apps (received, saved, captured from the screen) are one moment per row.
 */
internal fun splitMoments(source: MediaSource, items: List<MediaItem>, places: Places): List<List<MediaItem>> {
    if (items.isEmpty()) return emptyList()
    if (source != MediaSource.CAMERA) return listOf(items)
    val moments = mutableListOf(mutableListOf(items.first()))
    var lastPlace: GeoPoint? = places.location(items.first())
    items.drop(1).forEach { item ->
        val previous = moments.last().last()
        val gap = item.takenAtMillis - previous.takenAtMillis
        val here = places.location(item)
        val moved = here != null && lastPlace != null && distanceMeters(here, lastPlace) > MOMENT_MOVE_METERS
        if (gap > MOMENT_GAP_MILLIS || (moved && gap > MOMENT_MOVE_GAP_MILLIS)) {
            moments += mutableListOf(item)
        } else {
            moments.last() += item
        }
        if (here != null) lastPlace = here
    }
    return moments
}

/**
 * [items] (oldest first) as blocks: a photo joins the block before it when it looks like that block's first photo
 * ([PhotoLooks.similar]) or is from the same camera burst; photos not read yet join when taken within
 * [BURST_GAP_MILLIS]. Videos taken within [VIDEO_SET_METERS] of each other are one set, where the first of them is;
 * a video without a location stands alone. Photos and videos in [alone] (a cropped photo) are shown on their own. A
 * photo that starts a set merged by hand ([merged], by its first photo's id) is that whole set, and nothing joins it.
 */
internal fun blocksOf(
    items: List<MediaItem>,
    looks: PhotoLooks,
    alone: Set<String> = emptySet(),
    merged: Map<String, List<MediaItem>> = emptyMap(),
    places: Places = Places.NONE,
): List<FeedBlock> {
    val runs = mutableListOf<MutableList<MediaItem>>()
    // Sets of videos from one place; a later video of the place joins its set even with photos between.
    val videoRuns = mutableListOf<MutableList<MediaItem>>()
    // Runs nothing may join: sets merged by hand.
    val closed = mutableListOf<MutableList<MediaItem>>()
    items.forEach { item ->
        merged[item.id]?.let { members ->
            runs += members.toMutableList().also { closed += it }
            return@forEach
        }
        if (item.type == MediaType.VIDEO && item.id !in alone) {
            places.location(item)?.let { here ->
                val set = videoRuns.lastOrNull { run -> places.location(run.first())?.let { distanceMeters(it, here) <= VIDEO_SET_METERS } == true }
                if (set != null) {
                    set += item
                } else {
                    runs += mutableListOf(item).also { videoRuns += it }
                }
                return@forEach
            }
        }
        val run = runs.lastOrNull()
        val first = run?.first()
        val joins = run != null && closed.none { it === run } && first != null && item.type == MediaType.IMAGE && first.type == MediaType.IMAGE &&
            item.id !in alone && first.id !in alone && (
            // A camera burst is a set of its own (asked Nov 9, 2026): its shots join only each other, and a photo
            // that looks like them stays out, before or after it.
            if (first.burstId != null || item.burstId != null) {
                first.burstId == item.burstId
            } else if (looks.canCompare(first) && looks.canCompare(item)) {
                looks.similar(first, item)
            } else {
                item.takenAtMillis - run.last().takenAtMillis <= BURST_GAP_MILLIS
            }
            )
        if (joins) run += item else runs += mutableListOf(item)
    }
    return runs.map { if (it.size == 1) FeedBlock.Single(it.first()) else FeedBlock.Similar(it) }
}

/** The value [of] gives most often among [items], ignoring nulls; the earliest one on a tie. */
private fun <T> mostCommon(items: List<MediaItem>, of: (MediaItem) -> T?): T? =
    items.mapNotNull(of).groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

/**
 * Where the owner lives: the 0.1° square (about 10 km) most of [library]'s located photos were taken in, its photos'
 * average position. Null with fewer than [HOME_MIN_PHOTOS] located photos.
 */
internal fun home(library: List<MediaItem>, places: Places): GeoPoint? {
    val located = library.mapNotNull { places.location(it) }
    if (located.size < HOME_MIN_PHOTOS) return null
    val square = located.groupBy { (it.latitude * 10).toInt() to (it.longitude * 10).toInt() }.maxBy { it.value.size }.value
    return GeoPoint(square.map { it.latitude }.average(), square.map { it.longitude }.average())
}

/**
 * [home] of the library's photos [shown] shows, from the database (step 6b): the photos with a location of their own
 * counted per square by SQL, and those without one that a place was [found] for (by id). The same square as [home]
 * (on a tie, the one whose newest photo comes first) and the same average, added up in the same order. Blocking.
 */
internal fun home(table: LibraryTable, found: Map<String, GeoPoint>, shown: ShownSources): GeoPoint? {
    fun square(point: GeoPoint) = (point.latitude * 10).toInt().toLong() to (point.longitude * 10).toInt().toLong()
    val counts = HashMap<Pair<Long, Long>, Int>()
    table.squareCounts(shown.alwaysShown.map { it.day }).forEach { group ->
        if (if (group.day != null) shown.shows(group.day, group.source) else shown.isShown(group.source)) {
            counts[group.square] = (counts[group.square] ?: 0) + group.count
        }
    }
    val placed = table.unlocated(found.keys).mapNotNull { row ->
        found[row.key.id]?.takeIf { shown.shows(row.key.day, row.source) }?.let { LocatedRow(row.key, row.source, it) }
    }
    placed.forEach { row -> square(row.point!!).let { counts[it] = (counts[it] ?: 0) + 1 } }
    if (counts.values.sum() < HOME_MIN_PHOTOS) return null
    val most = counts.values.max()
    // Each square with the most photos, its photos in the library's order: groupBy meets the square of the first first.
    val photos = counts.filterValues { it == most }.keys.map { square ->
        (table.inSquare(square).filter { shown.shows(it.key.day, it.source) } + placed.filter { square(it.point!!) == square }).sortedBy { it.key }
    }.minBy { it.first().key }.map { it.point!! }
    return GeoPoint(photos.map { it.latitude }.average(), photos.map { it.longitude }.average())
}
