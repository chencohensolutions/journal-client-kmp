package com.chencohensolution.journal.stories

import com.chencohensolution.journal.db.Feed_section
import com.chencohensolution.journal.db.Feed_sound
import com.chencohensolution.journal.db.LibraryDatabase
import com.chencohensolution.journal.library.LibraryRow
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.SoundRecording
import com.chencohensolution.journal.library.libraryRow
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.similarity.PhotoLooks
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Everything Moments' rows are made from besides the library's items. */
internal class FeedContext(
    val shown: ShownSources,
    val grouping: TimelineGrouping,
    val places: Places,
    val looks: PhotoLooks,
    val alone: Set<String>,
    val sets: Map<String, String>,
    val sounds: List<SoundRecording>,
    /** What the rows were made with (settings, threshold): a different one makes them all again. */
    val version: String,
)

/**
 * Moments' rows kept in `feed_section` (docs/plans/library-on-disk.md, phase 5): made a window of days at a time
 * ([windowsFor], [buildWindow]) where `library_item` changed, as its triggers note in `feed_dirty`, or all of them a span
 * of a few thousand items at a time, and read a page at a time ([sections]). A row keeps its structure by ids ([SectionData]); its items are read from `library_item` when it is
 * read. Blocking: call off the main thread.
 */
internal class FeedTable(
    private val database: LibraryDatabase,
    /** Items per span when every row is made again ([refreshAll]); tests make it small. */
    private val spanItems: Int = SPAN_ITEMS,
) : FeedSource {
    private val queries get() = database.feedQueries
    private val state get() = database.stateQueries

    override fun items(first: LocalDay, last: LocalDay): List<MediaItem> =
        queries.itemsOfDays(first.toEpochDay(), last.toEpochDay(), ::libraryRow).executeAsList().map { it.item }

    override fun newestBefore(day: LocalDay): MediaItem? = queries.newestBeforeDay(day.toEpochDay(), ::libraryRow).executeAsOneOrNull()?.item

    override fun oldestAfter(day: LocalDay): MediaItem? = queries.oldestAfterDay(day.toEpochDay(), ::libraryRow).executeAsOneOrNull()?.item

    override fun daysOf(ids: Collection<String>): Set<LocalDay> =
        ids.chunked(CHUNK).flatMapTo(HashSet()) { chunk -> queries.daysOfItems(chunk).executeAsList().map(LocalDay::fromEpochDay) }

    /** Notes [days] (or all days) for [refresh]: what changed outside the library (a note, a place, a fingerprint). */
    fun markDirty(days: Collection<LocalDay>) = database.transaction { days.forEach { queries.markFeedDirty(it.toEpochDay()) } }

    fun markAllDirty() = queries.markFeedDirty(ALL)

    /** Tests only: runs once the rows are made, before they are written (the library changing meanwhile). */
    internal var afterBuild: () -> Unit = {}

    /**
     * Makes the rows of the days noted since the last refresh again; all of them when everything is noted or
     * [context] differs from what they were made with. Returns how many windows were made.
     */
    fun refresh(context: FeedContext): Int {
        markSoundDays(context.sounds)
        val marks = queries.dirtyMarks().executeAsList()
        val dirty = marks.map { it.day }
        // Many days noted (a large library read in, or changed while its rows were made): all made again a span at a
        // time ([refreshAll]), which takes seconds, rather than a window per day, which took minutes for 5,467 days on
        // the emulator.
        val everything = ALL in dirty || dirty.size > MANY_DAYS || state.stateValue(VERSION).executeAsOneOrNull() != context.version
        if (dirty.isEmpty() && !everything) return 0
        // Only the notings read are cleared: a day noted again while its rows were made stays noted and is made again
        // next time.
        fun finish() {
            marks.forEach { queries.clearFeedMark(it.day, it.n) }
            state.setState(VERSION, context.version)
            queries.clearFeedSounds()
            context.sounds.forEach { queries.insertFeedSound(Feed_sound(it.sha256, it.day.toEpochDay(), it.facts())) }
        }
        if (everything) {
            val spans = refreshAll(context)
            database.transaction { finish() }
            return spans
        }
        val windows = windowsFor(dirty.mapTo(HashSet(), LocalDay::fromEpochDay), this, context.grouping, context.sounds, context.sets)
        val built = windows.map { window -> window to build(window, this, context) }
        afterBuild()
        database.transaction {
            built.forEach { (window, sections) ->
                queries.deleteSectionsOfDays(first = window.first.toEpochDay(), last = window.last.toEpochDay())
                sections.forEach { queries.insertSection(it.toRow()) }
            }
            finish()
        }
        return windows.size
    }

    /**
     * Every row made again (a new database, a migration, a large library read in, other settings), a span of about
     * [spanItems] items at a time from the newest day down, each span written as it is made. A span is widened as a
     * window is ([widened]), so its edges are breaks between rows and the rows come out as building the whole library at
     * once would make them; but only a span's items are in memory, never the library's (all at once, 300,000 made-up
     * photos took about 4 minutes on the emulator with the heap near 300 MB). Returns how many spans were made.
     */
    private fun refreshAll(context: FeedContext): Int {
        // Items per day, a recording counting as one, newest day first.
        val weights = HashMap<Long, Long>()
        queries.itemsPerDay().executeAsList().forEach { weights[it.epoch_day] = it.items }
        context.sounds.forEach { val day = it.day.toEpochDay(); weights[day] = (weights[day] ?: 0) + 1 }
        if (weights.isEmpty()) {
            database.transaction { queries.deleteAllSections() }
            return 0
        }
        val days = weights.keys.sortedDescending()
        var next = 0
        // The newest day not made yet.
        var below = days.first()
        var spans = 0
        while (next < days.size) {
            var count = 0L
            val start = next
            while (next < days.size && (next == start || count < spanItems)) count += weights.getValue(days[next++])
            val source = LastItems(this)
            var window = widened(DayWindow(LocalDay.fromEpochDay(days[next - 1]), LocalDay.fromEpochDay(below)), source, context.grouping, context.sounds, context.sets)
            // The span above ends at a break, so this one never reaches into it; if it ever did, the days above are
            // noted, and made again as a window next time.
            val reachedAbove = window.last.toEpochDay() > below
            val above = window.last.toEpochDay()
            if (reachedAbove) window = DayWindow(window.first, LocalDay.fromEpochDay(below))
            val sections = build(window, source, context)
            afterBuild()
            database.transaction {
                queries.deleteSectionsOfDays(first = window.first.toEpochDay(), last = window.last.toEpochDay())
                sections.forEach { queries.insertSection(it.toRow()) }
                if (reachedAbove) (below + 1..above).forEach { queries.markFeedDirty(it) }
            }
            spans++
            below = window.first.toEpochDay() - 1
            while (next < days.size && days[next] > below) next++
        }
        // Rows of days the library no longer has.
        database.transaction { queries.deleteSectionsOutside(first = days.last(), last = days.first()) }
        return spans
    }

    private fun build(window: DayWindow, source: FeedSource, context: FeedContext): List<FeedSection> =
        buildWindow(window, source, context.shown, context.grouping, context.places, context.looks, context.alone, context.sets, emptySet(), context.sounds)

    /** [table] keeping the items it read last: a span is read once more to widen it, and again to make its rows. */
    private class LastItems(private val table: FeedTable) : FeedSource by table {
        private var last: Triple<LocalDay, LocalDay, List<MediaItem>>? = null

        override fun items(first: LocalDay, last: LocalDay): List<MediaItem> {
            this.last?.let { (f, l, items) -> if (f == first && l == last) return items }
            return table.items(first, last).also { this.last = Triple(first, last, it) }
        }
    }

    /**
     * Marks the days of the recordings that came, went or changed since the rows were made (kept in `feed_sound`, so
     * also across starts: the first rows after an update may have been made before the recordings were read), with the
     * days of the rows they were or are in. Asked by the owner on Oct 26, 2026, when calls turned on didn't show.
     */
    private fun markSoundDays(sounds: List<SoundRecording>) {
        val made = queries.feedSounds().executeAsList().associateBy { it.sha256 }
        val now = sounds.associateBy { it.sha256 }
        val days = HashSet<Long>()
        made.values.forEach { old -> if (now[old.sha256]?.let { it.day.toEpochDay() == old.epoch_day && it.facts() == old.facts } != true) days += old.epoch_day }
        now.values.forEach { sound -> made[sound.sha256].let { if (it == null || it.epoch_day != sound.day.toEpochDay() || it.facts != sound.facts()) days += sound.day.toEpochDay() } }
        if (days.isEmpty()) return
        val spans = days.flatMapTo(HashSet()) { day ->
            queries.sectionSpansOfDay(day).executeAsList().flatMap { it.first_day..it.last_day } + day
        }
        database.transaction { spans.forEach { queries.markFeedDirty(it) } }
    }

    /** What the rows show of a recording besides its day: a change makes its rows again. */
    private fun SoundRecording.facts() = "${kind.name}|$startMillis|$durationMillis|$isPrivate|${source.id}"

    /**
     * Up to [limit] rows older than [beforeMillis] (their newest moment), newest first, with their items read by id
     * and laid over by [shown] (scores, kept originals, backup statuses), and their recordings from [sounds].
     */
    fun sections(beforeMillis: Long, limit: Long, sounds: Map<String, SoundRecording>, shown: (LibraryRow) -> MediaItem): List<FeedSection> =
        decode(queries.sectionsBefore(beforeMillis, limit).executeAsList(), sounds, shown)

    /** The rows from the newest down to the one keyed [key], with it; none when there is no such row. */
    fun sectionsDownTo(key: String, sounds: Map<String, SoundRecording>, shown: (LibraryRow) -> MediaItem): List<FeedSection> =
        decode(queries.sectionsDownTo(key).executeAsList(), sounds, shown)

    /** The items of days [first]..[last] as shown ([shown]: scores, kept originals, statuses laid over). */
    fun shownItems(first: LocalDay, last: LocalDay, shown: (LibraryRow) -> MediaItem): List<MediaItem> =
        queries.itemsOfDays(first.toEpochDay(), last.toEpochDay(), ::libraryRow).executeAsList().map(shown)

    /**
     * The key of the newest row with photos of [day]: the row [timeline] gives the day. A row's days reach further than
     * its shown photos (a recording into the next day, photos of a hidden source), so a newer row may take in the day
     * without showing any of it; that one only when no row shows the day.
     */
    fun sectionOf(day: LocalDay): String? {
        val epochDay = day.toEpochDay()
        val rows = queries.sectionsOfDay(epochDay).executeAsList()
        val shown = rows.firstOrNull { row -> row.day_counts.split(",").any { it.substringBefore(":") == epochDay.toString() } }
        return (shown ?: rows.firstOrNull())?.key
    }

    /** The months and days of the whole feed, from each row's photos per day: the timeline, without reading a row. */
    fun timeline(): FeedTimeline {
        val days = LinkedHashMap<LocalDay, TimelineDay>()
        queries.timelineRows().executeAsList().forEach { row ->
            row.day_counts.split(",").filter { it.isNotEmpty() }.forEach { entry ->
                val day = LocalDay.fromEpochDay(entry.substringBefore(":").toLong())
                val count = entry.substringAfter(":").toInt()
                val known = days[day]
                days[day] = known?.copy(count = known.count + count) ?: TimelineDay(day, count, row.key)
            }
        }
        return FeedTimeline.of(days.values)
    }

    private fun decode(rows: List<Feed_section>, sounds: Map<String, SoundRecording>, shown: (LibraryRow) -> MediaItem): List<FeedSection> {
        val data = rows.map { json.decodeFromString(SectionData.serializer(), it.data_) }
        val ids = data.flatMap { it.itemIds() }.distinct()
        val items = ids.chunked(CHUNK).flatMap { queries.itemsById(it, ::libraryRow).executeAsList() }.associate { it.item.id to shown(it) }
        return data.mapNotNull { it.toSection(items, sounds) }
    }

    fun sectionCount(): Long = queries.sectionCount().executeAsOne()

    private companion object {
        /** Noted days past which all rows are made again at once. */
        const val MANY_DAYS = 365

        /** Items per span when every row is made again: a few thousand items in memory at a time. */
        const val SPAN_ITEMS = 4_000

        /** In `feed_dirty`: every day. */
        const val ALL = -1L
        const val VERSION = "feed_version"
        const val CHUNK = 500
        val json = Json { ignoreUnknownKeys = true }
    }

    private fun FeedSection.toRow() = Feed_section(
        key = key,
        first_day = span.oldestDay.toEpochDay(),
        last_day = span.newestDay.toEpochDay(),
        newest_millis = span.endMillis,
        day_counts = items.groupingBy { it.day }.eachCount().entries.sortedByDescending { it.key }
            .joinToString(",") { "${it.key.toEpochDay()}:${it.value}" },
        data_ = json.encodeToString(SectionData.serializer(), SectionData.of(this)),
    )
}

/** A row of Moments by ids, as `feed_section` keeps it: a [FeedSection] without its items. */
@Serializable
internal class SectionData(
    /** The row's items, newest first; a recording's stand-in ([SOUND_PREFIX]) with its time. */
    val span: List<SpanEntry>,
    val byGap: Boolean,
    val moments: List<MomentData>,
    val places: List<String>,
    val hidden: Map<String, Int> = emptyMap(),
    val sounds: List<SoundData> = emptyList(),
) {
    fun itemIds(): List<String> = span.filter { it.at == null }.map { it.id } + moments.flatMap { m -> m.blocks.flatten() }

    /** The row again, from its items ([items], by id) and recordings ([sounds], by sha256); null if any is gone. */
    fun toSection(items: Map<String, MediaItem>, sounds: Map<String, SoundRecording>): FeedSection? {
        val spanItems = span.map { entry ->
            if (entry.at == null) items[entry.id] ?: return null
            else {
                // A stand-in: "sound:<sha256>", or "sound-end:<sha256>/<n>" along a long recording.
                val sha = entry.id.removePrefix(SOUND_PREFIX).removePrefix("sound-end:").substringBefore("/")
                val recording = sounds[sha] ?: return null
                MediaItem(entry.id, MediaType.AUDIO, entry.at, recording.day, recording.durationMillis, 0, 0, null, null, source = recording.source, isOnDevice = false)
            }
        }
        return FeedSection(
            span = Moment(spanItems, if (byGap) GroupingMode.BY_GAP else GroupingMode.BY_DAY),
            moments = moments.map { moment ->
                FeedMoment(
                    source = MediaSource.of(moment.source),
                    blocks = moment.blocks.map { block ->
                        val blockItems = block.map { items[it] ?: return null }
                        if (blockItems.size == 1) FeedBlock.Single(blockItems.first()) else FeedBlock.Similar(blockItems)
                    },
                    place = moment.place,
                    recording = moment.recording?.let { sounds[it] ?: return null },
                )
            },
            places = places,
            hidden = hidden.entries.associate { MediaSource.of(it.key) to it.value },
            sounds = this.sounds.map { sound ->
                if (sound.voice != null) FeedSound.Voice(sounds[sound.voice] ?: return null)
                else FeedSound.Calls(sound.calls.map { sounds[it] ?: return null })
            },
        )
    }

    companion object {
        fun of(section: FeedSection) = SectionData(
            span = section.span.items.map { item ->
                if (item.type == MediaType.AUDIO && !item.isOnDevice && (item.id.startsWith(SOUND_PREFIX) || item.id.startsWith("sound-end:"))) {
                    SpanEntry(item.id, item.takenAtMillis)
                } else {
                    SpanEntry(item.id)
                }
            },
            byGap = section.span.mode == GroupingMode.BY_GAP,
            moments = section.moments.map { moment ->
                MomentData(moment.source.id, moment.blocks.map { block -> block.items.map { it.id } }, moment.place, moment.recording?.sha256)
            },
            places = section.places,
            hidden = section.hidden.mapKeys { it.key.id },
            sounds = section.sounds.map { sound ->
                when (sound) {
                    is FeedSound.Calls -> SoundData(calls = sound.calls.map { it.sha256 })
                    is FeedSound.Voice -> SoundData(voice = sound.recording.sha256)
                }
            },
        )
    }
}

@Serializable
internal class SpanEntry(val id: String, val at: Long? = null)

@Serializable
internal class MomentData(val source: String, val blocks: List<List<String>>, val place: String? = null, val recording: String? = null)

@Serializable
internal class SoundData(val calls: List<String> = emptyList(), val voice: String? = null)
