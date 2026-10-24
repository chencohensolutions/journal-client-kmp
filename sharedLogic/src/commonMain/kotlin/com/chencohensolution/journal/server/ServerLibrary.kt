package com.chencohensolution.journal.server

import com.apollographql.apollo.api.Optional
import com.chencohensolution.journal.graphql.ChangesQuery
import com.chencohensolution.journal.graphql.HiddenCollectionsQuery
import com.chencohensolution.journal.graphql.ServerInfoQuery
import com.chencohensolution.journal.graphql.SetHiddenCollectionsMutation
import com.chencohensolution.journal.graphql.SetPrivateMutation
import com.chencohensolution.journal.graphql.StorageUsageQuery
import com.chencohensolution.journal.graphql.type.MediaKind
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.settings.JournalSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.okio.decodeFromBufferedSource
import kotlinx.serialization.json.okio.encodeToBufferedSink
import okio.FileSystem
import okio.Path
import kotlin.coroutines.cancellation.CancellationException

/** A photo or video on the server, as the change feed describes it. */
@Serializable
data class ServerMedia(
    val sha256: String,
    val isVideo: Boolean,
    val fileName: String,
    val mimeType: String,
    val size: Long,
    /** RFC 3339 with the offset of where it was taken, as the uploading device sent it. */
    val takenAt: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    val durationMillis: Long? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** camera, whatsapp, screenshots, other. */
    val source: String? = null,
    val uploadedAt: String,
    /** The server's nudity scan, 0 to 1; null until scanned. The server hides what scores high (see HiddenCollections). */
    val nudity: Double? = null,
    /** The user marked it private; hidden unless "Show private media" (see PrivateMedia). */
    val isPrivate: Boolean = false,
    /** How good it looks, about 1 to 10 (the server's aesthetic score); null until scored. */
    val aesthetic: Double? = null,
) {
    /**
     * The library's view of it, for a photo that isn't in the device's library: shown from the server's previews, or
     * from [keptOriginal]. [id] is the device id it had when it was on this device, so its stories and note still find
     * it; else a `server:` id.
     */
    fun toMediaItem(id: String = SERVER_ID_PREFIX + sha256, keptOriginal: String? = null): MediaItem {
        val taken = takenAt?.let(::parseTimestamp) ?: parseTimestamp(uploadedAt)
        return MediaItem(
            id = id,
            type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
            takenAtMillis = taken?.millis ?: 0,
            day = taken?.day ?: LocalDay(1970, 1, 1),
            durationMillis = durationMillis,
            width = width ?: 0,
            height = height ?: 0,
            sizeBytes = size,
            fileName = fileName,
            backup = BackupStatus.BackedUp,
            source = MediaSource.entries.firstOrNull { it.name.equals(source, ignoreCase = true) } ?: MediaSource.OTHER,
            location = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null,
            mimeType = mimeType,
            sha256 = sha256,
            isOnDevice = false,
            keptOriginal = keptOriginal,
            isPrivate = isPrivate,
            aesthetic = aesthetic?.toFloat(),
        )
    }

    companion object {
        /** Ids of server-only items, so they never look like a device id (MediaStore ids are numbers). */
        const val SERVER_ID_PREFIX = "server:"
    }
}

/** An RFC 3339 time: the instant, and the calendar day where it was taken (the offset in the text). */
internal data class Timestamp(val millis: Long, val day: LocalDay)

private val RFC_3339 = Regex("""^(\d{4})-(\d{2})-(\d{2})[Tt ](\d{2}):(\d{2}):(\d{2})(\.\d+)?([Zz]|[+-]\d{2}:\d{2})$""")

internal fun parseTimestamp(text: String): Timestamp? {
    val m = RFC_3339.find(text.trim()) ?: return null
    val (y, mo, d, h, mi, s) = m.destructured
    val day = LocalDay(y.toInt(), mo.toInt(), d.toInt())
    val fraction = m.groupValues[7].drop(1).take(3).padEnd(3, '0').ifEmpty { "000" }.toInt()
    val zone = m.groupValues[8]
    val offsetMinutes = if (zone.equals("Z", ignoreCase = true)) 0 else {
        val sign = if (zone[0] == '-') -1 else 1
        sign * (zone.substring(1, 3).toInt() * 60 + zone.substring(4, 6).toInt())
    }
    val local = day.toEpochDay() * 86_400_000L + h.toInt() * 3_600_000L + mi.toInt() * 60_000L + s.toInt() * 1_000L + fraction
    return Timestamp(local - offsetMinutes * 60_000L, day)
}

/** What the user's originals take up on the server. */
data class StorageUsage(val photos: Int, val videos: Int, val bytes: Long)

/** How far a sync of more than one page has read the server's change feed: [done] of [total] changes. */
data class SyncProgress(val done: Int, val total: Int)

@Serializable
private data class ServerMediaFile(
    val v: Int = 1,
    val cursor: String? = null,
    val media: List<ServerMedia> = emptyList(),
    /** [ServerLibrary.hidden]. */
    val hidden: List<String> = emptyList(),
)

/**
 * What the server holds, kept on this device so the library can show photos that are only there (deleted from the
 * device, or taken on another one). Filled from the server's change feed, page by page, and saved with the cursor of the
 * last page applied, so the next sync asks only for what changed since. A cache: losing it means downloading the list
 * again. Kept in `server/media.json`.
 */
class ServerLibrary internal constructor(
    private val settings: JournalSettings,
    private val http: ServerHttp,
    private val fileSystem: FileSystem,
    directory: Path,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    /**
     * Called with the files the server took off its list (deleted, or hidden: see [HiddenCollections]) after a sync
     * applied them, so this phone deletes what it keeps of them (previews, originals, videos).
     */
    private val removed: suspend (List<String>) -> Unit = {},
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val file = directory / "media.json"
    private val tempFile = directory / "media.json.tmp"
    private val mutex = Mutex()
    private var cursor: String? = null
    private var syncJob: Job? = null

    private val _media = MutableStateFlow<List<ServerMedia>>(emptyList())
    val media: StateFlow<List<ServerMedia>> = _media.asStateFlow()

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The sha256s of the files the user hides on every device (a hidden collection, or nudity: see [HiddenCollections]).
     * The server sends nothing more of them; the library leaves out this phone's own copies of them too.
     */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _syncProgress = MutableStateFlow<SyncProgress?>(null)

    /**
     * While a sync reads more than one page (a new device, or a change of the hidden collections with many files),
     * how far it is; null otherwise.
     */
    val syncProgress: StateFlow<SyncProgress?> = _syncProgress.asStateFlow()

    init {
        scope.launch { mutex.withLock { load() } }
    }

    /** Asks the server what changed, unless a sync is running; offline, nothing changes. */
    fun refresh() {
        refreshFeatures()
        if (syncJob?.isActive == true) return
        launchSync(after = null)
    }

    /** Syncs once more after the sync running now, if any, which may have asked before a change made on the server. */
    fun syncAgain() = launchSync(after = syncJob)

    private fun launchSync(after: Job?) {
        syncJob = scope.launch {
            after?.join()
            try {
                sync()
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Offline or the server refused: the journal shows that already; the next refresh tries again.
            }
        }
    }

    private val _features = MutableStateFlow(settings.serverFeatures)

    /** What the server can do: its last answer, kept on this device; null before it answered once. */
    val features: StateFlow<ServerFeatures?> = _features.asStateFlow()

    /** Same as `features.value`, for Swift: [ServerFeatures.NONE] before the server answered. */
    val currentFeatures: ServerFeatures get() = _features.value ?: ServerFeatures.NONE

    /** Asks the server what it can do; offline, [features] keeps its last value. */
    fun refreshFeatures() {
        scope.launch {
            try {
                val answer = http.graphql { apollo ->
                    val response = apollo.query(ServerInfoQuery()).execute()
                    response.exception?.let { throw it }
                    response.data?.serverInfo?.toFeatures()
                } ?: return@launch
                settings.serverFeatures = answer
                _features.value = answer
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private val _usage = MutableStateFlow<StorageUsage?>(null)

    /** The last answer of [refreshUsage]; null until the server answered once. */
    val usage: StateFlow<StorageUsage?> = _usage.asStateFlow()

    /** Asks the server how much the user's originals take up; offline, [usage] keeps its last value. */
    fun refreshUsage() {
        scope.launch {
            try {
                _usage.value = fetchUsage() ?: return@launch
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
            }
        }
    }

    private suspend fun fetchUsage(): StorageUsage? = http.graphql { apollo ->
        val response = apollo.query(StorageUsageQuery()).execute()
        response.exception?.let { throw it }
        response.data?.storageUsage?.let { StorageUsage(it.photos, it.videos, it.bytes) }
    }

    /**
     * [item]'s sha256 on the server: its own once this device's backup read it, otherwise the server file with its name
     * and size, as the library and search match them (a photo that reached the server another way: an import, an
     * earlier install). Null when the server has no such file.
     */
    fun sha256Of(item: MediaItem): String? {
        item.sha256?.let { return it }
        val name = item.fileName?.lowercase() ?: return null
        return _media.value.firstOrNull { it.fileName.lowercase() == name && it.size == item.sizeBytes }?.sha256
    }

    /**
     * Marks [sha256s] private ([isPrivate]), or no longer private, on the server (with their edits and paired files), then syncs so
     * the library follows. False when the server couldn't be reached or refused.
     */
    suspend fun setPrivate(sha256s: List<String>, isPrivate: Boolean): Boolean {
        try {
            http.graphql { apollo ->
                val response = apollo.mutation(SetPrivateMutation(sha256s, isPrivate)).execute()
                response.exception?.let { throw it }
                response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
            } ?: return false
            syncJob?.join()
            sync()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        return true
    }

    /** What the user hides on every device; null when the server couldn't be reached or refused. */
    suspend fun hiddenCollections(): HiddenCollections? {
        return try {
            http.graphql { apollo ->
                val response = apollo.query(HiddenCollectionsQuery()).execute()
                response.exception?.let { throw it }
                response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
                response.data?.hiddenCollections?.hiddenCollectionsFields?.toHiddenCollections()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Replaces what the user hides on every device with [choice]. The server's answer, or null when it couldn't be
     * reached or refused. It doesn't sync: the screen calls [syncAgain] once, when it is left, for the library to drop
     * what is now hidden and get back what is shown again (the server sends only those).
     */
    suspend fun setHiddenCollections(choice: HiddenCollections): HiddenCollections? {
        return try {
            http.graphql { apollo ->
                val response = apollo.mutation(SetHiddenCollectionsMutation(choice.toInput())).execute()
                response.exception?.let { throw it }
                response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
                response.data?.setHiddenCollections?.hiddenCollectionsFields?.toHiddenCollections()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /**
     * One sync: every page since the saved cursor. What was applied is saved and shown once at the end, also when a page
     * fails partway, so the cursor and the list on disk always agree. Saving after every page wrote the whole list again
     * each time, which for a large library ran the phone out of memory.
     */
    internal suspend fun sync(): Unit = mutex.withLock {
        val known = _media.value.associateByTo(LinkedHashMap()) { it.sha256 }
        val hidden = _hidden.value.toMutableSet()
        val savedCursor = cursor
        var changed = false
        var hiddenChanged = false
        val gone = mutableListOf<String>()
        var done = 0
        try {
            http.graphql { apollo ->
                do {
                    val response = apollo.query(ChangesQuery(Optional.presentIfNotNull(cursor), Optional.present(PAGE))).execute()
                    response.exception?.let { throw it }
                    response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
                    val page = response.data?.changes ?: break
                    page.changes.forEach { change ->
                        val sha = change.path.removePrefix("media/")
                        val media = change.media
                        hiddenChanged = (if (change.hidden) hidden.add(sha) else hidden.remove(sha)) || hiddenChanged
                        if (change.deleted || media == null) {
                            if (known.remove(sha) != null) {
                                changed = true
                                gone += sha
                            }
                        } else {
                            known[media.sha256] = media.toServerMedia()
                            changed = true
                        }
                    }
                    cursor = page.cursor
                    done += page.changes.size
                    if (page.hasMore || _syncProgress.value != null) _syncProgress.value = SyncProgress(done, done + page.remaining)
                } while (page.hasMore)
            }
        } finally {
            _syncProgress.value = null
            if (changed || hiddenChanged || cursor != savedCursor) withContext(NonCancellable) {
                val list = known.values.toList()
                save(list, hidden)
                if (hiddenChanged) _hidden.value = hidden.toSet()
                if (changed) _media.value = list
                if (gone.isNotEmpty()) runCatching { removed(gone) }
            }
        }
    }

    private suspend fun load() = withContext(Dispatchers.IO) {
        if (!fileSystem.exists(file)) return@withContext
        val saved = runCatching { fileSystem.read(file) { json.decodeFromBufferedSource(ServerMediaFile.serializer(), this) } }.getOrNull()
            ?: return@withContext
        cursor = saved.cursor
        _media.value = saved.media
        _hidden.value = saved.hidden.toSet()
    }

    /** Streams the list to the file, so a large library never sits in memory as one string. */
    private suspend fun save(media: List<ServerMedia>, hidden: Set<String>) = withContext(Dispatchers.IO) {
        fileSystem.createDirectories(file.parent!!)
        val saved = ServerMediaFile(cursor = cursor, media = media, hidden = hidden.sorted())
        fileSystem.write(tempFile) { json.encodeToBufferedSink(ServerMediaFile.serializer(), saved, this) }
        fileSystem.atomicMove(tempFile, file)
    }

    private companion object {
        const val PAGE = 2000
        val json = Json { ignoreUnknownKeys = true }
    }
}

private fun ChangesQuery.Media.toServerMedia() = ServerMedia(
    sha256 = sha256,
    isVideo = kind == MediaKind.VIDEO,
    fileName = fileName,
    mimeType = mimeType,
    size = size,
    takenAt = takenAt,
    width = width,
    height = height,
    durationMillis = durationMs,
    latitude = location?.lat,
    longitude = location?.lon,
    source = source,
    uploadedAt = uploadedAt,
    nudity = nudity,
    isPrivate = private,
    aesthetic = aesthetic,
)
