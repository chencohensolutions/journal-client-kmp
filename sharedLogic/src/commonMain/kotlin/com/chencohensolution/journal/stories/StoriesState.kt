package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.SoundRecording
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.similarity.PhotoLooks

// What StoriesController publishes, and how it is built from the library and the notes.

/** A tag with how many photos carry it. */
data class TagSummary(
    /** As first typed. */
    val name: String,
    val count: Int,
    /** The newest photo with this tag on this device, for a thumbnail. */
    val latest: MediaItem?,
    /** RFC 3339 time it was last added to a photo. */
    val lastUsed: String,
)

data class StoriesState(
    /**
     * The Storyline tab: every photo or video with a title or paragraph that wasn't removed from it, one each (never a
     * set), newest first. It shows whatever source it is from.
     */
    val storyline: List<MediaItem> = emptyList(),
    /** Writing, tags and crops, by device id. */
    val notes: Map<String, PhotoNote> = emptyMap(),
    /** Every tag in use, most recently used first. */
    val tags: List<TagSummary> = emptyList(),
    /** Every photo and video on the device, by id. */
    val itemsById: Map<String, MediaItem> = emptyMap(),
    /** The Moments tab: every photo and video of the sources turned on, split by time, place and looks. */
    val feed: MomentsFeed = MomentsFeed(),
    /** The last save or delete that failed. */
    val error: String? = null,
    /** Where photos were taken, and the places' names. */
    val places: Places = Places.NONE,
    /** What the feed was built with, for [feedOf]. */
    val grouping: TimelineGrouping = TimelineGrouping(),
    val looks: PhotoLooks = PhotoLooks.NONE,
    /** What the app shows of each source; Moments, Explore, All photos and Tags follow it. */
    val shownSources: ShownSources = ShownSources(),
    /** The sound recordings the user shows, by sha256: for a recording's page. */
    val sounds: Map<String, SoundRecording> = emptyMap(),
) {
    // Compared by identity: each rebuild is a new state, and comparing two of a large library field by field took
    // longer than a frame on the main thread (StateFlow and Compose compare every new value with the last).
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()

    /** Whether [item] shows (see [ShownSources]). */
    fun shows(item: MediaItem): Boolean = shownSources.shows(item)

    /** [days] without what [shownSources] hides; days left empty are left out. All photos shows these. */
    fun shownDays(days: List<DayGroup>): List<DayGroup> =
        days.mapNotNull { day -> day.items.filter(::shows).takeIf { it.isNotEmpty() }?.let { DayGroup(day.day, it) } }

    /**
     * A Moments feed of only [items] (newest first), of what [shownSources] shows and without chips: a place's or a
     * year's page in Explore.
     */
    fun feedOf(items: List<MediaItem>): MomentsFeed =
        buildFeed(items, shownSources.copy(sayWhatsHidden = false), grouping, places, looks, alone = aloneIn(notes), sets = setsIn(notes))

    fun note(itemId: String): PhotoNote? = notes[itemId]

    /**
     * The photo a set of [items] opens on: the one the user turned it to last ([PhotoNote.front]), else the
     * best-looking (the server's aesthetic score), else null (the first).
     */
    fun frontOf(items: List<MediaItem>): String? =
        items.firstOrNull { notes[it.id]?.front == true }?.id ?: bestLooking(items)?.id

    /** Photos and videos tagged [tag] (ignoring case) that [shownSources] shows, by day, newest first. */
    fun tagged(tag: String): List<DayGroup> = notes.values
        .filter { note -> note.tags.any { it.equals(tag, ignoreCase = true) } }
        .mapNotNull { itemsById[it.deviceId]?.takeIf(::shows) }
        .sortedByDescending { it.takenAtMillis }
        .groupBy { it.day }
        .map { (day, items) -> DayGroup(day, items) }

    /** Tags whose name matches [query], most used first. */
    fun searchTags(query: String): List<TagSummary> = tags.filter { matches(it.name, query) }.sortedByDescending { it.count }
}

internal fun buildState(
    library: LibraryState,
    notes: Map<String, PhotoNote>,
    error: String?,
    grouping: TimelineGrouping,
    looks: PhotoLooks = PhotoLooks.NONE,
    shown: ShownSources = ShownSources(),
    places: Places = Places.NONE,
    revealed: Set<FeedReveal> = emptySet(),
): StoriesState {
    val itemsById = library.days.flatMap { it.items }.associateBy { it.id }
    // A tag counts the photos it shows; those not on this device yet count too.
    val tags = notes.values
        .filter { note -> itemsById[note.deviceId]?.let(shown::shows) != false }
        .flatMap { note -> note.tags.map { tag -> tag to note } }
        .groupBy { (tag, _) -> tag.lowercase() }
        .map { (_, uses) ->
            val tagged = uses.map { it.second }
            TagSummary(
                name = uses.minBy { it.second.updatedAt }.first,
                count = tagged.size,
                latest = tagged.mapNotNull { itemsById[it.deviceId] }.maxByOrNull { it.takenAtMillis },
                lastUsed = tagged.maxOf { it.updatedAt },
            )
        }
        .sortedByDescending { it.lastUsed }
    return StoriesState(
        storyline = notes.values.filter { it.onStoryline }.mapNotNull { itemsById[it.deviceId] }.sortedByDescending { it.takenAtMillis },
        places = places,
        grouping = grouping,
        looks = looks,
        shownSources = shown,
        notes = notes,
        tags = tags,
        itemsById = itemsById,
        feed = buildFeed(
            library.days.flatMap { it.items }, shown, grouping, places, looks,
            alone = aloneIn(notes),
            sets = setsIn(notes),
            revealed = revealed,
            sounds = library.sounds,
        ),
        sounds = library.sounds.associateBy { it.sha256 },
        error = error,
    )
}

/** A cropped photo is shown on its own, out of its near-duplicate set, and so is one taken out of a set. */
private fun aloneIn(notes: Map<String, PhotoNote>): Set<String> =
    notes.values.filter { !it.crops.isEmpty || it.apart }.map { it.deviceId }.toSet()

/** Photos merged by hand in the feed: photo id to set id. */
private fun setsIn(notes: Map<String, PhotoNote>): Map<String, String> =
    notes.values.mapNotNull { note -> note.feedSet?.let { note.deviceId to it } }.toMap()
