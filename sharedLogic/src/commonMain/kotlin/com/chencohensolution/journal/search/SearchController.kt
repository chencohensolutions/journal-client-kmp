package com.chencohensolution.journal.search

import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.graphql.PrepareSearchMutation
import com.chencohensolution.journal.graphql.SearchMediaQuery
import com.chencohensolution.journal.graphql.SearchModelQuery
import com.chencohensolution.journal.graphql.type.SearchModelState
import com.chencohensolution.journal.server.ServerError
import com.chencohensolution.journal.server.ServerErrorCode
import com.chencohensolution.journal.server.firstServerError
import com.chencohensolution.journal.graphql.type.GeoAreaInput
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.people.Person
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.server.ServerMedia
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.stories.GeoArea
import com.chencohensolution.journal.stories.StoriesState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

// Text search: a sentence ("a dog on the beach at sunset") sent to the server, which answers with the photos and
// videos it describes best (`searchMedia`, see the server's docs/search.md). The answer is a list of sha256s; they are
// shown as the library shows those photos, so what the library hides (private files, nudity over the threshold) stays
// hidden here too. The filter's years and towns narrow it: the server picks the best of that year or place. The server
// also groups the hits: almost identical photos as one set you swipe, and sets taken close together as one moment.

enum class SearchStatus {
    /** Nothing searched yet, or cleared. */
    IDLE,
    SEARCHING,
    DONE,
    FAILED,
}

/** Why a search failed. */
enum class SearchError {
    /** The server couldn't be reached, or this device isn't registered. */
    OFFLINE,

    /** The server answered with an error: it failed. */
    SERVER,

    /** Search is off on the server (no model), and this phone keeps nothing to search with. */
    OFF,
}

/** Whether the server can read a search's words at once (its `searchModel`). */
enum class SearchReadiness {
    /** Loaded: a search answers at once. */
    READY,

    /** Being loaded, a few seconds; a search now waits for it ("Getting search ready…"). */
    LOADING,

    /** Not loaded: the next search loads it. */
    UNLOADED,

    /** Text search is off on the server. */
    OFF,
}

/**
 * Gets the server's search model ready before the first search: [prepare] starts loading it and answers at once,
 * [waitWhileLoading] answers when a load in progress ends (at most 30 seconds).
 */
internal interface SearchWarmUp {
    suspend fun prepare(): SearchReadiness
    suspend fun waitWhileLoading(): SearchReadiness
}

/** A year ("2025") or town ("Paris") the matches are in, with how many of them. */
data class SearchChip(val value: String, val count: Int)

data class SearchState(
    /** What was searched for, as typed. */
    val query: String = "",
    val status: SearchStatus = SearchStatus.IDLE,
    /**
     * The photos found as the library shows them, of [year] and [town] when chosen: moments by their best photo, each
     * moment's sets by theirs, each set best first. What the viewer swipes through.
     */
    val items: List<MediaItem> = emptyList(),
    /** [items] as shown: each set of almost identical photos is one row you swipe (a set of one, a single photo). */
    val sets: List<List<MediaItem>> = emptyList(),
    /** How many of the user's files the server has read so far; the others can't be found yet. */
    val indexed: Int = 0,
    /** How many files the user has on the server. */
    val total: Int = 0,
    val error: SearchError? = null,
    /** The server's or the connection's own words, for a failed search. */
    val errorDetail: String? = null,
    /** The chosen year and town; null for any. */
    val year: Int? = null,
    val town: String? = null,
    /** The years of the matches, newest first, and their towns, most matches first; each counted within the other's choice. */
    val yearChips: List<SearchChip> = emptyList(),
    val townChips: List<SearchChip> = emptyList(),
    /** More matches can be shown ([SearchController.loadMore]): scrolling near the end asks for them. */
    val canLoadMore: Boolean = false,
    /** The next page is being asked for. */
    val loadingMore: Boolean = false,
    /** The server is loading its search model (a few seconds): a search waits for it. "Getting search ready…". */
    val preparing: Boolean = false,
    /** The server couldn't be reached, and this phone searched what it keeps ("Searched on this phone"). */
    val onPhone: Boolean = false,
    /** People chosen (chips in the field): only photos with every one of them, best match of the words, if any. */
    val people: List<Person> = emptyList(),
    /**
     * How many of the matches found so far (of the pages shown, in the chosen year and town) are in sources Show in the
     * app hides: hidden sources, what was imported while Imported is off, but not a day shown anyway ("Always show
     * this day"). Left out of [items] unless [showingHidden]; "12 more in sources you hide · Show".
     */
    val hiddenBySources: Int = 0,
    /** This search shows the matches in hidden sources too ([SearchController.showHiddenSources]). */
    val showingHidden: Boolean = false,
) {
    /** The server hasn't read every file yet, so some can't be found. */
    val stillReading: Boolean get() = status == SearchStatus.DONE && indexed < total

    companion object {
        /** Nothing searched yet; for Swift, which has no default arguments. */
        val NONE = SearchState()
    }
}

/**
 * The server's answer: the files found, best first, and how far it has read the library. [sets] and [moments] number
 * each file's set of almost identical photos and its moment (empty: each its own).
 */
internal data class SearchAnswer(
    val sha256s: List<String>,
    val indexed: Int,
    val total: Int,
    val sets: List<Int> = emptyList(),
    val moments: List<Int> = emptyList(),
    /** Answered by this phone because the server couldn't be reached ("Searched on this phone"). */
    val onPhone: Boolean = false,
) {
    val size: Int get() = sha256s.size

    /** The first [n] hits. */
    fun take(n: Int) = copy(sha256s = sha256s.take(n), sets = sets.take(n), moments = moments.take(n))

    /** [next] (the page after these) added at the end, with its counts. */
    operator fun plus(next: SearchAnswer) =
        SearchAnswer(sha256s + next.sha256s, next.indexed, next.total, sets + next.sets, moments + next.moments, onPhone || next.onPhone)
}

/**
 * Asks the server for the [limit] best matches of [text] after the first [offset], of [years] and inside [area] when
 * given; throws when it can't be reached ([SearchUnreachable]) or answers with an error.
 */
internal fun interface SearchApi {
    suspend fun search(text: String, limit: Int, offset: Int, years: List<Int>?, area: GeoArea?): SearchAnswer
}

/**
 * Asks for the photos with every one of [ids] (people or groups), the [limit] best matches of [text] after the first
 * [offset] (without text: the last taken first).
 */
internal fun interface PeopleSearch {
    suspend fun search(ids: List<String>, text: String?, limit: Int, offset: Int): SearchAnswer
}

/** The server couldn't be reached, or this device has no identity to ask with. */
internal class SearchUnreachable(message: String) : Exception(message)

/** [SearchApi] over GraphQL, as this device's identity. */
internal class ApolloSearchApi(
    private val settings: JournalSettings,
    private val http: ServerHttp,
) : SearchApi {
    override suspend fun search(text: String, limit: Int, offset: Int, years: List<Int>?, area: GeoArea?): SearchAnswer {
        return http.graphql { apollo ->
            val query = SearchMediaQuery(
                text = text,
                limit = Optional.present(limit),
                // Left out for a first page, so a server from before pages still answers it.
                offset = Optional.presentIfNotNull(offset.takeIf { it > 0 }),
                years = Optional.presentIfNotNull(years),
                area = Optional.presentIfNotNull(area?.let { GeoAreaInput(it.south, it.west, it.north, it.east) }),
                // The server leaves private files out unless asked. Sent only while they are shown, so a server from
                // before the argument still answers.
                includePrivate = Optional.presentIfNotNull(true.takeIf { settings.privateMedia.show }),
            )
            val response = apollo.query(query).execute()
            when (val exception = response.exception) {
                null -> Unit
                is ApolloNetworkException, is ApolloHttpException -> throw SearchUnreachable(exception.describe())
                else -> throw exception
            }
            firstServerError(response.errors)?.let { throw it }
            val found = response.data?.searchMedia ?: throw IllegalStateException("No answer")
            SearchAnswer(found.hits.map { it.media.sha256 }, found.indexed, found.total, found.hits.map { it.set }, found.hits.map { it.moment })
        } ?: throw SearchUnreachable("This device isn't registered")
    }
}

/** [SearchWarmUp] over GraphQL: `prepareSearch`, then `searchModel(wait: true)`. */
internal class ApolloSearchWarmUp(private val http: ServerHttp) : SearchWarmUp {
    override suspend fun prepare(): SearchReadiness = ask { apollo ->
        apollo.mutation(PrepareSearchMutation()).execute().let { it.exception?.let { e -> throw e }; it.data?.prepareSearch }
    }

    override suspend fun waitWhileLoading(): SearchReadiness = ask { apollo ->
        apollo.query(SearchModelQuery(Optional.present(true))).execute().let { it.exception?.let { e -> throw e }; it.data?.searchModel }
    }

    private suspend fun ask(block: suspend (com.apollographql.apollo.ApolloClient) -> SearchModelState?): SearchReadiness {
        val state = http.graphql(block) ?: return SearchReadiness.READY
        return when (state) {
            SearchModelState.READY -> SearchReadiness.READY
            SearchModelState.LOADING -> SearchReadiness.LOADING
            SearchModelState.UNLOADED -> SearchReadiness.UNLOADED
            SearchModelState.OFF -> SearchReadiness.OFF
            else -> SearchReadiness.READY
        }
    }
}

/**
 * The Search screen's logic: [search] asks the server for the photos a sentence describes and [state] lists them, best
 * first, as the library has them, a page ([pageSize]) at a time: [loadMore] shows the next. The first answer is the best
 * [CANDIDATES]; their years and towns are what the filter offers, and its first pages come from it. [chooseYear] /
 * [chooseTown] ask the server again for the best of that year or town, a page at a time (a town is sent as the box
 * around this device's photos of it, since the server has no place names). When the library or the places change (a
 * photo made private, a town named) the same answers are shown again through them. A new request replaces one running.
 * Search follows Show in the app (the owner, Oct 27, 2026): what its sources hide, as [stories]' shownSources say, is
 * left out and counted ([SearchState.hiddenBySources]) until [showHiddenSources] shows it for this search.
 */
class SearchController internal constructor(
    private val library: StateFlow<LibraryState>,
    private val api: SearchApi,
    /**
     * The server's files, to find a device photo backup hasn't read yet by its file name and size, in [library]'s whole
     * list without [bySha] (tests); the app passes none (with [bySha], the database finds them).
     */
    private val server: StateFlow<List<ServerMedia>>? = null,
    /**
     * The stories' places (where photos were taken and the towns' names), for the town chips, and the sources Show in
     * the app shows; null (tests): every source is shown.
     */
    private val stories: StateFlow<StoriesState>? = null,
    /** Photos per page (Settings › Search); read for each page. */
    private val pageSize: () -> Int = { JournalSettings.DEFAULT_SEARCH_PAGE_SIZE },
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    /** Gets the server's model ready when the screen opens ([prepare]); null: nothing to get ready. */
    private val warmUp: SearchWarmUp? = null,
    /** Searches with people chosen ([addPerson]); null: no people. */
    private val peopleSearch: PeopleSearch? = null,
    /** The answers' items read from the library database; null (tests): looked up in [library]'s whole list. */
    private val bySha: ShaLookup? = null,
    /**
     * The box around the library's photos of a town, from the database (LibraryController.townArea, step 6c); null
     * (tests): worked out from [library]'s whole list.
     */
    private val townArea: (suspend (town: String, places: Places) -> GeoArea?)? = null,
    /** "Show private media": while off, what [madePrivate] names leaves the results at once. */
    private val showsPrivate: () -> Boolean = { true },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var searchJob: Job? = null

    /** The first answer: what the filter counts, and the pages without a filter. */
    private var firstAnswer = NOTHING

    /** The matches fetched for what is chosen now: [firstAnswer] without a filter, else its pages so far. */
    private var loaded = NOTHING

    /** Where each page shown so far ends in [loaded]; the last is how many are shown. */
    private var pageEnds: List<Int> = emptyList()

    /** Whether the server may have more after [loaded]: its last answer was a full one. */
    private var serverHasMore = false

    /** The ids of what was made private here ([madePrivate]), left out while private media isn't shown. */
    private val madePrivate = HashSet<String>()

    /** The filter of [loaded], sent with each next page. */
    private var years: List<Int>? = null
    private var area: GeoArea? = null

    /** This search shows what Show in the app hides too ([showHiddenSources]); a new search hides it again. */
    private var showHidden = false

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: SearchState get() = _state.value

    init {
        scope.launch { library.collect { refresh() } }
        stories?.let { flow -> scope.launch { flow.map { it.places }.distinctUntilChanged().collect { refresh() } } }
        stories?.let { flow -> scope.launch { flow.map { it.shownSources }.distinctUntilChanged().collect { refresh() } } }
    }

    /** Searches for [text], with no year or town; blank clears instead. */
    fun search(text: String) {
        val query = text.trim()
        val people = _state.value.people
        if (query.isEmpty() && people.isEmpty()) return clear()
        _state.update { SearchState(query = query, status = SearchStatus.SEARCHING, people = people) }
        years = null
        area = null
        showHidden = false
        fetch(limit = CANDIDATES) { answer ->
            firstAnswer = answer
            show(answer, full = answer.size == CANDIDATES)
        }
    }

    /**
     * Adds [person] as a chip in the field (from the faces shown before anything is searched): the search shows only
     * photos with them, and with every other person chosen.
     */
    fun addPerson(person: Person) {
        val current = _state.value
        if (peopleSearch == null || current.people.any { it.id == person.id }) return
        _state.update { it.copy(people = it.people + person) }
        search(current.query)
    }

    /** Takes [id] out of the chips; with neither words nor people left, back to nothing searched. */
    fun removePerson(id: String) {
        _state.update { it.copy(people = it.people.filter { p -> p.id != id }) }
        search(_state.value.query)
    }

    /**
     * The server took [items] as private: while private media isn't shown they leave the results now, not once the
     * library has synced and read itself again, which can take a while (asked Oct 26, 2026).
     */
    fun madePrivate(items: List<MediaItem>) {
        madePrivate += items.map { it.id }
        refresh()
    }

    /** The best matches of [year] (null: any year), in the chosen town if one is. */
    fun chooseYear(year: Int?) = choose(year, _state.value.town)

    /** The best matches in [town] (null: anywhere), of the chosen year if one is. */
    fun chooseTown(town: String?) = choose(_state.value.year, town)

    /**
     * The next page: from what was fetched already, or from the server once that is all shown. Nothing while a page
     * is coming, or when there are no more.
     */
    fun loadMore() {
        val current = _state.value
        if (current.status != SearchStatus.DONE || current.loadingMore || !current.canLoadMore) return
        val shown = pageEnds.lastOrNull() ?: 0
        if (shown < loaded.size) {
            pageEnds = pageEnds + minOf(loaded.size, shown + pageSize())
            refresh()
            return
        }
        val limit = minOf(pageSize(), MAX_DEPTH - loaded.size)
        if (limit < 1) return
        _state.update { it.copy(loadingMore = true) }
        searchJob = scope.launch {
            try {
                val next = call(current.query, limit, loaded.size, years, area)
                loaded = loaded + next
                pageEnds = pageEnds + loaded.size
                serverHasMore = next.size == limit
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline or refused: what is shown stays, and no more is offered until the next search.
                serverHasMore = false
            }
            _state.update { it.copy(loadingMore = false) }
            refresh()
        }
    }

    private var prepareJob: Job? = null

    /**
     * The search screen opened: has the server get its model ready, so the first search doesn't wait for it to load.
     * While it loads, [SearchState.preparing] is true. Offline, nothing happens.
     */
    fun prepare() {
        val warmUp = warmUp ?: return
        if (prepareJob?.isActive == true) return
        prepareJob = scope.launch {
            try {
                if (warmUp.prepare() != SearchReadiness.LOADING) return@launch
                _state.update { it.copy(preparing = true) }
                warmUp.waitWhileLoading()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: the search itself will say so.
            } finally {
                _state.update { it.copy(preparing = false) }
            }
        }
    }

    /**
     * Shows ([show]) the matches in sources Show in the app hides, for this search only ("12 more in sources you hide ·
     * Show"), or hides them again. Show in the app itself doesn't change.
     */
    fun showHiddenSources(show: Boolean) {
        if (showHidden == show) return
        showHidden = show
        _state.update { it.copy(showingHidden = show) }
        refresh()
    }

    /** Back to nothing searched (and no people chosen). */
    fun clear() {
        searchJob?.cancel()
        refreshJob?.cancel()
        firstAnswer = NOTHING
        loaded = NOTHING
        pageEnds = emptyList()
        serverHasMore = false
        showHidden = false
        _state.value = SearchState()
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (SearchState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() = scope.cancel()

    private fun choose(year: Int?, town: String?) {
        val current = _state.value
        if (current.query.isEmpty() || firstAnswer.size == 0 || current.people.isNotEmpty()) return
        _state.update { it.copy(year = year, town = town) }
        years = year?.let(::listOf)
        // From the database the town's box is read when the page is asked for (fetch).
        area = if (townArea == null) town?.let(::areaOf) else null
        if (year == null && town == null) {
            // The first answer has the best of all already.
            searchJob?.cancel()
            show(firstAnswer, full = firstAnswer.size == CANDIDATES)
            _state.update { it.copy(status = SearchStatus.DONE, loadingMore = false) }
            refresh()
            return
        }
        _state.update { it.copy(status = SearchStatus.SEARCHING, loadingMore = false) }
        val limit = pageSize()
        fetch(limit, town?.takeIf { townArea != null }) { answer -> show(answer, full = answer.size == limit) }
    }

    /** Shows [answer]'s first page; [full]: the server may have more after it. */
    private fun show(answer: SearchAnswer, full: Boolean) {
        loaded = answer
        pageEnds = listOf(minOf(answer.size, pageSize()))
        serverHasMore = full
    }

    /**
     * Asks the server for the first [limit] matches of the query (with [years] and [area]) and shows them through [apply].
     * With [town], [area] is first read as the box around its photos ([townArea]).
     */
    private fun fetch(limit: Int, town: String? = null, apply: (SearchAnswer) -> Unit) {
        searchJob?.cancel()
        val query = _state.value.query
        val years = years
        searchJob = scope.launch {
            try {
                val places = stories?.value?.places
                if (town != null) area = if (places == null) null else townArea?.invoke(town, places)?.widenedForSearch()
                val answer = call(query, limit, 0, years, area)
                apply(answer)
                _state.update { it.copy(status = SearchStatus.DONE, indexed = answer.indexed, total = answer.total, error = null, errorDetail = null, onPhone = answer.onPhone) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchUnreachable) {
                fail(SearchError.OFFLINE, e.message)
            } catch (e: ServerError) {
                fail(if (e.code == ServerErrorCode.SEARCH_OFF) SearchError.OFF else SearchError.SERVER, e.message)
            } catch (e: Exception) {
                fail(SearchError.SERVER, e.describe())
            }
        }
    }

    /** The words, with the people chosen when there are any (who aren't narrowed by year or town). */
    private suspend fun call(query: String, limit: Int, offset: Int, years: List<Int>?, area: GeoArea?): SearchAnswer {
        val people = _state.value.people
        val search = peopleSearch
        return if (people.isNotEmpty() && search != null) {
            search.search(people.map { it.id }, query.ifBlank { null }, limit, offset)
        } else {
            api.search(query, limit, offset, years, area)
        }
    }

    private fun fail(error: SearchError, detail: String?) {
        loaded = NOTHING
        pageEnds = emptyList()
        serverHasMore = false
        _state.update { it.copy(status = SearchStatus.FAILED, items = emptyList(), sets = emptyList(), canLoadMore = false, error = error, errorDetail = detail) }
    }

    private var refreshJob: Job? = null

    /**
     * The items and chips again, from the answers kept, through the library and places as they are now. With [bySha]
     * the answers' items are read from the database first; a newer refresh replaces one still reading.
     */
    private fun refresh() {
        val lookup = bySha ?: return present(resolver())
        val current = _state.value
        if (current.status != SearchStatus.DONE && current.status != SearchStatus.SEARCHING) return
        val sha256s = (firstAnswer.sha256s + loaded.sha256s.take(pageEnds.lastOrNull() ?: 0)).distinct()
        refreshJob?.cancel()
        refreshJob = scope.launch { present(resolverOf(lookup(sha256s))) }
    }

    /** [refresh] through [resolve]. */
    private fun present(resolveAll: (List<String>) -> List<MediaItem>) {
        val current = _state.value
        if (current.status != SearchStatus.DONE && current.status != SearchStatus.SEARCHING) return
        val hiding = madePrivate.isNotEmpty() && !showsPrivate()
        val resolve = if (hiding) { shas: List<String> -> resolveAll(shas).filter { it.id !in madePrivate } } else resolveAll
        val town = current.town
        val sources = stories?.value?.shownSources
        val hides: (MediaItem) -> Boolean = { sources != null && !sources.shows(it) }
        // A town is asked for as a box, which can reach into the next town.
        val (sets, hiddenBySources) = grouped(resolve, hides) { town == null || townOf(it) == town }
        val matches = resolve(firstAnswer.sha256s).filter { showHidden || !hides(it) }
        val yearChips = matches.filter { town == null || townOf(it) == town }
            .groupingBy { it.day.year }.eachCount()
            .entries.sortedByDescending { it.key }.map { SearchChip(it.key.toString(), it.value) }
        val townChips = matches.filter { current.year == null || it.day.year == current.year }
            .mapNotNull(::townOf).groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { SearchChip(it.key, it.value) }
        val canLoadMore = (pageEnds.lastOrNull() ?: 0) < loaded.size || (serverHasMore && loaded.size < MAX_DEPTH)
        _state.update {
            it.copy(
                items = sets.flatten(), sets = sets, yearChips = yearChips, townChips = townChips, canLoadMore = canLoadMore,
                hiddenBySources = hiddenBySources, showingHidden = showHidden,
            )
        }
    }

    /**
     * The pages shown of [loaded], as the library has them ([keep] ones only), in sets: in each page the moments in the
     * order of their best photo, each moment's sets in the order of theirs, each set best first. A page never moves
     * what the pages before it showed: its photos of a set shown already join that set (more dots), and its other
     * photos come after. Those the sources hide ([hides]) are counted, and left out unless [showHidden].
     */
    private fun grouped(
        resolve: (List<String>) -> List<MediaItem>,
        hides: (MediaItem) -> Boolean,
        keep: (MediaItem) -> Boolean,
    ): Pair<List<List<MediaItem>>, Int> {
        val rows = mutableListOf<MutableList<MediaItem>>()
        val rowOfSet = HashMap<Int, MutableList<MediaItem>>()
        val seen = HashSet<String>()
        var hidden = 0
        var start = 0
        for (end in pageEnds) {
            val moments = LinkedHashMap<Int, LinkedHashMap<Int, MutableList<MediaItem>>>()
            for (i in start until minOf(end, loaded.size)) {
                val item = resolve(listOf(loaded.sha256s[i])).firstOrNull()?.takeIf(keep) ?: continue
                if (!seen.add(item.id)) continue
                if (hides(item)) {
                    hidden++
                    if (!showHidden) continue
                }
                // Without the server's numbers (an older server) each photo is its own.
                val set = loaded.sets.getOrNull(i) ?: (-1 - i)
                val moment = loaded.moments.getOrNull(i) ?: (-1 - i)
                val shownSet = rowOfSet[set]
                if (shownSet != null) shownSet.add(item) else moments.getOrPut(moment) { LinkedHashMap() }.getOrPut(set) { mutableListOf() }.add(item)
            }
            for (sets in moments.values) {
                for ((set, items) in sets) {
                    rows.add(items)
                    rowOfSet[set] = items
                }
            }
            start = end
        }
        return rows to hidden
    }

    private fun townOf(item: MediaItem): String? {
        val known = stories?.value?.places ?: return null
        return known.location(item)?.let(known::name)?.town
    }

    /**
     * The box around this device's photos of [town], a little wider (about a kilometer) so photos at its edge are in;
     * null when none of them has a location, and the town is then left to [refresh] alone.
     */
    private fun areaOf(town: String): GeoArea? {
        val known = stories?.value?.places ?: return null
        val points = library.value.days.asSequence().flatMap { it.items }
            .mapNotNull { item -> known.location(item)?.takeIf { known.name(it)?.town == town } }
            .toList()
        if (points.isEmpty()) return null
        return GeoArea(points.minOf { it.latitude }, points.minOf { it.longitude }, points.maxOf { it.latitude }, points.maxOf { it.longitude })
            .widenedForSearch()
    }

    /** The box around a town's photos [AREA_MARGIN] wider each way, as far as the world goes. */
    private fun GeoArea.widenedForSearch() = GeoArea(
        south = (south - AREA_MARGIN).coerceAtLeast(-90.0),
        west = (west - AREA_MARGIN).coerceAtLeast(-180.0),
        north = (north + AREA_MARGIN).coerceAtMost(90.0),
        east = (east + AREA_MARGIN).coerceAtMost(180.0),
    )

    private fun resolver(): (List<String>) -> List<MediaItem> = libraryResolver(library.value, server?.value.orEmpty())

    private companion object {
        /** The first answer: the best this many, whose years and towns the filter counts (the server allows up to 1000). */
        const val CANDIDATES = 1000

        /** How far down the matches pages may go (offset + limit on the server). */
        const val MAX_DEPTH = 10_000

        val NOTHING = SearchAnswer(emptyList(), 0, 0)

        /** Degrees added around a town's photos (about a kilometer). */
        const val AREA_MARGIN = 0.01
    }
}
