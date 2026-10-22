package com.chencohensolution.journal.search

import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.apollo.exception.ApolloNetworkException
import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.graphql.SearchMediaQuery
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.createApolloClient
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.network.useClient
import com.chencohensolution.journal.server.ServerMedia
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

// Text search: a sentence ("a dog on the beach at sunset") sent to the server, which answers with the photos and
// videos it describes best (`searchMedia`, see the server's docs/search.md). The answer is a list of sha256s; they are
// shown as the library shows those photos, so what the library hides (private files, nudity over the threshold) stays
// hidden here too.

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

data class SearchState(
    /** What was searched for, as typed. */
    val query: String = "",
    val status: SearchStatus = SearchStatus.IDLE,
    /** The photos found, best first, as the library shows them. */
    val items: List<MediaItem> = emptyList(),
    /** How many of the user's files the server has read so far; the others can't be found yet. */
    val indexed: Int = 0,
    /** How many files the user has on the server. */
    val total: Int = 0,
    val error: SearchError? = null,
    /** The server's or the connection's own words, for a failed search. */
    val errorDetail: String? = null,
) {
    /** The server hasn't read every file yet, so some can't be found. */
    val stillReading: Boolean get() = status == SearchStatus.DONE && indexed < total
}

/** The server's answer: the files found, best first, and how far it has read the library. */
internal data class SearchAnswer(val sha256s: List<String>, val indexed: Int, val total: Int)

/** Asks the server; throws when it can't be reached ([SearchUnreachable]) or answers with an error. */
internal fun interface SearchApi {
    suspend fun search(text: String, limit: Int): SearchAnswer
}

/** The server couldn't be reached, or this device has no identity to ask with. */
internal class SearchUnreachable(message: String) : Exception(message)

/** [SearchApi] over GraphQL, as this device's identity. */
internal class ApolloSearchApi(
    private val settings: JournalSettings,
    private val clientFactory: MtlsHttpClientFactory,
    private val trustedCas: List<ByteArray>,
) : SearchApi {
    override suspend fun search(text: String, limit: Int): SearchAnswer {
        val alias = settings.identityAlias ?: throw SearchUnreachable("This device isn't registered")
        return createApolloClient(clientFactory.create(alias, trustedCas), settings.serverUrl).useClient { apollo ->
            val response = apollo.query(SearchMediaQuery(text, Optional.present(limit))).execute()
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
 * first, as the library has them. When the library changes (a photo made private, the server's list synced) the same
 * answer is shown again through it. A new search replaces one still running.
 */
class SearchController internal constructor(
    private val library: StateFlow<LibraryState>,
    private val api: SearchApi,
    /** The server's files, to find a device photo backup hasn't read yet by its file name and size. */
    private val server: StateFlow<List<ServerMedia>>? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var searchJob: Job? = null

    /** The last answer's files, best first. */
    private var found: List<String> = emptyList()

    private val _state = MutableStateFlow(SearchState())
    val state: StateFlow<SearchState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: SearchState get() = _state.value

    init {
        scope.launch { library.collect { if (found.isNotEmpty()) _state.update { it.copy(items = resolve(found)) } } }
    }

    /** Searches for [text]; blank clears instead. */
    fun search(text: String) {
        val query = text.trim()
        if (query.isEmpty()) return clear()
        searchJob?.cancel()
        _state.update { it.copy(query = query, status = SearchStatus.SEARCHING, error = null, errorDetail = null) }
        searchJob = scope.launch {
            try {
                val answer = api.search(query, LIMIT)
                found = answer.sha256s
                _state.value = SearchState(query, SearchStatus.DONE, resolve(found), answer.indexed, answer.total)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SearchUnreachable) {
                fail(SearchError.OFFLINE, e.message)
            } catch (e: Exception) {
                fail(SearchError.SERVER, e.describe())
            }
        }
    }

    /** Back to nothing searched. */
    fun clear() {
        searchJob?.cancel()
        found = emptyList()
        _state.value = SearchState()
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (SearchState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() = scope.cancel()

    private fun fail(error: SearchError, detail: String?) {
        found = emptyList()
        _state.update { it.copy(status = SearchStatus.FAILED, items = emptyList(), indexed = 0, total = 0, error = error, errorDetail = detail) }
    }

    /**
     * [sha256s] as the library shows them, in their order. A device photo backup has read carries its sha256; one it
     * hasn't is found by the server file's name and size, as the library matches them. What the library doesn't show
     * is left out.
     */
    private fun resolve(sha256s: List<String>): List<MediaItem> {
        val items = library.value.days.flatMap { it.items }
        val bySha = items.filter { it.sha256 != null }.associateBy { it.sha256!! }
        val serverFiles = server?.value.orEmpty().associateBy { it.sha256 }
        val byNameAndSize by lazy { items.filter { it.sha256 == null && it.fileName != null }.associateBy { it.fileName!!.lowercase() to it.sizeBytes } }
        return sha256s.mapNotNull { sha ->
            bySha[sha] ?: serverFiles[sha]?.let { byNameAndSize[it.fileName.lowercase() to it.size] }
        }.distinctBy { it.id }
    }

    private companion object {
        /** Photos asked for per search, best first (the server allows up to 1000). */
        const val LIMIT = 100
    }
}
