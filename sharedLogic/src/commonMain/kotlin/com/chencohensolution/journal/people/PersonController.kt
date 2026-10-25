package com.chencohensolution.journal.people

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.search.ShaLookup
import com.chencohensolution.journal.search.libraryResolver
import com.chencohensolution.journal.search.resolverOf
import com.chencohensolution.journal.server.ServerMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** Town, year and person pages: by date, newest first, or the best-looking first (the server's aesthetic score). */
enum class PageSort { NEWEST, BEST }

/** Someone often in [person]'s photos: [photos] of them show both. */
data class Together(val person: Person, val photos: Int)

enum class PersonStatus { LOADING, READY, UNREACHABLE, FAILED }

/**
 * A person's page, or a group's ("Who's this?"). [items] are their photos as the library has them, in [sort]'s order
 * (only those matching [query] when it isn't empty); [faces] the face of theirs in each, by the item's id, for "Who's
 * this?"'s grid and "Not Mom". [person] is null until the people list has them (and after they were forgotten: [gone]).
 */
data class PersonState(
    val id: String,
    val person: Person? = null,
    val status: PersonStatus = PersonStatus.LOADING,
    val items: List<MediaItem> = emptyList(),
    val faces: Map<String, Face> = emptyMap(),
    val together: List<Together> = emptyList(),
    val query: String = "",
    val sort: PageSort = PageSort.NEWEST,
    /** "Who's this?": the name being typed, and the named people it matches. */
    val name: String = "",
    val matches: List<Person> = emptyList(),
    /** A change is on its way to the server. */
    val saving: Boolean = false,
    /** The last change didn't reach the server. */
    val notChanged: Boolean = false,
    /** Forgotten, or merged into someone else: the page should close. */
    val gone: Boolean = false,
    val error: String? = null,
) {
    /** The face shown for them: the server's cover. */
    val cover: Face? get() = person?.cover

    /** Their faces in the order of [items] (for "Who's this?"'s grid). */
    val itemFaces: List<Pair<MediaItem, Face>>
        get() = items.mapNotNull { item -> faces[item.id]?.let { item to it } }

    val firstYear: Int? get() = items.minOfOrNull { it.day.year }
    val lastYear: Int? get() = items.maxOfOrNull { it.day.year }
}

/**
 * The page of a person or a group ([id]): their photos (`searchMedia(people:)`, as the library has them), who they are
 * often with, a search within them, the sort, and the changes: rename, merge, forget, "Not Mom" on a photo; for a group,
 * naming it or joining it to a named person. Changes reload [people]; naming a group gives it a person's id, which
 * [state] follows. One per page shown.
 */
class PersonController internal constructor(
    id: String,
    private val api: PeopleApi,
    private val people: PeopleController,
    private val library: StateFlow<LibraryState>,
    private val server: StateFlow<List<ServerMedia>>,
    private val includePrivate: () -> Boolean,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    /** Their photos read from the library database; null (tests): looked up in [library]'s whole list. */
    private val bySha: ShaLookup? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loadJob: Job? = null

    /** The server's answer: the hits, in its order (the last taken first, or best match with words). */
    private var hits: List<PersonHit> = emptyList()

    private val _state = MutableStateFlow(PersonState(id, people.currentState.list.find(id)))
    val state: StateFlow<PersonState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: PersonState get() = _state.value

    init {
        scope.launch { library.collect { show() } }
        scope.launch {
            var changes = people.currentState.changes
            people.state.collect { list ->
                val found = list.list.find(_state.value.id)
                if (found != null) _state.update { it.copy(person = found) }
                // A face was corrected (here, in a photo's details): their photos may have changed.
                if (list.changes != changes) {
                    changes = list.changes
                    load()
                }
            }
        }
        load()
    }

    /** Asks the server for their photos again (also after Unreachable). */
    fun load() {
        loadJob?.cancel()
        val id = _state.value.id
        val query = _state.value.query
        if (_state.value.items.isEmpty()) _state.update { it.copy(status = PersonStatus.LOADING) }
        loadJob = scope.launch {
            // Typing in the search field asks once the typing stops.
            if (query.isNotBlank()) delay(QUERY_SETTLE_MILLIS)
            try {
                val answer = api.media(listOf(id), query.ifBlank { null }, PAGE, 0, includePrivate())
                hits = answer.hits
                _state.update { it.copy(status = PersonStatus.READY, error = null) }
                show()
            } catch (e: CancellationException) {
                throw e
            } catch (e: PeopleUnreachable) {
                _state.update { it.copy(status = PersonStatus.UNREACHABLE, error = e.message) }
            } catch (e: Exception) {
                _state.update { it.copy(status = PersonStatus.FAILED, error = e.describe()) }
            }
        }
    }

    /** Search within their photos; blank: all of them. */
    fun setQuery(text: String) {
        if (text == _state.value.query) return
        _state.update { it.copy(query = text) }
        load()
    }

    fun setSort(sort: PageSort) {
        _state.update { it.copy(sort = sort) }
        show()
    }

    /** What [bySha] read last: the item of each sha256 it found, and the sha256s it was asked for. */
    private var found: Map<String, MediaItem> = emptyMap()
    private var lookedUp: Set<String> = emptySet()
    private var showJob: Job? = null

    /**
     * The items, faces and who they are with again, from [hits] through the library as it is now. With [bySha], what
     * was read before shows at once ("Not Mom" leaves the page at once), then the items are read again.
     */
    private fun show() {
        val lookup = bySha ?: return present(libraryResolver(library.value, server.value))
        if (hits.all { it.sha256 in lookedUp }) present(resolverOf(found))
        val sha256s = hits.map { it.sha256 }
        showJob?.cancel()
        showJob = scope.launch {
            found = lookup(sha256s)
            lookedUp = sha256s.toSet()
            present(resolverOf(found))
        }
    }

    /** [show] through [resolve]. */
    private fun present(resolve: (List<String>) -> List<MediaItem>) {
        val current = _state.value
        val itemOf = HashMap<String, MediaItem>()
        for (hit in hits) resolve(listOf(hit.sha256)).firstOrNull()?.let { itemOf[hit.sha256] = it }
        var items = hits.mapNotNull { itemOf[it.sha256] }.distinctBy { it.id }
        items = when {
            current.sort == PageSort.BEST -> items.sortedWith(compareByDescending<MediaItem> { it.aesthetic ?: -1f }.thenByDescending { it.takenAtMillis })
            current.query.isBlank() -> items.sortedByDescending { it.takenAtMillis }
            else -> items
        }
        val id = current.id
        // The face of theirs in each photo, by the library item's id.
        val faces = HashMap<String, Face>()
        for (hit in hits) {
            val face = hit.faces.firstOrNull { it.personId == id } ?: continue
            val item = itemOf[hit.sha256] ?: continue
            faces[item.id] = face
        }
        val known = people.currentState.list
        val together = hits.flatMap { hit -> hit.faces.mapNotNull { it.personId }.filter { it != id }.distinct() }
            .groupingBy { it }.eachCount()
            .mapNotNull { (other, n) -> known.find(other)?.let { Together(it, n) } }
            .sortedWith(compareBy<Together> { it.person.isGroup }.thenByDescending { it.photos })
            .take(TOGETHER_SHOWN)
        _state.update { it.copy(items = items, faces = faces, together = if (current.query.isBlank()) together else it.together) }
    }

    /** "Who's this?": what is typed, and the named people it matches. */
    fun setName(text: String) {
        _state.update { it.copy(name = text, matches = people.matching(text)) }
    }

    /**
     * Names the group (it becomes a person) or renames the person. A group given a name someone has already (ignoring
     * case) joins them: the server does that.
     */
    fun rename(name: String) {
        val typed = name.trim()
        if (typed.isEmpty()) return
        change { api.name(_state.value.id, typed) }
    }

    /** Joins this group to the named person [personId] (Who's this? › a suggestion); the page becomes theirs. */
    fun join(personId: String) = change { api.merge(personId, listOf(_state.value.id)) }

    /** Merge with…: [otherId]'s faces become this person's. */
    fun merge(otherId: String) = change { api.merge(_state.value.id, listOf(otherId)) }

    /** Forget this person: the name goes; their faces go back into groups. The page closes ([PersonState.gone]). */
    fun forget() {
        val id = _state.value.id
        save {
            api.forget(id)
            people.reload()
            _state.update { it.copy(gone = true) }
        }
    }

    /**
     * "Not Mom" on [item]: takes their face in it from them, so they are never given it again. It leaves the page at
     * once; back if the server didn't take it.
     */
    fun notThem(item: MediaItem) {
        val face = _state.value.faces[item.id] ?: return
        val sha = face.sha256
        val before = hits
        hits = hits.filter { it.sha256 != sha }
        show()
        save(onFail = { hits = before; show() }) {
            api.moveFace(face.id, null)
            people.reload()
        }
    }

    /** A change that gives back a person: the page becomes theirs (a group named gets a person's id), and reloads. */
    private fun change(block: suspend () -> Person) = save {
        val person = block()
        _state.update { it.copy(id = person.id, person = person, name = "", matches = emptyList()) }
        people.reload()
        load()
    }

    private fun save(onFail: () -> Unit = {}, block: suspend () -> Unit) {
        if (_state.value.saving) return
        _state.update { it.copy(saving = true, notChanged = false) }
        scope.launch {
            try {
                block()
                _state.update { it.copy(saving = false) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: PeopleChanged) {
                // Someone else changed the people: start again from the server's list.
                onFail()
                people.reload()
                _state.update { it.copy(saving = false, notChanged = true, error = e.message) }
            } catch (e: Exception) {
                onFail()
                _state.update { it.copy(saving = false, notChanged = true, error = e.describe()) }
            }
        }
    }

    /** Edit faces › Done: the faces marked ✗ are not this person (and their lookalikes go too). */
    fun notThem(faces: List<Face>) {
        val id = _state.value.id
        if (faces.isEmpty()) return
        val gone = faces.map { it.sha256 }.toSet()
        val before = hits
        hits = hits.filter { hit -> hit.sha256 !in gone }
        show()
        save(onFail = { hits = before; show() }) {
            api.mark(id, emptyList(), faces.map { it.id })
            people.reload()
        }
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (PersonState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() = scope.cancel()

    private companion object {
        /** A person's photos asked for at once (the server's most); enough for a page you scroll. */
        const val PAGE = 1000
        const val TOGETHER_SHOWN = 12
        const val QUERY_SETTLE_MILLIS = 400L
    }
}
