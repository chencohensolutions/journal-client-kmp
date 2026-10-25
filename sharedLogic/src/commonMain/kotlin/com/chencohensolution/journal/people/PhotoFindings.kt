package com.chencohensolution.journal.people

import com.apollographql.apollo.api.Optional
import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.graphql.MediaFindingsQuery
import com.chencohensolution.journal.graphql.SimilarMediaQuery
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.search.ShaLookup
import com.chencohensolution.journal.search.resolverOf
import com.chencohensolution.journal.server.ServerFeatures
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.firstServerError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/** A face in the photo, and who it is: a named person or a group ([Person.isGroup]); null when in no one. */
data class FoundFace(val face: Face, val person: Person?)

/**
 * What the server found in a photo, for its details in the viewer: the [people] in it, the lines of [text] read in it
 * (top to bottom), and the photos that look most like it ([similar], as the library has them, most alike first). Each
 * is empty when there is none, the server has that index off, or it hasn't read the photo yet.
 */
data class PhotoFindings(
    val itemId: String = "",
    val people: List<FoundFace> = emptyList(),
    val text: List<String> = emptyList(),
    val similar: List<MediaItem> = emptyList(),
) {
    val isEmpty: Boolean get() = people.isEmpty() && text.isEmpty() && similar.isEmpty()

    companion object {
        val NONE = PhotoFindings()
    }
}

/** The server's answers for one file. */
internal data class FindingsAnswer(val faces: List<Face>, val text: List<String>, val similar: List<String>)

internal interface FindingsApi {
    /** [sha256]'s faces and text; null when the server can't be reached or doesn't have it. */
    suspend fun media(sha256: String): Pair<List<Face>, List<String>>?

    /** The sha256s most like [sha256]'s, most alike first; empty when similar photos are off or it isn't read yet. */
    suspend fun similar(sha256: String, limit: Int, includePrivate: Boolean): List<String>
}

internal class ApolloFindingsApi(private val http: ServerHttp) : FindingsApi {
    override suspend fun media(sha256: String): Pair<List<Face>, List<String>>? = http.graphql { apollo ->
        val response = apollo.query(MediaFindingsQuery(sha256)).execute()
        response.exception?.let { throw it }
        firstServerError(response.errors)?.let { throw it }
        response.data?.media?.let { media -> media.faces.map { it.faceFields.toFace() } to media.text.map { it.text } }
    }

    override suspend fun similar(sha256: String, limit: Int, includePrivate: Boolean): List<String> = http.graphql { apollo ->
        val response = apollo.query(
            SimilarMediaQuery(sha256, Optional.present(limit), Optional.presentIfNotNull(true.takeIf { includePrivate })),
        ).execute()
        response.exception?.let { throw it }
        // SIMILAR_OFF, NOT_READ_YET: nothing to show.
        if (!response.errors.isNullOrEmpty()) return@graphql emptyList()
        response.data?.similarMedia?.map { it.media.sha256 }.orEmpty()
    } ?: emptyList()
}

/**
 * The viewer's details: what the server found in the photo shown ([show]). Asked once per photo; [state] is the
 * photo's answer, or [PhotoFindings.NONE] while it comes (and offline). The faces' names come from [people]'s list.
 */
class PhotoFindingsController internal constructor(
    private val api: FindingsApi,
    private val people: PeopleController,
    /** The sha256 the server knows a photo by (ServerLibrary.sha256Of). */
    private val serverSha: suspend (MediaItem) -> String?,
    private val features: StateFlow<ServerFeatures?>,
    private val includePrivate: () -> Boolean,
    /** Similar photos as the library has them (LibraryController.itemsOfShas). */
    private val bySha: ShaLookup,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null
    private val cache = LinkedHashMap<String, FindingsAnswer>()

    private val _state = MutableStateFlow(PhotoFindings.NONE)
    val state: StateFlow<PhotoFindings> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: PhotoFindings get() = _state.value

    private var shown: MediaItem? = null

    init {
        // A face was corrected: the photo shown is asked again (who is in it may have changed).
        scope.launch {
            var changes = people.currentState.changes
            people.state.collect { state ->
                if (state.changes == changes) return@collect
                changes = state.changes
                cache.clear()
                shown?.let(::show)
            }
        }
    }

    /** Finds what the server found in [item]; nothing for a photo the server doesn't have. */
    fun show(item: MediaItem) {
        shown = item
        job?.cancel()
        _state.value = PhotoFindings(itemId = item.id)
        val on = features.value ?: ServerFeatures.NONE
        if (!on.faces && !on.textInPhotos && !on.similar) return
        job = scope.launch {
            // The server's file is looked up in the database (step 6e), so this waits for it.
            val sha = serverSha(item) ?: return@launch
            cache[sha]?.let { answer -> return@launch present(item.id, answer, resolver(answer)) }
            try {
                val media = async { if (on.faces || on.textInPhotos) api.media(sha) else null }
                val similar = async { if (on.similar) api.similar(sha, SIMILAR, includePrivate()) else emptyList() }
                val (faces, text) = media.await() ?: (emptyList<Face>() to emptyList())
                val answer = FindingsAnswer(faces, text, similar.await())
                cache[sha] = answer
                while (cache.size > CACHED) cache.remove(cache.keys.first())
                if (on.faces && faces.isNotEmpty() && people.currentState.status != PeopleStatus.READY) people.load()
                present(item.id, answer, resolver(answer))
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: the details show what this phone knows.
            }
        }
    }

    /** The library's items of [answer]'s similar photos, read from the database with [bySha]. */
    private suspend fun resolver(answer: FindingsAnswer): (List<String>) -> List<MediaItem> = resolverOf(bySha(answer.similar))

    private fun present(itemId: String, answer: FindingsAnswer, resolve: (List<String>) -> List<MediaItem>) {
        val known = people.currentState.list
        val found = answer.faces.filter { it.personId != null }.map { face -> FoundFace(face, known.find(face.personId!!)) }
            // Named people first, then groups; each once.
            .distinctBy { it.face.personId }
            .sortedBy { it.person?.isGroup ?: true }
        val similar = resolve(answer.similar).filter { it.id != itemId }
        _state.value = PhotoFindings(itemId, found, answer.text, similar)
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (PhotoFindings) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() = scope.cancel()

    private companion object {
        /** Similar photos asked for: the row shows 8, More the rest. */
        const val SIMILAR = 60
        const val CACHED = 50
    }
}
