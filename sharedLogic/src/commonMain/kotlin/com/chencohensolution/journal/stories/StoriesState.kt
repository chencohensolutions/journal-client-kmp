package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.similarity.PhotoLooks
import kotlinx.coroutines.flow.StateFlow

// What StoriesController publishes, and how it is built from the library, the stories and the notes.

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

/** Stories to offer when adding photos to one, before anything is typed. Each story appears once. */
data class StorySuggestions(
    /** Stories that already hold photos from the same days. */
    val onTheseDays: List<StoryView>,
    /** Stories with photos within [NEARBY_DAYS] days. */
    val nearby: List<StoryView>,
    /** The stories changed last. */
    val recent: List<StoryView>,
) {
    companion object {
        const val NEARBY_DAYS = 7L
        const val MAX_NEARBY = 5
        const val MAX_RECENT = 3
    }
}

data class StoriesState(
    /** Newest first, by [StoryView.timelineDay]. */
    val stories: List<StoryView> = emptyList(),
    /** Stories and unsorted moments, newest first; a story sits at its newest photo's day. */
    val timeline: List<TimelineEntry> = emptyList(),
    /** Photos and videos in no story and without tags, by day, newest first. */
    val unsortedDays: List<DayGroup> = emptyList(),
    /** The same photos as [unsortedDays], split as the grouping setting says; what the journal shows. */
    val moments: List<Moment> = emptyList(),
    /** Captions and tags, by device id. */
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
    /** What the app shows of each source; Moments, Explore, All photos, Tags and the days offered to a story follow it. */
    val shownSources: ShownSources = ShownSources(),
) {
    // Compared by identity: each rebuild is a new state, and comparing two of a large library field by field took
    // longer than a frame on the main thread (StateFlow and Compose compare every new value with the last).
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()

    val unsortedCount: Int = unsortedDays.sumOf { it.items.size }

    fun story(id: String): StoryView? = stories.firstOrNull { it.id == id }

    /** Whether [item] shows outside the stories it is in (see [ShownSources]). */
    fun shows(item: MediaItem): Boolean = shownSources.shows(item)

    /** [days] without what [shownSources] hides; days left empty are left out. All photos shows these. */
    fun shownDays(days: List<DayGroup>): List<DayGroup> =
        days.mapNotNull { day -> day.items.filter(::shows).takeIf { it.isNotEmpty() }?.let { DayGroup(day.day, it) } }

    /**
     * A Moments feed of only [items] (newest first), of what [shownSources] shows and without trips or chips: a
     * place's or a year's page in Explore.
     */
    fun feedOf(items: List<MediaItem>): MomentsFeed {
        val built = buildFeed(items, shownSources.copy(sayWhatsHidden = false), grouping, places, looks, alone = aloneIn(notes), sets = setsIn(notes))
        return built.copy(entries = built.entries.filterIsInstance<FeedEntry.Section>())
    }

    fun moment(key: String): Moment? = moments.firstOrNull { it.key == key }

    fun note(itemId: String): PhotoNote? = notes[itemId]

    /** Photos and videos tagged [tag] (ignoring case) that [shownSources] shows, by day, newest first. */
    fun tagged(tag: String): List<DayGroup> = notes.values
        .filter { note -> note.tags.any { it.equals(tag, ignoreCase = true) } }
        .mapNotNull { itemsById[it.deviceId]?.takeIf(::shows) }
        .sortedByDescending { it.takenAtMillis }
        .groupBy { it.day }
        .map { (day, items) -> DayGroup(day, items) }

    /** For the "Add to story" sheet before anything is typed. */
    fun storySuggestions(itemIds: List<String>): StorySuggestions {
        val days = itemIds.mapNotNull { itemsById[it]?.day?.toEpochDay() }.toSet()
        fun distance(story: StoryView): Long =
            story.items.minOfOrNull { item -> days.minOfOrNull { kotlin.math.abs(it - item.day.toEpochDay()) } ?: Long.MAX_VALUE } ?: Long.MAX_VALUE
        val onTheseDays = stories.filter { distance(it) == 0L }
        val nearby = stories.filter { it !in onTheseDays && distance(it) <= StorySuggestions.NEARBY_DAYS }
            .sortedBy(::distance).take(StorySuggestions.MAX_NEARBY)
        val recent = (stories - onTheseDays.toSet() - nearby.toSet())
            .sortedByDescending { it.story.updatedAt }.take(StorySuggestions.MAX_RECENT)
        return StorySuggestions(onTheseDays, nearby, recent)
    }

    /** Stories whose title matches [query] (see [matches]), the closest in time to [itemIds] first. */
    fun searchStories(query: String, itemIds: List<String>): List<StoryView> {
        val days = itemIds.mapNotNull { itemsById[it]?.day?.toEpochDay() }
        return stories.filter { matches(it.title, query) }.sortedBy { story ->
            story.items.minOfOrNull { item -> days.minOfOrNull { kotlin.math.abs(it - item.day.toEpochDay()) } ?: 0 } ?: Long.MAX_VALUE
        }
    }

    /** Tags whose name matches [query], most used first. */
    fun searchTags(query: String): List<TagSummary> = tags.filter { matches(it.name, query) }.sortedByDescending { it.count }

    /** Stories [itemId] belongs to. */
    fun storiesContaining(itemId: String): List<StoryView> =
        stories.filter { story -> story.story.members.any { it.deviceId == itemId } }

    /**
     * Unsorted days up to [NEIGHBOR_DAYS] before or after a trip-like story ([StoryView.isShortSpan]): likely part of
     * the same trip. Stories that span months get none. Only what [shownSources] shows is offered.
     */
    fun neighborDays(storyId: String): List<DayGroup> {
        val story = story(storyId)?.takeIf { it.isShortSpan } ?: return emptyList()
        val first = story.firstDay!!.toEpochDay()
        val last = story.lastDay!!.toEpochDay()
        return shownDays(unsortedDays).filter { day ->
            val epochDay = day.day.toEpochDay()
            epochDay in (first - NEIGHBOR_DAYS) until first || epochDay in (last + 1)..(last + NEIGHBOR_DAYS)
        }.sortedBy { it.day }
    }

    companion object {
        const val NEIGHBOR_DAYS = 2L
    }
}

/**
 * The photos to fingerprint for suggestions: every photo of every story, the story changed last first (a story or
 * group just made is read first), oldest photo first within it.
 */
internal fun photosToCompare(stories: List<StoryView>): List<MediaItem> = stories
    .sortedByDescending { it.story.updatedAt }
    .flatMap { story -> story.items.filter { it.type == MediaType.IMAGE } }
    .distinctBy { it.id }

internal fun buildState(
    library: LibraryState,
    stories: List<Story>,
    notes: Map<String, PhotoNote>,
    error: String?,
    grouping: TimelineGrouping,
    looks: PhotoLooks = PhotoLooks.NONE,
    shown: ShownSources = ShownSources(),
    dismissedTrips: Set<String> = emptySet(),
    places: Places = Places.NONE,
    revealed: Set<FeedReveal> = emptySet(),
): StoriesState {
    val itemsById = library.days.flatMap { it.items }.associateBy { it.id }
    val views = stories
        .map { story ->
            val items = story.members.mapNotNull { itemsById[it.deviceId] }.sortedBy { it.takenAtMillis }
            StoryView(story, items, looks.only(items.map { it.id }))
        }
        .sortedWith(compareByDescending<StoryView> { it.timelineDay }.thenByDescending { it.story.createdAt })
    // A photo in a story, or with a tag, has a place already; only the rest is unsorted.
    val sorted = stories.flatMap { story -> story.members.map { it.deviceId } }.toSet() +
        notes.values.filter { it.tags.isNotEmpty() }.map { it.deviceId }
    val unsorted = library.days.mapNotNull { day ->
        day.items.filter { it.id !in sorted }.takeIf { it.isNotEmpty() }?.let { DayGroup(day.day, it) }
    }
    val moments = groupMoments(unsorted.flatMap { it.items }, grouping)
    // Newest first; on the same day the story comes before the unsorted photos. The sort is stable, so moments of
    // one day keep their order.
    val timeline = (views.map { it.timelineDay to TimelineEntry.StoryEntry(it) } + moments.map { it.newestDay to TimelineEntry.MomentEntry(it) })
        .sortedWith(compareByDescending<Pair<LocalDay, TimelineEntry>> { it.first }.thenBy { if (it.second is TimelineEntry.StoryEntry) 0 else 1 })
        .map { it.second }
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
        places = places,
        grouping = grouping,
        looks = looks,
        shownSources = shown,
        stories = views,
        timeline = timeline,
        unsortedDays = unsorted,
        moments = moments,
        notes = notes,
        tags = tags,
        itemsById = itemsById,
        feed = buildFeed(
            library.days.flatMap { it.items }, shown, grouping, places, looks, dismissedTrips, sorted,
            alone = aloneIn(notes),
            sets = setsIn(notes),
            revealed = revealed,
        ),
        error = error,
    )
}

/** A cropped photo is shown on its own, out of its near-duplicate set, and so is one taken out of a set. */
private fun aloneIn(notes: Map<String, PhotoNote>): Set<String> =
    notes.values.filter { !it.crops.isEmpty || it.apart }.map { it.deviceId }.toSet()

/** Photos merged by hand in the feed: photo id to set id. */
private fun setsIn(notes: Map<String, PhotoNote>): Map<String, String> =
    notes.values.mapNotNull { note -> note.feedSet?.let { note.deviceId to it } }.toMap()
