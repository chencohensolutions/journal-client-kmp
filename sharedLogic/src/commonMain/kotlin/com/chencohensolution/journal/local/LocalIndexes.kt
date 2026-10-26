package com.chencohensolution.journal.local

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.settings.JournalSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
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
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.coroutines.cancellation.CancellationException

/** The server's indexes a phone may keep, in the order Settings lists them, by the server's [index] names. */
enum class PhoneIndexKind(val index: String) {
    /** Text search (SigLIP 2), with the model that reads a search's words. */
    WORDS("search"),

    /** Faces, and the people and groups. */
    PEOPLE("faces"),

    /** Text in photos. */
    TEXT("ocr"),

    /** Similar photos (DINOv2). */
    SIMILAR("similar"),

    /** Aesthetic scores: a set's best photo, covers, Best first. */
    AESTHETIC("aesthetic"),
    ;

    companion object {
        fun of(index: String): PhoneIndexKind? = entries.firstOrNull { it.index == index }
    }
}

/**
 * One index in Settings › On this phone: kept here or not ([on]); what a download from the start takes ([bytes], plus
 * [modelBytes] for the model that reads a search's words); what is kept now ([keptBytes], [keptFiles] of [files]).
 * Not [available] when the server has it off.
 */
data class PhoneIndex(
    val kind: PhoneIndexKind,
    val on: Boolean,
    val available: Boolean = true,
    val bytes: Long = 0,
    val modelBytes: Long = 0,
    val keptBytes: Long = 0,
    val files: Int = 0,
    val keptFiles: Int = 0,
)

/** A download in progress: [doneBytes] of [totalBytes], now of [kind]. */
data class PhoneDownload(val kind: PhoneIndexKind, val doneBytes: Long, val totalBytes: Long)

/** Settings › Search › On this phone. */
data class PhoneSearchState(
    val indexes: List<PhoneIndex> = PhoneIndexKind.entries.map { PhoneIndex(it, on = false, available = false) },
    val wifiOnly: Boolean = true,
    /** When the kept indexes last caught up with the server; null: never. */
    val updatedAtMillis: Long? = null,
    /** Files the server has read that aren't here yet. */
    val newOnServer: Int = 0,
    val download: PhoneDownload? = null,
    /** A download is due but waits for Wi-Fi. */
    val waitingForWifi: Boolean = false,
    /** Space left on the phone. */
    val freeBytes: Long = 0,
    /** The last download failed (offline, or the server refused): its words. */
    val error: String? = null,
    /** The server's manifest is known (else the sizes are what was last seen). */
    val loaded: Boolean = false,
) {
    /** The indexes kept: turned on, and offered by the server. */
    val kept: List<PhoneIndex> get() = indexes.filter { it.on && it.available }
    val keptBytes: Long get() = indexes.sumOf { it.keptBytes }

    companion object {
        val NONE = PhoneSearchState()
    }
}

/** Turns a search's tokens into a vector with the text half of the search model on this phone (ONNX Runtime). */
interface TextEncoding {
    /** The vector of [tokens] (64 of them) with the model at [modelPath]; null when it can't be run. */
    fun encode(modelPath: String, tokens: LongArray): FloatArray?

    /** Lets the model go (memory); the next [encode] loads it again. Not `release`, which Objective-C's NSObject has. */
    fun releaseModel()
}

@Serializable
private data class PeopleFile(val etag: String? = null, val doc: PeopleDoc = PeopleDoc())

/**
 * The server's indexes this phone keeps to search itself, offline too: Settings › Search › On this phone. Each kept
 * index is a table ([LocalTable]) in [directory], filled from the server's stream and caught up with it daily (when the
 * app comes back after a day) and on Update now; text search also keeps its model (`models/`). What is kept is read
 * by search ([LocalSearchApi]), People and a photo's details when the server can't be reached, and the aesthetic
 * scores by the library ([scores]). One per app.
 */
class LocalIndexes internal constructor(
    private val settings: JournalSettings,
    private val api: LocalIndexApi,
    private val fileSystem: FileSystem,
    private val directory: Path,
    /** Whether the phone is on Wi-Fi (or another network that costs nothing). */
    private val onWifi: () -> Boolean,
    /** Space left on the phone, in bytes. */
    private val freeSpace: () -> Long = { 0 },
    private val now: () -> Long,
    /** Runs the text half of the search model; null on a platform without it. */
    internal val encoding: TextEncoding? = null,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()
    private var updateJob: Job? = null
    private var manifest: List<LocalIndexInfo>? = null
    private val tables = HashMap<PhoneIndexKind, LocalTable>()
    private var people: PeopleFile? = null
    private var loaded = false

    private val _state = MutableStateFlow(PhoneSearchState(wifiOnly = settings.phoneIndexesWifiOnly))
    val state: StateFlow<PhoneSearchState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: PhoneSearchState get() = _state.value

    private val _scores = MutableStateFlow<Map<String, Float>>(emptyMap())

    /** The aesthetic scores kept here, by sha256: a set's best photo, covers, Best first. */
    val scores: StateFlow<Map<String, Float>> = _scores.asStateFlow()

    /** Text search's table changed (downloaded, updated): what local search reads. */
    private val _version = MutableStateFlow(0)
    internal val version: StateFlow<Int> = _version.asStateFlow()

    init {
        scope.launch {
            mutex.withLock { loadTables() }
            publish()
            // A kept index whose file couldn't be read (an older version, or damaged) is downloaded again now.
            val missing = PhoneIndexKind.entries.any { it.index in settings.phoneIndexes && it !in tables }
            if (missing && settings.phoneIndexesUpdatedAt != null) update(force = false)
        }
    }

    private fun tablePath(kind: PhoneIndexKind) = directory / kind.index / "table.bin"
    private val peoplePath get() = directory / "faces" / "people.json"
    private fun modelPath(file: ModelFileInfo) = directory / "models" / file.name

    private suspend fun loadTables() = withContext(Dispatchers.IO) {
        if (loaded) return@withContext
        for (kind in PhoneIndexKind.entries) {
            if (kind.index !in settings.phoneIndexes) continue
            LocalTable.load(fileSystem, tablePath(kind))?.let { tables[kind] = it }
        }
        people = runCatching {
            if (fileSystem.exists(peoplePath)) json.decodeFromString(PeopleFile.serializer(), fileSystem.read(peoplePath) { readUtf8() }) else null
        }.getOrNull()
        loaded = true
        (tables[PhoneIndexKind.AESTHETIC] as? ScoreTable)?.let { _scores.value = HashMap(it.scores) }
    }

    /** Settings › On this phone opened: asks the server what it offers now (sizes, what is new). */
    fun refresh() {
        scope.launch {
            try {
                manifest = api.manifest()
                _state.update { it.copy(error = null) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline: the sizes stay as last seen.
            }
            publish()
        }
    }

    /** Keeps [kind] on this phone (downloading it now, or on Wi-Fi), or deletes what is kept of it. */
    fun set(kind: PhoneIndexKind, on: Boolean) {
        val kept = settings.phoneIndexes
        settings.phoneIndexes = if (on) kept + kind.index else kept - kind.index
        if (on) {
            update(force = false)
        } else {
            scope.launch {
                mutex.withLock {
                    tables.remove(kind)
                    withContext(Dispatchers.IO) {
                        fileSystem.deleteRecursively(directory / kind.index)
                        if (kind == PhoneIndexKind.WORDS) fileSystem.deleteRecursively(directory / "models")
                    }
                    if (kind == PhoneIndexKind.AESTHETIC) _scores.value = emptyMap()
                    if (kind == PhoneIndexKind.PEOPLE) people = null
                    if (kind == PhoneIndexKind.WORDS) encoding?.releaseModel()
                    _version.update { it + 1 }
                }
                publish()
            }
        }
        publish()
    }

    fun setWifiOnly(on: Boolean) {
        settings.phoneIndexesWifiOnly = on
        _state.update { it.copy(wifiOnly = on) }
        if (!on && _state.value.waitingForWifi) update(force = false)
    }

    /** The app came back: catches up when the last look was a day ago or more. */
    fun updateIfDue() {
        val last = _state.value.updatedAtMillis ?: 0
        if (settings.phoneIndexes.isNotEmpty() && now() - last >= DAY) update(force = false)
    }

    /** Update now (also Download now while waiting for Wi-Fi): catches up whatever the network. */
    fun updateNow() = update(force = true)

    /** Pause: stops the download; what came is kept, and it goes on from there next time. */
    fun pause() {
        updateJob?.cancel()
    }

    private fun update(force: Boolean) {
        if (updateJob?.isActive == true) return
        if (!force && settings.phoneIndexesWifiOnly && !onWifi()) {
            _state.update { it.copy(waitingForWifi = true) }
            return
        }
        _state.update { it.copy(waitingForWifi = false, error = null) }
        updateJob = scope.launch {
            try {
                mutex.withLock { download() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Out of memory too: the screen says it stopped, instead of showing a download that went still.
                _state.update { it.copy(error = if (e::class.simpleName == "OutOfMemoryError") "not enough memory on this phone" else e.describe()) }
            } finally {
                _state.update { it.copy(download = null) }
                publish()
            }
        }
    }

    /** Every kept index caught up: its model files, then its stream. Saved as it goes, so a break loses little. */
    private suspend fun download() {
        loadTables()
        val offered = api.manifest().also { manifest = it }
        for (kind in PhoneIndexKind.entries) {
            if (kind.index !in settings.phoneIndexes) continue
            val info = offered.firstOrNull { it.name == kind.index } ?: continue
            if (kind == PhoneIndexKind.WORDS) {
                for (file in info.phoneModels) {
                    val target = modelPath(file)
                    val have = withContext(Dispatchers.IO) { fileSystem.metadataOrNull(target)?.size ?: 0 }
                    if (have == file.bytes && withContext(Dispatchers.IO) { fileSystem.exists(directory / "models" / (file.name + ".ok")) }) continue
                    _state.update { it.copy(download = PhoneDownload(kind, have, file.bytes)) }
                    val done = withContext(Dispatchers.IO) {
                        api.model(file, fileSystem, target) { got -> _state.update { it.copy(download = PhoneDownload(kind, got, file.bytes)) } }
                    }
                    if (!done) throw StreamBroke("${file.name} broke off")
                    withContext(Dispatchers.IO) { fileSystem.write(directory / "models" / (file.name + ".ok")) {} }
                }
            }
            stream(kind, info)
            if (kind == PhoneIndexKind.PEOPLE) {
                val known = people
                api.people(known?.etag)?.let { (doc, etag) ->
                    people = PeopleFile(etag, doc)
                    withContext(Dispatchers.IO) {
                        fileSystem.createDirectories(peoplePath.parent!!)
                        fileSystem.write(peoplePath) { writeUtf8(json.encodeToString(PeopleFile.serializer(), people!!)) }
                    }
                }
            }
        }
        settings.phoneIndexesUpdatedAt = now()
    }

    /** [kind]'s stream from its cursor, applied to its table, which is saved at the end (and when it breaks). */
    private suspend fun stream(kind: PhoneIndexKind, info: LocalIndexInfo) {
        val existing = tables[kind]
        var table = existing ?: LocalTable.of(TableHead(index = kind.index, model = info.model, format = info.format, dim = info.dim), fileSystem, directory / kind.index)
        var cursor = existing?.head?.cursor
        var applied = 0L
        _state.update { it.copy(download = PhoneDownload(kind, 0, info.bytes)) }
        val sink = object : StreamSink {
            override fun head(head: StreamHead) {
                if (head.reset || table.head.model != head.model || table.head.dim != head.dim || table.head.format != head.format) {
                    table = LocalTable.of(TableHead(index = kind.index, model = head.model, format = head.format, dim = head.dim), fileSystem, directory / kind.index)
                }
                // Room for every file the server has read, and a little more, before the records come.
                table.reserve(info.files + info.files / 16)
            }

            override fun record(record: ByteArray) {
                table.put(record)
                applied += record.size
                if (applied % PROGRESS_STEP < record.size) _state.update { it.copy(download = PhoneDownload(kind, applied, info.bytes)) }
            }

            override fun drop(sha256: String) = table.drop(sha256)

            override fun checkpoint(cursor: String) {
                table.head = table.head.copy(cursor = cursor)
            }
        }
        try {
            cursor = withContext(Dispatchers.IO) { api.stream(kind.index, cursor, sink) }
            table.head = table.head.copy(cursor = cursor, updatedAt = now())
        } finally {
            withContext(NonCancellable + Dispatchers.IO) { table.save(fileSystem, tablePath(kind)) }
            tables[kind] = table
            if (kind == PhoneIndexKind.AESTHETIC) (table as? ScoreTable)?.let { _scores.value = HashMap(it.scores) }
            _version.update { it + 1 }
        }
    }

    private fun publish() {
        val kept = settings.phoneIndexes
        val offered = manifest
        val indexes = PhoneIndexKind.entries.map { kind ->
            val info = offered?.firstOrNull { it.name == kind.index }
            val table = tables[kind]
            val models = info?.phoneModels.orEmpty()
            PhoneIndex(
                kind = kind,
                on = kind.index in kept,
                available = offered == null || info != null,
                bytes = info?.bytes ?: 0,
                modelBytes = models.sumOf { it.bytes },
                keptBytes = if (table == null) 0 else table.bytes(fileSystem, tablePath(kind)) +
                    (if (kind == PhoneIndexKind.WORDS) models.sumOf { fileSystem.metadataOrNull(modelPath(it))?.size ?: 0 } else 0),
                files = info?.files ?: 0,
                keptFiles = table?.size ?: 0,
            )
        }
        val behind = indexes.filter { it.on && it.available }.maxOfOrNull { (it.files - it.keptFiles).coerceAtLeast(0) } ?: 0
        _state.update {
            it.copy(
                indexes = indexes,
                wifiOnly = settings.phoneIndexesWifiOnly,
                updatedAtMillis = settings.phoneIndexesUpdatedAt,
                newOnServer = behind,
                freeBytes = freeSpace(),
                loaded = offered != null,
            )
        }
    }

    // What search, People and a photo's details read.

    internal fun table(kind: PhoneIndexKind): LocalTable? = if (kind.index in settings.phoneIndexes) tables[kind] else null

    internal fun peopleDoc(): PeopleDoc? = if (PhoneIndexKind.PEOPLE.index in settings.phoneIndexes) people?.doc else null

    private val queuePath get() = directory / "faces" / "corrections.json"
    private var queue: List<FaceCorrection>? = null

    /** The corrections waiting for the server, oldest first. */
    internal suspend fun pendingCorrections(): List<FaceCorrection> = mutex.withLock { loadQueue() }

    private suspend fun loadQueue(): List<FaceCorrection> = queue ?: withContext(Dispatchers.IO) {
        runCatching {
            if (fileSystem.exists(queuePath)) json.decodeFromString(CorrectionQueue.serializer(), fileSystem.read(queuePath) { readUtf8() }).corrections else null
        }.getOrNull().orEmpty()
    }.also { queue = it }

    private suspend fun saveQueue(list: List<FaceCorrection>) {
        queue = list
        withContext(Dispatchers.IO) {
            fileSystem.createDirectories(queuePath.parent!!)
            val temp = queuePath.parent!! / "corrections.json.tmp"
            fileSystem.write(temp) { writeUtf8(json.encodeToString(CorrectionQueue.serializer(), CorrectionQueue(corrections = list))) }
            fileSystem.atomicMove(temp, queuePath)
        }
    }

    /**
     * Applies [correction] to the people this phone keeps, at once, as the server would; with [queue], also keeps it to
     * send when the server can be reached. False when this phone keeps no people (nothing to apply it to).
     */
    internal suspend fun correct(correction: FaceCorrection, queue: Boolean): Boolean = mutex.withLock {
        loadTables()
        val kept = people ?: return@withLock false
        if (PhoneIndexKind.PEOPLE.index !in settings.phoneIndexes) return@withLock false
        val faces = tables[PhoneIndexKind.PEOPLE] as? FaceTable
        val doc = withContext(Dispatchers.Default) {
            applyCorrection(kept.doc, correction) { id ->
                val n = id.substringAfter(':').toIntOrNull() ?: return@applyCorrection null
                faces?.vector(id.substringBefore(':'), n)
            }
        }
        // The ETag goes: the server's next document replaces this one whatever it says.
        people = PeopleFile(null, doc)
        withContext(Dispatchers.IO) {
            fileSystem.createDirectories(peoplePath.parent!!)
            fileSystem.write(peoplePath) { writeUtf8(json.encodeToString(PeopleFile.serializer(), people!!)) }
        }
        if (queue) saveQueue(loadQueue() + correction)
        true
    }

    /** [correction] reached the server (or it refused it for good): it leaves the queue. */
    internal suspend fun sent(correction: FaceCorrection) = mutex.withLock {
        saveQueue(loadQueue() - correction)
    }

    /** The text model's file here, when text search is kept and its model downloaded whole. */
    internal fun textModel(): String? {
        if (PhoneIndexKind.WORDS.index !in settings.phoneIndexes) return null
        val models = directory / "models"
        if (!fileSystem.exists(models)) return null
        val ok = fileSystem.list(models).filter { it.name.endsWith(".onnx.ok") }.map { it.name.removeSuffix(".ok") }
        return ok.firstOrNull()?.let { (models / it).toString() }
    }

    /** Whether this phone can search by words itself: the table and the model are here. */
    internal val canSearchWords: Boolean get() = table(PhoneIndexKind.WORDS) != null && textModel() != null && encoding != null

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (PhoneSearchState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun observeScores(onChange: (Map<String, Float>) -> Unit): ObservationHandle {
        val job = scope.launch { scores.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() = scope.cancel()

    private companion object {
        const val DAY = 24 * 3_600_000L
        const val PROGRESS_STEP = 1L shl 20
        val json = Json { ignoreUnknownKeys = true }
    }
}
