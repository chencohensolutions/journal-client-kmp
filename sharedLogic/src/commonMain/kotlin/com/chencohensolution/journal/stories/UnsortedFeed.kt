package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.places.distanceMeters
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.similarity.PhotoLooks

/*
 * Unsorted photos read like a story that arranged itself: each row of the journal (a day, or a run of photos; see
 * the grouping setting) split into moments by time, place and source, with near-duplicates shown as one photo you
 * swipe. Nothing here is saved: it is worked out again from the library, and only "+ Story" makes a story of it.
 */

/** One photo, or near-duplicates (a burst, or photos that look alike) shown as one photo you swipe through. */
sealed interface FeedBlock {
    val items: List<MediaItem>

    data class Single(val item: MediaItem) : FeedBlock {
        override val items: List<MediaItem> get() = listOf(item)
    }

    data class Similar(override val items: List<MediaItem>) : FeedBlock
}

/** Photos from one source taken close together, in one place; oldest first. */
data class FeedMoment(
    val source: MediaSource,
    val blocks: List<FeedBlock>,
    /** The most precise name of where most of them were taken, once looked up. */
    val place: String?,
) {
    val items: List<MediaItem> get() = blocks.flatMap { it.items }
    val key: String get() = blocks.first().items.first().id
    val startMillis: Long get() = blocks.first().items.first().takenAtMillis
    val endMillis: Long get() = blocks.last().items.last().takenAtMillis
}

/** One row of the journal ([span]: a day, or a run of photos), its moments newest first. */
data class FeedSection(
    val span: Moment,
    val moments: List<FeedMoment>,
    /** The towns its camera photos were taken in, in the order visited. */
    val places: List<String>,
) {
    val key: String get() = span.key

    /** In the order shown: moments newest first, each oldest first. */
    val items: List<MediaItem> get() = moments.flatMap { it.items }
}

/**
 * Days in a row away from home, offered as one story ("Save as story"), never made one by itself. Its sections
 * follow it in the feed.
 */
data class FeedTrip(
    /** "trip-<first day>"; dismissing a trip remembers it. */
    val key: String,
    /** The town most of it was in. */
    val place: String?,
    val firstDay: LocalDay,
    val lastDay: LocalDay,
    val items: List<MediaItem>,
) {
    val days: Int get() = (lastDay.toEpochDay() - firstDay.toEpochDay() + 1).toInt()
    val cover: MediaItem get() = items.first()
}

sealed interface FeedEntry {
    data class Section(val section: FeedSection) : FeedEntry
    data class Trip(val trip: FeedTrip) : FeedEntry
}

/** The Unsorted tab: the unsorted photos of the [shown] sources, newest first. */
data class UnsortedFeed(
    val entries: List<FeedEntry> = emptyList(),
    /** Unsorted photos per source, shown or not. */
    val counts: Map<MediaSource, Int> = emptyMap(),
    val shown: Set<MediaSource> = DEFAULT_SOURCES,
) {
    // Compared by identity, like StoriesState: screens key on it, and a field-by-field comparison is slow.
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()

    fun section(key: String): FeedSection? =
        entries.firstNotNullOfOrNull { (it as? FeedEntry.Section)?.section?.takeIf { section -> section.key == key } }

    fun trip(key: String): FeedTrip? =
        entries.firstNotNullOfOrNull { (it as? FeedEntry.Trip)?.trip?.takeIf { trip -> trip.key == key } }

    /** The near-duplicate sets among [itemIds], to group when they become a story. */
    fun similarSets(itemIds: Collection<String>): List<List<MediaItem>> {
        val wanted = itemIds.toSet()
        return entries.flatMap { entry ->
            (entry as? FeedEntry.Section)?.section?.moments.orEmpty()
                .flatMap { it.blocks }
                .filterIsInstance<FeedBlock.Similar>()
                .map { block -> block.items.filter { it.id in wanted } }
                .filter { it.size >= 2 }
        }
    }

    companion object {
        /** What the filter bar shows until the user changes it. */
        val DEFAULT_SOURCES: Set<MediaSource> = setOf(MediaSource.CAMERA, MediaSource.WHATSAPP)
    }
}

/** A new camera moment starts after this long without a photo… */
internal const val MOMENT_GAP_MILLIS = 2 * 3_600_000L

/** …or after this long and [MOMENT_MOVE_METERS] away, so a walk stays one moment and a drive to another town doesn't. */
internal const val MOMENT_MOVE_GAP_MILLIS = 20 * 60_000L
internal const val MOMENT_MOVE_METERS = 1_000.0

/** Days with most located photos this far from home are away; two or more in a row are a trip. */
internal const val TRIP_AWAY_METERS = 50_000.0
internal const val TRIP_MIN_DAYS = 2

/** Home is only guessed from this many located photos or more. */
internal const val HOME_MIN_PHOTOS = 20

/**
 * The feed of [unsorted] (newest first): only the [shown] sources, split into rows like the journal ([grouping]),
 * each into moments ([splitMoments]) of blocks ([blocksOf]). Trips are found among the rows using every located
 * photo of [library] to tell where home is; [dismissedTrips] are left out.
 */
internal fun buildFeed(
    unsorted: List<MediaItem>,
    library: List<MediaItem>,
    shown: Set<MediaSource>,
    grouping: TimelineGrouping,
    places: Places = Places.NONE,
    looks: PhotoLooks = PhotoLooks.NONE,
    dismissedTrips: Set<String> = emptySet(),
): UnsortedFeed {
    val counts = unsorted.groupingBy { it.source }.eachCount()
    val sections = groupMoments(unsorted.filter { it.source in shown }, grouping).map { span ->
        val moments = span.items.groupBy { it.source }
            .flatMap { (source, items) -> splitMoments(source, items.reversed(), places) }
            .map { items -> FeedMoment(items.first().source, blocksOf(items, looks), mostCommon(items) { places.nameOf(it)?.label }) }
            .sortedByDescending { it.startMillis }
        val towns = moments.reversed()
            .filter { it.source == MediaSource.CAMERA }
            .flatMap { moment -> moment.items.mapNotNull { places.nameOf(it)?.town } }
        FeedSection(span, moments, towns.distinct())
    }
    // A trip comes just above the newest row of its last day.
    val trips = findTrips(sections, home(library, places), places).filter { it.key !in dismissedTrips }
    val tripAbove = trips.associateBy { trip -> sections.indexOfFirst { section -> section.items.any { it.day == trip.lastDay } } }
    val entries = sections.flatMapIndexed { i, section -> listOfNotNull(tripAbove[i]?.let(FeedEntry::Trip), FeedEntry.Section(section)) }
    return UnsortedFeed(entries, counts, shown)
}

private fun Places.nameOf(item: MediaItem) = location(item)?.let(::name)

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
 * [BURST_GAP_MILLIS]. Videos stand alone.
 */
internal fun blocksOf(items: List<MediaItem>, looks: PhotoLooks): List<FeedBlock> {
    val runs = mutableListOf<MutableList<MediaItem>>()
    items.forEach { item ->
        val run = runs.lastOrNull()
        val first = run?.first()
        val joins = run != null && first != null && item.type == MediaType.IMAGE && first.type == MediaType.IMAGE && (
            (first.burstId != null && first.burstId == item.burstId) ||
                (if (looks.canCompare(first) && looks.canCompare(item)) {
                    looks.similar(first, item)
                } else {
                    item.takenAtMillis - run.last().takenAtMillis <= BURST_GAP_MILLIS
                })
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
 * Runs of [TRIP_MIN_DAYS] or more days in a row whose located camera photos were mostly taken [TRIP_AWAY_METERS]
 * from [home]. Days without unsorted photos break a run; days without located photos don't.
 */
internal fun findTrips(sections: List<FeedSection>, home: GeoPoint?, places: Places): List<FeedTrip> {
    if (home == null) return emptyList()
    val byDay = sections.flatMap { it.items }.groupBy { it.day }
    val days = byDay.keys.sorted()
    val runs = mutableListOf<MutableList<LocalDay>>()
    var previous: LocalDay? = null
    days.forEach { day ->
        val located = byDay.getValue(day).filter { it.source == MediaSource.CAMERA }.mapNotNull { places.location(it) }
        val away = located.count { distanceMeters(it, home) > TRIP_AWAY_METERS } * 2 > located.size
        val follows = previous?.let { day.toEpochDay() - it.toEpochDay() == 1L } == true
        when {
            located.isEmpty() && follows && runs.lastOrNull()?.last() == previous -> runs.last() += day
            away && follows && runs.lastOrNull()?.last() == previous -> runs.last() += day
            away -> runs += mutableListOf(day)
        }
        previous = day
    }
    return runs
        .map { run -> run.dropLastWhile { day -> byDay.getValue(day).none { places.location(it) != null } } }
        .filter { it.size >= TRIP_MIN_DAYS }
        .map { run ->
            val items = run.flatMap { byDay.getValue(it) }.sortedBy { it.takenAtMillis }
            FeedTrip("trip-${run.first()}", mostCommon(items) { places.nameOf(it)?.town }, run.first(), run.last(), items)
        }
}
