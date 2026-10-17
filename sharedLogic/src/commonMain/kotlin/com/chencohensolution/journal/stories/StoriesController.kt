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
data class GroupView(val group: StoryGroup, val items: List<MediaItem>) {
    val id: String get() = group.id
    val title: String? get() = group.title
    val text: String get() = group.text
    val itemCount: Int get() = items.size

    /** The photo on the group's tile. */
    val cover: MediaItem = items.firstOrNull { it.id == group.coverId } ?: items.first()
}

/** What a story shows in its grid: a photo or video, or a group as one tile. */
sealed interface StoryTile {
    data class Media(val item: MediaItem) : StoryTile
    data class Group(val group: GroupView) : StoryTile
}

/** One day of a story's grid. A group sits on the day of its first photo. */
data class StoryDay(val day: LocalDay, val tiles: List<StoryTile>)

/** Photos taken in quick succession that could become a group. */
data class BurstSuggestion(val items: List<MediaItem>) {
    /** The first photo's id; remembered when the suggestion is dismissed. */
    val key: String get() = items.first().id
    val count: Int get() = items.size
    val startMillis: Long get() = items.first().takenAtMillis
    val seconds: Long get() = (items.last().takenAtMillis - items.first().takenAtMillis) / 1000
}

/** A story with its photos and videos as they are on this device. */
data class StoryView(
    val story: Story,
    /** Members found on this device, oldest first. */
    val items: List<MediaItem>,
) {
    val id: String get() = story.id
    val title: String get() = story.title
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

    /** Groups with at least one photo on this device. */
    val groups: List<GroupView> = run {
        val byId = items.associateBy { it.id }
        story.groups.mapNotNull { group ->
            group.memberIds.mapNotNull { byId[it] }.takeIf { it.isNotEmpty() }?.let { GroupView(group, it) }
        }
    }

    /** The grid, oldest first: loose photos, and each group where its first photo would be. */
    val tiles: List<StoryTile> = run {
        val groupOf = groups.flatMap { group -> group.items.map { it.id to group } }.toMap()
        val placed = mutableSetOf<String>()
        items.mapNotNull { item ->
            val group = groupOf[item.id] ?: return@mapNotNull StoryTile.Media(item)
            if (placed.add(group.id)) StoryTile.Group(group) else null
        }
    }

    /** [tiles] by day, oldest day first: a story reads like a story. */
    val tileDays: List<StoryDay> = tiles
        .groupBy { tile -> tile.firstItem().day }
        .map { (day, dayTiles) -> StoryDay(day, dayTiles) }

    /** What the viewer swipes through: loose photos, and each group as its cover. */
    val viewerItems: List<MediaItem> = tiles.map { it.firstItem(cover = true) }

    /** Bursts among the loose photos, except dismissed ones. */
    val suggestions: List<BurstSuggestion> = findBursts(
        tiles.filterIsInstance<StoryTile.Media>().map { it.item },
        story.dismissedSuggestions.toSet(),
    )

    fun group(id: String): GroupView? = groups.firstOrNull { it.id == id }

    /** The group [itemId] is in within this story, if any. */
    fun groupOf(itemId: String): GroupView? = groups.firstOrNull { group -> group.items.any { it.id == itemId } }
}

private fun StoryTile.firstItem(cover: Boolean = false): MediaItem = when (this) {
    is StoryTile.Media -> item
    is StoryTile.Group -> if (cover) group.cover else group.items.first()
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

/** Fewer photos than this aren't worth a suggestion. */
internal const val BURST_MIN_PHOTOS = 3

/**
 * Splits [items] (oldest first) into runs of photos taken within [BURST_GAP_MILLIS] of each other, or sharing the
 * camera's burst id, and suggests the runs of at least [BURST_MIN_PHOTOS]. Videos are left out.
 */
internal fun findBursts(items: List<MediaItem>, dismissed: Set<String>): List<BurstSuggestion> {
    val runs = mutableListOf<MutableList<MediaItem>>()
    items.filter { it.type == MediaType.IMAGE }.forEach { item ->
        val previous = runs.lastOrNull()?.last()
        val sameBurst = previous != null && item.burstId != null && item.burstId == previous.burstId
        if (previous != null && (item.takenAtMillis - previous.takenAtMillis <= BURST_GAP_MILLIS || sameBurst)) {
            runs.last() += item
        } else {
            runs += mutableListOf(item)
        }
    }
    return runs.filter { it.size >= BURST_MIN_PHOTOS && it.first().id !in dismissed }.map(::BurstSuggestion)
}

/** One row of the journal timeline. */
sealed interface TimelineEntry {
    data class StoryEntry(val story: StoryView) : TimelineEntry
    data class DayEntry(val day: DayGroup) : TimelineEntry
}

data class StoriesState(
    /** Newest first, by [StoryView.timelineDay]. */
    val stories: List<StoryView> = emptyList(),
    /** Stories and unsorted days, newest first; a story sits at its newest photo's day. */
    val timeline: List<TimelineEntry> = emptyList(),
    /** Photos and videos in no story and without tags, by day, newest first. */
    val unsortedDays: List<DayGroup> = emptyList(),
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
    /** New story ids; see [StoryIds]. */
    private val newId: () -> String,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val now: () -> String = { Clock.System.now().toString() },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val libraryState = library.state
    private var stories = loadStories()
    private var notes = loadNotes()
    private var error: String? = null

    // Rebuilt right away on every change here, so a screen opened just after `createStory` already sees the story.
    private val _state = MutableStateFlow(buildState(libraryState.value, stories, notes, error))
    val state: StateFlow<StoriesState> = _state.asStateFlow()

    init {
        scope.launch { libraryState.collect { publish() } }
    }

    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: StoriesState get() = state.value

    /** Creates a story holding [itemIds]; returns its id, or null when [title] is blank or no item is on the device. */
    fun createStory(title: String, itemIds: List<String>): String? {
        val name = title.trim().ifEmpty { return null }
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

    fun rename(storyId: String, title: String) {
        val name = title.trim().ifEmpty { return }
        change(storyId) { it.copy(title = name) }
    }

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
     * Groups [itemIds] (at least two members of the story) into one tile; photos already in another group of this
     * story move to the new one. Returns the group's id.
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

    /** "Group them" on a burst suggestion. */
    fun acceptSuggestion(storyId: String, key: String): String? {
        val suggestion = currentState.story(storyId)?.suggestions?.firstOrNull { it.key == key } ?: return null
        return createGroup(storyId, suggestion.items.map { it.id })
    }

    /** "Not now" on a burst suggestion: it won't be suggested again. */
    fun dismissSuggestion(storyId: String, key: String) = change(storyId) { story ->
        story.copy(dismissedSuggestions = (story.dismissedSuggestions + key).distinct())
    }

    /** A blank title removes it; the tile then shows the time only. */
    fun renameGroup(storyId: String, groupId: String, title: String) = changeGroup(storyId, groupId) {
        it.copy(title = title.trim().ifEmpty { null })
    }

    /** The group's section of story.md. */
    fun setGroupText(storyId: String, groupId: String, text: String) = changeGroup(storyId, groupId) {
        it.copy(text = text.trim())
    }

    fun setCover(storyId: String, groupId: String, itemId: String) = changeGroup(storyId, groupId) { group ->
        if (itemId in group.memberIds) group.copy(coverId = itemId) else group
    }

    /** Back to loose photos in the story; a group left with one photo goes away. */
    fun removeFromGroup(storyId: String, groupId: String, itemIds: List<String>) = changeGroup(storyId, groupId) {
        it.copy(memberIds = it.memberIds - itemIds.toSet())
    }

    /** Dissolves the group; its photos stay in the story and its text moves to the story's opening text. */
    fun ungroup(storyId: String, groupId: String) = changeGroup(storyId, groupId) { it.copy(memberIds = emptyList()) }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (StoriesState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() {
        scope.cancel()
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

    private fun change(storyId: String, transform: (Story) -> Story) {
        val current = stories.firstOrNull { it.id == storyId } ?: return
        save(normalizeGroups(transform(current)).copy(updatedAt = now()))
    }

    /**
     * Keeps groups consistent after any change: members must be in the story, a group needs two photos, the cover
     * must be a member. A group that goes away hands its text to the story's opening text.
     */
    private fun normalizeGroups(story: Story): Story {
        val inStory = story.members.map { it.deviceId }.toSet()
        var intro = story.intro
        val groups = story.groups.mapNotNull { group ->
            val memberIds = group.memberIds.filter { it in inStory }.distinct()
            if (memberIds.size < 2) {
                if (group.text.isNotBlank()) intro = listOf(intro, group.text).filter { it.isNotBlank() }.joinToString("\n\n")
                null
            } else {
                group.copy(memberIds = memberIds, coverId = group.coverId.takeIf { it in memberIds } ?: memberIds.first())
            }
        }
        return story.copy(groups = groups, intro = intro)
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
        _state.value = buildState(libraryState.value, stories, notes, error)
    }

    private fun itemsById(ids: List<String>): List<MediaItem> {
        val wanted = ids.toSet()
        return libraryState.value.days.flatMap { day -> day.items.filter { it.id in wanted } }
    }

    private fun unsortedIdsOf(day: LocalDay): List<String> =
        state.value.unsortedDays.firstOrNull { it.day == day }?.items?.map { it.id }.orEmpty()
}

private fun MediaItem.toMember() = StoryMember(deviceId = id, fileName = fileName, takenAtMillis = takenAtMillis)

internal fun buildState(library: LibraryState, stories: List<Story>, notes: Map<String, PhotoNote>, error: String?): StoriesState {
    val itemsById = library.days.flatMap { it.items }.associateBy { it.id }
    val views = stories
        .map { story -> StoryView(story, story.members.mapNotNull { itemsById[it.deviceId] }.sortedBy { it.takenAtMillis }) }
        .sortedWith(compareByDescending<StoryView> { it.timelineDay }.thenByDescending { it.story.createdAt })
    // A photo in a story, or with a tag, has a place already; only the rest is unsorted.
    val sorted = stories.flatMap { story -> story.members.map { it.deviceId } }.toSet() +
        notes.values.filter { it.tags.isNotEmpty() }.map { it.deviceId }
    val unsorted = library.days.mapNotNull { day ->
        day.items.filter { it.id !in sorted }.takeIf { it.isNotEmpty() }?.let { DayGroup(day.day, it) }
    }
    // Newest first; on the same day the story comes before the unsorted photos.
    val timeline = (views.map { it.timelineDay to TimelineEntry.StoryEntry(it) } + unsorted.map { it.day to TimelineEntry.DayEntry(it) })
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
        notes = notes,
        tags = tags,
        itemsById = itemsById,
        error = error,
    )
}
