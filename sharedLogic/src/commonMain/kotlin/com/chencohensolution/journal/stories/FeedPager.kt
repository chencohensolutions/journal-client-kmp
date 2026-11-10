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
import kotlin.time.TimeSource

/**
 * Moments read from the database a window at a time (docs/plans/library-on-disk.md, phase 5): the newest rows first,
 * a page older or newer as the screen scrolls ([loadMore], [loadNewer]), and a window around a day gone to ([loadTo]).
 * The window holds at most [MAX_PAGES] pages: past that the pages at its far end are let go, so the feed in memory is
 * about as large whatever the library's size or how far it was scrolled (asked Nov 10, 2026: going to the oldest day
 * read all 2,169 rows of a 92,727-photo library, 3.5 s and 120 MB). Keeps [table] current ([refresh]) and reads the
 * window again after each change. A row whose hidden photos are shown ([FeedReveal]) is made again in memory with
 * them, never saved.
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

    // The window, by its rows' newest moments (the feed's order), both ends included: rows made again keep their
    // moments, not their keys. [newest] is null while the window starts at the top, so new photos come into it.
    private var newest: Long? = null
    private var oldest: Long? = null
    private var hasOlder = false

    /** The keys and newest moments of the rows of the window, as last read (also those that show nothing). */
    private var windowKeys: Set<String> = emptySet()
    private var windowMillis: List<Long> = emptyList()

    private fun maxRows() = pageSize() * MAX_PAGES
    private var timeline = FeedTimeline.NONE
    private var counts: Map<MediaSource, Int> = emptyMap()

    /**
     * Makes the rows of what changed again ([FeedTable.refresh]) and reads the window again (at least a page): the feed
     * as it is now, with [shown] laying scores, kept originals and statuses over each item.
     */
    suspend fun refresh(context: FeedContext, revealed: Set<FeedReveal>, shown: (LibraryRow) -> MediaItem): MomentsFeed = mutex.withLock {
        withContext(io) {
            val start = TimeSource.Monotonic.markNow()
            val windows = table.refresh(context)
            if (windows > 0 || timeline === FeedTimeline.NONE) {
                Measure.log?.invoke("Moments: $windows windows made again in ${start.elapsedNow().inWholeMilliseconds} ms")
                timeline = table.timeline()
                counts = library.sourceCounts()
            }
            if (newest == null) {
                // At the top: as many rows as it holds (at least a page) from the newest down, which takes in new ones.
                val rows = minOf(maxOf(windowMillis.size, pageSize()), maxRows())
                oldest = table.millisBefore(Long.MAX_VALUE, rows).let { if (it.size < rows) Long.MIN_VALUE else it.last() }
            }
            read(context, revealed, shown).takeIf { windowKeys.isNotEmpty() || newest == null } ?: run {
                // Nothing left between its ends (its rows made again elsewhere): the top instead.
                newest = null
                oldest = table.millisBefore(Long.MAX_VALUE, pageSize()).let { if (it.size < pageSize()) Long.MIN_VALUE else it.last() }
                read(context, revealed, shown)
            }
        }
    }

    /** One more page of older rows; the newest are let go past [MAX_PAGES]. Null when there are none. */
    suspend fun loadMore(context: FeedContext, revealed: Set<FeedReveal>, shown: (LibraryRow) -> MediaItem): MomentsFeed? = mutex.withLock {
        val from = oldest ?: return@withLock null
        if (!hasOlder) return@withLock null
        withContext(io) {
            val more = table.millisBefore(from, pageSize())
            if (more.isEmpty()) return@withContext null
            oldest = more.last()
            val rows = windowMillis + more
            if (rows.size > maxRows()) newest = rows[rows.size - maxRows()]
            read(context, revealed, shown)
        }
    }

    /** One more page of newer rows, while the window doesn't start at the top; the oldest are let go past [MAX_PAGES]. */
    suspend fun loadNewer(context: FeedContext, revealed: Set<FeedReveal>, shown: (LibraryRow) -> MediaItem): MomentsFeed? = mutex.withLock {
        val from = newest ?: return@withLock null
        withContext(io) {
            val more = table.millisAfter(from, pageSize())
            newest = if (more.size < pageSize()) null else more.last()
            val rows = more.reversed() + windowMillis
            if (rows.size > maxRows()) oldest = rows[maxRows() - 1]
            read(context, revealed, shown)
        }
    }

    /**
     * The row of [day] (the timeline's day, a photo shown in Moments): its key, and the feed when it had to be read
     * (null when it is in the window already). A day outside the window gets a window of its own, a page above its row
     * and two below, in place of the one read (whatever it is, a far day reads no more than that). The row is looked
     * up and read in one turn, so a refresh making the rows again (with new keys) can't come between.
     */
    suspend fun loadTo(day: LocalDay, context: FeedContext, revealed: Set<FeedReveal>, shown: (LibraryRow) -> MediaItem): FeedJump = mutex.withLock {
        withContext(io) {
            val key = table.sectionOf(day) ?: return@withContext FeedJump(null, null)
            // By the rows' keys as the database has them: a row that shows nothing is in the window too (counting the
            // rows shown read the whole feed again on every jump to the oldest day, Nov 10, 2026).
            if (key in windowKeys) return@withContext FeedJump(key, null)
            val at = table.millisOf(key) ?: return@withContext FeedJump(null, null)
            val above = table.millisAfter(at, pageSize())
            newest = if (above.size < pageSize()) null else above.last()
            oldest = table.millisBefore(at, 2 * pageSize()).lastOrNull() ?: at
            FeedJump(key, read(context, revealed, shown))
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

    /** The rows of the window, those with shown hidden photos made again with them. */
    private fun read(context: FeedContext, revealed: Set<FeedReveal>, shown: (LibraryRow) -> MediaItem): MomentsFeed {
        val sounds = context.sounds.associateBy { it.sha256 }
        val bottom = oldest ?: Long.MIN_VALUE
        val window = table.window(newest ?: Long.MAX_VALUE, bottom, sounds, shown)
        windowKeys = window.keys
        windowMillis = window.millis
        hasOlder = table.millisBefore(bottom, 1).isNotEmpty()
        newest?.let { if (table.millisAfter(it, 1).isEmpty()) newest = null }
        val sections = window.sections
        val tuckedShown = context.tuckedShown
        val withRevealed = if (revealed.isEmpty() && tuckedShown.isEmpty()) sections else sections.map { section ->
            val tuckedHere = section.tucked > 0 && section.days.any { it in tuckedShown }
            if (revealed.none { it.section == section.key } && !tuckedHere) section else reveal(section, context, revealed, shown) ?: section
        }
        return MomentsFeed(
            entries = withRevealed.map(FeedEntry::Section),
            counts = counts,
            shown = context.shown.shownAmong(counts),
            sheet = context.shown.choices(counts),
            givenTimeline = timeline,
            importedShown = context.shown.importedShown,
            importedSheet = context.shown.importedChoices(counts),
            hasNewer = newest != null,
            hasOlder = hasOlder,
        )
    }

    /** [section] made again with its shown hidden photos ([revealed]), from the window of its days. */
    private fun reveal(section: FeedSection, context: FeedContext, revealed: Set<FeedReveal>, shown: (LibraryRow) -> MediaItem): FeedSection? {
        val window = windowsFor(section.days.toSet(), table, context.grouping, context.sounds, context.sets).firstOrNull() ?: return null
        val source = object : FeedSource by table {
            override fun items(first: LocalDay, last: LocalDay): List<MediaItem> = table.shownItems(first, last, shown)
        }
        return buildWindow(
            window, source, context.shown, context.grouping, context.places, context.looks, context.alone, context.sets, revealed, context.sounds,
            context.tuckedShown, context.placements,
        )
            .firstOrNull { it.key == section.key }
    }
}

/** Pages a window of Moments holds at most ([FeedPager]): about 180 rows at the default page. */
internal const val MAX_PAGES = 6

/** Where going to a day lands ([FeedPager.loadTo]): its row's key (null when no row has the day), and the feed read down to it, if more was read. */
internal class FeedJump(val key: String?, val feed: MomentsFeed?)
