package com.chencohensolution.journal.storage

import com.chencohensolution.journal.backup.MediaFiles
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaSource
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
import kotlinx.coroutines.NonCancellable
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

    /** Moves [items] to the trash without asking, all in one request; the ids of those that left the library. */
    suspend fun trash(items: List<MediaItem>): Set<String>
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
    /** The sources whose photos may leave the phone (all by default). */
    val sources: Set<MediaSource> = MediaSource.entries.toSet(),
    /** What [rule] would take off the phone now; null until counted. */
    val plan: FreeUpPlan? = null,
    val activity: FreeUpActivity = FreeUpActivity.Idle,
    /** Checked files waiting for the user's confirmation, and their bytes. */
    val waitingCount: Int = 0,
    val waitingBytes: Long = 0,
    val lastResult: FreeUpResult? = null,
) {
    /** Checked files wait and no run is going: the platform shows its one prompt for them (once). */
    val needsConfirmation: Boolean get() = waitingCount > 0 && activity !is FreeUpActivity.Running

    /** One line for the Settings row. */
    val summary: FreeUpSummary get() {
        val running = (activity as? FreeUpActivity.Running)?.progress
        return when {
            running != null -> FreeUpSummary.Checking((running.done + 1).coerceAtMost(running.total), running.total)
            waitingCount > 0 -> FreeUpSummary.Waiting(waitingCount, waitingBytes)
            plan == null -> FreeUpSummary.Counting
            plan.groups.isEmpty() -> FreeUpSummary.Nothing
            else -> FreeUpSummary.CanFree(plan.bytes)
        }
    }

    /** What the run is doing, why it stopped or what the last one did, and what waits: one line each. */
    val statusLines: List<FreeUpLine> get() = buildList {
        when (val a = activity) {
            is FreeUpActivity.Running -> add(FreeUpLine.Checking((a.progress.done + 1).coerceAtMost(a.progress.total), a.progress.total, a.progress.currentName))
            is FreeUpActivity.Stopped -> add(FreeUpLine.Stopped(a.message))
            FreeUpActivity.Idle -> lastResult?.let { result ->
                if (result.removed > 0) add(FreeUpLine.Removed(result.removed, result.removedBytes))
                result.kept.forEach { (reason, count) -> add(FreeUpLine.Kept(reason, count)) }
            }
        }
        if (waitingCount > 0) add(FreeUpLine.Waiting(waitingCount, waitingBytes))
    }
}

/** The Free up space row in Settings. */
sealed interface FreeUpSummary {
    data class Checking(val number: Int, val total: Int) : FreeUpSummary
    data class Waiting(val count: Int, val bytes: Long) : FreeUpSummary
    data object Counting : FreeUpSummary
    data object Nothing : FreeUpSummary
    data class CanFree(val bytes: Long) : FreeUpSummary
}

/** One line of Free up space's status. */
sealed interface FreeUpLine {
    data class Checking(val number: Int, val total: Int, val fileName: String?) : FreeUpLine
    data class Stopped(val message: String) : FreeUpLine
    data class Removed(val count: Int, val bytes: Long) : FreeUpLine
    data class Kept(val reason: KeptReason, val count: Int) : FreeUpLine
    data class Waiting(val count: Int, val bytes: Long) : FreeUpLine
}

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

    private val _state = MutableStateFlow(FreeUpState(rule = settings.freeUpRule, sources = settings.freeUpSources))
    val state: StateFlow<FreeUpState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: FreeUpState get() = _state.value

    fun setRule(rule: FreeUpRule) {
        settings.freeUpRule = rule
        _state.update { it.copy(rule = rule, plan = null) }
        refreshPlan()
    }

    /** Lets [source]'s photos leave the phone, or keeps them; the last source can't be turned off. */
    fun toggleSource(source: MediaSource) {
        val current = _state.value.sources
        val sources = if (source in current) (current - source).ifEmpty { return } else current + source
        settings.freeUpSources = sources
        _state.update { it.copy(sources = sources, plan = null) }
        refreshPlan()
    }

    /** Counts what the rule would take off the phone now, and what waits for confirmation. */
    fun refreshPlan() {
        scope.launch {
            val rule = _state.value.rule
            val sources = _state.value.sources
            val plan = runCatching { freeUpPlan(library.loadItems(), ledger.all(), rule, now(), sources) }.getOrNull()
            val waiting = readWaiting()
            _state.update { state ->
                state.copy(
                    plan = plan.takeIf { state.rule == rule && state.sources == sources },
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
        val plan = freeUpPlan(library.loadItems(), ledger.all(), settings.freeUpRule, now(), settings.freeUpSources)
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
        // Groups that passed, trashed together: one system request per batch instead of one per photo.
        val passed = mutableListOf<FreeUpGroup>()
        suspend fun flush() {
            if (passed.isEmpty()) return
            val gone = if (trash.canTrashWithoutAsking()) trash.trash(passed.flatMap { it.items }) else emptySet()
            for (group in passed) {
                // A pair leaves together: what the batch left behind of it waits for the prompt.
                val left = group.items.filter { it.id !in gone }
                removed += group.items.size - left.size
                removedBytes += group.items.filter { it.id in gone }.sumOf { it.sizeBytes ?: 0 }
                if (left.isEmpty()) continue
                val number = (waiting.maxOfOrNull { it.group } ?: 0) + 1
                waiting = waiting + left.map { item ->
                    WaitingFile(item.id, item.type == MediaType.VIDEO, item.sizeBytes ?: 0, item.modifiedMillis, ledger.entry(item.id)!!.sha256, number)
                }
            }
            passed.clear()
            writeWaiting(waiting)
        }
        try {
            for (group in plan.groups) {
                if (waiting.any { w -> group.items.any { it.id == w.id } }) {
                    progress = progress.copy(done = progress.done + 1, bytesDone = progress.bytesDone + group.bytes)
                    continue
                }
                progress = progress.copy(currentName = group.items.first().fileName)
                report()
                val reason = checker.check(group)
                if (reason != null) {
                    kept[reason] = (kept[reason] ?: 0) + group.items.size
                    if (reason != KeptReason.DAMAGED_ON_SERVER) onLedgerChanged()
                } else {
                    passed += group
                    if (passed.sumOf { it.items.size } >= TRASH_BATCH) flush()
                }
                progress = progress.copy(done = progress.done + 1, bytesDone = progress.bytesDone + group.bytes, currentName = null)
                report()
            }
            flush()
        } catch (e: CancellationException) {
            // Stopped: what passed so far still goes (it was checked); the rest stays.
            withContext(NonCancellable) { runCatching { flush() } }
            _state.update { it.copy(activity = FreeUpActivity.Idle) }
            throw e
        } catch (e: Exception) {
            runCatching { flush() }
            _state.update { it.copy(activity = FreeUpActivity.Stopped(e.describe())) }
            refreshPlan()
            return null
        }
        val result = FreeUpResult(removed, removedBytes, waiting.size - waitingBefore, kept)
        _state.update {
            it.copy(activity = FreeUpActivity.Idle, lastResult = result, waitingCount = waiting.size, waitingBytes = waiting.sumOf { w -> w.size })
        }
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
        /** Files moved to the trash in one request. */
        const val TRASH_BATCH = 50
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
