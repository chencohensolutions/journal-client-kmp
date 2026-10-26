package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.settings.MomentsPaging
import com.chencohensolution.journal.library.LibraryKey
import com.chencohensolution.journal.library.LibraryRow
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.Measure
import com.chencohensolution.journal.library.SoundRecording
import com.chencohensolution.journal.places.CELL_DEGREES
import com.chencohensolution.journal.places.PlaceBatches
import com.chencohensolution.journal.places.cellKey
import com.chencohensolution.journal.settings.ShownSources
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Moments read from the database a page at a time (docs/plans/library-on-disk.md, phase 5): the newest rows first,
 * more as the screen asks ([loadMore], [loadTo]), so the feed in memory is what was scrolled through, never the whole
 * library. Keeps [table] current ([refresh]) and reads again the rows it has shown after each change. A row whose
 * hidden photos are shown ([FeedReveal]) is made again in memory with them, never saved.
 */
internal class FeedPager(
    private val table: FeedTable,
    private val library: LibraryTable,
    /** Where the database is read and written. */
    private val io: CoroutineDispatcher,
    /** Rows read at a time (Settings › Advanced › Loading Moments), read again for each page. */
    private val pageSize: () -> Int = { MomentsPaging.DEFAULT_ROWS_PER_PAGE },
) {
    private val mutex = Mutex()
    private var loaded = 0
    private var everything = false
    private var timeline = FeedTimeline.NONE
    private var counts: Map<MediaSource, Int> = emptyMap()

    /**
     * Makes the rows of what changed again ([FeedTable.refresh]) and reads the rows shown so far (at least a page)
     * again: the feed as it is now, with [shown] laying scores, kept originals and statuses over each item.
     */
    suspend fun refresh(context: FeedContext, revealed: Set<FeedReveal>, shown: (LibraryRow) -> MediaItem): MomentsFeed = mutex.withLock {
        withContext(io) {
            val windows = table.refresh(context)
            if (windows > 0 || timeline === FeedTimeline.NONE) {
                Measure.log?.invoke("Moments: $windows windows made again")
                timeline = table.timeline()
                counts = library.sourceCounts()
            }
            read(maxOf(loaded, pageSize()), context, revealed, shown)
        }
    }

    /** One more page of older rows. */
    suspend fun loadMore(context: FeedContext, revealed: Set<FeedReveal>, shown: (LibraryRow) -> MediaItem): MomentsFeed? = mutex.withLock {
        if (everything) return@withLock null
        withContext(io) { read(loaded + pageSize(), context, revealed, shown) }
    }

    /**
     * The rows down to the row of [day] (the timeline's day, a photo shown in Moments), and a page after it: that row's
     * key, and the feed when more had to be read (null when the row is read already). The row is looked up and read in
     * one turn, so a refresh making the rows again (with new keys) can't come between.
     */
    suspend fun loadTo(day: LocalDay, context: FeedContext, revealed: Set<FeedReveal>, shown: (LibraryRow) -> MediaItem): FeedJump = mutex.withLock {
        withContext(io) {
            val key = table.sectionOf(day) ?: return@withContext FeedJump(null, null)
            val down = table.sectionsDownTo(key, context.sounds.associateBy { it.sha256 }, shown).size
            FeedJump(key, if (down == 0 || down <= loaded) null else read(down + pageSize(), context, revealed, shown))
        }
    }

    /** Notes [days] for the next [refresh]: what changed outside the library (a note, a place, a fingerprint). */
    suspend fun markDays(days: Collection<LocalDay>) = withContext(io) { table.markDirty(days) }

    /** The key of the row with photos of [day]. */
    suspend fun sectionOf(day: LocalDay): String? = mutex.withLock { withContext(io) { table.sectionOf(day) } }

    /** The days of the library's items [ids] (those it has). */
    suspend fun daysOf(ids: Collection<String>): Set<LocalDay> = if (ids.isEmpty()) emptySet() else withContext(io) { table.daysOf(ids) }

    /**
     * The days of the library's items in [cells] (places newly named, by [cellKey]): those with a location of their own
     * there, and those without one that [placed] (the locations found for photos, by id) puts there.
     */
    suspend fun daysInCells(cells: Set<String>, placed: Map<String, GeoPoint>): Set<LocalDay> = if (cells.isEmpty()) emptySet() else withContext(io) {
        val indexes = cells.map { cell -> cell.split(",").map(String::toLong) }
        // The box around the cells, a cell wider each way so rounding at an edge never leaves a photo out.
        val south = (indexes.minOf { it[0] } - 1) * CELL_DEGREES
        val north = (indexes.maxOf { it[0] } + 2) * CELL_DEGREES
        val west = (indexes.minOf { it[1] } - 1) * CELL_DEGREES
        val east = (indexes.maxOf { it[1] } + 2) * CELL_DEGREES
        val days = library.located(south, west, north, east).filter { (_, _, point) -> cellKey(point) in cells }.mapTo(HashSet()) { it.second }
        days += library.daysOfUnlocated(placed.filter { (_, point) -> cellKey(point) in cells }.keys)
        days
    }

    /** Up to [limit] of the phone's photos in the screens' order after [after]: what fingerprints are read for (step 6b). */
    suspend fun deviceImages(after: LibraryKey?, limit: Int): List<MediaItem> = withContext(io) { library.deviceImages(after, limit) }

    /** The photos to locate and the places to name, a batch at a time (step 6b). */
    val placeBatches: PlaceBatches = object : PlaceBatches {
        override suspend fun unlocated(after: LibraryKey?, limit: Int) = withContext(io) { library.deviceUnlocated(after, limit) }

        override suspend fun points(after: LibraryKey?, limit: Int) = withContext(io) { library.points(after, limit) }
    }

    /** Explore's home among the photos [shown] shows, with the places [found] for photos without their own (step 6b). */
    suspend fun home(found: Map<String, GeoPoint>, shown: ShownSources): GeoPoint? = withContext(io) { com.chencohensolution.journal.stories.home(library, found, shown) }

    /** [block] over the library's rows, on the database's dispatcher: Explore's and the map's reads (step 6c). */
    suspend fun <T> readLibrary(block: (LibraryTable) -> T): T = withContext(io) { block(library) }

    /** The newest [count] rows, those with shown hidden photos made again with them. */
    private fun read(count: Int, context: FeedContext, revealed: Set<FeedReveal>, shown: (LibraryRow) -> MediaItem): MomentsFeed {
        val sounds = context.sounds.associateBy { it.sha256 }
        val sections = table.sections(Long.MAX_VALUE, count.toLong(), sounds, shown)
        loaded = sections.size
        everything = sections.size < count
        val withRevealed = if (revealed.isEmpty()) sections else sections.map { section ->
            if (revealed.none { it.section == section.key }) section else reveal(section, context, revealed, shown) ?: section
        }
        return MomentsFeed(
            entries = withRevealed.map(FeedEntry::Section),
            counts = counts,
            shown = context.shown.shownOf(MediaSource.choices(counts.keys)),
            sheet = context.shown.choices(counts),
            givenTimeline = timeline,
        )
    }

    /** [section] made again with its shown hidden photos ([revealed]), from the window of its days. */
    private fun reveal(section: FeedSection, context: FeedContext, revealed: Set<FeedReveal>, shown: (LibraryRow) -> MediaItem): FeedSection? {
        val window = windowsFor(section.days.toSet(), table, context.grouping, context.sounds, context.sets).firstOrNull() ?: return null
        val source = object : FeedSource by table {
            override fun items(first: LocalDay, last: LocalDay): List<MediaItem> = table.shownItems(first, last, shown)
        }
        return buildWindow(window, source, context.shown, context.grouping, context.places, context.looks, context.alone, context.sets, revealed, context.sounds)
            .firstOrNull { it.key == section.key }
    }
}

/** Where going to a day lands ([FeedPager.loadTo]): its row's key (null when no row has the day), and the feed read down to it, if more was read. */
internal class FeedJump(val key: String?, val feed: MomentsFeed?)
