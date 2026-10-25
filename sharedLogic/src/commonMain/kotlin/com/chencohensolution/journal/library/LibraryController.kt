package com.chencohensolution.journal.library

import com.chencohensolution.journal.backup.BackupActivity
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.server.KeptOriginals
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerMedia
import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.PrivateMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

/** The photos and videos taken on one day, newest first. */
data class DayGroup(val day: LocalDay, val items: List<MediaItem>)

/** Filters of the All photos screen. */
enum class LibraryFilter { ALL, NOT_BACKED_UP, VIDEOS }

data class LibraryState(
    val access: PhotoAccess = PhotoAccess.NOT_DETERMINED,
    /** True while the library is being read. */
    val loading: Boolean = false,
    /** Every item, grouped by day, newest day first. */
    val days: List<DayGroup> = emptyList(),
    val loadError: String? = null,
    /** The sources backup takes, whose photos count as not backed up; null (backup off) counts every photo. */
    val backupSources: Set<MediaSource>? = null,
    /**
     * The server's sound recordings the user shows, newest first: never in [days], so nothing that expects a picture
     * meets one; only Moments and a recording's page show them. Private ones are left out like private photos.
     */
    val sounds: List<SoundRecording> = emptyList(),
) {
    val itemCount: Int = days.sumOf { it.items.size }

    private fun waitsForBackup(item: MediaItem) =
        item.backup != BackupStatus.BackedUp && (backupSources == null || item.source in backupSources)

    /** Items not on the server yet (anything but [BackupStatus.BackedUp]) that backup takes. */
    val notBackedUpCount: Int = days.sumOf { day -> day.items.count(::waitsForBackup) }

    /** Size of [notBackedUpCount]'s items; counts only items whose size is known. */
    val notBackedUpBytes: Long = days.sumOf { day -> day.items.filter(::waitsForBackup).sumOf { it.sizeBytes ?: 0L } }

    /** Items that are in no story. Stories don't exist yet, so that's every item. */
    val unsortedCount: Int get() = itemCount

    /** [days] narrowed to [filter]; days left empty are dropped. */
    fun days(filter: LibraryFilter): List<DayGroup> = when (filter) {
        LibraryFilter.ALL -> days
        LibraryFilter.NOT_BACKED_UP -> days.filterItems { it.backup != BackupStatus.BackedUp }
        LibraryFilter.VIDEOS -> days.filterItems { it.type == MediaType.VIDEO }
    }

    private fun List<DayGroup>.filterItems(keep: (MediaItem) -> Boolean): List<DayGroup> =
        mapNotNull { group -> group.items.filter(keep).takeIf { it.isNotEmpty() }?.let { DayGroup(group.day, it) } }
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

    private val _state = MutableStateFlow(LibraryState(access = currentAccess()))
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
        server?.let { serverLibrary -> scope.launch { serverLibrary.media.collect { publish() } } }
        server?.let { serverLibrary -> scope.launch { serverLibrary.hidden.collect { publish() } } }
        originals?.let { kept -> scope.launch { kept.load(); kept.kept.collect { publish() } } }
        privateMedia?.let { flow -> scope.launch { flow.collect { publish() } } }
        scores?.let { flow -> scope.launch { flow.collect { publish() } } }
    }

    /**
     * Puts the current device items, server-only items and backup statuses into [state]. When the items or the server's
     * list changed, they are merged again in the background ([background]) and shown once done; meanwhile the last
     * merge stays on screen. One merge runs at a time: a merge can't be stopped part way (it never suspends), so a
     * change during one is merged once it ends, from whatever is newest then. Backing up many small files changes the
     * ledger every second; starting a merge of the whole library for each ran out of memory (Oct 25, 2026).
     */
    private fun publish() {
        val readItems = readItems
        val backupState = backup?.value
        val serverList = server?.media?.value
        val hiddenOnServer = server?.hidden?.value.orEmpty()
        val kept = originals?.kept?.value.orEmpty()
        val private = privateMedia?.value ?: savedPrivateMedia
        val scored = scores?.value.orEmpty()
        val inputs = listOf(readItems, backupState?.entries, serverList, hiddenOnServer, kept, private, scored)
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
                val (days, soundList) = withContext(background) {
                    val (soundMedia, serverMedia) = serverList.orEmpty().partition { it.isAudio }
                    groupByDay(
                        mergeWithServer(readItems, backupState?.entries.orEmpty(), serverMedia, kept.mapValues { it.value.toString() }, private, hiddenOnServer, scored),
                    ) to soundMedia.filterNot { private.hides(it.isPrivate) }.map { it.toSoundRecording() }.sortedByDescending { it.startMillis }
                }
                merged = days
                sounds = soundList
                mergedFrom = inputs
                mergingFrom = null
                showStatuses()
                if (mergeAgain) {
                    mergeAgain = false
                    publish()
                }
            }
            return
        }
        showStatuses()
    }

    /** Puts [merged] into [state] with each photo's backup status as it is now. */
    private fun showStatuses() {
        val backupState = backup?.value
        // Statuses change only when a file finishes, the queue changes or the uploading one's ring moves a step: the
        // transfer rate and byte counts of a run don't rebuild the library (and everything shown from it). Several files
        // upload at once; each one's ring counts.
        val running = (backupState?.activity as? BackupActivity.Running)?.progress
        val statusKey = listOf(
            merged, backupState?.entries, backupState?.queued, backupState?.countedSources,
            running?.currentId, running?.let { (it.currentFraction * STATUS_STEPS).toInt() },
            running?.uploading?.mapValues { (it.value * STATUS_STEPS).toInt() },
        )
        if (publishedKey != null && publishedKey!!.indices.all { i ->
                val a = publishedKey!![i]
                val b = statusKey[i]
                if (i <= 2) a === b else a == b
            }
        ) return
        publishedKey = statusKey
        val days = if (backupState == null) merged else merged.map { group ->
            group.copy(
                items = group.items.map { item ->
                    if (!item.isOnDevice) return@map item
                    val status = backupState.status(item)
                    // Unchanged photos stay the same object, so screens skip them.
                    if (status == item.backup) item else item.copy(backup = status)
                },
            )
        }
        _state.update { it.copy(days = days, backupSources = backupState?.countedSources, sounds = sounds) }
    }

    /** The sound recordings of the last [publish], built with [merged]. */
    private var sounds: List<SoundRecording> = emptyList()

    /** The merge running in the background, and what it merges from (see [publish]). */
    private var mergeJob: Job? = null
    private var mergingFrom: List<Any?>? = null

    /** Something changed while a merge ran: merge again when it ends. */
    private var mergeAgain = false

    /** What [state] was last built from (see [publish]). */
    private var publishedKey: List<Any?>? = null
    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: LibraryState get() = _state.value

    /** Whether the app has asked for photo access before; on Android a later denial can only be undone in Settings. */
    val accessRequested: Boolean get() = settings.photoAccessRequested

    /** Re-checks the permission and re-reads the library; call when the app comes to the foreground. */
    fun refresh() {
        val access = currentAccess()
        _state.update { it.copy(access = access) }
        server?.refresh()
        if (access == PhotoAccess.FULL || access == PhotoAccess.LIMITED) {
            load()
        } else {
            // Without photo access only the server's photos are shown.
            loadJob?.cancel()
            readItems = emptyList()
            _state.update { it.copy(loading = false, loadError = null) }
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
                readItems = library.loadItems()
                _state.update { it.copy(loading = false) }
                publish()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = e.describe()) }
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
): List<MediaItem> {
    val items = device.map { item -> entries[item.id]?.takeIf { it.matches(item) }?.let { item.copy(sha256 = it.sha256) } ?: item }
        .filter { it.sha256 == null || it.sha256 !in hiddenOnServer }
    if (server.isEmpty()) return items
    val shas = items.mapNotNullTo(HashSet()) { it.sha256 }
    val namesAndSizes = items.mapNotNullTo(HashSet()) { item -> item.fileName?.lowercase()?.let { it to item.sizeBytes } }
    val deviceIds = device.mapTo(HashSet()) { it.id }
    val formerIds = entries.values.filter { it.uploaded && it.id !in deviceIds }.associate { it.sha256 to it.id }
    val hidden = server.filter { privateMedia.hides(it.isPrivate) }
    val hiddenShas = hidden.mapTo(HashSet()) { it.sha256 }
    val hiddenNamesAndSizes = hidden.mapTo(HashSet<Pair<String?, Long?>>()) { it.fileName.lowercase() to it.size }
    val marked = server.filter { it.isPrivate }
    val markedShas = marked.mapTo(HashSet()) { it.sha256 }
    val markedNamesAndSizes = marked.mapTo(HashSet<Pair<String?, Long?>>()) { it.fileName.lowercase() to it.size }
    fun MediaItem.matches(shas: Set<String>, namesAndSizes: Set<Pair<String?, Long?>>): Boolean {
        val sha = sha256
        return if (sha != null) sha in shas else (fileName?.lowercase() to sizeBytes) in namesAndSizes
    }
    // The server's aesthetic scores, for the device's own copies too.
    fun scoreOf(media: ServerMedia): Float? = scores[media.sha256] ?: media.aesthetic?.toFloat()
    val scored = server.filter { scoreOf(it) != null }
    val scoreBySha = scored.associate { it.sha256 to scoreOf(it)!! }
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
        it.sha256 !in shas && (it.fileName.lowercase() to it.size) !in namesAndSizes && it.sha256 !in hiddenShas
    }
    return shown + serverOnly.map { media ->
        media.toMediaItem(id = formerIds[media.sha256] ?: (ServerMedia.SERVER_ID_PREFIX + media.sha256), keptOriginal = kept[media.sha256])
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
