package com.chencohensolution.journal.stories

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryRow
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.Measure
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.SoundRecording
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** Fingerprints and places found in the background are shown at most this often. */
internal const val BACKGROUND_REBUILD_MILLIS = 3_000L

/**
 * Photos screens asked for by id that are kept read ([StoriesController.want]): the oldest asked for are let go past it.
 * A screen that shows one again asks for it again.
 */
private const val MAX_WANTED = 2_000

/** Bumped when the way Moments' rows are made changes, so the database's rows are all made again once. */
private const val FEED_VERSION = 1

/**
 * What the user adds to their photos, kept on this device: each photo's writing (and its earlier versions), tags,
 * crops and Moments sets. Combines them with the photo library into the Storyline and Moments tabs. Syncing to the
 * server comes with uploads. (The name is from when the app had stories, which the storyline replaced.)
 */
@OptIn(ExperimentalTime::class)
class StoriesController internal constructor(
    private val noteStore: PhotoNoteStore,
    private val library: LibraryController,
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
    /**
     * Moments read from the database a page at a time (docs/plans/library-on-disk.md, phase 5); without it (tests)
     * the whole feed is built in memory.
     */
    private val feed: FeedPager? = null,
    /** A database row as the screens show it (LibraryController.shownRow), for [feed]'s items. */
    private val shownRow: (LibraryRow) -> MediaItem = { it.item },
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

    /** With [feed]: the notes' photos and those screens asked for ([want]), as last read from the database. */
    private var readItems: Map<String, MediaItem> = emptyMap()

    /** The photos screens asked for, the latest last; at most [MAX_WANTED]. */
    private val wanted = LinkedHashSet<String>()

    /** Of [wanted], those the library doesn't have (StoriesState.missing). */
    private var missing: Set<String> = emptySet()

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
                // From the database: the notes' photos and those screens asked for read again (their statuses, or
                // gone). Then what changed outside the library marks its days, the rows of the marked days are made
                // again and the rows shown so far read again. Not before the caches are read, so the rows are made
                // with the places and fingerprints the phone keeps.
                if (feed != null) readAgain()
                if (feed != null && cachesLoaded) {
                    markChangedDays(feed)
                    pagedFeed = feed.refresh(feedContext(), revealed, shownRow)
                }
                // Shown unless another state is published while this one is built (an edit, rows read further down);
                // one published before now was made from no more than this one is.
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

    // MARK: Moments from the database

    /** The rows of Moments read so far ([feed]); null until the first page is read. */
    private var pagedFeed: MomentsFeed? = null

    /** What the rows were last made with, to mark the days of what changed since. */
    private var placesSeen: Places? = null
    private var looksSeen: PhotoLooks? = null

    private val cachesLoaded: Boolean get() = similarity?.loaded?.value != false && places?.loaded?.value != false

    /** Everything besides the library's items that Moments' rows are made from. */
    private fun feedContext(): FeedContext {
        val settings = settingsState.value
        val shown = settings.shownSources
        val version = listOf(
            shown.hidden.map { it.id }.sorted(), shown.sayWhatsHidden, shown.alwaysShown.map { "${it.day}:${it.source.id}" }.sorted(),
            settings.grouping.mode, settings.grouping.gapHours, settings.similarity.percent, FEED_VERSION,
        ).joinToString("|")
        return FeedContext(
            shown, settings.grouping, places?.places?.value ?: Places.NONE, looks, aloneIn(notes), setsIn(notes),
            libraryState.value.sounds, version,
        )
    }

    /**
     * Marks the days of what changed since the rows were made, besides the library's items (which the database's
     * triggers mark): photos newly located or in a place newly named, newly fingerprinted.
     */
    private suspend fun markChangedDays(feed: FeedPager) {
        // The days are the database's: the library's whole list isn't read for them (step 6a).
        val days = HashSet<LocalDay>()
        val nowPlaces = places?.places?.value ?: Places.NONE
        placesSeen?.takeIf { it !== nowPlaces }?.let { before ->
            val moved = nowPlaces.locations.filter { (id, point) -> before.locations[id] != point }.keys
            val named = nowPlaces.names.filter { (cell, name) -> before.names[cell] != name }.keys
            days += feed.daysOf(moved)
            days += feed.daysInCells(named, nowPlaces.locations)
        }
        placesSeen = nowPlaces
        val nowLooks = similarity?.looks?.value
        looksSeen?.takeIf { nowLooks != null && it.fingerprints !== nowLooks.fingerprints }?.let { before ->
            days += feed.daysOf(nowLooks!!.fingerprints.filter { (id, print) -> before.fingerprints[id] != print }.keys)
        }
        looksSeen = nowLooks
        // Recordings that came or went are marked by FeedTable itself, against those the rows were made with (step 6f).
        if (days.isNotEmpty()) feed.markDays(days)
    }

    // MARK: The background readers and home from the database

    /** Explore's home as last worked out from the database, and what from (the places found, the sources shown, rows). */
    private var home: ExploreHome? = null
    private var homeFound: Map<String, GeoPoint>? = null
    private var homeFrom: List<Any?>? = null

    /** What the fingerprints and places were last asked for from (the rows, the sources shown), to ask again on a change. */
    private var comparedFrom: List<Any?>? = null
    private var locatedFrom: Int? = null

    private var homeJob: Job? = null

    /**
     * Works Explore's home out again from the database when the places found, the sources shown or the rows changed, in
     * the background (it reads every located row's square), and shows it once known.
     */
    private fun findHome(feed: FeedPager) {
        if (homeJob?.isActive == true) return
        homeJob = scope.launch {
            while (true) {
                val found = places?.places?.value?.locations.orEmpty()
                val shown = settingsState.value.shownSources
                val from = listOf(shown.hidden, shown.alwaysShown, library.rowsChanged)
                // The places found by identity: comparing thousands of them each time would cost more than it saves.
                if (found === homeFound && from == homeFrom) break
                homeFound = found
                homeFrom = from
                val now = ExploreHome(Measure.time({ "Explore's home worked out" }) { feed.home(found, shown) })
                if (now != home) {
                    home = now
                    _state.value = _state.value.copy(home = now)
                }
            }
        }
    }

    /**
     * Asks for the photos to fingerprint and to locate from the database, a batch at a time (step 6b), instead of handing
     * over the whole library: again (from the newest) when rows came, went or moved, or the sources shown changed. Not
     * before the library's first whole read, so they don't slow it down; Explore's home likewise.
     */
    private fun readFromDatabase(feed: FeedPager) {
        if (!libraryState.value.complete) return
        findHome(feed)
        val rows = library.rowsChanged
        val shown = settingsState.value.shownSources
        val compare = listOf(rows, shown.hidden, shown.alwaysShown)
        if (similarity != null && compare != comparedFrom) {
            comparedFrom = compare
            // The photos Moments shows, as the rows are made with their fingerprints.
            similarity.requestFrom(feed::deviceImages) { shown.shows(it) }
        }
        if (places != null && rows != locatedFrom) {
            locatedFrom = rows
            places.requestFrom(feed.placeBatches)
        }
    }

    // MARK: Explore and the map from the database

    /**
     * What Explore's summaries or the map's overview were worked out from: the rows' changes, the places and the scores
     * (by identity: comparing thousands of them would cost more than it saves), the sources shown and home.
     */
    private class WorkedOut<T>(val from: List<Any?>, val value: T) {
        fun fits(other: List<Any?>) = other.size == from.size && other.indices.all { from[it] === other[it] || (other[it] !is Places && other[it] !is Map<*, *> && from[it] == other[it]) }
    }

    private var explored: WorkedOut<ExploreSummary>? = null
    private var mapped: WorkedOut<MapOverview>? = null
    private val exploreLock = Mutex()

    /** Everything in the library as the screens list it, newest first: without the database (tests, the path before). */
    private fun everything(): List<MediaItem> = libraryState.value.days.flatMap { it.items }

    /**
     * Explore's lists for [state] (its places, sources shown and home): from the database (docs/plans/library-on-disk.md,
     * step 6c), worked out again only when its rows, the places, the scores, the sources shown or home changed; the
     * covers are read again each time, so their statuses are current. Without it, from the library's items.
     */
    suspend fun explore(state: StoriesState = _state.value): ExploreSummary {
        val pager = feed ?: return withContext(background) { exploreOf(everything(), state.places, state.shownSources, state.home) }
        return exploreLock.withLock {
            val from = listOf(library.rowsWritten, state.places, library.scoresShown, state.shownSources, state.home)
            val kept = explored?.takeIf { it.fits(from) }?.value
            if (kept != null) {
                pager.readLibrary { table -> kept.withCovers(table.byIds(kept.coverIds()).mapValues { shownRow(it.value) }) }
            } else {
                Measure.time({ "Explore worked out" }) {
                    pager.readLibrary { table -> exploreOf(table, state.places, state.shownSources, state.home, library::shownScore, shownRow) }
                }.also { explored = WorkedOut(from, it) }
            }
        }
    }

    /**
     * The photos and videos [scope] takes in, of every source (the caller leaves out what [StoriesState.shows] hides),
     * newest first: a town's or a year's page, the map's photos in view. Read from the database (step 6c); without it,
     * from the library's items.
     */
    suspend fun scopeItems(scope: ExploreScope, state: StoriesState = _state.value): List<MediaItem> {
        val pager = feed ?: return withContext(background) { everything().filter { scope.matches(it, state.places) } }
        return pager.readLibrary { table -> scopeItemsOf(table, state.places, scope, shownRow) }
    }

    /** The map's overview for [state]: from the database (step 6c), kept until its rows, the places or the sources shown change. */
    suspend fun mapOverview(state: StoriesState = _state.value): MapOverview {
        val pager = feed ?: return withContext(background) { mapOverviewOf(everything(), state.places, state.shownSources) }
        return exploreLock.withLock {
            val from = listOf(library.rowsWritten, state.places, state.shownSources)
            mapped?.takeIf { it.fits(from) }?.value ?: pager.readLibrary { table -> mapOverviewOf(table, state.places, state.shownSources) }
                .also { mapped = WorkedOut(from, it) }
        }
    }

    /**
     * The photos the map draws around [visible] at [zoom] for the years [from] to [to], and how many are in view, from
     * [overview] (made by [mapOverview] for the same [state]).
     */
    suspend fun mapArea(overview: MapOverview, visible: GeoArea, zoom: Double, from: Int, to: Int, state: StoriesState = _state.value): MapArea {
        val pager = feed ?: return withContext(background) { mapAreaOf(overview, visible, zoom, from, to) }
        return pager.readLibrary { table -> mapAreaOf(table, overview, state.shownSources, visible, zoom, from, to) }
    }

    // MARK: Items by id from the database

    /** Reads the notes' photos and [wanted] again: the library changed (statuses, a photo deleted or hidden). */
    private suspend fun readAgain() {
        val ids = notes.keys + wanted
        val read = library.items(ids)
        // Photos asked for or noted while this read went keep what was read for them.
        readItems = readItems.filterKeys { it !in ids && (it in wanted || it in notes) } + read
        missing = wanted.filterTo(HashSet()) { it !in readItems }
    }

    /**
     * A screen shows the photos [ids] by id (the viewer's list, similar photos, a crop): those [StoriesState.itemsById]
     * lacks are read from the database and published; those the library doesn't have go to [StoriesState.missing].
     * They are kept current with the library from then on. Returns once they are in the state. Without Moments from the
     * database, [StoriesState.itemsById] holds every photo already and nothing is read.
     */
    suspend fun want(ids: Collection<String>) {
        if (feed == null || ids.isEmpty()) return
        ids.forEach { id ->
            wanted.remove(id)
            wanted += id
        }
        while (wanted.size > MAX_WANTED) {
            val oldest = wanted.first()
            wanted.remove(oldest)
            if (oldest !in notes) readItems = readItems - oldest
        }
        val known = _state.value.itemsById
        val toRead = ids.filter { it !in known && it !in readItems }
        if (toRead.isEmpty()) return
        val read = library.items(toRead)
        readItems = readItems + read
        missing = missing - read.keys + toRead.filter { it !in read && it in wanted }
        val state = _state.value
        _state.value = state.copy(itemsById = state.itemsById + read, missing = missing)
    }

    /**
     * Shows the photo [itemId] in Moments: reads Moments' rows down to its day, looked up in the database (the photo
     * may be in no row read yet).
     */
    fun loadMomentsToItem(itemId: String) {
        val pager = feed ?: return
        scope.launch {
            val day = pager.daysOf(listOf(itemId)).firstOrNull() ?: return@launch
            loadMomentsTo(day) {}
        }
    }

    /** Reads the next page of Moments' rows, if there is one (the screen scrolled near the end). */
    fun loadMoreMoments() {
        val pager = feed ?: return
        scope.launch {
            val more = pager.loadMore(feedContext(), revealed, shownRow) ?: return@launch
            showPaged(more)
        }
    }

    /**
     * Reads Moments' rows down to the row of [day] (going to a date, showing a photo in Moments); calls [onLoaded]
     * with that row's key once it is in the feed, null when no row has that day.
     */
    fun loadMomentsTo(day: LocalDay, onLoaded: (String?) -> Unit) {
        val pager = feed ?: return onLoaded(null)
        // A later date cancels one still being read: dragging the ruler's dial asks for every month it passes, and read
        // one after another the last one came long after the finger stopped (Oct 26, 2026). The one cancelled never
        // calls back; the screens only follow the last one anyway.
        jumpJob?.cancel()
        jumpJob = scope.launch {
            val key = Measure.time({ "Moments: went to $day" }) {
                val jump = pager.loadTo(day, feedContext(), revealed, shownRow)
                jump.feed?.let { showPaged(it) }
                jump.key
            }
            onLoaded(key)
        }
    }

    private var jumpJob: Job? = null

    /**
     * Publishes the rows read [more], built off the main thread like a rebuild. A rebuild already going, made from the
     * rows read before, is not shown after it (it would take the new rows away again, and a jump to them with it).
     */
    private suspend fun showPaged(more: MomentsFeed) {
        pagedFeed = more
        val startedAt = ++version
        val inputs = inputs()
        val built = withContext(background) { inputs.build() }
        if (version == startedAt) show(built)
    }

    /**
     * Shows [source]'s hidden photos on the Moments row [sectionKey] ([FeedSection.key]), in a band under its
     * photos: its "+6 WhatsApp" chip. The setting doesn't change, and nothing is saved.
     */
    fun showOnRow(sectionKey: String, source: MediaSource) {
        revealed = revealed + FeedReveal(sectionKey, source)
        if (feed != null) outsideChanges.value++
        publish()
    }

    /** Hides again what [showOnRow] showed. */
    fun hideOnRow(sectionKey: String, source: MediaSource) {
        revealed = revealed - FeedReveal(sectionKey, source)
        if (feed != null) outsideChanges.value++
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
        // From the state's index: searching the whole library, on the main thread, for each swipe set took 14% of it
        // while scrolling. A photo the state doesn't know (it is rebuilt in the background, or with Moments from the
        // database, in no row read) is looked up in the library instead: from the database, then changed.
        val known = _state.value.itemsById
        val items = itemIds.mapNotNull { known[it] }.takeIf { it.size == itemIds.size }
        when {
            items != null -> changeNotesOf(items, rebuild, transform)
            feed != null -> scope.launch {
                val read = library.items(itemIds)
                changeNotesOf(itemIds.mapNotNull { known[it] ?: read[it] }, rebuild, transform)
            }
            else -> changeNotesOf(itemsById(itemIds), rebuild, transform)
        }
    }

    private fun changeNotesOf(items: List<MediaItem>, rebuild: Boolean, transform: (PhotoNote) -> PhotoNote) {
        val time = now()
        var changedAny = false
        // With Moments from the database, a photo given a note is one the screens use from now on (storyline, tags).
        if (feed != null) readItems = readItems + items.associateBy { it.id }
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
        // From the database: the days of the photos whose notes changed are made again (a crop, a set).
        if (changedAny && rebuild && feed != null) {
            val days = items.mapTo(HashSet()) { it.day }
            scope.launch {
                feed.markDays(days)
                outsideChanges.value++
            }
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
        // Home may have been worked out while this state was built.
        _state.value = if (feed != null && built.state.home != home) built.state.copy(home = home) else built.state
        if (feed != null) {
            readFromDatabase(feed)
        } else {
            similarity?.request(built.toCompare)
            places?.request(built.toLocate)
        }
    }

    /** Everything the state is built from, as it is now. */
    private fun inputs() = Inputs(
        libraryState.value, notes, error, settingsState.value, looks, places?.places?.value ?: Places.NONE, revealed,
        // From the database: its first page read. Else the whole library merged. Either way with the caches read.
        ready = cachesLoaded && (if (feed != null) pagedFeed != null else libraryState.value.complete),
        paged = if (feed != null) pagedFeed ?: MomentsFeed() else null,
        items = if (feed != null) readItems else null,
        missing = missing,
        home = if (feed != null) home else null,
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
        val paged: MomentsFeed?,
        val items: Map<String, MediaItem>?,
        val missing: Set<String>,
        val home: ExploreHome?,
    ) {
        fun build(): Built {
            val state = buildState(
                library, notes, error, settings.grouping, looks,
                shown = settings.shownSources,
                places = places,
                revealed = revealed,
                ready = ready,
                paged = paged,
                items = items,
                missing = missing,
                home = home,
            )
            // From the database the readers are fed a batch at a time instead (readFromDatabase, step 6b).
            if (paged != null) return Built(state, emptyList(), emptyList())
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

    /** A state, and without the database the photos to fingerprint and to locate for it. */
    private class Built(val state: StoriesState, val toCompare: List<MediaItem>, val toLocate: List<MediaItem>)

    /** [ids]' items from the library's whole list: without Moments from the database (tests). */
    private fun itemsById(ids: List<String>): List<MediaItem> {
        val wanted = ids.toSet()
        return libraryState.value.days.flatMap { day -> day.items.filter { it.id in wanted } }
    }
}
