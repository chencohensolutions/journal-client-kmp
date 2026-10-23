package com.chencohensolution.journal.server

import com.chencohensolution.journal.connection.ObservationHandle
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What the Hidden collections screen shows. */
sealed interface HiddenCollectionsState {
    /** Asking the server. */
    data object Loading : HiddenCollectionsState

    /** The server didn't answer; the screen offers to try again. */
    data object Unreachable : HiddenCollectionsState

    data class Ready(
        val collections: HiddenCollections,
        /** What is typed in "Find an app". */
        val query: String,
        /** The screenshot apps whose name contains [query], most files first. */
        val shownApps: List<HiddenCollections.Collection>,
        /** A change is on its way to the server; switches are ignored until it is back. */
        val saving: Boolean,
        /** The last change didn't reach the server, and the switch went back. */
        val notChanged: Boolean,
    ) : HiddenCollectionsState
}

/**
 * Settings → Private media → Hidden collections: reads the user's choice from the server and saves each switch at
 * once. Nothing is kept on this device. The library syncs once, when the screen is left ([close]), not after each
 * switch: each change makes the server send the files it affects at the next sync. One per opening of the screen.
 */
class HiddenCollectionsController internal constructor(
    private val library: ServerLibrary,
    dispatcher: CoroutineDispatcher,
) {
    constructor(library: ServerLibrary) : this(library, Dispatchers.Main)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loading: Job? = null
    private var saving: Job? = null

    /** A change reached the server, so the library syncs when the screen is left. */
    private var changed = false

    private val _state = MutableStateFlow<HiddenCollectionsState>(HiddenCollectionsState.Loading)
    val state: StateFlow<HiddenCollectionsState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: HiddenCollectionsState get() = _state.value

    /** Asks the server for the choice and the counts; does nothing while already asking or once loaded. */
    fun load() {
        if (loading?.isActive == true || _state.value is HiddenCollectionsState.Ready) return
        _state.value = HiddenCollectionsState.Loading
        loading = scope.launch {
            val collections = library.hiddenCollections()
            _state.value = if (collections == null) HiddenCollectionsState.Unreachable else ready(collections, query = "")
        }
    }

    /** After [HiddenCollectionsState.Unreachable]. */
    fun retry() = load()

    /**
     * Hides or shows [collection] (one of the current state's own: they are matched by identity). The switch moves at
     * once; if the server doesn't take it, it goes back and the state says "not changed". Ignored while saving.
     */
    fun set(collection: HiddenCollections.Collection, hidden: Boolean) = save { it.with(collection, hidden) }

    /** Hides nudity, or shows it; like [set]. */
    fun setNudityHidden(hidden: Boolean) = save { it.withNudity(hidden) }

    /** The score from which nudity is hidden; values outside [HiddenCollections.Nudity.RANGE] are ignored. Like [set]. */
    fun setNudityFromPercent(percent: Int) {
        if (percent !in HiddenCollections.Nudity.RANGE) return
        save { it.withNudity(hidden = true, fromPercent = percent) }
    }

    private fun save(change: (HiddenCollections) -> HiddenCollections) {
        val before = _state.value as? HiddenCollectionsState.Ready ?: return
        if (before.saving) return
        val choice = change(before.collections)
        if (choice == before.collections) return
        _state.value = ready(choice, before.query).copy(saving = true)
        saving = scope.launch {
            // Finished even when the screen is left meanwhile: [close] syncs after it.
            val saved = withContext(NonCancellable) { library.setHiddenCollections(choice) }
            if (saved != null) changed = true
            val query = (_state.value as? HiddenCollectionsState.Ready)?.query ?: before.query
            _state.value = ready(saved ?: before.collections, query).copy(notChanged = saved == null)
        }
    }

    /** "Find an app": narrows the screenshot apps to those whose name contains [text], ignoring case. */
    fun setQuery(text: String) {
        val ready = _state.value as? HiddenCollectionsState.Ready ?: return
        _state.value = ready(ready.collections, text).copy(saving = ready.saving, notChanged = ready.notChanged)
    }

    private fun ready(collections: HiddenCollections, query: String) = HiddenCollectionsState.Ready(
        collections = collections,
        query = query,
        shownApps = collections.screenshotApps.filter { it.name.contains(query.trim(), ignoreCase = true) },
        saving = false,
        notChanged = false,
    )

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (HiddenCollectionsState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    /** The screen is gone: syncs the library once if anything changed, after a save still on its way. */
    fun close() {
        val save = saving
        if (save?.isActive == true) {
            save.invokeOnCompletion { if (changed) library.syncAgain() }
        } else if (changed) {
            library.syncAgain()
        }
        scope.cancel()
    }
}
