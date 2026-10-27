package com.chencohensolution.journal.server

import com.apollographql.apollo.api.Optional
import com.chencohensolution.journal.graphql.CamerasQuery
import com.chencohensolution.journal.graphql.ChangesQuery
import com.chencohensolution.journal.graphql.FolderNamesQuery
import com.chencohensolution.journal.graphql.SetSoundFolderMutation
import com.chencohensolution.journal.graphql.SoundFoldersQuery
import com.chencohensolution.journal.graphql.SetCameraMutation
import com.chencohensolution.journal.graphql.HiddenCollectionsQuery
import com.chencohensolution.journal.graphql.ServerInfoQuery
import com.chencohensolution.journal.graphql.SetCallMutation
import com.chencohensolution.journal.graphql.SetHiddenCollectionsMutation
import com.chencohensolution.journal.graphql.SetPrivateMutation
import com.chencohensolution.journal.graphql.StorageUsageQuery
import com.chencohensolution.journal.graphql.type.CallInput
import com.chencohensolution.journal.graphql.type.MediaKind
import com.chencohensolution.journal.db.LibraryDatabase
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.FolderNames
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.SoundRecording
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
import okio.FileSystem
import okio.Path
import kotlin.coroutines.cancellation.CancellationException

/** A photo, video or sound recording on the server, as the change feed describes it. */
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
    /** camera, screenshots, received, other, call, recording, or a folder ("folder:whatsapp"): see [MediaSource]. */
    val source: String? = null,
    val uploadedAt: String,
    /** The server's nudity scan, 0 to 1; null until scanned. The server hides what scores high (see HiddenCollections). */
    val nudity: Double? = null,
    /** The user marked it private; hidden unless "Show private media" (see PrivateMedia). */
    val isPrivate: Boolean = false,
    /** How good it looks, about 1 to 10 (the server's aesthetic score); null until scored. */
    val aesthetic: Double? = null,
    /** A sound recording (kind AUDIO): kept apart from the photos, as [toSoundRecording]. */
    val isAudio: Boolean = false,
    /** A sound recording that is a phone call, with who it was with as the phone named the file. */
    val isCall: Boolean = false,
    val callContact: String? = null,
    val callNumber: String? = null,
    /** The device that uploaded it, or "import" for what the server's import placed ([isImported]). */
    val uploadedBy: String? = null,
    /** The app on screen in a screenshot, as its file name says ("Bumble"); null for anything else. */
    val screenshotApp: String? = null,
    /** The camera that took it, as its tags say ("Canon", "Canon EOS 80D"); null without tags or from an older server. */
    val cameraMake: String? = null,
    val cameraModel: String? = null,
) {
    /** "Canon EOS 80D", "samsung SM-S931B": the camera as a photo's info names it; null when its tags name none. */
    val camera: String? get() = cameraName(cameraMake, cameraModel)

    /** The server's import placed it: its source is "import:" and its kind ([MediaSource.imported]). */
    val isImported: Boolean get() = uploadedBy == IMPORTED_BY

    /** Where it came from as the library shows it: the phone's folder, or what was imported of which kind. */
    val mediaSource: MediaSource get() {
        val plain = MediaSource.of(source)
        return if (isImported) MediaSource.imported(plain) else plain
    }

    /** A sound recording as Moments and its page show it; only for [isAudio]. */
    fun toSoundRecording(): SoundRecording {
        val taken = takenAt?.let(::parseTimestamp) ?: parseTimestamp(uploadedAt)
        val mediaSource = MediaSource.of(source)
        return SoundRecording(
            sha256 = sha256,
            kind = AudioKind.of(mediaSource, isCall),
            startMillis = taken?.millis ?: 0,
            day = taken?.day ?: LocalDay(1970, 1, 1),
            durationMillis = durationMillis ?: 0,
            contact = callContact,
            number = callNumber,
            mimeType = mimeType,
            source = mediaSource,
            isPrivate = isPrivate,
        )
    }

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
            source = mediaSource,
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

        /** [uploadedBy] of what the server's import placed (scripts/import). */
        const val IMPORTED_BY = "import"
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

/** The version of `server/media.json` written now (see [ServerMediaFile]). */
private const val FILE_VERSION = 2

/** The most sha256s `setPrivate` takes in one call. */
private const val SET_PRIVATE_BATCH = 1000

/** What the user's originals take up on the server. */
data class StorageUsage(val photos: Int, val videos: Int, val bytes: Long)

/** How far a sync of more than one page has read the server's change feed: [done] of [total] changes. */
data class SyncProgress(val done: Int, val total: Int)

@Serializable
private data class ServerMediaFile(
    /** 2 since sound recordings are kept (v 1 left them out, so its cursor is dropped once to fetch them). */
    val v: Int = FILE_VERSION,
    val cursor: String? = null,
    val media: List<ServerMedia> = emptyList(),
    /** [ServerLibrary.hidden]. */
    val hidden: List<String> = emptyList(),
)

/**
 * What the server holds, kept on this device so the library can show photos that are only there (deleted from the
 * device, or taken on another one). Filled from the server's change feed, page by page, each page written to the
 * library database with its cursor ([ServerMediaStore]), so the next sync asks only for what changed since. A cache:
 * losing it means downloading the list again. Until Oct 25, 2026 it was kept in `server/media.json`, which is read
 * into the database once and deleted.
 *
 * While "Show private media" is off ([showPrivate]), the private files are left out of the database but for what hides
 * this phone's copies of them ([privateFiles]), asked by the owner on Oct 25, 2026; turning it on reads the whole feed
 * again ([showPrivate]).
 */
class ServerLibrary internal constructor(
    private val settings: JournalSettings,
    private val http: ServerHttp,
    private val fileSystem: FileSystem,
    /** Where `media.json` was kept, read once into [database]. */
    private val directory: Path,
    database: LibraryDatabase,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    /**
     * Called with the files the server took off its list (deleted, or hidden: see [HiddenCollections]) after a sync
     * applied them, so this phone deletes what it keeps of them (previews, originals, videos).
     */
    private val removed: suspend (List<String>) -> Unit = {},
    /** Made-up files kept beside the server's own (debug builds: MadeUpLibrary): written once, deleted once this is empty. */
    private val madeUp: List<ServerMedia> = emptyList(),
    /** "Show private media" as saved: whether the database keeps the private files when it is first filled. */
    private val showPrivateAtStart: () -> Boolean = { true },
    /** Where the database is read and written. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val file = directory / "media.json"
    private val store = ServerMediaStore(database)
    private val mutex = Mutex()
    private var cursor: String? = null
    private var syncJob: Job? = null

    /** Whether the database keeps the private files (see [showPrivate]); set by [load]. */
    private var holdsPrivate = true

    private val _privateFiles = MutableStateFlow<List<PrivateFile>>(emptyList())

    /** The private files left out while "Show private media" is off: the library hides this phone's copies of them. */
    val privateFiles: StateFlow<List<PrivateFile>> = _privateFiles.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** True once the database has been read at start: [mediaVersion], [hidden] and [privateFiles] are what it holds. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _mediaVersion = MutableStateFlow(0L)

    /**
     * Goes up by one each time the server's list in the database changed (read at start, a sync, "Show private media"),
     * for what reads it ([sha256Of], [file], [sounds], [forEachFile]) to read again. Until Oct 25, 2026 the whole list
     * (103k files on the owner's phone) was held in memory here as `media`; now it stays in the database
     * (docs/plans/library-on-disk.md, step 6e).
     */
    val mediaVersion: StateFlow<Long> = _mediaVersion.asStateFlow()

    /** Every file of the list, in the order it was written: only for the library merged in memory (tests) and tests. */
    internal suspend fun all(): List<ServerMedia> = withContext(io) { store.media() }

    /** The file [sha256] as the list holds it; null when it has none (deleted, hidden, or private while not shown). */
    suspend fun file(sha256: String): ServerMedia? = withContext(io) { store.file(sha256) }

    /** The sound recordings of the list ([ServerMedia.isAudio]), private ones included, in the order it was written. */
    internal suspend fun sounds(): List<ServerMedia> = withContext(io) { store.sounds() }

    /** Gives [each] the few facts of every file of the list, read one row at a time (search and people on the phone). */
    internal suspend fun forEachFile(each: (FileFacts) -> Unit) = withContext(io) { store.forEachFile(each) }

    private val _hidden = MutableStateFlow<Set<String>>(emptySet())

    /**
     * The sha256s of the files the user hides on every device (a hidden collection, or nudity: see [HiddenCollections]).
     * The server sends nothing more of them; the library leaves out this phone's own copies of them too.
     */
    val hidden: StateFlow<Set<String>> = _hidden.asStateFlow()

    private val _hiddenEverywhere = MutableStateFlow<HiddenCollections?>(null)

    /**
     * What Private settings hides on every device, as it last read or saved it: null until it is opened, behind the
     * phone's lock, while the app runs (asked Oct 27, 2026). Show in the app says from it what is hidden of each
     * source; it is never kept on the device.
     */
    val hiddenEverywhere: StateFlow<HiddenCollections?> = _hiddenEverywhere.asStateFlow()

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
     * earlier install). Null when the server has no such file. Read from the database, the first file written with that
     * name and size, as [com.chencohensolution.journal.search.serverSha] finds it in the whole list.
     */
    suspend fun sha256Of(item: MediaItem): String? {
        item.sha256?.let { return it }
        val name = item.fileName ?: return null
        val size = item.sizeBytes ?: return null
        return withContext(io) { store.shaNamedAs(name, size) }
    }

    /**
     * Corrects who a recorded call was with (both replaced; empty is none), then syncs so the recording follows. False
     * when the server couldn't be reached or refused.
     */
    suspend fun setCall(sha256: String, contact: String?, number: String?): Boolean {
        try {
            http.graphql { apollo ->
                val call = CallInput(contact = Optional.presentIfNotNull(contact?.trim()), number = Optional.presentIfNotNull(number?.trim()))
                val response = apollo.mutation(SetCallMutation(sha256, call)).execute()
                response.exception?.let { throw it }
                response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
                Unit // not the line above's null, which reads as "not registered"
            } ?: return false
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        follow()
        return true
    }

    /**
     * Marks [sha256s] private ([isPrivate]), or no longer private, on the server (with their edits and paired files), then syncs so
     * the library follows. Many are sent [SET_PRIVATE_BATCH] at a time, the most the server takes in one call. False when
     * the server couldn't be reached or refused (the batches sent before that stay marked). Answers once the server has:
     * the sync runs after, since it waits for one already running and the library then reads itself again (until
     * Oct 26, 2026 the button spun through all of that).
     */
    suspend fun setPrivate(sha256s: List<String>, isPrivate: Boolean): Boolean {
        try {
            for (batch in sha256s.distinct().chunked(SET_PRIVATE_BATCH)) {
                http.graphql { apollo ->
                    val response = apollo.mutation(SetPrivateMutation(batch, isPrivate)).execute()
                    response.exception?.let { throw it }
                    response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
                    Unit // not the line above's null, which reads as "not registered" (until Oct 26, 2026 every mark said it failed)
                } ?: return false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            return false
        }
        scope.launch { follow() }
        return true
    }

    /**
     * Syncs after a change the server made, so the library follows. The change is made whether or not this sync gets
     * through, so a failed one isn't the change's failure: the next sync brings it.
     */
    private suspend fun follow() {
        try {
            syncJob?.join()
            sync()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or the server refused: the journal shows that already; the next refresh tries again.
        }
    }

    /** What the user hides on every device; null when the server couldn't be reached or refused. */
    suspend fun hiddenCollections(): HiddenCollections? {
        return try {
            http.graphql { apollo ->
                val response = apollo.query(HiddenCollectionsQuery()).execute()
                response.exception?.let { throw it }
                response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
                response.data?.hiddenCollections?.hiddenCollectionsFields?.toHiddenCollections()
            }?.also { _hiddenEverywhere.value = it }
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
            }?.also { _hiddenEverywhere.value = it }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Every camera model the user's files name, most files first; null when the server couldn't be reached or refused. */
    suspend fun cameras(): List<CameraModel>? = serverCall { apollo ->
        val response = apollo.query(CamerasQuery()).execute()
        response.exception?.let { throw it }
        response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
        response.data?.cameras?.map { it.cameraModelFields.toCameraModel() }
    }

    /**
     * Marks [camera] as the user's ([mine]) or not, and answers with every model, or null when the server couldn't be
     * reached or refused. The model's files then move on the server in the background ([CameraModel.pending]).
     */
    suspend fun setCamera(camera: CameraModel, mine: Boolean): List<CameraModel>? = serverCall { apollo ->
        val response = apollo.mutation(SetCameraMutation(camera.make, camera.model, mine)).execute()
        response.exception?.let { throw it }
        response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
        response.data?.setCamera?.map { it.cameraModelFields.toCameraModel() }
    }

    /**
     * Every folder the user's sound recordings came from, most files first, with the user's mark on each; null when the
     * server couldn't be reached or refused.
     */
    suspend fun soundFolders(): List<ServerSoundFolder>? = serverCall { apollo ->
        val response = apollo.query(SoundFoldersQuery()).execute()
        response.exception?.let { throw it }
        response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
        response.data?.soundFolders?.map { it.soundFolderFields.toSoundFolder() }
    }

    /**
     * Merges the sound folder [id] into calls or recordings ([mark]), or takes the mark back ([SoundFolderMark.NONE]),
     * and answers with every sound folder, or null when the server couldn't be reached or refused. The folder's files
     * then move on the server in the background ([ServerSoundFolder.pending]).
     */
    suspend fun setSoundFolder(id: String, mark: SoundFolderMark): List<ServerSoundFolder>? = serverCall { apollo ->
        val response = apollo.mutation(SetSoundFolderMutation(id, mark.toInput())).execute()
        response.exception?.let { throw it }
        response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
        response.data?.setSoundFolder?.map { it.soundFolderFields.toSoundFolder() }
    }

    private suspend fun <T> serverCall(call: suspend (com.apollographql.apollo.ApolloClient) -> T?): T? = try {
        http.graphql(call)
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        null
    }

    /**
     * Asks the server how it names each folder source ([FolderNames]), from a server that names them
     * ([ServerFeatures.soundFolders]), and keeps the answer in the database; offline, the names kept stay.
     */
    private suspend fun refreshFolderNames() {
        if (_features.value?.soundFolders != true) return
        val names = serverCall { apollo ->
            val response = apollo.query(FolderNamesQuery()).execute()
            response.exception?.let { throw it }
            response.errors?.firstOrNull()?.let { throw IllegalStateException(it.message) }
            response.data?.folderNames?.associate { it.id to it.name }
        } ?: return
        withContext(io) { store.keepFolderNames(names) }
        FolderNames.setServer(names)
    }

    /**
     * One sync: every page since the saved cursor, each written to the database with its cursor as it comes. The list
     * is shown once at the end, also when a page fails partway.
     */
    internal suspend fun sync(): Unit = mutex.withLock {
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
                    val media = mutableListOf<ServerMedia>()
                    val pageGone = mutableListOf<String>()
                    val hidden = mutableListOf<String>()
                    val shownAgain = mutableListOf<String>()
                    page.changes.forEach { change ->
                        val sha = change.path.removePrefix("media/")
                        if (change.hidden) hidden += sha else shownAgain += sha
                        // A kind this version doesn't know is left out like a deleted file.
                        val item = change.media?.takeIf { it.kind != MediaKind.UNKNOWN__ }
                        if (change.deleted || item == null) pageGone += sha else media += item.toServerMedia()
                    }
                    val applied = withContext(io) { store.apply(FeedPage(media, pageGone, hidden, shownAgain, page.cursor), holdsPrivate) }
                    cursor = page.cursor
                    changed = changed || applied.mediaChanged
                    hiddenChanged = hiddenChanged || applied.hiddenChanged
                    gone += applied.removed
                    done += page.changes.size
                    if (page.hasMore || _syncProgress.value != null) _syncProgress.value = SyncProgress(done, done + page.remaining)
                } while (page.hasMore)
            }
            refreshFolderNames()
        } finally {
            _syncProgress.value = null
            if (changed || hiddenChanged) withContext(NonCancellable) { publish(media = changed, hidden = hiddenChanged) }
            if (gone.isNotEmpty()) withContext(NonCancellable) { runCatching { removed(gone) } }
        }
    }

    /**
     * "Show private media" changed: while off, the private files leave the database but for what hides this phone's
     * copies of them ([privateFiles]); turned on, the whole feed is read again to bring them back.
     */
    fun showPrivate(show: Boolean) {
        scope.launch {
            val again = mutex.withLock {
                if (show == holdsPrivate) return@withLock false
                withContext(io) { store.holdPrivate(show) }
                holdsPrivate = show
                if (show) cursor = null
                withContext(NonCancellable) { publish(media = true, hidden = false) }
                show
            }
            if (again) syncAgain()
        }
    }

    /** Tells what the database holds now: [mediaVersion] goes up (and [privateFiles] is read), and [hidden] is read. */
    private suspend fun publish(media: Boolean, hidden: Boolean) {
        val keys = if (media) withContext(io) { store.privateFiles() } else null
        val hiddenShas = if (hidden) withContext(io) { store.hidden() } else null
        hiddenShas?.let { _hidden.value = it }
        keys?.let {
            _privateFiles.value = it
            _mediaVersion.value++
        }
    }

    /** Reads the database at start; the first time, `server/media.json` is read into it and deleted. */
    private suspend fun load() {
        withContext(io) {
            if (fileSystem.exists(file)) {
                val saved = runCatching { fileSystem.read(file) { json.decodeFromBufferedSource(ServerMediaFile.serializer(), this) } }.getOrNull()
                // A file from before sound recordings were kept: its cursor is past the ones it left out, so the feed
                // starts again.
                if (saved != null) store.replaceAll(saved.media, saved.hidden, saved.cursor.takeIf { saved.v >= FILE_VERSION }, showPrivateAtStart())
                fileSystem.delete(file)
            }
            fileSystem.delete(directory / "media.json.tmp", mustExist = false)
            cursor = store.cursor()
            holdsPrivate = store.holdsPrivate() ?: showPrivateAtStart().also { store.holdPrivate(it) }
            store.keepMadeUp(madeUp)
            FolderNames.setServer(store.folderNames())
        }
        publish(media = true, hidden = true)
        _loaded.value = true
    }

    private companion object {
        const val PAGE = 2000
        val json = Json { ignoreUnknownKeys = true }
    }
}

private fun ChangesQuery.Media.toServerMedia() = ServerMedia(
    sha256 = sha256,
    isVideo = kind == MediaKind.VIDEO,
    isAudio = kind == MediaKind.AUDIO,
    isCall = call != null,
    callContact = call?.contact,
    callNumber = call?.number,
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
    uploadedBy = uploadedBy,
    screenshotApp = screenshotApp,
    cameraMake = camera?.make,
    cameraModel = camera?.model,
)
