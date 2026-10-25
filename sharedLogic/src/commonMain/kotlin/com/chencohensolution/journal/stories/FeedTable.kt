package com.chencohensolution.journal.stories

import com.chencohensolution.journal.db.Feed_section
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
 * ([windowsFor], [buildWindow]) where `library_item` changed, as its triggers note in `feed_dirty`, and read a page at a
 * time ([sections]). A row keeps its structure by ids ([SectionData]); its items are read from `library_item` when it is
 * read. Blocking: call off the main thread.
 */
internal class FeedTable(private val database: LibraryDatabase) : FeedSource {
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

    /**
     * Makes the rows of the days noted since the last refresh again; all of them when everything is noted or
     * [context] differs from what they were made with. Returns how many windows were made.
     */
    fun refresh(context: FeedContext): Int {
        val dirty = queries.dirtyDays().executeAsList()
        val everything = ALL in dirty || state.stateValue(VERSION).executeAsOneOrNull() != context.version
        if (dirty.isEmpty() && !everything) return 0
        val windows = if (everything) {
            // Every day with an item or a recording.
            val days = queries.libraryDays().executeAsOne()
            val all = listOfNotNull(days.first, days.last).map(LocalDay::fromEpochDay) + context.sounds.map { it.day }
            if (all.isEmpty()) emptyList() else listOf(DayWindow(all.min(), all.max()))
        } else {
            windowsFor(dirty.mapTo(HashSet(), LocalDay::fromEpochDay), this, context.grouping, context.sounds, context.sets)
        }
        val built = windows.map { window ->
            window to buildWindow(window, this, context.shown, context.grouping, context.places, context.looks, context.alone, context.sets, emptySet(), context.sounds)
        }
        database.transaction {
            if (everything) queries.deleteAllSections()
            built.forEach { (window, sections) ->
                if (!everything) queries.deleteSectionsOfDays(first = window.first.toEpochDay(), last = window.last.toEpochDay())
                sections.forEach { queries.insertSection(it.toRow()) }
            }
            if (everything) queries.clearAllFeedDirty() else dirty.chunked(CHUNK).forEach { queries.clearFeedDirty(it) }
            state.setState(VERSION, context.version)
        }
        return windows.size
    }

    /**
     * Up to [limit] rows older than [beforeMillis] (their newest moment), newest first, with their items read by id
     * and laid over by [shown] (scores, kept originals, backup statuses), and their recordings from [sounds].
     */
    fun sections(beforeMillis: Long, limit: Long, sounds: Map<String, SoundRecording>, shown: (LibraryRow) -> MediaItem): List<FeedSection> {
        val rows = queries.sectionsBefore(beforeMillis, limit).executeAsList()
        val data = rows.map { json.decodeFromString(SectionData.serializer(), it.data_) }
        val ids = data.flatMap { it.itemIds() }.distinct()
        val items = ids.chunked(CHUNK).flatMap { queries.itemsById(it, ::libraryRow).executeAsList() }.associate { it.item.id to shown(it) }
        return data.mapNotNull { it.toSection(items, sounds) }
    }

    fun sectionCount(): Long = queries.sectionCount().executeAsOne()

    private companion object {
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
                    source = MediaSource.parse(moment.source) ?: MediaSource.OTHER,
                    blocks = moment.blocks.map { block ->
                        val blockItems = block.map { items[it] ?: return null }
                        if (blockItems.size == 1) FeedBlock.Single(blockItems.first()) else FeedBlock.Similar(blockItems)
                    },
                    place = moment.place,
                    recording = moment.recording?.let { sounds[it] ?: return null },
                )
            },
            places = places,
            hidden = hidden.entries.associate { (MediaSource.parse(it.key) ?: MediaSource.OTHER) to it.value },
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
