package com.chencohensolution.journal.storage

import com.chencohensolution.journal.backup.MediaFiles
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.settings.JournalSettings
import io.ktor.client.plugins.timeout
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path
import kotlin.coroutines.cancellation.CancellationException

/** The platform's way to move photos to its trash (Android: the gallery's trash, kept there 30 days). */
interface PhotoTrash {
    /** Whether [trash] works now without the user's prompt (Android: the app has "Media management" access). */
    fun canTrashWithoutAsking(): Boolean

    /** Moves [items] to the trash without asking; true when every one of them left the library. */
    suspend fun trash(items: List<MediaItem>): Boolean
}

/** Where a run is: [done] of [total] groups checked. */
data class FreeUpProgress(
    val done: Int,
    val total: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    val currentName: String? = null,
)

sealed interface FreeUpActivity {
    data object Idle : FreeUpActivity
    data class Running(val progress: FreeUpProgress) : FreeUpActivity

    /** The last run stopped (offline, server down); what was checked so far is kept. */
    data class Stopped(val message: String) : FreeUpActivity
}

/** What the last run did. */
data class FreeUpResult(
    /** Files moved to the trash, and their bytes. */
    val removed: Int,
    val removedBytes: Long,
    /** Files checked that wait for the user to confirm the system prompt. */
    val waiting: Int,
    /** Files that stay on the phone, by why. */
    val kept: Map<KeptReason, Int>,
)

data class FreeUpState(
    val rule: FreeUpRule = FreeUpRule.DEFAULT,
    /** What [rule] would take off the phone now; null until counted. */
    val plan: FreeUpPlan? = null,
    val activity: FreeUpActivity = FreeUpActivity.Idle,
    /** Checked files waiting for the user's confirmation, and their bytes. */
    val waitingCount: Int = 0,
    val waitingBytes: Long = 0,
    val lastResult: FreeUpResult? = null,
)

/** A checked file waiting for the user's confirmation, as it was when checked. */
@Serializable
internal data class WaitingFile(
    val id: String,
    val video: Boolean,
    val size: Long,
    val modifiedMillis: Long?,
    val sha256: String,
    /** Files with the same number leave together (a pair). */
    val group: Int,
)

/**
 * "Free up space" (Settings → Storage on this phone): takes originals the server has off this phone, by a rule. A run
 * goes through the chosen photos one group at a time; each file is hashed here while the server hashes its copy
 * ([FreeUpChecker]), and a group that passed goes to the phone's trash right away when the platform allows it without
 * asking ([PhotoTrash]), else waits in `waiting.json` for one system prompt, shown from the app.
 */
class FreeUpController internal constructor(
    private val settings: JournalSettings,
    private val ledger: UploadLedger,
    private val library: MediaLibrary,
    files: MediaFiles,
    server: ServerCheck,
    private val fileSystem: FileSystem,
    directory: Path,
    private val now: () -> Long,
    /** Called when files were found changed or missing on the server, so backup sends them again. */
    private val onLedgerChanged: () -> Unit = {},
    /** Asks the platform to run in the background (with its progress notification). */
    private val onStart: () -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val runMutex = Mutex()
    private val fileMutex = Mutex()
    private val checker = FreeUpChecker(files, ledger, server)
    private val waitingFile = directory / "waiting.json"

    private val _state = MutableStateFlow(FreeUpState(rule = settings.freeUpRule))
    val state: StateFlow<FreeUpState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: FreeUpState get() = _state.value

    fun setRule(rule: FreeUpRule) {
        settings.freeUpRule = rule
        _state.update { it.copy(rule = rule, plan = null) }
        refreshPlan()
    }

    /** Counts what the rule would take off the phone now, and what waits for confirmation. */
    fun refreshPlan() {
        scope.launch {
            val rule = _state.value.rule
            val plan = runCatching { freeUpPlan(library.loadItems(), ledger.all(), rule, now()) }.getOrNull()
            val waiting = readWaiting()
            _state.update { state ->
                state.copy(
                    plan = plan.takeIf { state.rule == rule },
                    waitingCount = waiting.size,
                    waitingBytes = waiting.sumOf { it.size },
                )
            }
        }
    }

    /** "Free up space": runs in the background. */
    fun start() = onStart()

    /**
     * One run, called by the platform's background worker; runs never overlap. Null when it stopped early (see
     * [FreeUpState.activity]).
     */
    suspend fun runOnce(trash: PhotoTrash, onProgress: (FreeUpProgress) -> Unit = {}): FreeUpResult? = runMutex.withLock {
        val plan = freeUpPlan(library.loadItems(), ledger.all(), _state.value.rule, now())
        var progress = FreeUpProgress(0, plan.groups.size, 0, plan.bytes)
        val kept = mutableMapOf<KeptReason, Int>()
        var removed = 0
        var removedBytes = 0L
        var waiting = readWaiting()
        val waitingBefore = waiting.size
        fun report() {
            _state.update { it.copy(activity = FreeUpActivity.Running(progress), waitingCount = waiting.size, waitingBytes = waiting.sumOf { w -> w.size }) }
            onProgress(progress)
        }
        report()
        try {
            for (group in plan.groups) {
                if (waiting.any { w -> group.items.any { it.id == w.id } }) {
                    progress = progress.copy(done = progress.done + 1, bytesDone = progress.bytesDone + group.bytes)
                    continue
                }
                progress = progress.copy(currentName = group.items.first().fileName)
                report()
                val reason = checker.check(group)
                when {
                    reason != null -> {
                        kept[reason] = (kept[reason] ?: 0) + group.items.size
                        if (reason != KeptReason.DAMAGED_ON_SERVER) onLedgerChanged()
                    }
                    trash.canTrashWithoutAsking() && trash.trash(group.items) -> {
                        removed += group.items.size
                        removedBytes += group.bytes
                    }
                    else -> {
                        val number = (waiting.maxOfOrNull { it.group } ?: 0) + 1
                        waiting = waiting + group.items.map { item ->
                            WaitingFile(item.id, item.type == MediaType.VIDEO, item.sizeBytes ?: 0, item.modifiedMillis, ledger.entry(item.id)!!.sha256, number)
                        }
                        writeWaiting(waiting)
                    }
                }
                progress = progress.copy(done = progress.done + 1, bytesDone = progress.bytesDone + group.bytes, currentName = null)
                report()
            }
        } catch (e: CancellationException) {
            _state.update { it.copy(activity = FreeUpActivity.Idle) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(activity = FreeUpActivity.Stopped(e.describe())) }
            refreshPlan()
            return null
        }
        val result = FreeUpResult(removed, removedBytes, waiting.size - waitingBefore, kept)
        _state.update { it.copy(activity = FreeUpActivity.Idle, lastResult = result) }
        refreshPlan()
        result
    }

    /**
     * The checked files waiting for the user's confirmation, as they are in the library now. A file changed since it
     * was checked (size or date) is left out, and so is the rest of its group.
     */
    suspend fun waitingItems(): List<MediaItem> {
        val waiting = readWaiting()
        if (waiting.isEmpty()) return emptyList()
        val items = library.loadItems().associateBy { it.id }
        return waiting.groupBy { it.group }.values.flatMap { group ->
            val current = group.map { w -> items[w.id]?.takeIf { it.isOnDevice && it.sizeBytes == w.size && it.modifiedMillis == w.modifiedMillis } }
            if (current.any { it == null }) emptyList() else current.filterNotNull()
        }
    }

    /** The system prompt was answered (either way): nothing waits any more. */
    fun confirmationDone() {
        scope.launch {
            writeWaiting(emptyList())
            refreshPlan()
        }
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (FreeUpState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun readWaiting(): List<WaitingFile> = fileMutex.withLock {
        withContext(Dispatchers.IO) {
            if (!fileSystem.exists(waitingFile)) return@withContext emptyList()
            runCatching { json.decodeFromString(WAITING, fileSystem.read(waitingFile) { readUtf8() }) }.getOrDefault(emptyList())
        }
    }

    private suspend fun writeWaiting(waiting: List<WaitingFile>) = fileMutex.withLock {
        withContext(Dispatchers.IO) {
            if (waiting.isEmpty()) {
                fileSystem.delete(waitingFile, mustExist = false)
                return@withContext
            }
            fileSystem.createDirectories(waitingFile.parent!!)
            val temp = waitingFile.parent!! / "waiting.json.tmp"
            fileSystem.write(temp) { writeUtf8(json.encodeToString(WAITING, waiting)) }
            fileSystem.atomicMove(temp, waitingFile)
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
        val WAITING = ListSerializer(WaitingFile.serializer())
    }
}

/** The server's `POST /media/{sha256}/verify` (journal-server-go docs/media.md). */
internal class ServerVerifyApi(private val http: ServerHttp) : ServerCheck {
    override suspend fun verify(sha256: String): ServerVerdict {
        val client = http.client() ?: error("This phone isn't registered with the server")
        val response = client.post(http.url("/media/$sha256/verify")) {
            // The server reads the whole file first: minutes for a large video on a slow disk.
            timeout {
                requestTimeoutMillis = VERIFY_TIMEOUT_MILLIS
                socketTimeoutMillis = VERIFY_TIMEOUT_MILLIS
            }
        }
        if (response.status != HttpStatusCode.OK) error("The server answered ${response.status.value} when asked to check a file")
        val result = Json.parseToJsonElement(response.bodyAsText()).jsonObject["result"]?.jsonPrimitive?.content
        return when (result) {
            "ok" -> ServerVerdict.OK
            "missing" -> ServerVerdict.MISSING
            "mismatch" -> ServerVerdict.MISMATCH
            else -> error("Unexpected answer from the server: $result")
        }
    }

    private companion object {
        const val VERIFY_TIMEOUT_MILLIS = 30L * 60 * 1000
    }
}
