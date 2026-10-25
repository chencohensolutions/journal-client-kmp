package com.chencohensolution.journal.stories

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.Measure
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
 * What the user adds to their photos, kept on this device: each photo's writing (and its earlier versions), tags,
 * crops and Moments sets. Combines them with the photo library into the Storyline and Moments tabs. Syncing to the
 * server comes with uploads. (The name is from when the app had stories, which the storyline replaced.)
 */
@OptIn(ExperimentalTime::class)
class StoriesController internal constructor(
    private val noteStore: PhotoNoteStore,
    library: LibraryController,
    /** The grouping setting decides how Moments is split into rows. */
    settings: SettingsController,
    /** New ids for sets merged by hand in Moments; see [StoryIds]. */
    private val newId: () -> String,
    /** Finds photos that look alike, for Moments' sets; without it they go by time only. */
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
    private var notes = loadNotes()
    private var error: String? = null
    // Hidden sources shown on one row of the Moments tab; forgotten when the app closes.
    private var revealed = emptySet<FeedReveal>()
    // Compared with the threshold chosen in Settings.
    private val looks get() =
        (similarity?.looks?.value ?: PhotoLooks.NONE).withSameScene(settingsState.value.similarity.percent / 100f)

    // Rebuilt right away on every change made here, so a screen opened just after a change already sees it.
    private val _state = MutableStateFlow(inputs().build().state)
    val state: StateFlow<StoriesState> = _state.asStateFlow()

    // Counts changes made here: a background rebuild started before one is dropped, as it was built from older notes.
    private var version = 0

    /** Background rebuilds started so far, for the tests that keep them few (docs/plans/library-on-disk.md). */
    internal var rebuilds = 0
        private set

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
        // The caches kept on this phone being read: the feed is ready once they are (StoriesState.ready).
        similarity?.let { scope.launch { it.loaded.collect { outsideChanges.value++ } } }
        places?.let { scope.launch { it.loaded.collect { outsideChanges.value++ } } }
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
                rebuilds++
                val built = withContext(background) { Measure.time({ "Moments rebuilt" }) { inputs.build() } }
                if (version == startedAt) show(built)
            }
        }
    }

    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: StoriesState get() = state.value

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

    // MARK: Writing, sets, crops and tags

    /**
     * The photo's title and paragraph, shown wherever the photo appears; what was there before is kept as a version
     * (see [PhotoNote.withWriting]).
     */
    fun setWriting(itemId: String, title: String, text: String) {
        val time = now()
        changeNotes(listOf(itemId)) { it.withWriting(title, text, time) }
    }

    /** Back to an earlier version of the photo's writing; what it replaces becomes a version too, so nothing is lost. */
    fun restoreWriting(itemId: String, version: NoteVersion) {
        val time = now()
        changeNotes(listOf(itemId)) { it.withWriting(version.title, version.text, time, keepPrevious = true) }
    }

    /**
     * "Remove from storyline" ([inStoryline] false): the photo keeps its writing and shows it in Moments, but leaves the
     * storyline; true puts it back.
     */
    fun setInStoryline(itemId: String, inStoryline: Boolean) = changeNotes(listOf(itemId)) { it.copy(inStoryline = inStoryline) }

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

    /**
     * Where the viewer was zoomed in on the photo as it was left ([zoom]: the part on screen; null: fitted), so it opens
     * there next time. Kept in the photo's note.
     */
    fun setZoom(itemId: String, zoom: CropRect?) = changeNotes(listOf(itemId), rebuild = false) { it.copy(zoom = zoom) }

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

    private fun loadNotes(): Map<String, PhotoNote> = try {
        noteStore.loadAll().associateBy { it.deviceId }
    } catch (e: Exception) {
        emptyMap()
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
    private fun inputs() = Inputs(
        libraryState.value, notes, error, settingsState.value, looks, places?.places?.value ?: Places.NONE, revealed,
        // The whole library, or its newest rows with the counts of the whole (LibraryState.counts), and the caches read.
        ready = (libraryState.value.complete || libraryState.value.counts != null) &&
            similarity?.loaded?.value != false && places?.loaded?.value != false,
    )

    private class Inputs(
        val library: LibraryState,
        val notes: Map<String, PhotoNote>,
        val error: String?,
        val settings: SettingsState,
        val looks: PhotoLooks,
        val places: Places,
        val revealed: Set<FeedReveal>,
        val ready: Boolean,
    ) {
        fun build(): Built {
            val state = buildState(
                library, notes, error, settings.grouping, looks,
                shown = settings.shownSources,
                places = places,
                revealed = revealed,
                ready = ready,
            )
            return Built(
                state,
                // The Moments tab's photos, newest first.
                state.feed.entries.flatMap { entry ->
                    (entry as? FeedEntry.Section)?.section?.items.orEmpty().filter { it.type == MediaType.IMAGE }
                },
                // All of them: they tell where home is, for Explore.
                library.days.flatMap { it.items },
            )
        }
    }

    /** A state, and the photos to fingerprint and to locate for it. */
    private class Built(val state: StoriesState, val toCompare: List<MediaItem>, val toLocate: List<MediaItem>)

    private fun itemsById(ids: List<String>): List<MediaItem> {
        val wanted = ids.toSet()
        return libraryState.value.days.flatMap { day -> day.items.filter { it.id in wanted } }
    }
}
