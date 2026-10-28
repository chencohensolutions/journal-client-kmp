package com.chencohensolution.journal.library

import com.chencohensolution.journal.backup.BackupActivity
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.server.KeptOriginals
import com.chencohensolution.journal.server.PrivateFile
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerMedia
import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.PrivateMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** The photos and videos taken on one day, newest first. */
data class DayGroup(val day: LocalDay, val items: List<MediaItem>)

/** The library's counts the screens show (LibraryState), as the database counts them (step 6d). */
data class LibraryTotals(
    /** Every item of the library. */
    val items: Int,
    /** The items backup takes that aren't backed up (see BackupState.status), and their bytes (where known). */
    val notBackedUp: Int,
    val notBackedUpBytes: Long,
    /** [items] and [notBackedUp] per source as the database spells it in ids (imported ones apart), for Show in the app. */
    val bySource: Map<MediaSource, SourceTotal> = emptyMap(),
)

/** One source's items, and of them the phone's that backup takes and aren't backed up, as [LibraryTotals] counts them. */
data class SourceTotal(val items: Int, val notBackedUp: Int) {
    operator fun plus(other: SourceTotal) = SourceTotal(items + other.items, notBackedUp + other.notBackedUp)
}

data class LibraryState(
    val access: PhotoAccess = PhotoAccess.NOT_DETERMINED,
    /** True while the library is being read. */
    val loading: Boolean = false,
    /**
     * True once the library is whole: without a database once [days] is merged, with one once its first refresh made
     * every row (the background readers wait for it, so they don't slow the start down).
     */
    val complete: Boolean = false,
    /**
     * Every item, grouped by day, newest day first: only without a database (tests, previews, the UI lab). With one it
     * stays empty, and the screens read what they show from the database (docs/plans/library-on-disk.md, step 6f).
     */
    val days: List<DayGroup> = emptyList(),
    /**
     * With a database, goes up each time what the screens show of the library changed (rows written, scores, kept
     * originals, backup statuses), so those reading it from the database read again; [days] tells that without one.
     */
    val version: Int = 0,
    val loadError: String? = null,
    /** The sources backup takes, whose photos count as not backed up; null (backup off) counts every photo. */
    val backupSources: Set<MediaSource>? = null,
    /**
     * The server's sound recordings the user shows, newest first: never in [days], so nothing that expects a picture
     * meets one; only Moments and a recording's page show them. Private ones are left out like private photos.
     */
    val sounds: List<SoundRecording> = emptyList(),
    /**
     * [itemCount], [notBackedUpCount] and [notBackedUpBytes] as the database counted them over the whole library
     * (docs/plans/library-on-disk.md, step 6d); null without a database, where they are counted over [days].
     */
    val totals: LibraryTotals? = null,
) {
    /** Every item of the library. */
    val itemCount: Int = totals?.items ?: days.sumOf { it.items.size }

    // An empty file (an app's 0-byte placeholder) never waits: backup leaves it out (BackupEngine's queue).
    private fun waitsForBackup(item: MediaItem) =
        item.backup != BackupStatus.BackedUp && (item.sizeBytes ?: 0) > 0 && (backupSources == null || item.source in backupSources)

    /** Items not on the server yet (anything but [BackupStatus.BackedUp]) that backup takes; empty files aren't. */
    val notBackedUpCount: Int = totals?.notBackedUp ?: days.sumOf { day -> day.items.count(::waitsForBackup) }

    /** Size of [notBackedUpCount]'s items; counts only items whose size is known. */
    val notBackedUpBytes: Long = totals?.notBackedUpBytes ?: days.sumOf { day -> day.items.filter(::waitsForBackup).sumOf { it.sizeBytes ?: 0L } }

    /**
     * Items per source and those of them not backed up, the same counts as [itemCount] and [notBackedUpCount] split by
     * source (imported ones apart): Show in the app's lines.
     */
    fun sourceTotals(): Map<MediaSource, SourceTotal> = totals?.bySource ?: buildMap {
        days.forEach { day ->
            day.items.forEach { item ->
                val total = SourceTotal(1, if (waitsForBackup(item)) 1 else 0)
                put(item.source, get(item.source)?.plus(total) ?: total)
            }
        }
    }

    /** Whether the library has nothing to show (yet). */
    val isEmpty: Boolean get() = itemCount == 0

    /** Items that are in no story. Stories don't exist yet, so that's every item. */
    val unsortedCount: Int get() = itemCount
}

/**
 * Shared logic of the library screens: tracks photo access, reads the device library, adds the photos that are only on
 * the server, and groups everything by day. Platform UIs ask for the permission themselves and then call
 * [onAccessRequested].
 */
class LibraryController internal constructor(
    private val library: MediaLibrary,
    private val settings: JournalSettings,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    /** Each photo's backup status comes from here; without it everything is [BackupStatus.NotBackedUp]. */
    private val backup: StateFlow<BackupState>? = null,
    /** What the server holds; its photos that aren't on this device are shown too. */
    private val server: ServerLibrary? = null,
    /** Originals downloaded from the server and kept; such photos are shown from them. */
    private val originals: KeptOriginals? = null,
    /** "Show private media" (SettingsController.privateMedia); without it, as saved when the controller was made. */
    private val privateMedia: StateFlow<PrivateMedia>? = null,
    /** Aesthetic scores this phone keeps (On this phone), by sha256: a set's best photo, covers, Best first. */
    private val scores: StateFlow<Map<String, Float>>? = null,
    /**
     * The library kept in the database (docs/plans/library-on-disk.md, phase 4): each change writes, and patches into
     * [state], only the rows it changes. Without it (tests) everything is merged in memory ([mergeWithServer]).
     */
    private val table: LibraryTable? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loadJob: Job? = null

    /**
     * Where merging with the server runs: off the main thread, as it reads every photo (a toggle such as Show private
     * media froze the screen for a second with a large library). Tests pass their own dispatcher and get it for this
     * too, so waiting for it is deterministic.
     */
    private val background: CoroutineDispatcher = if (dispatcher === Dispatchers.Main) Dispatchers.Default else dispatcher

    private val savedPrivateMedia = settings.privateMedia

    /** The device library as read. */
    private var readItems: List<MediaItem> = emptyList()

    /** Device and server-only items grouped by day, before backup statuses; rebuilt when one of its inputs changes. */
    private var merged: List<DayGroup> = emptyList()
    private var mergedFrom: List<Any?>? = null

    // With a table, loading until its first rows are read (see libraryReady).
    private val _state = MutableStateFlow(LibraryState(access = currentAccess(), loading = table != null))
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    // Photos taken, received or removed while the app is open: read again once they settle (a burst is one read).
    private var changeJob: Job? = null
    private val stopWatching = library.watch {
        scope.launch {
            changeJob?.cancel()
            changeJob = scope.launch {
                delay(CHANGE_SETTLE_MILLIS)
                val access = currentAccess()
                if (access == PhotoAccess.FULL || access == PhotoAccess.LIMITED) load()
            }
        }
    }

    init {
        backup?.let { flow ->
            var wasRunning = false
            scope.launch {
                flow.collect { backupState ->
                    publish()
                    // A run that uploaded something: the server's list has new photos (also from other devices).
                    val running = backupState.activity is BackupActivity.Running
                    if (wasRunning && !running) server?.refresh()
                    wasRunning = running
                }
            }
        }
        server?.let { serverLibrary -> scope.launch { serverLibrary.mediaVersion.collect { serverChanged(serverLibrary) } } }
        server?.let { serverLibrary -> scope.launch { serverLibrary.hidden.collect { publish() } } }
        originals?.let { kept -> scope.launch { kept.load(); kept.kept.collect { publish() } } }
        // The server's list keeps the private files only while they are shown (ServerLibrary.showPrivate).
        server?.let { serverLibrary -> scope.launch { serverLibrary.privateFiles.collect { publish() } } }
        scope.launch {
            (privateMedia ?: MutableStateFlow(savedPrivateMedia)).collect {
                server?.showPrivate(it.show)
                publish()
            }
        }
        scores?.let { flow -> scope.launch { flow.collect { publish() } } }
        // The phone's library in the database keeps out what the user hides (StoredMediaLibrary.leaveOut).
        (library as? LeavesOut)?.let { stored ->
            scope.launch {
                // Not before the server's list is read from the database: what it hides isn't known until then.
                server?.loaded?.first { it }
                combine(
                    server?.hidden ?: MutableStateFlow(emptySet()),
                    server?.privateFiles ?: MutableStateFlow(emptyList()),
                    privateMedia ?: MutableStateFlow(savedPrivateMedia),
                ) { hidden, privateFiles, private -> Triple(hidden, privateFiles, private) }
                    .distinctUntilChanged()
                    .collect { (hidden, privateFiles, private) ->
                        val changed = stored.leaveOut(hiddenOnPhone(backup?.value?.entries.orEmpty(), hidden, privateFiles, private))
                        val access = currentAccess()
                        if (changed && phoneRead && (access == PhotoAccess.FULL || access == PhotoAccess.LIMITED)) load()
                    }
            }
        }
    }

    /**
     * Puts the current device items, server-only items and backup statuses into [state]. When the items or the server's
     * list changed, they are merged again in the background ([background]) and shown once done; meanwhile the last
     * merge stays on screen. One merge runs at a time: a merge can't be stopped part way (it never suspends), so a
     * change during one is merged once it ends, from whatever is newest then. Backing up many small files changes the
     * ledger every second; starting a merge of the whole library for each ran out of memory (Oct 25, 2026).
     */
    private fun publish() {
        if (table != null) return publishFromTable(table)
        val readItems = readItems
        val backupState = backup?.value
        val serverList = serverList
        val hiddenOnServer = server?.hidden?.value.orEmpty()
        val privateFiles = server?.privateFiles?.value.orEmpty()
        val kept = originals?.kept?.value.orEmpty()
        val private = privateMedia?.value ?: savedPrivateMedia
        val scored = scores?.value.orEmpty()
        val inputs = listOf(readItems, backupState?.entries, serverList, hiddenOnServer, kept, private, scored, privateFiles)
        fun same(other: List<Any?>?) = other != null && other.indices.all { other[it] === inputs[it] }
        if (!same(mergedFrom)) {
            // Already being merged from these: it shows them when done.
            if (same(mergingFrom)) return
            if (mergeJob?.isActive == true) {
                mergeAgain = true
                return
            }
            mergingFrom = inputs
            mergeJob = scope.launch {
                merges++
                val (days, soundList) = withContext(background) {
                    Measure.time({ "Merge of ${readItems.size} + ${serverList.orEmpty().size} items" }) {
                        val (soundMedia, serverMedia) = serverList.orEmpty().partition { it.isAudio }
                        groupByDay(
                            mergeWithServer(readItems, backupState?.entries.orEmpty(), serverMedia, kept.mapValues { it.value.toString() }, private, hiddenOnServer, scored, privateFiles),
                        ) to soundMedia.filterNot { private.hides(it.isPrivate) }.map { it.toSoundRecording() }.sortedByDescending { it.startMillis }
                    }
                }
                merged = days
                sounds = soundList
                _state.update { it.copy(complete = true) }
                mergedFrom = inputs
                mergingFrom = null
                showStatuses()
                if (mergeAgain) {
                    mergeAgain = false
                    // This merge is done: the next one may start (this job still counts as active until it returns).
                    mergeJob = null
                    publish()
                }
            }
            return
        }
        showStatuses()
    }

    /**
     * The server's list as last read, whole: only without [table] (tests), where it is merged in memory. With a table it
     * stays in the database (docs/plans/library-on-disk.md, step 6e), and only its sound recordings are read.
     */
    private var serverList: List<ServerMedia>? = if (server != null) emptyList() else null

    /** A new object each time the server's list changed, for [publishFromTable] to tell by identity. */
    private var serverSeen: Any? = null

    /**
     * The server's list in the database changed ([ServerLibrary.mediaVersion]): without a table it is read whole for the
     * merge, with one only its sound recordings ([LibraryState.sounds]) are read, and the rows are made again.
     */
    private suspend fun serverChanged(serverLibrary: ServerLibrary) {
        if (table == null) {
            serverList = serverLibrary.all()
        } else {
            val private = privateMedia?.value ?: savedPrivateMedia
            sounds = serverLibrary.sounds().filter { !private.hides(it.isPrivate) }.map { it.toSoundRecording() }.sortedByDescending { it.startMillis }
        }
        serverSeen = Any()
        publish()
    }

    // MARK: The library from the database

    /** Database work: off the main thread; tests pass their own dispatcher, so waiting for it is deterministic. */
    private val io: CoroutineDispatcher = if (dispatcher === Dispatchers.Main) Dispatchers.IO else dispatcher

    /**
     * With a table, a new object each time the phone's library was read into the database ([KeepsItems.update]): the
     * items themselves stay there, never in memory (step 6f).
     */
    private var phoneSeen: Any? = null

    /** Whether the phone's library was read since the controller was made (with a table; without one, [readItems]). */
    private val phoneRead: Boolean get() = phoneSeen != null || readItems.isNotEmpty()

    /** What the rows were last made from, by identity (see [publishFromTable]). */
    private var tableFrom: List<Any?>? = null
    private var shownFrom: List<Any?>? = null
    private var entriesSeen: Map<String, LedgerEntry> = emptyMap()

    private var refreshJob: Job? = null
    private var refreshAgain = false
    private var keptRead = false

    /** Whether the library has rows to show: at once without a table, else once it is counted and has rows or is made. */
    private val libraryReady: Boolean get() = table == null || ready
    private var ready = false

    /** With a table, whether its first refresh is done: every row is made. */
    private var made = false

    /**
     * [publish] with [table]: the rows are made again in the database when the phone's library, the server's list or
     * what is hidden changed, or a ledger change touches one of them (a backup of calls doesn't). Nothing of the rows is
     * kept here: [LibraryState.version] tells the screens to read again. Scores and kept originals change only what is
     * shown ([shownRow]).
     */
    private fun publishFromTable(table: LibraryTable) {
        val backupState = backup?.value
        val private = privateMedia?.value ?: savedPrivateMedia
        val inputs = listOf(phoneSeen, serverSeen, server?.hidden?.value, server?.privateFiles?.value, private)
        if (tableFrom == null || tableFrom!!.indices.any { tableFrom!![it] !== inputs[it] }) {
            tableFrom = inputs
            refreshTable(table)
        }
        // The ledger changed: its triggers noted which items that can touch (a call backup reads touches none).
        val entries = backupState?.entries.orEmpty()
        if (entries !== entriesSeen) {
            // Backup's counts change only with the entries of the library's own items (see recount).
            entries.forEach { (id, entry) -> if (entriesSeen[id] != entry) { ledgerChanged += id; entriesChanged += id } }
            entriesSeen.keys.forEach { if (it !in entries) { ledgerChanged += it; entriesChanged += it } }
            entriesSeen = entries
            refreshTable(table)
        }
        val overlay = listOf(originals?.kept?.value, scores?.value)
        if (shownFrom == null || shownFrom!!.indices.any { shownFrom!![it] !== overlay[it] }) shownFrom = overlay
        showStatuses()
    }

    /** Makes the rows again in the database, one refresh at a time. */
    private fun refreshTable(table: LibraryTable) {
        if (refreshJob?.isActive == true) {
            refreshAgain = true
            return
        }
        refreshJob = scope.launch {
            if (!keptRead) {
                // What the database kept since the last start is shown at once: the screens read it themselves.
                keptRead = true
                if (withContext(io) { table.hasRows() }) {
                    rowsWritten++
                    rowsChanged++
                    showStatuses()
                }
            }
            do {
                refreshAgain = false
                val update = withContext(io) { table.update() }
                if (update.isEmpty) continue
                // Only refreshes that changed rows count: one with nothing noted is a single quick query.
                merges++
                Measure.log?.invoke("Library: ${update.changed} rows changed, ${update.removed} gone")
                rowsWritten++
                if (update.moved > 0 || update.removed > 0) rowsChanged++
                showStatuses()
            } while (refreshAgain)
            // A new database is made whole by its first refresh: every row is in now.
            made = true
            if (!_state.value.complete) _state.update { it.copy(complete = true) }
            if (countJob?.isActive != true && _state.value.totals != null) showReady()
        }
    }

    /** The library has rows to show (or is known to be empty): the spinner goes, unless the phone's library is read. */
    private fun showReady() {
        if (ready) return
        ready = true
        _state.update { it.copy(loading = loadJob?.isActive == true) }
    }

    /**
     * A row of the library database as the screens show it: with this phone's score and kept original ([shown]) and its
     * backup status now. For what reads the database itself (Moments' pages: FeedPager). Safe from any thread.
     */
    internal fun shownRow(row: LibraryRow): MediaItem {
        val item = shown(row, originals?.kept?.value.orEmpty().mapValues { it.value.toString() }, scores?.value.orEmpty())
        val backupState = backup?.value ?: return item
        if (!item.isOnDevice) return item
        val status = backupState.status(item)
        return if (status == item.backup) item else item.copy(backup = status)
    }

    /**
     * The items of [ids] that are in the library, as shown, by id: read from the database (docs/plans/library-on-disk.md,
     * step 6a), so a screen never needs the whole list for a few photos. Without a table, from [state].
     */
    suspend fun items(ids: Collection<String>): Map<String, MediaItem> {
        if (ids.isEmpty()) return emptyMap()
        val table = table ?: return ids.toSet().let { wanted ->
            _state.value.days.asSequence().flatMap { it.items }.filter { it.id in wanted }.associateBy { it.id }
        }
        return withContext(io) { table.byIds(ids).mapValues { shownRow(it.value) } }
    }

    /**
     * The library's item for each of [sha256s] (the server's answers: a search, a person's photos, similar photos), as
     * [com.chencohensolution.journal.search.libraryResolver] finds them in the whole list, read from the database;
     * sha256s the library doesn't show are left out. Without a table, through that lookup over [state].
     */
    internal suspend fun itemsOfShas(sha256s: Collection<String>): Map<String, MediaItem> {
        if (sha256s.isEmpty()) return emptyMap()
        val table = table ?: return com.chencohensolution.journal.search.libraryResolver(_state.value, serverList.orEmpty()).let { resolve ->
            sha256s.distinct().mapNotNull { sha -> resolve(listOf(sha)).firstOrNull()?.let { sha to it } }.toMap()
        }
        return withContext(io) { table.bySha(sha256s).mapValues { shownRow(it.value) } }
    }

    /**
     * The score a row with the server file [serverSha] and the server's [aesthetic] is shown with: this phone's for that
     * file first, as [shownRow] lays it over. For what reads many rows a few numbers each (Explore's covers, step 6c).
     */
    internal fun shownScore(serverSha: String?, aesthetic: Float?): Float? = serverSha?.let { scores?.value?.get(it) } ?: aesthetic

    /** The scores [shownScore] lays over, by identity: what Explore's summaries were worked out with. */
    internal val scoresShown: Any? get() = scores?.value

    /**
     * The box around the library's photos in [town] (of every source), by where they were taken or the place found for
     * them ([places]): what search sends for a town. From the database's place squares (step 6c); without a table, from
     * [state]. Null when none of them has a place.
     */
    suspend fun townArea(town: String, places: com.chencohensolution.journal.places.Places): com.chencohensolution.journal.stories.GeoArea? {
        val table = table ?: return withContext(background) {
            val points = _state.value.days.asSequence().flatMap { it.items }
                .mapNotNull { item -> places.location(item)?.takeIf { places.name(it)?.town == town } }
                .toList()
            if (points.isEmpty()) {
                null
            } else {
                com.chencohensolution.journal.stories.GeoArea(points.minOf { it.latitude }, points.minOf { it.longitude }, points.maxOf { it.latitude }, points.maxOf { it.longitude })
            }
        }
        return withContext(io) { com.chencohensolution.journal.stories.townArea(table, places, town) }
    }

    /** The library's items of [sha256s] in their order, each once (see [itemsOfShas]). */
    suspend fun itemsBySha(sha256s: List<String>): List<MediaItem> = itemsOfShas(sha256s).let { found ->
        sha256s.mapNotNull { found[it] }.distinctBy { it.id }
    }

    // MARK: Counts from the database (step 6d)

    /** Ids whose ledger entry changed since the counts were last made. */
    private var ledgerChanged = HashSet<String>()

    /** The database's counts per source and what they were made at (rows written; see [recount]). */
    private var sourceCounts: List<SourceCounts>? = null
    private var sourceCountsAt = -1
    private var countedKey: List<Any?>? = null
    private var countJob: Job? = null
    private var countAgain = false

    /** Counts of the whole library in the database so far, for the tests that keep them few. */
    internal var recounts = 0
        private set

    /**
     * With a table, counts the library and what backup hasn't taken in the database ([LibraryState.totals]), so no
     * screen needs the whole list for them: again when rows were written, a ledger entry of one of the library's items
     * changed (a backup of calls changes none), the files on their way or the sources backup takes changed. One count
     * runs at a time; a change meanwhile counts again once it ends.
     */
    private fun recount() {
        val table = table ?: return
        val backupState = backup?.value
        val key = listOf(rowsWritten, backupState?.countedSources, uploadingIds(backupState), backupState == null)
        if (key == countedKey && ledgerChanged.isEmpty()) return
        if (countJob?.isActive == true) {
            countAgain = true
            return
        }
        countJob = scope.launch {
            do {
                countAgain = false
                val state = backup?.value
                val uploading = uploadingIds(state)
                val written = rowsWritten
                countedKey = listOf(written, state?.countedSources, uploading, state == null)
                val changed = ledgerChanged.also { ledgerChanged = HashSet() }
                val known = sourceCounts.takeIf { sourceCountsAt == written }
                val (counts, again) = withContext(io) {
                    val counts = if (known != null && (changed.isEmpty() || !table.hasAny(changed))) known else Measure.time({ "Library counted" }) { table.backupCounts() }.also { recounts++ }
                    counts to if (uploading.isEmpty()) emptyList() else table.backedUpAmong(uploading)
                }
                sourceCounts = counts
                sourceCountsAt = written
                val totals = libraryTotals(counts, again, state)
                if (totals != _state.value.totals) {
                    Measure.log?.invoke("Library: ${totals.items} items, ${totals.notBackedUp} not backed up (${totals.notBackedUpBytes} bytes)")
                    _state.update { it.copy(totals = totals) }
                }
            } while (countAgain)
            // Counted: the spinner can go once there is something to show or every row is made.
            if (made || (_state.value.totals?.items ?: 0) > 0) showReady()
        }
    }

    /** Puts [merged] into [state] with each photo's backup status as it is now. */
    private fun showStatuses() {
        recount()
        if (table != null) return showTableStatuses(table)
        val backupState = backup?.value
        // Statuses change only when a file finishes, the queue changes or the uploading one's ring moves a step: the
        // transfer rate and byte counts of a run don't rebuild the library (and everything shown from it). Several files
        // upload at once; each one's ring counts.
        // The sound recordings are read apart from the rows (step 6e), so a new list of them counts too.
        val running = (backupState?.activity as? BackupActivity.Running)?.progress
        val statusKey = listOf(
            merged, backupState?.entries, backupState?.queued, sounds, backupState?.countedSources,
            running?.currentId, running?.let { (it.currentFraction * STATUS_STEPS).toInt() },
            running?.uploading?.mapValues { (it.value * STATUS_STEPS).toInt() },
        )
        if (publishedKey != null && publishedKey!!.indices.all { i ->
                val a = publishedKey!![i]
                val b = statusKey[i]
                if (i <= 3) a === b else a == b
            }
        ) return
        publishedKey = statusKey
        val days = if (backupState == null) merged else merged.map { group ->
            var changed = false
            val items = group.items.map { item ->
                if (!item.isOnDevice) return@map item
                val status = backupState.status(item)
                // Unchanged photos stay the same object, so screens skip them; so do days with none changed.
                if (status == item.backup) item else item.copy(backup = status).also { changed = true }
            }
            if (changed) group.copy(items = items) else group
        }
        _state.update { it.copy(days = days, backupSources = backupState?.countedSources, sounds = sounds) }
    }

    /** What [showTableStatuses] last published from. */
    private var tableShown: List<Any?>? = null
    private var queuedShown: Set<String> = emptySet()
    private var stepsShown: Map<String, Int> = emptyMap()

    /** Ids whose ledger entry changed since [showTableStatuses] last looked (see [publishFromTable]). */
    private var entriesChanged = HashSet<String>()

    /**
     * With a table: a new [LibraryState.version] when what the screens read from the database looks different now: rows
     * written, scores or kept originals, the sound recordings or the sources backup takes changed (at once), or the
     * status of a library item may have (a ledger entry, the queue, a file on its way or its ring moving a step), which
     * the database is asked first, so a backup of calls changes nothing shown.
     */
    private fun showTableStatuses(table: LibraryTable) {
        val backupState = backup?.value
        val running = (backupState?.activity as? BackupActivity.Running)?.progress
        val fixed = listOf(sounds, shownFrom?.getOrNull(0), shownFrom?.getOrNull(1), backupState?.countedSources, rowsWritten)
        val queued = backupState?.queued.orEmpty()
        val steps = HashMap<String, Int>().apply {
            running?.uploading?.forEach { (id, fraction) -> put(id, (fraction * STATUS_STEPS).toInt()) }
            running?.currentId?.let { put(it, (running.currentFraction * STATUS_STEPS).toInt()) }
        }
        val before = tableShown
        val changedFixed = before == null || before.indices.any { i -> if (i <= 2) before[i] !== fixed[i] else before[i] != fixed[i] }
        val ids = HashSet<String>()
        if (!changedFixed) {
            ids += entriesChanged
            if (queued !== queuedShown) {
                queued.forEach { if (it !in queuedShown) ids += it }
                queuedShown.forEach { if (it !in queued) ids += it }
            }
            steps.forEach { (id, step) -> if (stepsShown[id] != step) ids += id }
            stepsShown.keys.forEach { if (it !in steps) ids += it }
        }
        tableShown = fixed
        queuedShown = queued
        stepsShown = steps
        entriesChanged = HashSet()
        if (changedFixed) {
            newVersion()
        } else if (ids.isNotEmpty()) {
            scope.launch { if (withContext(io) { table.hasAny(ids) }) newVersion() }
        }
    }

    private fun newVersion() {
        _state.update { it.copy(version = it.version + 1, backupSources = backup?.value?.countedSources, sounds = sounds) }
    }

    /** The sound recordings of the last [publish], built with [merged]. */
    private var sounds: List<SoundRecording> = emptyList()

    /** The merge running in the background, and what it merges from (see [publish]). */
    private var mergeJob: Job? = null
    private var mergingFrom: List<Any?>? = null

    /** Something changed while a merge ran: merge again when it ends. */
    private var mergeAgain = false

    /** Merges started so far, for the tests that keep them few (docs/plans/library-on-disk.md). */
    internal var merges = 0
        private set

    /**
     * With a table, counts the changes that can give the background readers (fingerprints, places) work or move Explore's
     * home: rows new to the library or gone, or whose location, source, type or place on the phone changed (a backup's
     * new sha256s don't). The readers start again from the newest when it moves (StoriesController, step 6b).
     */
    internal var rowsChanged = 0
        private set

    /** With a table, counts every change to its rows (a backup's new sha256s too): what Explore's summaries go by (step 6c). */
    internal var rowsWritten = 0
        private set

    /** What [state] was last built from (see [publish]). */
    private var publishedKey: List<Any?>? = null
    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: LibraryState get() = _state.value

    /** Whether the app has asked for photo access before; on Android a later denial can only be undone in Settings. */
    val accessRequested: Boolean get() = settings.photoAccessRequested

    /** Re-checks the permission and re-reads the library; call when the app comes to the foreground. */
    fun refresh() {
        val access = currentAccess()
        // Measuring: the phone's photos once dropped to none for a while right after an install (Oct 25, 2026).
        if (access != _state.value.access) Measure.log?.invoke("Photo access: ${_state.value.access} to $access")
        _state.update { it.copy(access = access) }
        server?.refresh()
        if (access == PhotoAccess.FULL || access == PhotoAccess.LIMITED) {
            load()
        } else {
            // Without photo access only the server's photos are shown.
            loadJob?.cancel()
            readItems = emptyList()
            if (table != null) phoneSeen = Any()
            _state.update { it.copy(loading = !libraryReady, loadError = null) }
            publish()
        }
    }

    /** Call after the system permission prompt closed, whatever the answer. */
    fun onAccessRequested() {
        settings.photoAccessRequested = true
        refresh()
    }

    /** "Not now" on the photo access screen: don't show it again on launch. */
    fun skipAccess() {
        settings.photoAccessRequested = true
        _state.update { it.copy(access = currentAccess()) }
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (LibraryState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() {
        stopWatching()
        scope.cancel()
    }

    private fun currentAccess(): PhotoAccess {
        val access = library.access()
        // Granted at some point, so a later denial is the user's answer (revoked in Settings).
        if (access == PhotoAccess.FULL || access == PhotoAccess.LIMITED) settings.photoAccessRequested = true
        // Android reports "never asked" as denied.
        return if (access == PhotoAccess.DENIED && !settings.photoAccessRequested) PhotoAccess.NOT_DETERMINED else access
    }

    private fun load() {
        loadJob?.cancel()
        _state.update { it.copy(loading = true, loadError = null) }
        loadJob = scope.launch {
            try {
                val kept = library as? KeepsItems
                if (table != null && kept != null) {
                    kept.update()
                    phoneSeen = Any()
                } else {
                    readItems = library.loadItems()
                }
                _state.update { it.copy(loading = !libraryReady) }
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = !libraryReady, loadError = e.describe()) }
            }
        }
    }
}

/**
 * The device's items, with their sha256 where backup read it, followed by the server's photos that aren't on this
 * device. A server photo is on the device when a device photo has its sha256, or, before backup read it, the same file
 * name and size (a new device whose photos are already on the server would otherwise show each one twice). A server
 * photo that was on this device (deleted from it since) keeps its device id, so its stories and note still find it;
 * [kept] are originals the app downloaded, by sha256. What [privateMedia] hides is left out, the device's copies too
 * (matched the same way), so nothing of it is shown or fetched; what is shown of a private file is marked [MediaItem.isPrivate].
 * The device's copies of what the server hides ([hiddenOnServer], by sha256: ServerLibrary.hidden) are left out too; a
 * device photo backup hasn't read yet can't be matched to them, and shows until it has.
 */
internal fun mergeWithServer(
    device: List<MediaItem>,
    entries: Map<String, LedgerEntry>,
    server: List<ServerMedia>,
    kept: Map<String, String> = emptyMap(),
    privateMedia: PrivateMedia = PrivateMedia(),
    hiddenOnServer: Set<String> = emptySet(),
    /** Aesthetic scores kept on this phone, by sha256; they go before the change feed's. */
    scores: Map<String, Float> = emptyMap(),
    /** Private files the server's list left out (ServerLibrary.privateFiles): hidden like those it lists as private. */
    privateFiles: List<PrivateFile> = emptyList(),
): List<MediaItem> {
    val items = device.map { item -> entries[item.id]?.takeIf { it.matches(item) }?.let { item.copy(sha256 = it.sha256) } ?: item }
        .filter { it.sha256 == null || it.sha256 !in hiddenOnServer }
    if (server.isEmpty() && privateFiles.isEmpty()) return items
    val shas = items.mapNotNullTo(HashSet()) { it.sha256 }
    val namesAndSizes = items.mapNotNullTo(HashSet()) { item -> item.fileName?.lowercase()?.let { it to item.sizeBytes } }
    val deviceIds = device.mapTo(HashSet()) { it.id }
    val formerIds = entries.values.filter { it.uploaded && it.id !in deviceIds }.associate { it.sha256 to it.id }
    val hidden = server.filter { privateMedia.hides(it.isPrivate) }
    val leftOut = if (privateMedia.hides(true)) privateFiles else emptyList()
    // A JPEG the server keeps as JPEG XL is the phone's copy of the JPEG XL: both sha256s name it.
    val hiddenShas = hidden.flatMapTo(HashSet()) { listOfNotNull(it.sha256, it.encodedSha256) }
        .apply { leftOut.flatMapTo(this) { listOfNotNull(it.sha256, it.encodedSha256) } }
    val hiddenNamesAndSizes = hidden.mapTo(HashSet<Pair<String?, Long?>>()) { it.fileName.lowercase() to it.size }
        .apply { leftOut.mapTo(this) { it.fileName.lowercase() to it.size } }
    val marked = server.filter { it.isPrivate }
    val markedShas = marked.flatMapTo(HashSet()) { listOfNotNull(it.sha256, it.encodedSha256) }
        .apply { privateFiles.flatMapTo(this) { listOfNotNull(it.sha256, it.encodedSha256) } }
    val markedNamesAndSizes = marked.mapTo(HashSet<Pair<String?, Long?>>()) { it.fileName.lowercase() to it.size }
        .apply { privateFiles.mapTo(this) { it.fileName.lowercase() to it.size } }
    fun MediaItem.matches(shas: Set<String>, namesAndSizes: Set<Pair<String?, Long?>>): Boolean {
        val sha = sha256
        return if (sha != null) sha in shas else (fileName?.lowercase() to sizeBytes) in namesAndSizes
    }
    // The server's aesthetic scores, for the device's own copies too.
    fun scoreOf(media: ServerMedia): Float? = scores[media.sha256] ?: media.aesthetic?.toFloat()
    val scored = server.filter { scoreOf(it) != null }
    val scoreBySha = scored.associate { it.sha256 to scoreOf(it)!! } +
        scored.mapNotNull { media -> media.encodedSha256?.let { it to scoreOf(media)!! } }
    val scoreByNameAndSize = scored.associate { (it.fileName.lowercase() to it.size) to scoreOf(it)!! }
    fun MediaItem.scored(): MediaItem {
        val score = sha256?.let(scoreBySha::get) ?: if (sha256 == null) scoreByNameAndSize[fileName?.lowercase() to sizeBytes] else null
        return if (score == null || score == aesthetic) this else copy(aesthetic = score)
    }
    val shown = items.mapNotNull { item ->
        when {
            item.matches(hiddenShas, hiddenNamesAndSizes) -> null
            item.matches(markedShas, markedNamesAndSizes) -> item.copy(isPrivate = true).scored()
            else -> item.scored()
        }
    }
    val serverOnly = server.filter {
        it.sha256 !in shas && it.encodedSha256 !in shas && (it.fileName.lowercase() to it.size) !in namesAndSizes && it.sha256 !in hiddenShas
    }
    return shown + serverOnly.map { media ->
        val formerId = formerIds[media.sha256] ?: media.encodedSha256?.let(formerIds::get)
        media.toMediaItem(id = formerId ?: (ServerMedia.SERVER_ID_PREFIX + media.sha256), keptOriginal = kept[media.sha256])
            .let { item -> scores[media.sha256]?.let { item.copy(aesthetic = it) } ?: item }
    }
}

/** The uploading photo's ring moves in this many steps (5%). */
private const val STATUS_STEPS = 20

/** How long the library must be quiet after a change before it is read again. */
private const val CHANGE_SETTLE_MILLIS = 1_000L

/** Groups [items] by day, newest day first and newest item first within a day. */
internal fun groupByDay(items: List<MediaItem>): List<DayGroup> =
    items.sortedByDescending { it.takenAtMillis }
        .groupBy { it.day }
        .map { (day, dayItems) -> DayGroup(day, dayItems) }
        .sortedByDescending { it.day }
