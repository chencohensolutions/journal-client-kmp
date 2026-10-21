package com.chencohensolution.journal.stories

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.places.PhotoPlaces
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.SettingsState
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Fingerprints and places found in the background are shown at most this often. */
internal const val BACKGROUND_REBUILD_MILLIS = 3_000L

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
    /** Finds where photos were taken, for the Moments tab; without it moments go by time only. */
    private val places: PhotoPlaces? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val now: () -> String = { Clock.System.now().toString() },
    /**
     * Where the state is rebuilt when the library, settings, fingerprints or places change: a rebuild of a large
     * library takes long enough to make scrolling stutter. Off the main thread in the app; tests run it on their own
     * dispatcher, so waiting for it is deterministic.
     */
    private val background: CoroutineDispatcher = if (dispatcher === Dispatchers.Main) Dispatchers.Default else dispatcher,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val libraryState = library.state
    private val settingsState = settings.state
    private var stories = loadStories()
    private var notes = loadNotes()
    private var error: String? = null
    // Hidden sources shown on one row of the Moments tab; forgotten when the app closes.
    private var revealed = emptySet<FeedReveal>()
    // Compared with the threshold chosen in Settings.
    private val looks get() =
        (similarity?.looks?.value ?: PhotoLooks.NONE).withSameScene(settingsState.value.similarity.percent / 100f)

    // Rebuilt right away on every change made here, so a screen opened just after `createStory` already sees the story.
    private val _state = MutableStateFlow(inputs().build().state)
    val state: StateFlow<StoriesState> = _state.asStateFlow()

    // Counts changes made here: a background rebuild started before one is dropped, as it was built from older stories.
    private var version = 0

    // Bumped for each change from outside; collecting it skips to the latest, so a burst of changes is one rebuild.
    private val outsideChanges = MutableStateFlow(0)

    // Fingerprints and places arrive in small batches for as long as a large library is being read: they rebuild the
    // state at most every BACKGROUND_REBUILD_MILLIS, so the screens don't redraw several times a second meanwhile.
    private val backgroundChanges = MutableStateFlow(0)

    init {
        scope.launch { libraryState.collect { outsideChanges.value++ } }
        scope.launch { settingsState.collect { outsideChanges.value++ } }
        similarity?.let { scope.launch { it.looks.collect { backgroundChanges.value++ } } }
        places?.let { scope.launch { it.places.collect { backgroundChanges.value++ } } }
        scope.launch {
            backgroundChanges.collect {
                outsideChanges.value++
                delay(BACKGROUND_REBUILD_MILLIS)
            }
        }
        scope.launch {
            outsideChanges.collect {
                val startedAt = version
                val inputs = inputs()
                val built = withContext(background) { inputs.build() }
                if (version == startedAt) show(built)
            }
        }
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

    /**
     * A row of the Moments tab ([FeedSection.key]), or "Save as story" on a trip ([FeedTrip.key]): a story
     * of all its photos (also those in other stories already), with each set of near-duplicates already a group. Returns the story's id.
     */
    fun createStoryFromFeed(title: String, key: String): String? {
        val feed = state.value.feed
        val items = feed.section(key)?.items ?: feed.trip(key)?.items ?: return null
        val storyId = createStory(title, items.map { it.id }) ?: return null
        feed.similarSets(items.map { it.id }).forEach { set -> createGroup(storyId, set.map { it.id }) }
        return storyId
    }

    /**
     * Shows [source]'s hidden photos on the Moments row [sectionKey] ([FeedSection.key]), in a band under its
     * photos: its "+6 WhatsApp" chip. The setting doesn't change, and nothing is saved.
     */
    fun showOnRow(sectionKey: String, source: MediaSource) {
        revealed = revealed + FeedReveal(sectionKey, source)
        publish()
    }

    /** Hides again what [showOnRow] showed. */
    fun hideOnRow(sectionKey: String, source: MediaSource) {
        revealed = revealed - FeedReveal(sectionKey, source)
        publish()
    }

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
        places?.close()
    }

    // MARK: Captions and tags

    /** The photo's caption, shown wherever the photo appears. */
    fun setCaption(itemId: String, text: String) = changeNotes(listOf(itemId)) { it.copy(caption = text.trim()) }

    /**
     * Moments: [itemIds] (the photos of the sets and photos picked) become one set you swipe, shown where its oldest
     * photo is. Nothing happens with fewer than two.
     */
    fun mergeInFeed(itemIds: List<String>) {
        if (itemIds.distinct().size < 2) return
        val set = newId()
        changeNotes(itemIds.distinct()) { it.copy(feedSet = set, apart = false) }
    }

    /** Moments: the photo leaves its set (made by hand or by itself) and isn't put in one by itself again. */
    fun takeOutOfSet(itemId: String) = changeNotes(listOf(itemId)) { it.copy(feedSet = null, apart = true) }

    /**
     * Moments: the set of [itemIds] (in its order) was turned to [itemId], so it opens there next time, also on another
     * device once notes are synced. Kept in the photo's note; turning back to the first photo clears it.
     */
    fun turnSet(itemIds: List<String>, itemId: String) =
        changeNotes(itemIds, rebuild = false) { it.copy(front = it.deviceId == itemId && itemId != itemIds.first()) }

    /** The photo's crops, for looking only (the photo isn't changed); empty crops remove them. */
    fun setCrops(itemId: String, crops: PhotoCrops) = changeNotes(listOf(itemId)) { it.copy(crops = crops) }

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

    /**
     * Saves the notes [transform] changes. Without [rebuild] (for what changes nothing else on screen, done on each
     * swipe) only the state's notes are updated, as rebuilding the whole state can take longer than a frame, and
     * nothing at all when no note changed: a new state redraws the whole home screen, and a set scrolling into view
     * reports the page it already shows.
     */
    private fun changeNotes(itemIds: List<String>, rebuild: Boolean = true, transform: (PhotoNote) -> PhotoNote) {
        val time = now()
        var changedAny = false
        // From the state's index: searching the whole library, on the main thread, for each swipe set took 14% of it
        // while scrolling. A photo
        // the state doesn't know yet (it is rebuilt in the background) is searched for in the library instead.
        val known = _state.value.itemsById
        val items = itemIds.mapNotNull { known[it] }.takeIf { it.size == itemIds.size } ?: itemsById(itemIds)
        try {
            items.forEach { item ->
                val current = notes[item.id] ?: PhotoNote(item.id, item.fileName, item.takenAtMillis, updatedAt = time)
                val changed = transform(current)
                if (changed == current) return@forEach
                changedAny = true
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
        if (rebuild || error != null) publish() else if (changedAny) _state.value = _state.value.copy(notes = notes)
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

    /** After a change made here: rebuilt right away. */
    private fun publish() {
        version++
        show(inputs().build())
    }

    private fun show(built: Built) {
        _state.value = built.state
        similarity?.request(built.toCompare)
        places?.request(built.toLocate)
    }

    /** Everything the state is built from, as it is now. */
    private fun inputs() = Inputs(libraryState.value, stories, notes, error, settingsState.value, looks, places?.places?.value ?: Places.NONE, revealed)

    private class Inputs(
        val library: LibraryState,
        val stories: List<Story>,
        val notes: Map<String, PhotoNote>,
        val error: String?,
        val settings: SettingsState,
        val looks: PhotoLooks,
        val places: Places,
        val revealed: Set<FeedReveal>,
    ) {
        fun build(): Built {
            val state = buildState(
                library, stories, notes, error, settings.grouping, looks,
                shown = settings.shownSources,
                dismissedTrips = settings.dismissedTrips,
                places = places,
                revealed = revealed,
            )
            return Built(
                state,
                // Story photos first (their suggestions are asked for), then the Moments tab's, newest first.
                photosToCompare(state.stories) + state.feed.entries.flatMap { entry ->
                    (entry as? FeedEntry.Section)?.section?.items.orEmpty().filter { it.type == MediaType.IMAGE }
                },
                // Unsorted photos first, then the rest: all of them tell where home is.
                (state.unsortedDays + library.days).flatMap { it.items }.distinctBy { it.id },
            )
        }
    }

    /** A state, and the photos to fingerprint and to locate for it. */
    private class Built(val state: StoriesState, val toCompare: List<MediaItem>, val toLocate: List<MediaItem>)

    private fun itemsById(ids: List<String>): List<MediaItem> {
        val wanted = ids.toSet()
        return libraryState.value.days.flatMap { day -> day.items.filter { it.id in wanted } }
    }

    private fun unsortedIdsOf(day: LocalDay): List<String> =
        state.value.unsortedDays.firstOrNull { it.day == day }?.items?.map { it.id }.orEmpty()
}

private fun MediaItem.toMember() = StoryMember(deviceId = id, fileName = fileName, takenAtMillis = takenAtMillis)
