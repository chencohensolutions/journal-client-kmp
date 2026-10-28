package com.chencohensolution.journal.server

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Error
import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.graphql.ServerCopiesQuery
import com.chencohensolution.journal.graphql.StartCopyMutation
import com.chencohensolution.journal.graphql.StopCopyMutation
import com.chencohensolution.journal.graphql.fragment.DataCopyFields
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
import com.chencohensolution.journal.graphql.type.CopyProblem as ServerCopyProblem

// Settings › Backup › Copies on other disks: the server keeps plain copies of this user's files in other folders
// (journal-server-go docs/copies.md), daily and as files arrive. A phone sees how each is doing, from the server's
// last compare of the copy with the user's files, and can start a pass now; it sees only its own user's copies.

/** Why a copy isn't kept up now. */
enum class CopyProblem {
    /** Its folder can't be reached: a disk unplugged, a share that is down. Passes stop until it is back. */
    FOLDER_MISSING,

    /** The last pass stopped: the copy's disk is full. */
    DISK_FULL,
}

/** How much a copy's state matters, worst last: it picks the color of its line, bar and mark. */
enum class CopyHealth {
    /** Holds every file. */
    OK,

    /** Copying, or lacking what arrived since its last pass. */
    BUSY,

    /** Kept up, but files failed or it sits on the server's own disk. */
    WARN,

    /** Not kept up: its folder is gone or its disk is full. */
    ERROR,
}

/**
 * A pass going now. It first finds what it will copy ([counting]); then it copies [totalFiles] originals of
 * [totalBytes], [doneFiles] in place and [doneBytes] written so far, the file in hand included.
 */
data class CopyRunning(
    val startedAtMillis: Long,
    val counting: Boolean = false,
    val totalFiles: Int = 0,
    val totalBytes: Long = 0,
    val doneFiles: Int = 0,
    val doneBytes: Long = 0,
) {
    /** How far the pass is in bytes, 0 to 1; 0 while counting. */
    val fraction: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
}

/** How the copy's last full pass ended; [stopped] when Stop ended it before it copied everything. */
data class CopyPass(val finishedAtMillis: Long, val files: Int, val bytes: Long, val failed: Int, val stopped: Boolean = false)

/** One copy folder: how many of the user's files it holds, and how its passes go. */
data class DataCopy(
    /** The folder as the server's config.json names it, "E:\Journal copy\hen". */
    val folder: String,
    /** Copies each file a few seconds after it arrives, besides the daily pass. */
    val realtime: Boolean,
    /** The daily pass, in the server's local time ("03:30"). */
    val dailyAt: String,
    /** On the server's own disk: a failing disk would take both. */
    val sameDisk: Boolean,
    /** The user's files at the last compare, and how many of them the copy held (0 before a compare). */
    val originals: Int,
    val copiedFiles: Int,
    /** What the copy lacked, and its size. */
    val missingFiles: Int,
    val missingBytes: Long,
    /** Files still in the copy that are gone from the server (kept: nothing is deleted from a copy). */
    val inCopyOnly: Int,
    /** Free space on the copy's disk; null while its folder can't be reached. */
    val freeBytes: Long?,
    /** When the counts were found; null before the first compare. */
    val checkedAtMillis: Long?,
    /** A compare is being made: the counts change when it ends. */
    val checking: Boolean,
    val running: CopyRunning?,
    val lastPass: CopyPass?,
    val problem: CopyProblem?,
    /** Since when [problem] holds. */
    val problemSinceMillis: Long?,
) {
    /**
     * How many of the user's files the copy holds now: while a pass copies, all but what it still has to copy;
     * otherwise what the last compare found.
     */
    val heldFiles: Int
        get() {
            val running = running ?: return copiedFiles
            if (running.counting) return copiedFiles
            return (originals - (running.totalFiles - running.doneFiles)).coerceIn(0, originals)
        }

    /** Files the last pass couldn't copy; the next one tries them again. */
    val failedFiles: Int get() = lastPass?.failed ?: 0

    /** Holds every file, at the last compare. */
    val upToDate: Boolean get() = checkedAtMillis != null && missingFiles == 0

    val health: CopyHealth
        get() = when {
            problem != null -> CopyHealth.ERROR
            failedFiles > 0 || sameDisk -> CopyHealth.WARN
            running != null || missingFiles > 0 -> CopyHealth.BUSY
            else -> CopyHealth.OK
        }
}

/** How the copies are, in a line (Backup's Copies row). */
sealed interface CopiesSummary {
    /** The worst copy needs a look: its folder is gone, its disk full, files failed, or it is on the server's disk. */
    data class NeedsALook(val folder: String) : CopiesSummary

    data class Copying(val folder: String) : CopiesSummary

    /** No copy was compared yet. */
    data object Checking : CopiesSummary

    data class AllUpToDate(val count: Int) : CopiesSummary

    data class SomeUpToDate(val upToDate: Int, val count: Int) : CopiesSummary
}

data class ServerCopiesState(
    /** In the server's order; empty when the user has none. */
    val copies: List<DataCopy> = emptyList(),
    /** The last call failed (the server can't be reached); what is shown is from before. */
    val error: String? = null,
    /** Copy now was asked and the server hasn't answered yet. */
    val acting: Boolean = false,
    /** The server answered at least once. */
    val loaded: Boolean = false,
) {
    /** The oldest of the copies' compares: what the page says it checked. */
    val checkedAtMillis: Long? get() = copies.mapNotNull { it.checkedAtMillis }.minOrNull()

    /** A pass or a compare is going: worth asking again soon. */
    val busy: Boolean get() = copies.any { it.running != null || it.checking }

    /** The copies in a line; null when there are none. */
    val summary: CopiesSummary?
        get() {
            if (copies.isEmpty()) return null
            val worst = copies.maxBy { it.health.ordinal }
            return when {
                worst.health == CopyHealth.ERROR || worst.health == CopyHealth.WARN -> CopiesSummary.NeedsALook(worst.folder)
                copies.any { it.running != null } -> CopiesSummary.Copying(copies.first { it.running != null }.folder)
                copies.all { it.checkedAtMillis == null } -> CopiesSummary.Checking
                copies.all { it.upToDate } -> CopiesSummary.AllUpToDate(copies.size)
                else -> CopiesSummary.SomeUpToDate(copies.count { it.upToDate }, copies.size)
            }
        }

    companion object {
        /** Before the server answered; for Swift, which has no default arguments. */
        val NONE = ServerCopiesState()
    }
}

internal interface CopiesApi {
    suspend fun copies(): List<DataCopy>
    suspend fun start(folder: String): DataCopy
    suspend fun stop(folder: String): DataCopy
}

internal class HttpCopiesApi(private val http: ServerHttp) : CopiesApi {
    override suspend fun copies() = call {
        val response = it.query(ServerCopiesQuery()).execute()
        answer(response.exception, response.errors, response.data?.copies?.map { copy -> copy.dataCopyFields.toCopy() })
    }

    override suspend fun start(folder: String) = call {
        val response = it.mutation(StartCopyMutation(folder)).execute()
        answer(response.exception, response.errors, response.data?.startCopy?.dataCopyFields?.toCopy())
    }

    override suspend fun stop(folder: String) = call {
        val response = it.mutation(StopCopyMutation(folder)).execute()
        answer(response.exception, response.errors, response.data?.stopCopy?.dataCopyFields?.toCopy())
    }

    private suspend fun <T> call(block: suspend (ApolloClient) -> T): T =
        http.graphql(block) ?: throw ServerError("This device isn't registered", null)

    private fun <T> answer(exception: Exception?, errors: List<Error>?, value: T?): T {
        exception?.let { throw it }
        firstServerError(errors)?.let { throw it }
        return value ?: throw ServerError("The server sent no answer", null)
    }
}

@OptIn(ExperimentalTime::class)
private fun millis(time: String?): Long? = time?.let { runCatching { Instant.parse(it).toEpochMilliseconds() }.getOrNull() }

internal fun DataCopyFields.toCopy() = DataCopy(
    folder = folder,
    realtime = realtime,
    dailyAt = dailyAt,
    sameDisk = sameDisk,
    originals = originals,
    copiedFiles = copied,
    missingFiles = missing,
    missingBytes = missingBytes,
    inCopyOnly = inCopyOnly,
    freeBytes = freeBytes,
    checkedAtMillis = millis(checkedAt),
    checking = checking,
    running = running?.let {
        CopyRunning(millis(it.startedAt) ?: 0, it.counting, it.totalFiles, it.totalBytes, it.doneFiles, it.doneBytes)
    },
    lastPass = lastPass?.let { CopyPass(millis(it.finishedAt) ?: 0, it.files, it.bytes, it.failed, it.stopped) },
    problem = when (problem) {
        ServerCopyProblem.FOLDER_MISSING -> CopyProblem.FOLDER_MISSING
        ServerCopyProblem.DISK_FULL -> CopyProblem.DISK_FULL
        else -> null
    },
    problemSinceMillis = millis(problemSince),
)

/**
 * This user's copies on the server, for Settings › Backup and its Copies page. [refresh] asks once (Backup's row);
 * while the page shows, [watch] asks every [busyEveryMillis] while a pass or a compare is going, every
 * [idleEveryMillis] otherwise.
 */
class ServerCopies internal constructor(
    private val api: CopiesApi,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val busyEveryMillis: Long = 3_000,
    private val idleEveryMillis: Long = 30_000,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** Cuts [watch]'s wait short after Copy now, so the pass just started is followed at once. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private val _state = MutableStateFlow(ServerCopiesState())
    val state: StateFlow<ServerCopiesState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: ServerCopiesState get() = _state.value

    /** Asks the server once. */
    fun refresh() {
        scope.launch { ask() }
    }

    /** Starts a full pass of the copy in [folder] now. */
    fun copyNow(folder: String) = act(folder, api::start)

    /** Stops the pass of the copy in [folder] going now; what it copied stays. */
    fun stop(folder: String) = act(folder, api::stop)

    private fun act(folder: String, call: suspend (String) -> DataCopy) {
        _state.update { it.copy(acting = true) }
        scope.launch {
            try {
                val copy = call(folder)
                _state.update { state ->
                    state.copy(copies = state.copies.map { if (it.folder == folder) copy else it }, error = null, acting = false)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "The server can't be reached", acting = false) }
            }
            wake.trySend(Unit)
        }
    }

    /** Asks the server how the copies are doing until the handle is cancelled: while the page shows. */
    fun watch(): ObservationHandle {
        val job = scope.launch {
            while (true) {
                ask()
                withTimeoutOrNull(if (_state.value.busy) busyEveryMillis else idleEveryMillis) { wake.receive() }
            }
        }
        return ObservationHandle { job.cancel() }
    }

    private suspend fun ask() {
        try {
            val copies = api.copies()
            _state.update { it.copy(copies = copies, error = null, loaded = true) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(error = e.message ?: "The server can't be reached") }
        }
    }

    fun observe(onChange: (ServerCopiesState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() = scope.cancel()
}
