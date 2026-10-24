package com.chencohensolution.journal.server

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Error
import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.graphql.PauseIndexingMutation
import com.chencohensolution.journal.graphql.ServerIndexingQuery
import com.chencohensolution.journal.graphql.StartIndexingMutation
import com.chencohensolution.journal.graphql.fragment.IndexingFields
import com.chencohensolution.journal.graphql.type.IndexingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

// Settings › Search › On your server: the server reads each of this user's photos once for its indexes (search,
// people, text in photos, similar photos, best-looking), nightly or as they come (journal-server-go docs/indexes.md).
// A phone can start a run of its user's photos now, follow it and pause it; it sees only its own user's run.

/** Where this user's run is. */
enum class IndexingRunState {
    /** No run in hand. */
    IDLE,

    /** Waiting for other runs on the server to end ([ServerIndexingState.queuedBehind]). */
    QUEUED,
    RUNNING,

    /** Paused from a phone; [ServerIndexing.start] goes on. */
    PAUSED,
}

/** How many photos one index read in the run, and how many it couldn't: search, faces, ocr, similar, aesthetic, nudity. */
data class IndexingCount(val name: String, val read: Int, val failed: Int)

/** How this user's last run ended. */
data class IndexingLastRun(
    val startedAtMillis: Long,
    val finishedAtMillis: Long,
    val files: Int,
    val failed: Int,
    /** Paused, or the server stopped, before every photo was read. */
    val stopped: Boolean,
    /** Why it couldn't go on; null when it could. */
    val error: String?,
)

data class ServerIndexingState(
    val run: IndexingRunState = IndexingRunState.IDLE,
    /** The nightly run started it, not a phone. */
    val nightly: Boolean = false,
    /** Photos read in this run (with those read before a pause), and how many it reads in all. */
    val done: Int = 0,
    val total: Int = 0,
    val startedAtMillis: Long? = null,
    /** QUEUED: runs ahead of this one. */
    val queuedBehind: Int? = null,
    val indexes: List<IndexingCount> = emptyList(),
    val lastRun: IndexingLastRun? = null,
    /** When the next nightly run starts; null on a server that reads photos as they come. */
    val nextNightlyMillis: Long? = null,
    /** The last call failed (the server can't be reached); what is shown is from before. */
    val error: String? = null,
    /** Start or pause was asked and the server hasn't answered yet. */
    val acting: Boolean = false,
    /** The server answered at least once. */
    val loaded: Boolean = false,
) {
    /** How far the run is, 0 to 1. */
    val fraction: Float get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else 0f

    companion object {
        /** Before the server answered; for Swift, which has no default arguments. */
        val NONE = ServerIndexingState()
    }
}

internal interface IndexingApi {
    suspend fun status(): ServerIndexingState
    suspend fun start(): ServerIndexingState
    suspend fun pause(): ServerIndexingState
}

internal class HttpIndexingApi(private val http: ServerHttp) : IndexingApi {
    override suspend fun status() = call {
        val response = it.query(ServerIndexingQuery()).execute()
        answer(response.exception, response.errors, response.data?.indexing?.indexingFields)
    }

    override suspend fun start() = call {
        val response = it.mutation(StartIndexingMutation()).execute()
        answer(response.exception, response.errors, response.data?.startIndexing?.indexingFields)
    }

    override suspend fun pause() = call {
        val response = it.mutation(PauseIndexingMutation()).execute()
        answer(response.exception, response.errors, response.data?.pauseIndexing?.indexingFields)
    }

    private suspend fun call(block: suspend (ApolloClient) -> ServerIndexingState): ServerIndexingState =
        http.graphql(block) ?: throw ServerError("This device isn't registered", null)

    private fun answer(exception: Exception?, errors: List<Error>?, fields: IndexingFields?): ServerIndexingState {
        exception?.let { throw it }
        firstServerError(errors)?.let { throw it }
        return fields?.toState() ?: throw ServerError("The server sent no answer", null)
    }
}

@OptIn(ExperimentalTime::class)
private fun millis(time: String?): Long? = time?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

internal fun IndexingFields.toState() = ServerIndexingState(
    run = when (state) {
        IndexingState.QUEUED -> IndexingRunState.QUEUED
        IndexingState.RUNNING -> IndexingRunState.RUNNING
        IndexingState.PAUSED -> IndexingRunState.PAUSED
        else -> IndexingRunState.IDLE
    },
    nightly = nightly,
    done = done,
    total = total,
    startedAtMillis = millis(startedAt),
    queuedBehind = queuedBehind,
    indexes = indexes.map { IndexingCount(it.name, it.read, it.failed) },
    lastRun = lastRun?.let {
        IndexingLastRun(millis(it.startedAt) ?: 0, millis(it.finishedAt) ?: 0, it.files, it.failed, it.stopped, it.error)
    },
    nextNightlyMillis = millis(nextNightly),
    loaded = true,
)

/**
 * The reading of this user's photos on the server, for Settings › Search › On your server. While the screen shows,
 * [watch] asks the server how it is going: every [busyEveryMillis] while a run is going or waiting, every
 * [idleEveryMillis] otherwise.
 */
class ServerIndexing internal constructor(
    private val api: IndexingApi,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val busyEveryMillis: Long = 2_000,
    private val idleEveryMillis: Long = 30_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Cuts [watch]'s wait short after start or pause, so a run just started is followed every 2 s at once. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(ServerIndexingState())
    val state: StateFlow<ServerIndexingState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: ServerIndexingState get() = _state.value

    /** Asks the server once. */
    fun refresh() {
        scope.launch { ask(api::status) }
    }

    /** Reads this user's new photos now, or goes on with a paused run. */
    fun start() {
        _state.update { it.copy(acting = true) }
        scope.launch {
            ask(api::start)
            wake.trySend(Unit)
        }
    }

    /** Pauses this user's run after the photo in hand. */
    fun pause() {
        _state.update { it.copy(acting = true) }
        scope.launch {
            ask(api::pause)
            wake.trySend(Unit)
        }
    }

    /** Asks the server how the run is going until the handle is cancelled: while the screen shows. */
    fun watch(): ObservationHandle {
        val job = scope.launch {
            while (true) {
                ask(api::status)
                val busy = _state.value.run == IndexingRunState.RUNNING || _state.value.run == IndexingRunState.QUEUED
                withTimeoutOrNull(if (busy) busyEveryMillis else idleEveryMillis) { wake.receive() }
            }
        }
        return ObservationHandle { job.cancel() }
    }

    private suspend fun ask(call: suspend () -> ServerIndexingState) {
        try {
            _state.value = call()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "The server can't be reached", acting = false) }
        }
    }

    fun observe(onChange: (ServerIndexingState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() = scope.cancel()
}
