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
import kotlinx.coroutines.launch
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
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loadJob: Job? = null

    /** The device library as read. */
    private var readItems: List<MediaItem> = emptyList()

    /** Device and server-only items grouped by day, before backup statuses; rebuilt when one of its inputs changes. */
    private var merged: List<DayGroup> = emptyList()
    private var mergedFrom: List<Any?>? = null

    private val _state = MutableStateFlow(LibraryState(access = currentAccess()))
    val state: StateFlow<LibraryState> = _state.asStateFlow()

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
        originals?.let { kept -> scope.launch { kept.load(); kept.kept.collect { publish() } } }
    }

    /** Puts the current device items, server-only items and backup statuses into [state]. */
    private fun publish() {
        val backupState = backup?.value
        val serverMedia = server?.media?.value.orEmpty()
        val kept = originals?.kept?.value.orEmpty()
        val inputs = listOf(readItems, backupState?.entries, serverMedia, kept)
        if (mergedFrom == null || mergedFrom!!.indices.any { mergedFrom!![it] !== inputs[it] }) {
            merged = groupByDay(mergeWithServer(readItems, backupState?.entries.orEmpty(), serverMedia, kept.mapValues { it.value.toString() }))
            mergedFrom = inputs
        }
        val days = if (backupState == null) merged else merged.map { group ->
            group.copy(items = group.items.map { if (it.isOnDevice) it.copy(backup = backupState.status(it)) else it })
        }
        _state.update { it.copy(days = days, backupSources = backupState?.countedSources) }
    }
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
 * [kept] are originals the app downloaded, by sha256.
 */
internal fun mergeWithServer(
    device: List<MediaItem>,
    entries: Map<String, LedgerEntry>,
    server: List<ServerMedia>,
    kept: Map<String, String> = emptyMap(),
): List<MediaItem> {
    val items = device.map { item -> entries[item.id]?.takeIf { it.matches(item) }?.let { item.copy(sha256 = it.sha256) } ?: item }
    if (server.isEmpty()) return items
    val shas = items.mapNotNullTo(HashSet()) { it.sha256 }
    val namesAndSizes = items.mapNotNullTo(HashSet()) { item -> item.fileName?.lowercase()?.let { it to item.sizeBytes } }
    val deviceIds = device.mapTo(HashSet()) { it.id }
    val formerIds = entries.values.filter { it.uploaded && it.id !in deviceIds }.associate { it.sha256 to it.id }
    val serverOnly = server.filter { it.sha256 !in shas && (it.fileName.lowercase() to it.size) !in namesAndSizes }
    return items + serverOnly.map { media ->
        media.toMediaItem(id = formerIds[media.sha256] ?: (ServerMedia.SERVER_ID_PREFIX + media.sha256), keptOriginal = kept[media.sha256])
    }
}

/** Groups [items] by day, newest day first and newest item first within a day. */
internal fun groupByDay(items: List<MediaItem>): List<DayGroup> =
    items.sortedByDescending { it.takenAtMillis }
        .groupBy { it.day }
        .map { (day, dayItems) -> DayGroup(day, dayItems) }
        .sortedByDescending { it.day }
