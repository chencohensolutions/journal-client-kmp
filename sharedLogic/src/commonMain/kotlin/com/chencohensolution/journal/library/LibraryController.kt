package com.chencohensolution.journal.library

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
) {
    val itemCount: Int = days.sumOf { it.items.size }

    /** Items not on the server yet (anything but [BackupStatus.BackedUp]). */
    val notBackedUpCount: Int = days.sumOf { day -> day.items.count { it.backup != BackupStatus.BackedUp } }

    /** Size of the items not on the server yet; counts only items whose size is known. */
    val notBackedUpBytes: Long =
        days.sumOf { day -> day.items.filter { it.backup != BackupStatus.BackedUp }.sumOf { it.sizeBytes ?: 0L } }

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
 * Shared logic of the library screens: tracks photo access, reads the device library and groups it by day.
 * Platform UIs ask for the permission themselves and then call [onAccessRequested].
 */
class LibraryController internal constructor(
    private val library: MediaLibrary,
    private val settings: JournalSettings,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loadJob: Job? = null

    private val _state = MutableStateFlow(LibraryState(access = currentAccess()))
    val state: StateFlow<LibraryState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: LibraryState get() = _state.value

    /** Whether the app has asked for photo access before; on Android a later denial can only be undone in Settings. */
    val accessRequested: Boolean get() = settings.photoAccessRequested

    /** Re-checks the permission and re-reads the library; call when the app comes to the foreground. */
    fun refresh() {
        val access = currentAccess()
        _state.update { it.copy(access = access) }
        if (access == PhotoAccess.FULL || access == PhotoAccess.LIMITED) {
            load()
        } else {
            loadJob?.cancel()
            _state.update { it.copy(loading = false, days = emptyList(), loadError = null) }
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
                val days = groupByDay(library.loadItems())
                _state.update { it.copy(loading = false, days = days) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, loadError = e.describe()) }
            }
        }
    }
}

/** Groups [items] by day, newest day first and newest item first within a day. */
internal fun groupByDay(items: List<MediaItem>): List<DayGroup> =
    items.sortedByDescending { it.takenAtMillis }
        .groupBy { it.day }
        .map { (day, dayItems) -> DayGroup(day, dayItems) }
        .sortedByDescending { it.day }
