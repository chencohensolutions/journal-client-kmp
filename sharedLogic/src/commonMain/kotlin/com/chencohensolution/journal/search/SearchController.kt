package com.chencohensolution.journal.search

import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.graphql.SearchMediaQuery
import com.chencohensolution.journal.graphql.type.GeoAreaInput
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.createApolloClient
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.network.useClient
import com.chencohensolution.journal.server.ServerMedia
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
// hidden here too. The year and town chips narrow it: the server picks the best of that year or place.

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

    /** The server answered with an error: search is off there (no model), or it failed. */
    SERVER,
}

/** A year ("2025") or town ("Paris") the matches are in, with how many of them. */
data class SearchChip(val value: String, val count: Int)

data class SearchState(
    /** What was searched for, as typed. */
    val query: String = "",
    val status: SearchStatus = SearchStatus.IDLE,
    /** The photos found, best first, as the library shows them; of [year] and [town] when chosen. */
    val items: List<MediaItem> = emptyList(),
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
) {
    /** The server hasn't read every file yet, so some can't be found. */
    val stillReading: Boolean get() = status == SearchStatus.DONE && indexed < total
}

/** The server's answer: the files found, best first, and how far it has read the library. */
internal data class SearchAnswer(val sha256s: List<String>, val indexed: Int, val total: Int)

/**
 * Asks the server for the [limit] best matches of [text], of [years] and inside [area] when given; throws when it can't
 * be reached ([SearchUnreachable]) or answers with an error.
 */
internal fun interface SearchApi {
    suspend fun search(text: String, limit: Int, years: List<Int>?, area: GeoArea?): SearchAnswer
}

/** The server couldn't be reached, or this device has no identity to ask with. */
internal class SearchUnreachable(message: String) : Exception(message)

/** [SearchApi] over GraphQL, as this device's identity. */
internal class ApolloSearchApi(
    private val settings: JournalSettings,
    private val clientFactory: MtlsHttpClientFactory,
    private val trustedCas: List<ByteArray>,
) : SearchApi {
    override suspend fun search(text: String, limit: Int, years: List<Int>?, area: GeoArea?): SearchAnswer {
        val alias = settings.identityAlias ?: throw SearchUnreachable("This device isn't registered")
        return createApolloClient(clientFactory.create(alias, trustedCas), settings.serverUrl).useClient { apollo ->
            val query = SearchMediaQuery(
                text = text,
                limit = Optional.present(limit),
                years = Optional.presentIfNotNull(years),
                area = Optional.presentIfNotNull(area?.let { GeoAreaInput(it.south, it.west, it.north, it.east) }),
            )
            val response = apollo.query(query).execute()
            when (val exception = response.exception) {
                null -> Unit
                is ApolloNetworkException, is ApolloHttpException -> throw SearchUnreachable(exception.describe())
                else -> throw exception
            }
            response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
            val found = response.data?.searchMedia ?: throw IllegalStateException("No answer")
            SearchAnswer(found.hits.map { it.media.sha256 }, found.indexed, found.total)
        }
    }
}

/**
 * The Search screen's logic: [search] asks the server for the photos a sentence describes and [state] lists them, best
 * first, as the library has them. The first answer is the best [CANDIDATES]; their years and towns become the chips,
 * and [chooseYear] / [chooseTown] ask the server again for the best of that year or town (a town is sent as the box
 * around this device's photos of it, since the server has no place names). When the library or the places change (a
 * photo made private, a town named) the same answers are shown again through them. A new request replaces one running.
 */
class SearchController internal constructor(
    private val library: StateFlow<LibraryState>,
    private val api: SearchApi,
    /** The server's files, to find a device photo backup hasn't read yet by its file name and size. */
    private val server: StateFlow<List<ServerMedia>>? = null,
    /** The stories' places (where photos were taken and the towns' names), for the town chips. */
    private val stories: StateFlow<StoriesState>? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var searchJob: Job? = null

    /** The first answer's files, best first: what the chips count. */
    private var candidates: List<String> = emptyList()

    /** The files shown: the first answer's best, or the answer for the chosen year and town. */
    private var shown: List<String> = emptyList()

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: SearchState get() = _state.value

    init {
        scope.launch { library.collect { refresh() } }
        stories?.let { flow -> scope.launch { flow.map { it.places }.distinctUntilChanged().collect { refresh() } } }
    }

    /** Searches for [text], with no year or town; blank clears instead. */
    fun search(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return clear()
        _state.update { SearchState(query = query, status = SearchStatus.SEARCHING) }
        run { answer ->
            candidates = answer.sha256s
            shown = answer.sha256s.take(SHOWN)
        }
    }

    /** The best matches of [year] (null: any year), in the chosen town if one is. */
    fun chooseYear(year: Int?) = choose(year, _state.value.town)

    /** The best matches in [town] (null: anywhere), of the chosen year if one is. */
    fun chooseTown(town: String?) = choose(_state.value.year, town)

    /** Back to nothing searched. */
    fun clear() {
        searchJob?.cancel()
        candidates = emptyList()
        shown = emptyList()
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
        if (current.query.isEmpty() || candidates.isEmpty()) return
        _state.update { it.copy(year = year, town = town) }
        if (year == null && town == null) {
            // The first answer has the best of all already.
            searchJob?.cancel()
            shown = candidates.take(SHOWN)
            _state.update { it.copy(status = SearchStatus.DONE) }
            refresh()
            return
        }
        _state.update { it.copy(status = SearchStatus.SEARCHING) }
        run(years = year?.let(::listOf), area = town?.let(::areaOf)) { answer -> shown = answer.sha256s }
    }

    /** Asks the server for the query (with [years] and [area]) and shows its answer through [apply]. */
    private fun run(years: List<Int>? = null, area: GeoArea? = null, apply: (SearchAnswer) -> Unit) {
        searchJob?.cancel()
        val query = _state.value.query
        val limit = if (years == null && area == null) CANDIDATES else SHOWN
        searchJob = scope.launch {
            try {
                val answer = api.search(query, limit, years, area)
                apply(answer)
                _state.update { it.copy(status = SearchStatus.DONE, indexed = answer.indexed, total = answer.total, error = null, errorDetail = null) }
                refresh()
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchUnreachable) {
                fail(SearchError.OFFLINE, e.message)
            } catch (e: Exception) {
                fail(SearchError.SERVER, e.describe())
            }
        }
    }

    private fun fail(error: SearchError, detail: String?) {
        shown = emptyList()
        _state.update { it.copy(status = SearchStatus.FAILED, items = emptyList(), error = error, errorDetail = detail) }
    }

    /** The items and chips again, from the answers kept, through the library and places as they are now. */
    private fun refresh() {
        val current = _state.value
        if (current.status != SearchStatus.DONE && current.status != SearchStatus.SEARCHING) return
        val resolve = resolver()
        val town = current.town
        // A town is asked for as a box, which can reach into the next town.
        val items = resolve(shown).filter { town == null || townOf(it) == town }
        val matches = resolve(candidates)
        val yearChips = matches.filter { town == null || townOf(it) == town }
            .groupingBy { it.day.year }.eachCount()
            .entries.sortedByDescending { it.key }.map { SearchChip(it.key.toString(), it.value) }
        val townChips = matches.filter { current.year == null || it.day.year == current.year }
            .mapNotNull(::townOf).groupingBy { it }.eachCount()
            .entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
            .map { SearchChip(it.key, it.value) }
        _state.update { it.copy(items = items, yearChips = yearChips, townChips = townChips) }
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
        return GeoArea(
            south = (points.minOf { it.latitude } - AREA_MARGIN).coerceAtLeast(-90.0),
            west = (points.minOf { it.longitude } - AREA_MARGIN).coerceAtLeast(-180.0),
            north = (points.maxOf { it.latitude } + AREA_MARGIN).coerceAtMost(90.0),
            east = (points.maxOf { it.longitude } + AREA_MARGIN).coerceAtMost(180.0),
        )
    }

    /**
     * sha256s → the library's items, in their order. A device photo backup has read carries its sha256; one it
     * hasn't is found by the server file's name and size, as the library matches them. What the library doesn't show
     * is left out.
     */
    private fun resolver(): (List<String>) -> List<MediaItem> {
        val items = library.value.days.flatMap { it.items }
        val bySha = items.filter { it.sha256 != null }.associateBy { it.sha256!! }
        val serverFiles = server?.value.orEmpty().associateBy { it.sha256 }
        val byNameAndSize by lazy { items.filter { it.sha256 == null && it.fileName != null }.associateBy { it.fileName!!.lowercase() to it.sizeBytes } }
        return { sha256s ->
            sha256s.mapNotNull { sha ->
                bySha[sha] ?: serverFiles[sha]?.let { byNameAndSize[it.fileName.lowercase() to it.size] }
            }.distinctBy { it.id }
        }
    }

    private companion object {
        /** The first answer: the best this many, whose years and towns the chips count (the server allows up to 1000). */
        const val CANDIDATES = 1000

        /** Photos shown per search, best first. */
        const val SHOWN = 100

        /** Degrees added around a town's photos (about a kilometer). */
        const val AREA_MARGIN = 0.01
    }
}
