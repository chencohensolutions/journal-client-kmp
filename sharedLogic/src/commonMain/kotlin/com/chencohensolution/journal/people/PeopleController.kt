package com.chencohensolution.journal.people

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.network.describe
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

enum class PeopleStatus {
    /** Not asked yet, or asking for the first time. */
    LOADING,
    READY,

    /** The server couldn't be reached; what was loaded before (if anything) still shows. */
    UNREACHABLE,

    /** The server answered with an error (face search is off there, or it failed). */
    FAILED,
}

/** Explore's People: the named people and the groups to name, as the server last told them. */
data class PeopleState(
    val status: PeopleStatus = PeopleStatus.LOADING,
    val list: PeopleList = PeopleList(emptyList(), emptyList(), 0, 0),
    val error: String? = null,
    /** Counts the corrections of faces made: a person's page and a photo's details load again when it moves. */
    val changes: Int = 0,
    /** A correction is on its way. */
    val correcting: Boolean = false,
    /** The last correction didn't reach the server, and the phone keeps no people to queue it with. */
    val notChanged: Boolean = false,
) {
    /** The server is still reading files for faces: [PeopleList.read] of [PeopleList.total]. */
    val stillReading: Boolean get() = list.total > 0 && list.read < list.total

    companion object {
        val NONE = PeopleState()
    }
}

/**
 * The user's people and groups of faces (Explore › People), loaded from the server when shown ([load]) and again after
 * a change on a person's page ([reload]). One per app, shared by the People list and the person pages, which read the
 * names and covers from it.
 */
class PeopleController internal constructor(
    private val api: PeopleApi,
    /** Whether private files count ("Show private media"); read for each load. */
    private val includePrivate: () -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loading: Job? = null

    private val _state = MutableStateFlow(PeopleState())
    val state: StateFlow<PeopleState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: PeopleState get() = _state.value

    /** Loads the list unless it is loading; shown again each time People opens, so new faces come in. */
    fun load() {
        if (loading?.isActive == true) return
        loading = scope.launch { fetch() }
    }

    /** Loads the list again now (after naming, merging, moving or forgetting). */
    fun reload() {
        loading?.cancel()
        loading = scope.launch { fetch() }
    }

    internal suspend fun fetch() {
        try {
            val list = api.people(includePrivate())
            _state.update { it.copy(status = PeopleStatus.READY, list = list, error = null) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: PeopleUnreachable) {
            _state.update { it.copy(status = PeopleStatus.UNREACHABLE, error = e.message) }
        } catch (e: Exception) {
            _state.update { it.copy(status = PeopleStatus.FAILED, error = e.describe()) }
        }
    }

    // Correcting faces (docs/screens/people.md "Correcting faces"): each is sent to the server (or, offline, applied
    // to the people this phone keeps and queued), then the list loads again and [PeopleState.changes] moves, so the
    // pages showing faces follow.

    /** "This is Mom": [face] is [personId]'s, and one of the faces she is known by. */
    fun confirm(face: Face, personId: String) = correct { api.mark(personId, listOf(face.id), emptyList()) }

    /** "Not Mom", with no one named instead: [face] leaves [personId], and so do faces more like it than like her. */
    fun notPerson(face: Face, personId: String) = correct { api.mark(personId, emptyList(), listOf(face.id)) }

    /** "Not Mom" on many faces at once (a person's faces, marked ✗). */
    fun notPerson(faces: List<Face>, personId: String) {
        if (faces.isEmpty()) return
        correct { api.mark(personId, emptyList(), faces.map { it.id }) }
    }

    /** "Who is it?" › a person: [face] is [personId]'s, and leaves whoever had it. */
    fun moveFace(face: Face, personId: String) = correct { api.moveFace(face.id, personId) }

    /** "Who is it?" › a name: [face] is the person called [name], or a new person of that name. */
    fun nameFace(face: Face, name: String) {
        val typed = name.trim()
        if (typed.isEmpty()) return
        correct { api.nameFace(face.id, typed) }
    }

    private fun correct(block: suspend () -> Unit) {
        _state.update { it.copy(correcting = true, notChanged = false) }
        scope.launch {
            try {
                block()
                fetch()
                _state.update { it.copy(correcting = false, changes = it.changes + 1) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PeopleChanged) {
                fetch()
                _state.update { it.copy(correcting = false, notChanged = true, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(correcting = false, notChanged = true, error = e.describe()) }
            }
        }
    }

    /** Named people whose name contains [text] (ignoring case), those with the most photos first: "Who's this?"'s suggestions. */
    fun matching(text: String): List<Person> {
        val typed = text.trim()
        if (typed.isEmpty()) return emptyList()
        return _state.value.list.people.filter { it.name!!.contains(typed, ignoreCase = true) }
            .sortedWith(compareByDescending<Person> { it.name.equals(typed, ignoreCase = true) }.thenByDescending { it.files })
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (PeopleState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() = scope.cancel()
}
