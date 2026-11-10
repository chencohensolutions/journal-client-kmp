package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
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
    /**
     * The storyline by day as drawn: a day with tucked-away photos says how many, and shows them while revealed
     * ([StoriesController.tucked]). [storyline] is what shows of it.
     */
    val storylineDays: List<StorylineDay> = emptyList(),
    /** Writing, tags and crops, by device id. */
    val notes: Map<String, PhotoNote> = emptyMap(),
    /** Every tag in use, most recently used first. */
    val tags: List<TagSummary> = emptyList(),
    /**
     * The photos and videos the screens use, by id. With Moments from the database ([whole] false): the photos with
     * notes, Moments' rows read so far and what screens asked for ([StoriesController.want]), read from the database
     * (docs/plans/library-on-disk.md, step 6a); else every photo and video of the library.
     */
    val itemsById: Map<String, MediaItem> = emptyMap(),
    /** Whether [itemsById] holds the whole library, so a photo not in it is gone. */
    val whole: Boolean = true,
    /** Photos screens asked for ([StoriesController.want]) that the library doesn't have: deleted, or hidden since. */
    val missing: Set<String> = emptySet(),
    /** The Moments tab: every photo and video of the sources turned on, split by time, place and looks. */
    val feed: MomentsFeed = MomentsFeed(),
    /** The last save or delete that failed. */
    val error: String? = null,
    /** Where photos were taken, and the places' names. */
    val places: Places = Places.NONE,
    /** What the feed was built with, for [feedOf]. */
    val grouping: TimelineGrouping = TimelineGrouping(),
    val looks: PhotoLooks = PhotoLooks.NONE,
    /** What the app shows of each source; Moments, Explore and Tags follow it. */
    val shownSources: ShownSources = ShownSources(),
    /** The sound recordings the user shows, by sha256: for a recording's page. */
    val sounds: Map<String, SoundRecording> = emptyMap(),
    /**
     * True once it is built from the whole library and the places and fingerprints this phone keeps: Moments shows a
     * spinner until then, so it draws once (asked by the owner, Oct 25, 2026).
     */
    val ready: Boolean = false,
    /** Explore's home from the library database; null without one (Explore works it out from the items, see [placesOf]). */
    val home: ExploreHome? = null,
) {
    // Compared by identity: each rebuild is a new state, and comparing two of a large library field by field took
    // longer than a frame on the main thread (StateFlow and Compose compare every new value with the last).
    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = super.hashCode()

    /** Whether [item] shows (see [ShownSources]). */
    fun shows(item: MediaItem): Boolean = shownSources.shows(item)

    /**
     * A Moments feed of only [items] (newest first), of what [shownSources] shows and without chips: a place's or a
     * year's page in Explore.
     */
    fun feedOf(items: List<MediaItem>): MomentsFeed =
        // Explore leaves out what is tucked away, with no pill.
        buildFeed(items.filterNot { it.isTucked }, shownSources.copy(sayWhatsHidden = false), grouping, places, looks, alone = aloneIn(notes), sets = setsIn(notes), placements = placementsIn(notes))
            .withSets(notes)

    fun note(itemId: String): PhotoNote? = notes[itemId]

    /**
     * Whether the photo [itemId] is known to be gone from the library (deleted, hidden): a screen showing it drops it.
     * Not when it is only not read yet ([itemsById] holds part of the library): the screen keeps what it had.
     */
    fun gone(itemId: String): Boolean = itemId !in itemsById && (whole || itemId in missing)

    /**
     * The cover of a set of [items], where it opens and what shows of it with no highlights: its first highlight
     * ([PhotoNote.highlight]), else the best-looking (the server's aesthetic score), else null (the first).
     */
    fun frontOf(items: List<MediaItem>): String? = chosenCoverOf(items) ?: highlightsOf(items).firstOrNull()?.id ?: bestLooking(items)?.id

    /** The cover chosen for the moment [items] are (Make cover, Nov 10, 2026), if one is and it is among them. */
    private fun chosenCoverOf(items: List<MediaItem>): String? {
        val info = items.firstNotNullOfOrNull { notes[it.id]?.setInfo } ?: return null
        return info.cover?.takeIf { cover -> items.any { it.id == cover } }
    }

    /** Of [items] (a set), its highlights in their order. */
    fun highlightsOf(items: List<MediaItem>): List<MediaItem> = highlightsOf(items, notes)

    /** The moment made by hand that [itemId] is in, if any ([StoriesController.makeMoment]). */
    fun namedSetOf(itemId: String): NamedSet? = notes[itemId]?.feedSet?.let { namedSets[it] }

    /** The moments made by hand, worked out once per state: the storyline asks for each of its photos. */
    private val namedSets: Map<String, NamedSet> by lazy { namedSetsIn(notes) }

    /**
     * The moment made by hand whose cover [itemId] is: in the storyline it stands for the moment (its name and
     * description, opening the whole set). Null for any other photo.
     */
    fun storylineMomentOf(itemId: String): NamedSet? = namedSetOf(itemId)?.takeIf { it.coverId == itemId }

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
    /** The days whose tucked-away photos show ([TuckedSession.days]). */
    tuckedShown: Set<LocalDay> = emptySet(),
    ready: Boolean = true,
    /** Moments read from the database a page at a time (FeedPager): used as it is, the whole feed isn't built. */
    paged: MomentsFeed? = null,
    /**
     * With [paged], the items the screens use besides its rows' (the notes' photos, what screens asked for), read from
     * the database; the library's whole list isn't used for them.
     */
    items: Map<String, MediaItem>? = null,
    missing: Set<String> = emptySet(),
    home: ExploreHome? = null,
): StoriesState {
    val itemsById = if (paged != null && items != null) paged.itemsById() + items else library.days.flatMap { it.items }.associateBy { it.id }
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
    val storylineDays = storylineDays(
        // Every moment made by hand is there too, by its cover (asked Nov 10, 2026), unless removed from it.
        // Its cover is the one chosen (Make cover) or the photo that keeps its name; Remove from storyline on either
        // takes it out.
        run {
            val named = namedSetsIn(notes)
            val moments = notes.values.filter { it.setInfo != null && it.inStoryline }
                .mapNotNull { holder -> named[holder.feedSet]?.coverId }
                .filter { notes[it]?.inStoryline != false }
            (notes.values.filter { it.onStoryline }.map { it.deviceId } + moments)
                .distinct().mapNotNull { itemsById[it] }
                .sortedByDescending { it.takenAtMillis }
        },
        TuckedSession(days = tuckedShown),
    )
    return StoriesState(
        storyline = storylineDays.flatMap { it.items },
        storylineDays = storylineDays,
        places = places,
        grouping = grouping,
        looks = looks,
        shownSources = shown,
        notes = notes,
        tags = tags,
        itemsById = itemsById,
        whole = paged == null || items == null,
        missing = missing,
        // Empty until ready, so Moments never draws a feed that is about to change.
        feed = (paged ?: if (!ready) MomentsFeed() else buildFeed(
            library.days.flatMap { it.items }, shown, grouping, places, looks,
            alone = aloneIn(notes),
            sets = setsIn(notes),
            revealed = revealed,
            sounds = library.sounds,
            tuckedShown = tuckedShown,
            placements = placementsIn(notes),
        )).withSets(notes),
        sounds = library.sounds.associateBy { it.sha256 },
        ready = ready,
        home = home,
        error = error,
    )
}

/** The photos and videos of the rows read, also those of hidden sources shown on a row, by id. */
internal fun MomentsFeed.itemsById(): Map<String, MediaItem> {
    val items = HashMap<String, MediaItem>()
    entries.forEach { entry ->
        val section = (entry as? FeedEntry.Section)?.section ?: return@forEach
        section.moments.forEach { moment -> moment.items.forEach { items[it.id] = it } }
        section.revealed.forEach { band -> band.items.forEach { items[it.id] = it } }
    }
    return items
}

/** A cropped photo is shown on its own, out of its near-duplicate set, and so is one taken out of a set. */
internal fun aloneIn(notes: Map<String, PhotoNote>): Set<String> =
    notes.values.filter { !it.crops.isEmpty || it.apart }.map { it.deviceId }.toSet()

/** Photos merged by hand in the feed: photo id to set id. */
internal fun setsIn(notes: Map<String, PhotoNote>): Map<String, String> =
    notes.values.mapNotNull { note -> note.feedSet?.let { note.deviceId to it } }.toMap()
