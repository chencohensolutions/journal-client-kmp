package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.LibraryRow
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.Measure
import com.chencohensolution.journal.library.SoundRecording
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
    /** Rows read at a time. */
    private val pageSize: () -> Int = { PAGE },
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

    /** The rows down to the one keyed [key] (the timeline's day, a photo shown in Moments), and a page after it. */
    suspend fun loadTo(key: String, context: FeedContext, revealed: Set<FeedReveal>, shown: (LibraryRow) -> MediaItem): MomentsFeed? = mutex.withLock {
        withContext(io) {
            val down = table.sectionsDownTo(key, context.sounds.associateBy { it.sha256 }, shown).size
            if (down == 0 || down <= loaded) null else read(down + pageSize(), context, revealed, shown)
        }
    }

    /** Notes [days] for the next [refresh]: what changed outside the library (a note, a place, a fingerprint). */
    suspend fun markDays(days: Collection<LocalDay>) = withContext(io) { table.markDirty(days) }

    /** The key of the row with photos of [day]. */
    suspend fun sectionOf(day: LocalDay): String? = withContext(io) { table.sectionOf(day) }

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

    private companion object {
        /** Rows read at a time: several screens of Moments. */
        const val PAGE = 30
    }
}
