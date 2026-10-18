package com.chencohensolution.journal.stories

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.similarity.PhotoLooks
import com.chencohensolution.journal.similarity.PhotoSimilarity
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** A group with its photos as they are on this device. */
data class GroupView(
    val group: StoryGroup,
    /** Its photos and its subgroups' photos, oldest first. */
    val items: List<MediaItem>,
    /** What its page shows: its own photos, and each subgroup as one tile, oldest first. */
    val tiles: List<StoryTile> = items.map { StoryTile.Media(it) },
    /** Fingerprints of its own photos, for [suggestions]. */
    val looks: PhotoLooks = PhotoLooks.NONE,
    /** The story's [Story.dismissedSuggestions]. */
    val dismissed: Set<String> = emptySet(),
) {
    val id: String get() = group.id
    val title: String? get() = group.title
    val text: String get() = group.text
    val itemCount: Int get() = items.size

    /** The group this one is a tile of; null when it is a tile of the story. */
    val parentId: String? get() = group.parentId

    /** The photo on the group's tile. */
    val cover: MediaItem = items.firstOrNull { it.id == group.coverId } ?: items.first()

    /** What the viewer swipes through from the group's page: its photos, and each subgroup as its cover. */
    val viewerItems: List<MediaItem> get() = tiles.map { it.firstItem(cover = true) }

    /** The subgroup shown as [itemId] in [viewerItems], if that photo stands for one. */
    fun coverStop(itemId: String): GroupView? = tiles.coverStop(itemId)

    /**
     * Its own photos that look alike (or were taken in a burst), to become subgroups; never all of its tiles, which
     * would only wrap the group again. Worked out when first read.
     */
    val suggestions: List<BurstSuggestion> by lazy {
        val own = tiles.filterIsInstance<StoryTile.Media>().map { it.item }
        findSuggestions(own, dismissed, looks).filter { it.count < tiles.size }
    }
}

private fun List<StoryTile>.coverStop(itemId: String): GroupView? =
    filterIsInstance<StoryTile.Group>().firstOrNull { it.group.cover.id == itemId }?.group

/** What a story shows in its grid: a photo or video, or a group as one tile. */
sealed interface StoryTile {
    /** Names the tile in the story's layout: the photo's device id, or [groupTileKey]. */
    val key: String

    data class Media(val item: MediaItem) : StoryTile {
        override val key: String get() = item.id
    }

    data class Group(val group: GroupView) : StoryTile {
        override val key: String get() = groupTileKey(group.id)
    }
}

/** One day of a story's grid, packed into [bands]. A group sits on the day of its first photo. */
data class StoryDay(val day: LocalDay, val tiles: List<StoryTile>, val bands: List<StoryBand>)

/**
 * Photos that could become a group: ones that look alike, taken at any time ([byLooks]), or, for photos not read
 * yet, ones taken in quick succession.
 */
data class BurstSuggestion(val items: List<MediaItem>, val byLooks: Boolean = false) {
    /** The first photo's id; remembered when the suggestion is dismissed. */
    val key: String get() = items.first().id
    val count: Int get() = items.size
    val startMillis: Long get() = items.first().takenAtMillis
    val seconds: Long get() = (items.last().takenAtMillis - items.first().takenAtMillis) / 1000
    val sameDay: Boolean get() = items.first().day == items.last().day
}

/** A story with its photos and videos as they are on this device. */
data class StoryView(
    val story: Story,
    /** Members found on this device, oldest first. */
    val items: List<MediaItem>,
    /** Fingerprints of its photos worked out so far; they make [suggestions] find photos that look alike. */
    val looks: PhotoLooks = PhotoLooks.NONE,
) {
    val id: String get() = story.id
    /** "" when the story has no title. */
    val title: String get() = story.title
    val hasTitle: Boolean get() = story.title.isNotBlank()
    val intro: String get() = story.intro

    /** Day of its oldest photo on this device; null when none is. */
    val firstDay: LocalDay? = items.firstOrNull()?.day

    /** Day of its newest photo on this device; null when none is. */
    val lastDay: LocalDay? = items.lastOrNull()?.day

    /** Days from the first photo to the last; 0 without photos. */
    val dayCount: Int = if (firstDay != null && lastDay != null) (lastDay.toEpochDay() - firstDay.toEpochDay() + 1).toInt() else 0

    /** Where the story sits in the journal: its newest photo's day, or the day it was created. */
    val timelineDay: LocalDay = lastDay ?: LocalDay.parse(story.createdAt.take(10)) ?: LocalDay(1970, 1, 1)

    /** Photos days apart rather than months, like a trip: then neighboring days are worth suggesting. */
    val isShortSpan: Boolean = dayCount in 1..SHORT_SPAN_DAYS

    val itemCount: Int get() = items.size
    val notBackedUpCount: Int = items.count { it.backup != BackupStatus.BackedUp }

    /** Members that are no longer on this device (deleted, or not visible with limited access). */
    val missingCount: Int = story.members.size - items.size

    /** The story's own groups (not nested in another) with at least one photo on this device. */
    private val rootGroups: List<GroupView>

    /** Groups with at least one photo on this device, nested ones included: each followed by its subgroups. */
    val groups: List<GroupView>

    private val dismissed = story.dismissedSuggestions.toSet()

    init {
        val byId = items.associateBy { it.id }
        val known = story.groups.map { it.id }.toSet()
        val childrenOf = story.groups.groupBy { it.parentId }
        val visited = mutableSetOf<String>()
        val all = mutableListOf<GroupView?>()
        fun view(group: StoryGroup): GroupView? {
            if (!visited.add(group.id)) return null
            val slot = all.size
            all += null
            val own = group.memberIds.mapNotNull { byId[it] }.map { StoryTile.Media(it) }
            val subgroups = childrenOf[group.id].orEmpty().mapNotNull(::view).map { StoryTile.Group(it) }
            val tiles = (own + subgroups).sortedBy { it.firstItem().takenAtMillis }
            if (tiles.isEmpty()) return null
            val items = tiles.flatMap { it.allItems() }.sortedBy { it.takenAtMillis }
            return GroupView(group, items, tiles, looks.only(own.map { it.item.id }), dismissed).also { all[slot] = it }
        }
        rootGroups = story.groups.filter { it.parentId == null || it.parentId !in known }.mapNotNull(::view)
        groups = all.filterNotNull()
    }

    /**
     * The grid: loose photos, and each of the story's own groups where its first photo would be, oldest first unless
     * the owner arranged them ([Story.tileOrder]).
     */
    val tiles: List<StoryTile> = run {
        val grouped = story.groups.flatMap { it.memberIds }.toSet()
        val byTime = items.filter { it.id !in grouped }.map { StoryTile.Media(it) } + rootGroups.map { StoryTile.Group(it) }
        arrangeTiles(byTime, story.tileOrder, key = { it.key }, time = { it.firstItem().takenAtMillis })
    }

    /** [tiles] by day, oldest day first: a story reads like a story. Within a day, in the story's order. */
    val tileDays: List<StoryDay> = tiles
        .groupBy { tile -> tile.firstItem().day }
        .toList()
        .sortedBy { (day, _) -> day }
        .map { (day, dayTiles) -> StoryDay(day, dayTiles, packDay(dayTiles, story.layout)) }

    /** What the viewer swipes through: loose photos, and each group as its cover. */
    val viewerItems: List<MediaItem> = tiles.map { it.firstItem(cover = true) }

    /** The loose photos and videos: the story's own, in no group. */
    val looseItems: List<MediaItem> get() = tiles.filterIsInstance<StoryTile.Media>().map { it.item }

    /** Photos that look alike (or bursts) among the loose photos, except dismissed ones. Worked out when first read. */
    val suggestions: List<BurstSuggestion> by lazy { findSuggestions(looseItems, dismissed, looks) }

    fun group(id: String): GroupView? = groups.firstOrNull { it.id == id }

    /** The group a page of the story shows as [itemId] in [viewerItems], if that photo stands for one. */
    fun coverStop(itemId: String): GroupView? = tiles.coverStop(itemId)

    /** The groups around [groupId], the story's own first; for the group page's heading. */
    fun parentsOf(groupId: String): List<GroupView> {
        val parents = mutableListOf<GroupView>()
        var parent = group(groupId)?.parentId?.let(::group)
        while (parent != null && parent !in parents) {
            parents.add(0, parent)
            parent = parent.parentId?.let(::group)
        }
        return parents
    }
}

private fun StoryTile.firstItem(cover: Boolean = false): MediaItem = when (this) {
    is StoryTile.Media -> item
    is StoryTile.Group -> if (cover) group.cover else group.items.first()
}

private fun StoryTile.allItems(): List<MediaItem> = when (this) {
    is StoryTile.Media -> listOf(item)
    is StoryTile.Group -> group.items
}

/** A story whose photos span at most this many days is trip-like; see [StoryView.isShortSpan]. */
internal const val SHORT_SPAN_DAYS = 14

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

/** A new burst starts after a gap longer than this between two photos. */
internal const val BURST_GAP_MILLIS = 10_000L

/** Fewer photos than this taken in quick succession aren't worth a suggestion; two that look alike are. */
internal const val BURST_MIN_PHOTOS = 3

/**
 * What to suggest grouping among [items] (oldest first). Photos with a fingerprint go by looks, whenever they were
 * taken: each joins the first set where it looks like ([PhotoLooks.similar], or the same camera burst) more than half
 * of the photos (so sets don't drift through chains of look-alikes), and sets of two or more are suggested. Photos not read yet go by time ([findBursts]). Videos and
 * sets holding a dismissed photo are left out. Oldest first.
 */
internal fun findSuggestions(items: List<MediaItem>, dismissed: Set<String>, looks: PhotoLooks = PhotoLooks.NONE): List<BurstSuggestion> {
    val (read, unread) = items.filter { it.type == MediaType.IMAGE }.partition(looks::canCompare)
    val sets = mutableListOf<MutableList<MediaItem>>()
    read.forEach { photo ->
        val set = sets.firstOrNull { set -> set.count { looks.similar(it, photo) || sameBurst(it, photo) } * 2 > set.size }
        if (set != null) set += photo else sets += mutableListOf(photo)
    }
    val byLooks = sets.filter { it.size >= 2 && it.none { photo -> photo.id in dismissed } }.map { BurstSuggestion(it, byLooks = true) }
    return (byLooks + findBursts(unread, dismissed)).sortedBy { it.startMillis }
}

private fun sameBurst(a: MediaItem, b: MediaItem) = a.burstId != null && a.burstId == b.burstId

/**
 * Splits [items] (oldest first) into runs of photos taken within [BURST_GAP_MILLIS] of each other, or sharing the
 * camera's burst id, and suggests the runs of at least [BURST_MIN_PHOTOS]. Videos and runs holding a dismissed photo
 * are left out.
 */
internal fun findBursts(items: List<MediaItem>, dismissed: Set<String>): List<BurstSuggestion> {
    val runs = mutableListOf<MutableList<MediaItem>>()
    items.filter { it.type == MediaType.IMAGE }.forEach { item ->
        val previous = runs.lastOrNull()?.last()
        if (previous != null && (item.takenAtMillis - previous.takenAtMillis <= BURST_GAP_MILLIS || sameBurst(previous, item))) {
            runs.last() += item
        } else {
            runs += mutableListOf(item)
        }
    }
    return runs.filter { run -> run.size >= BURST_MIN_PHOTOS && run.none { it.id in dismissed } }.map(::BurstSuggestion)
}

/**
 * Photos and videos in no story, shown as one row of the journal, newest first: one day's photos, or with
 * [GroupingMode.BY_GAP] a run of photos without a long gap between them, which can cross midnight.
 */
data class Moment(val items: List<MediaItem>, val mode: GroupingMode) {
    /** Its oldest item's id: stays the same when newer photos join it. */
    val key: String get() = items.last().id
    val newestDay: LocalDay get() = items.first().day
    val oldestDay: LocalDay get() = items.last().day
    val startMillis: Long get() = items.last().takenAtMillis
    val endMillis: Long get() = items.first().takenAtMillis
}

/**
 * Splits [items] (newest first) into moments: by day, or wherever more than [TimelineGrouping.gapHours] pass
 * without a photo.
 */
internal fun groupMoments(items: List<MediaItem>, grouping: TimelineGrouping): List<Moment> = when (grouping.mode) {
    GroupingMode.BY_DAY -> items.groupBy { it.day }.values.map { Moment(it, grouping.mode) }
    GroupingMode.BY_GAP -> {
        val runs = mutableListOf<MutableList<MediaItem>>()
        items.forEach { item ->
            val newer = runs.lastOrNull()?.last()
            if (newer != null && newer.takenAtMillis - item.takenAtMillis <= grouping.gapMillis) runs.last() += item else runs += mutableListOf(item)
        }
        runs.map { Moment(it, grouping.mode) }
    }
}

/** One row of the journal timeline. */
sealed interface TimelineEntry {
    data class StoryEntry(val story: StoryView) : TimelineEntry
    data class MomentEntry(val moment: Moment) : TimelineEntry
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
    /** The last save or delete that failed. */
    val error: String? = null,
) {
    val unsortedCount: Int = unsortedDays.sumOf { it.items.size }

    fun story(id: String): StoryView? = stories.firstOrNull { it.id == id }

    fun moment(key: String): Moment? = moments.firstOrNull { it.key == key }

    fun note(itemId: String): PhotoNote? = notes[itemId]

    /** Photos and videos tagged [tag] (ignoring case), by day, newest first. */
    fun tagged(tag: String): List<DayGroup> = notes.values
        .filter { note -> note.tags.any { it.equals(tag, ignoreCase = true) } }
        .mapNotNull { itemsById[it.deviceId] }
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
     * the same trip. Stories that span months get none.
     */
    fun neighborDays(storyId: String): List<DayGroup> {
        val story = story(storyId)?.takeIf { it.isShortSpan } ?: return emptyList()
        val first = story.firstDay!!.toEpochDay()
        val last = story.lastDay!!.toEpochDay()
        return unsortedDays.filter { day ->
            val epochDay = day.day.toEpochDay()
            epochDay in (first - NEIGHBOR_DAYS) until first || epochDay in (last + 1)..(last + NEIGHBOR_DAYS)
        }.sortedBy { it.day }
    }

    companion object {
        const val NEIGHBOR_DAYS = 2L
    }
}

/**
 * Whether [text] matches a search: [query] appears anywhere in it ignoring case, or, for queries of four letters or
 * more, a word of [text] is one typo away from it.
 */
internal fun matches(text: String, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val t = text.lowercase()
    if (q in t) return true
    return q.length >= 4 && t.split(Regex("\\W+")).any { word -> withinOneEdit(word, q) || withinOneEdit(word.take(q.length), q) }
}

private fun withinOneEdit(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) {
            i++; j++
            continue
        }
        if (++edits > 1) return false
        when {
            a.length > b.length -> i++
            a.length < b.length -> j++
            else -> { i++; j++ }
        }
    }
    return edits + (a.length - i) + (b.length - j) <= 1
}

/**
 * What the user adds to their photos, kept on this device: stories (create, rename, delete, add and remove photos,
 * group them, write their text), and each photo's caption and tags. Combines them with the photo library into the
 * journal timeline. Syncing to the server comes with uploads.
 */
@OptIn(ExperimentalTime::class)
class StoriesController internal constructor(
    private val store: StoryStore,
    private val noteStore: PhotoNoteStore,
    library: LibraryController,
    /** The grouping setting decides how unsorted photos are split into moments. */
    settings: SettingsController,
    /** New story ids; see [StoryIds]. */
    private val newId: () -> String,
    /** Finds photos that look alike for burst suggestions; without it they go by time only. */
    private val similarity: PhotoSimilarity? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val now: () -> String = { Clock.System.now().toString() },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val libraryState = library.state
    private val settingsState = settings.state
    private var stories = loadStories()
    private var notes = loadNotes()
    private var error: String? = null
    // Compared with the threshold chosen in Settings.
    private val looks get() =
        (similarity?.looks?.value ?: PhotoLooks.NONE).withSameScene(settingsState.value.similarity.percent / 100f)

    // Rebuilt right away on every change here, so a screen opened just after `createStory` already sees the story.
    private val _state = MutableStateFlow(buildState(libraryState.value, stories, notes, error, settingsState.value.grouping, looks))
    val state: StateFlow<StoriesState> = _state.asStateFlow()

    init {
        scope.launch { libraryState.collect { publish() } }
        scope.launch { settingsState.collect { publish() } }
        similarity?.let { scope.launch { it.looks.collect { publish() } } }
    }

    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: StoriesState get() = state.value

    /**
     * Creates a story holding [itemIds]; returns its id, or null when no item is on the device. The title is optional:
     * a blank one is saved as "" and the story shows its dates instead.
     */
    fun createStory(title: String, itemIds: List<String>): String? {
        val name = title.trim()
        val items = itemsById(itemIds).ifEmpty { return null }
        val time = now()
        val story = Story(
            id = newId(),
            title = name,
            members = items.sortedBy { it.takenAtMillis }.map { it.toMember() },
            createdAt = time,
            updatedAt = time,
        )
        return if (save(story)) story.id else null
    }

    /** Creates a story from the unsorted photos and videos of [day]. */
    fun createStoryFromDay(title: String, day: LocalDay): String? = createStory(title, unsortedIdsOf(day))

    /** Creates a story from a moment of the journal ([Moment.key]). */
    fun createStoryFromMoment(title: String, key: String): String? =
        createStory(title, state.value.moment(key)?.items?.map { it.id }.orEmpty())

    fun addItems(storyId: String, itemIds: List<String>) = change(storyId) { story ->
        val known = story.members.map { it.deviceId }.toSet()
        val added = itemsById(itemIds).filter { it.id !in known }.map { it.toMember() }
        story.copy(members = (story.members + added).sortedBy { it.takenAtMillis })
    }

    /** Adds the unsorted photos and videos of [day], e.g. a neighboring day of a trip. */
    fun addDay(storyId: String, day: LocalDay) = addItems(storyId, unsortedIdsOf(day))

    /** Takes items out of the story, and out of its groups. */
    fun removeItems(storyId: String, itemIds: List<String>) = change(storyId) { story ->
        story.copy(members = story.members.filter { it.deviceId !in itemIds })
    }

    /** A blank title removes it. */
    fun rename(storyId: String, title: String) = change(storyId) { it.copy(title = title.trim()) }

    /** The story's opening text in story.md. */
    fun setIntro(storyId: String, text: String) = change(storyId) { it.copy(intro = text.trim()) }

    /** Deletes the story only; its photos and videos stay on the device. */
    fun delete(storyId: String) {
        try {
            store.delete(storyId)
            stories = stories.filter { it.id != storyId }
            error = null
        } catch (e: Exception) {
            error = e.describe()
        }
        publish()
    }

    /**
     * Groups [itemIds] (at least two members of the story) into one tile of the story; photos already in another
     * group of this story move to the new one. Returns the group's id.
     */
    fun createGroup(storyId: String, itemIds: List<String>, title: String? = null): String? {
        val story = stories.firstOrNull { it.id == storyId } ?: return null
        val ids = story.members.filter { it.deviceId in itemIds }.sortedBy { it.takenAtMillis }.map { it.deviceId }
        if (ids.size < 2) return null
        val groupId = nextGroupId(story)
        change(storyId) {
            it.copy(
                groups = it.groups.map { group -> group.copy(memberIds = group.memberIds - ids.toSet()) } +
                    StoryGroup(groupId, title?.trim()?.ifEmpty { null }, ids.first(), ids),
            )
        }
        return groupId
    }

    /**
     * Puts the picked tiles ([StoryTile.key]s: photos and groups, at least two) into a new group, which takes their
     * place: in the story, or with [parentId] in that group, as a subgroup. Picked groups become its subgroups.
     * Picking every tile of a group would only wrap it again, so it does nothing. Returns the new group's id.
     */
    fun groupTiles(storyId: String, tileKeys: List<String>, parentId: String? = null): String? {
        val story = stories.firstOrNull { it.id == storyId } ?: return null
        val container = containerTiles(storyId, parentId) ?: return null
        val picked = container.filter { it.key in tileKeys }
        if (picked.size < 2 || (parentId != null && picked.size == container.size)) return null
        val photos = picked.filterIsInstance<StoryTile.Media>().map { it.item.id }.toSet()
        val subgroups = picked.filterIsInstance<StoryTile.Group>().map { it.group.id }.toSet()
        val groupId = nextGroupId(story)
        val cover = picked.minBy { it.firstItem().takenAtMillis }.firstItem().id
        change(storyId) {
            it.copy(
                groups = it.groups.map { group ->
                    when {
                        group.id in subgroups -> group.copy(parentId = groupId)
                        group.id == parentId -> group.copy(memberIds = group.memberIds - photos)
                        else -> group
                    }
                } + StoryGroup(groupId, null, cover, photos.toList(), parentId = parentId),
            )
        }
        return groupId
    }

    /**
     * Makes the picked tiles ([StoryTile.key]s, at least two, of the story or with [parentId] of that group) one
     * group by merging: everything joins the group that comes first among them, which keeps its id, title, cover and
     * layout; the other groups' photos and subgroups move into it, and their text is added to its own (a missing
     * title is taken from them). With no group among them it is [groupTiles]. Returns the group's id.
     */
    fun mergeTiles(storyId: String, tileKeys: List<String>, parentId: String? = null): String? {
        val container = containerTiles(storyId, parentId) ?: return null
        val picked = container.filter { it.key in tileKeys }
        if (picked.size < 2) return null
        val groups = picked.filterIsInstance<StoryTile.Group>().map { it.group.group }
        val target = groups.firstOrNull() ?: return groupTiles(storyId, tileKeys, parentId)
        val others = groups.drop(1)
        val otherIds = others.map { it.id }.toSet()
        val loose = picked.filterIsInstance<StoryTile.Media>().map { it.item.id }.toSet()
        val joined = others.flatMap { it.memberIds } + loose
        change(storyId) { story ->
            val byTime = story.members.associate { it.deviceId to it.takenAtMillis }
            story.copy(
                groups = story.groups.filter { it.id !in otherIds }.map { group ->
                    when {
                        group.id == target.id -> group.copy(
                            title = group.title ?: others.firstNotNullOfOrNull { it.title },
                            memberIds = (group.memberIds + joined).distinct().sortedBy { byTime[it] },
                            text = (listOf(group) + others).map { it.text }.filter { it.isNotBlank() }.joinToString("\n\n"),
                        )
                        group.parentId in otherIds -> group.copy(parentId = target.id)
                        group.id == parentId -> group.copy(memberIds = group.memberIds - loose)
                        else -> group
                    }
                },
            )
        }
        return target.id
    }

    /** The tiles of the story's grid, or with [groupId] of that group's page. */
    private fun containerTiles(storyId: String, groupId: String?): List<StoryTile>? {
        val view = currentState.story(storyId) ?: return null
        return if (groupId == null) view.tiles else view.group(groupId)?.tiles
    }

    /** Puts a story back as it was, e.g. to undo [groupTiles]: [snapshot] is the [StoryView.story] from before. */
    fun restore(snapshot: Story) = change(snapshot.id) { snapshot }

    /** "Group them" on a burst suggestion. */
    fun acceptSuggestion(storyId: String, key: String): String? {
        val suggestion = currentState.story(storyId)?.suggestions?.firstOrNull { it.key == key } ?: return null
        return createGroup(storyId, suggestion.items.map { it.id })
    }

    /** "Group them" on a suggestion on a group's page: the photos become a subgroup. */
    fun acceptGroupSuggestion(storyId: String, groupId: String, key: String): String? {
        val suggestion = currentState.story(storyId)?.group(groupId)?.suggestions?.firstOrNull { it.key == key } ?: return null
        return groupTiles(storyId, suggestion.items.map { it.id }, parentId = groupId)
    }

    /** "Not now" on a suggestion, in the story or on a group's page: it won't be suggested again. */
    fun dismissSuggestion(storyId: String, key: String) = change(storyId) { story ->
        story.copy(dismissedSuggestions = (story.dismissedSuggestions + key).distinct())
    }

    /**
     * Removes every group of the story, nested ones too, so its photos are loose again and suggestions start over:
     * "Not now" answers are forgotten. Group text joins the story's opening text, so nothing written is lost. Undo
     * with [restore].
     */
    fun resetGroups(storyId: String) = change(storyId) { story ->
        var reset = story
        while (reset.groups.isNotEmpty()) reset = dissolve(reset, reset.groups.first().id)
        reset.copy(dismissedSuggestions = emptyList())
    }

    /** A blank title removes it; the tile then shows the time only. */
    fun renameGroup(storyId: String, groupId: String, title: String) = changeGroup(storyId, groupId) {
        it.copy(title = title.trim().ifEmpty { null })
    }

    /** The group's section of story.md. */
    fun setGroupText(storyId: String, groupId: String, text: String) = changeGroup(storyId, groupId) {
        it.copy(text = text.trim())
    }

    /**
     * Any photo of the group or of its subgroups. A focus point and zoom chosen for the old cover don't fit the new
     * one, so they go back to automatic.
     */
    fun setCover(storyId: String, groupId: String, itemId: String) = change(storyId) { story ->
        val group = story.groups.firstOrNull { it.id == groupId }
        if (group == null || itemId !in story.photosIn(groupId) || itemId == group.coverId) return@change story
        val key = groupTileKey(groupId)
        story.copy(
            groups = story.groups.map { if (it.id == groupId) it.copy(coverId = itemId) else it },
            layout = story.layout + (key to (story.layout[key] ?: TileLayout()).copy(focus = null, zoom = null)),
        )
    }

    /**
     * Takes photos out of the group (from any of its subgroups too) into the level above: the group around it, or
     * the story's loose photos. A group left with one tile goes away.
     */
    fun removeFromGroup(storyId: String, groupId: String, itemIds: List<String>) = change(storyId) { story ->
        val group = story.groups.firstOrNull { it.id == groupId } ?: return@change story
        val moving = story.photosIn(groupId).intersect(itemIds.toSet())
        story.copy(
            groups = story.groups.map {
                val kept = it.copy(memberIds = it.memberIds - moving)
                if (it.id == group.parentId) kept.copy(memberIds = kept.memberIds + moving) else kept
            },
        )
    }

    /**
     * Dissolves the group: its photos and subgroups move to the level above (the group around it, or the story), and
     * so does its text, added to that group's text or the story's opening text.
     */
    fun ungroup(storyId: String, groupId: String) = change(storyId) { dissolve(it, groupId) }

    // MARK: Layout

    /** The tile's size on the story's grid ([StoryTile.key]); null goes back to the automatic size. */
    fun setTileSize(storyId: String, tileKey: String, size: TileSize?) = changeLayout(storyId, tileKey) { it.copy(size = size) }

    /**
     * The point of the tile's photo (a group's cover) that stays in view when the tile crops it, and how much closer
     * than filling the tile to show it: 1 to [MAX_ZOOM].
     */
    fun setTileFocus(storyId: String, tileKey: String, x: Float, y: Float, zoom: Float) =
        changeLayout(storyId, tileKey) { it.copy(focus = FocusPoint.of(x, y), zoom = cleanZoom(zoom)) }

    /** Moves the tile [offset] places later (negative: earlier) among the tiles of its day. */
    fun moveTile(storyId: String, tileKey: String, offset: Int) {
        val view = currentState.story(storyId) ?: return
        val dayKeys = view.tileDays.firstOrNull { day -> day.tiles.any { it.key == tileKey } }?.tiles?.map { it.key } ?: return
        val from = dayKeys.indexOf(tileKey)
        val to = (from + offset).coerceIn(dayKeys.indices)
        if (to == from) return
        val moved = dayKeys.toMutableList().apply { add(to, removeAt(from)) }.iterator()
        // The day's tiles take their new order in the same slots; other days stay as they are.
        val order = view.tiles.map { tile -> if (tile.key in dayKeys) moved.next() else tile.key }
        change(storyId) { it.copy(tileOrder = order) }
    }

    /** Back to the automatic layout: sizes, focus points and order. */
    fun resetLayout(storyId: String) = change(storyId) { it.copy(layout = emptyMap(), tileOrder = emptyList()) }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (StoriesState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() {
        scope.cancel()
        similarity?.close()
    }

    // MARK: Captions and tags

    /** The photo's caption, shown wherever the photo appears. */
    fun setCaption(itemId: String, text: String) = changeNotes(listOf(itemId)) { it.copy(caption = text.trim()) }

    /**
     * Tags [itemIds] with [tag]; an existing tag with the same name in another case is reused. Returns the tag as
     * saved, or null when it is blank. Tagged photos leave Unsorted.
     */
    fun addTag(itemIds: List<String>, tag: String): String? {
        val typed = tag.trim().replace(Regex("\\s+"), " ").ifEmpty { return null }
        val name = currentState.tags.firstOrNull { it.name.equals(typed, ignoreCase = true) }?.name ?: typed
        changeNotes(itemIds) { note ->
            if (note.tags.any { it.equals(name, ignoreCase = true) }) note else note.copy(tags = note.tags + name)
        }
        return name
    }

    fun removeTag(itemIds: List<String>, tag: String) = changeNotes(itemIds) { note ->
        note.copy(tags = note.tags.filterNot { it.equals(tag, ignoreCase = true) })
    }

    private fun changeNotes(itemIds: List<String>, transform: (PhotoNote) -> PhotoNote) {
        val time = now()
        try {
            itemsById(itemIds).forEach { item ->
                val current = notes[item.id] ?: PhotoNote(item.id, item.fileName, item.takenAtMillis, updatedAt = time)
                val changed = transform(current)
                if (changed == current) return@forEach
                if (changed.isEmpty) {
                    noteStore.delete(item.id)
                    notes = notes - item.id
                } else {
                    val saved = changed.copy(updatedAt = time)
                    noteStore.save(saved)
                    notes = notes + (item.id to saved)
                }
            }
            error = null
        } catch (e: Exception) {
            error = e.describe()
        }
        publish()
    }

    private fun loadStories(): List<Story> = try {
        store.loadAll()
    } catch (e: Exception) {
        emptyList()
    }

    private fun loadNotes(): Map<String, PhotoNote> = try {
        noteStore.loadAll().associateBy { it.deviceId }
    } catch (e: Exception) {
        emptyMap()
    }

    private fun changeGroup(storyId: String, groupId: String, transform: (StoryGroup) -> StoryGroup) = change(storyId) { story ->
        story.copy(groups = story.groups.map { if (it.id == groupId) transform(it) else it })
    }

    private fun changeLayout(storyId: String, tileKey: String, transform: (TileLayout) -> TileLayout) = change(storyId) { story ->
        story.copy(layout = story.layout + (tileKey to transform(story.layout[tileKey] ?: TileLayout())))
    }

    private fun change(storyId: String, transform: (Story) -> Story) {
        val current = stories.firstOrNull { it.id == storyId } ?: return
        val changed = normalizeLayout(normalizeGroups(transform(current)))
        if (changed == current) return
        save(changed.copy(updatedAt = now()))
    }

    /**
     * Layout entries only for tiles of the story's grid: a photo that joined a group, or left, loses its own, and so
     * does a group that became a subgroup.
     */
    private fun normalizeLayout(story: Story): Story {
        val grouped = story.groups.flatMap { it.memberIds }.toSet()
        val keys = story.members.map { it.deviceId }.filter { it !in grouped }.toSet() +
            story.groups.filter { it.parentId == null }.map { groupTileKey(it.id) }
        return story.copy(
            layout = story.layout.filter { (key, layout) -> key in keys && !layout.isEmpty },
            tileOrder = story.tileOrder.filter { it in keys }.distinct(),
        )
    }

    /**
     * Keeps groups consistent after any change: members must be in the story, a parent must exist (and not be the
     * group itself or inside it), a group needs two tiles (photos and subgroups), the cover must be one of its photos.
     * A group that goes away is [dissolve]d, so no photo or text is lost.
     */
    private fun normalizeGroups(story: Story): Story {
        val inStory = story.members.map { it.deviceId }.toSet()
        val ids = story.groups.map { it.id }.toSet()
        val groups = story.groups.map { group ->
            group.copy(memberIds = group.memberIds.filter { it in inStory }.distinct(), parentId = group.parentId?.takeIf { it in ids })
        }.toMutableList()
        // A loop of parents would hide its groups; the first group found in one becomes the story's own.
        groups.indices.forEach { index ->
            val seen = mutableSetOf(groups[index].id)
            var parent = groups[index].parentId
            while (parent != null) {
                if (!seen.add(parent)) {
                    groups[index] = groups[index].copy(parentId = null)
                    break
                }
                parent = groups.first { it.id == parent }.parentId
            }
        }
        var normalized = story.copy(groups = groups)
        while (true) {
            val small = normalized.groups.firstOrNull { group ->
                group.memberIds.size + normalized.groups.count { it.parentId == group.id } < 2
            } ?: break
            normalized = dissolve(normalized, small.id)
        }
        val byTime = story.members.associate { it.deviceId to it.takenAtMillis }
        return normalized.copy(
            groups = normalized.groups.map { group ->
                val photos = normalized.photosIn(group.id)
                group.copy(coverId = group.coverId.takeIf { it in photos } ?: photos.minByOrNull { byTime[it] ?: 0 } ?: group.coverId)
            },
        )
    }

    /**
     * Removes group [groupId]; its photos, subgroups and text move to the group around it, or to the story (loose
     * photos, and the opening text).
     */
    private fun dissolve(story: Story, groupId: String): Story {
        val group = story.groups.firstOrNull { it.id == groupId } ?: return story
        fun joinText(a: String) = listOf(a, group.text).filter { it.isNotBlank() }.joinToString("\n\n")
        return story.copy(
            groups = story.groups.filter { it.id != groupId }.map {
                when {
                    it.id == group.parentId -> it.copy(memberIds = it.memberIds + group.memberIds, text = joinText(it.text))
                    it.parentId == groupId -> it.copy(parentId = group.parentId)
                    else -> it
                }
            },
            intro = if (group.parentId == null) joinText(story.intro) else story.intro,
        )
    }

    private fun nextGroupId(story: Story): String {
        val used = (story.groups.map { it.id } + story.textExtras.keys).mapNotNull { it.removePrefix("g").toIntOrNull() }
        return "g${(used.maxOrNull() ?: 0) + 1}"
    }

    private fun save(story: Story): Boolean {
        val saved = try {
            store.save(story)
            stories = stories.filter { it.id != story.id } + story
            error = null
            true
        } catch (e: Exception) {
            error = e.describe()
            false
        }
        publish()
        return saved
    }

    private fun publish() {
        val state = buildState(libraryState.value, stories, notes, error, settingsState.value.grouping, looks)
        _state.value = state
        similarity?.request(photosToCompare(state.stories))
    }

    private fun itemsById(ids: List<String>): List<MediaItem> {
        val wanted = ids.toSet()
        return libraryState.value.days.flatMap { day -> day.items.filter { it.id in wanted } }
    }

    private fun unsortedIdsOf(day: LocalDay): List<String> =
        state.value.unsortedDays.firstOrNull { it.day == day }?.items?.map { it.id }.orEmpty()
}

private fun MediaItem.toMember() = StoryMember(deviceId = id, fileName = fileName, takenAtMillis = takenAtMillis)

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
    val tags = notes.values
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
        stories = views,
        timeline = timeline,
        unsortedDays = unsorted,
        moments = moments,
        notes = notes,
        tags = tags,
        itemsById = itemsById,
        error = error,
    )
}
