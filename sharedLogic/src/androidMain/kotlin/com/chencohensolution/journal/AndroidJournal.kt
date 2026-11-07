package com.chencohensolution.journal

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.chencohensolution.journal.db.LibraryDatabase
import android.os.Build
import com.chencohensolution.journal.backup.AndroidMediaFiles
import com.chencohensolution.journal.enrollment.HttpRenewApi
import com.chencohensolution.journal.enrollment.CertificateRenewal
import com.chencohensolution.journal.backup.AndroidSoundFolders
import com.chencohensolution.journal.backup.BackupController
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.enrollment.AndroidDeviceKeyStore
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.stories.StoryIds
import com.chencohensolution.journal.stories.FeedPager
import com.chencohensolution.journal.stories.FeedTable
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.deleteOldStories
import com.chencohensolution.journal.library.AndroidMediaLibrary
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.MadeUpLibrary
import com.chencohensolution.journal.library.Measure
import com.chencohensolution.journal.library.StoredMediaLibrary
import com.chencohensolution.journal.network.AndroidMtlsHttpClientFactory
import com.chencohensolution.journal.places.AndroidPlaceReader
import com.chencohensolution.journal.server.HttpIndexingApi
import com.chencohensolution.journal.server.KeptOriginals
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.ServerIndexing
import com.chencohensolution.journal.server.HttpCopiesApi
import com.chencohensolution.journal.server.ServerCopies
import com.chencohensolution.journal.swap.HttpSwapApi
import com.chencohensolution.journal.swap.SwapController
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerPreviews
import com.chencohensolution.journal.places.PhotoPlaces
import com.chencohensolution.journal.places.PlaceCache
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.StatFs
import com.chencohensolution.journal.local.AndroidTextEncoding
import com.chencohensolution.journal.local.HttpLocalIndexApi
import com.chencohensolution.journal.local.LocalIndexes
import com.chencohensolution.journal.local.LocalSearchApi
import com.chencohensolution.journal.local.PhoneFallbackFindingsApi
import com.chencohensolution.journal.local.PhoneFallbackPeopleApi
import com.chencohensolution.journal.local.PhoneFirstSearchApi
import com.chencohensolution.journal.people.ApiPeopleSearch
import com.chencohensolution.journal.people.ApolloFindingsApi
import com.chencohensolution.journal.people.ApolloPeopleApi
import com.chencohensolution.journal.people.PeopleController
import com.chencohensolution.journal.people.PersonController
import com.chencohensolution.journal.people.PhotoFindingsController
import com.chencohensolution.journal.search.ApolloSearchApi
import com.chencohensolution.journal.search.ApolloSearchWarmUp
import com.chencohensolution.journal.search.SearchController
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.PrivateMedia
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.SharedPreferencesStore
import com.chencohensolution.journal.storage.FreeUpController
import com.chencohensolution.journal.storage.PhoneStorage
import com.chencohensolution.journal.storage.ServerVerifyApi
import com.chencohensolution.journal.similarity.AndroidPhotoAnalyzer
import com.chencohensolution.journal.similarity.FingerprintCache
import com.chencohensolution.journal.similarity.PhotoSimilarity
import com.chencohensolution.journal.tls.SERVER_CA_PEM
import com.chencohensolution.journal.tls.decodePemCertificates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.StateFlow
import okhttp3.OkHttpClient
import okio.FileSystem
import okio.Path.Companion.toOkioPath

/**
 * Debug builds only, before anything else is made: logs [Measure]'s timing lines under "JournalMeasure", and takes
 * the size of the made-up library ([MadeUpLibrary]) from the start command:
 * `adb shell am start -S -n com.chencohensolution.journal/.MainActivity --ei madeUpLibrary 300000` (0 removes it).
 * The size is kept for later starts.
 */
fun debugStart(context: Context, intent: Intent?) {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return
    Measure.log = { Log.i("JournalMeasure", it) }
    val count = intent?.takeIf { it.hasExtra(MADE_UP_EXTRA) }?.getIntExtra(MADE_UP_EXTRA, 0) ?: return
    JournalSettings(SharedPreferencesStore(context)).madeUpLibrary = count
}

private const val MADE_UP_EXTRA = "madeUpLibrary"

private var madeUpLibrary: MadeUpLibrary? = null

/** The made-up library to add to the real one, in debug builds where [debugStart] set a size; null otherwise. */
@Synchronized
private fun madeUpLibrary(context: Context): MadeUpLibrary? {
    if (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE == 0) return null
    val count = JournalSettings(SharedPreferencesStore(context)).madeUpLibrary.takeIf { it > 0 } ?: return null
    return madeUpLibrary ?: MadeUpLibrary(count).also { madeUpLibrary = it }
}

/** Android entry point into the shared logic. */
fun createJournalController(context: Context): JournalController =
    JournalController(
        settings = JournalSettings(SharedPreferencesStore(context)),
        clientFactory = AndroidMtlsHttpClientFactory(context),
        http = serverHttp(context),
    )

/** The enrollment wizard; a successful enrollment switches [journal] to the new identity. */
fun createEnrollmentController(context: Context, journal: JournalController): EnrollmentController =
    EnrollmentController(
        settings = JournalSettings(SharedPreferencesStore(context)),
        clientFactory = AndroidMtlsHttpClientFactory(context),
        keyStore = AndroidDeviceKeyStore(),
        defaultDeviceName = Build.MODEL,
        onEnrolled = journal::useEnrolledIdentity,
    )

/** Renews this device's certificate in its last 30 days; a renewal switches [journal] to the new identity. */
fun createCertificateRenewal(context: Context, journal: JournalController): CertificateRenewal {
    val settings = JournalSettings(SharedPreferencesStore(context))
    return CertificateRenewal(
        settings = settings,
        keyStore = AndroidDeviceKeyStore(),
        api = HttpRenewApi(serverHttp(context)),
        test = CertificateRenewal.connectionTest(settings, AndroidMtlsHttpClientFactory(context)),
        onRenewed = journal::useRenewedIdentity,
    )
}

/**
 * The device photo library (MediaStore), with each photo's backup status from [backup], and the photos that are only on
 * the server from [server]; what [privateMedia] hides is left out.
 */
fun createLibraryController(
    context: Context,
    backup: BackupController? = null,
    server: ServerLibrary? = null,
    originals: KeptOriginals? = null,
    privateMedia: StateFlow<PrivateMedia>? = null,
): LibraryController =
    LibraryController(
        library = StoredMediaLibrary(AndroidMediaLibrary(context), libraryDatabase(context), madeUp = madeUpLibrary(context)?.deviceItems.orEmpty()),
        table = LibraryTable(libraryDatabase(context)),
        settings = JournalSettings(SharedPreferencesStore(context)),
        backup = backup?.state,
        server = server,
        originals = originals,
        privateMedia = privateMedia,
        scores = localIndexes(context).scores,
    )

private var localIndexes: LocalIndexes? = null

/**
 * Settings › Search › On this phone: the server's indexes kept in `files/local`, to search without the server. One
 * per process.
 */
@Synchronized
fun localIndexes(context: Context): LocalIndexes =
    localIndexes ?: run {
        val app = context.applicationContext
        LocalIndexes(
            settings = JournalSettings(SharedPreferencesStore(app)),
            api = HttpLocalIndexApi(serverHttp(app)),
            fileSystem = FileSystem.SYSTEM,
            directory = app.filesDir.toOkioPath() / "local",
            onWifi = {
                val connectivity = app.getSystemService(ConnectivityManager::class.java)
                connectivity?.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == true
            },
            freeSpace = { runCatching { StatFs(app.filesDir.path).availableBytes }.getOrDefault(0) },
            now = System::currentTimeMillis,
            encoding = AndroidTextEncoding(),
        )
    }.also { localIndexes = it }

private var serverIndexing: ServerIndexing? = null

/** Settings › Search › On your server: the reading of this user's photos for the server's indexes. One per process. */
@Synchronized
fun serverIndexing(context: Context): ServerIndexing =
    serverIndexing ?: ServerIndexing(HttpIndexingApi(serverHttp(context.applicationContext))).also { serverIndexing = it }

private var serverCopies: ServerCopies? = null

/** Settings › Backup › Copies on other disks: this user's copies on the server. One per process. */
@Synchronized
fun serverCopies(context: Context): ServerCopies =
    serverCopies ?: ServerCopies(HttpCopiesApi(serverHttp(context.applicationContext))).also { serverCopies = it }

private var swaps: SwapController? = null

/** Settings › Backup › Swap with a friend: this user's swaps with friends' servers. One per process. */
@Synchronized
fun swaps(context: Context): SwapController =
    swaps ?: SwapController(HttpSwapApi(serverHttp(context.applicationContext))).also { swaps = it }

private fun localSearchApi(context: Context): LocalSearchApi {
    val settings = JournalSettings(SharedPreferencesStore(context))
    return LocalSearchApi(localIndexes(context), serverLibrary(context)::forEachFile, { settings.privateMedia.show }, FileSystem.SYSTEM)
}

/**
 * Text search on the server; its answers are shown as [library] has those photos ([server] for ones not read yet), with
 * [stories]' places for the filter's towns, a page of [settings]' size at a time.
 */
fun createSearchController(
    context: Context,
    library: LibraryController,
    server: ServerLibrary,
    stories: StoriesController,
    settings: SettingsController,
): SearchController =
    SearchController(
        library = library.state,
        api = PhoneFirstSearchApi(localSearchApi(context), ApolloSearchApi(JournalSettings(SharedPreferencesStore(context)), serverHttp(context))),
        stories = stories.state,
        pageSize = { settings.currentState.searchPageSize },
        warmUp = ApolloSearchWarmUp(serverHttp(context)),
        peopleSearch = ApiPeopleSearch(peopleApi(context)) { JournalSettings(SharedPreferencesStore(context)).privateMedia.show },
        bySha = library::itemsOfShas,
        townArea = library::townArea,
        showsPrivate = { JournalSettings(SharedPreferencesStore(context)).privateMedia.show },
    )

private var peopleController: PeopleController? = null

/** Explore's People: the user's people and groups of faces on the server. One per process. */
@Synchronized
fun peopleController(context: Context): PeopleController =
    peopleController ?: JournalSettings(SharedPreferencesStore(context)).let { settings ->
        PeopleController(peopleApi(context), includePrivate = { settings.privateMedia.show })
    }.also { peopleController = it }

/** A person's page, or a group's ("Who's this?"): [id] from [peopleController]'s list. */
fun createPersonController(context: Context, id: String, library: LibraryController): PersonController {
    val settings = JournalSettings(SharedPreferencesStore(context))
    return PersonController(
        id = id,
        api = peopleApi(context),
        people = peopleController(context),
        library = library.state,
        server = null,
        includePrivate = { settings.privateMedia.show },
        bySha = library::itemsOfShas,
    )
}

/** What the server found in the photo the viewer shows: people, text, similar photos. */
fun createPhotoFindingsController(context: Context, library: LibraryController): PhotoFindingsController {
    val settings = JournalSettings(SharedPreferencesStore(context))
    val server = serverLibrary(context)
    return PhotoFindingsController(
        api = PhoneFallbackFindingsApi(ApolloFindingsApi(serverHttp(context)), localIndexes(context)),
        people = peopleController(context),
        serverSha = server::sha256Of,
        features = server.features,
        includePrivate = { settings.privateMedia.show },
        bySha = library::itemsOfShas,
    )
}

/** People from the server, or while it can't be reached, from what this phone keeps. */
private fun peopleApi(context: Context) = PhoneFallbackPeopleApi(ApolloPeopleApi(serverHttp(context)), localIndexes(context), serverLibrary(context)::forEachFile)

private var serverLibrary: ServerLibrary? = null
private var serverPreviews: ServerPreviews? = null
private var keptOriginals: KeptOriginals? = null
private var serverHttp: ServerHttp? = null

@Synchronized
private fun serverHttp(context: Context): ServerHttp =
    serverHttp ?: ServerHttp(JournalSettings(SharedPreferencesStore(context)), AndroidMtlsHttpClientFactory(context), decodePemCertificates(SERVER_CA_PEM))
        .also { serverHttp = it }

private var libraryDatabase: LibraryDatabase? = null

/**
 * The library on the phone (docs/plans/library-on-disk.md), `no_backup/library.db`: a cache, so it stays out of
 * Android's backups. One per process; WAL lets readers go on while a sync writes.
 */
@Synchronized
private fun libraryDatabase(context: Context): LibraryDatabase =
    libraryDatabase ?: LibraryDatabase(
        AndroidSqliteDriver(
            schema = LibraryDatabase.Schema,
            context = context.applicationContext,
            name = "library.db",
            useNoBackupDirectory = true,
            callback = object : AndroidSqliteDriver.Callback(LibraryDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    db.enableWriteAheadLogging()
                }
            },
        ),
    ).also { libraryDatabase = it }

/** What the server holds, kept in the library database ([libraryDatabase]). One per process. */
@Synchronized
fun serverLibrary(context: Context): ServerLibrary =
    serverLibrary ?: ServerLibrary(
        settings = JournalSettings(SharedPreferencesStore(context)),
        http = serverHttp(context),
        fileSystem = FileSystem.SYSTEM,
        directory = context.filesDir.toOkioPath() / "server",
        database = libraryDatabase(context),
        showPrivateAtStart = { JournalSettings(SharedPreferencesStore(context)).privateMedia.show },
        // What this phone keeps of a file the server took off its list (deleted, or hidden by the user) goes too.
        removed = { sha256s ->
            phoneStorage(context).remove(sha256s)
            sha256s.forEach { keptOriginals(context).delete(it) }
        },
        madeUp = madeUpLibrary(context)?.serverMedia.orEmpty(),
    ).also { serverLibrary = it }

/** Previews of server-only photos, cached in `cache/previews` (up to the limit in Settings, 10 GB by default). One per process. */
@Synchronized
fun serverPreviews(context: Context): ServerPreviews =
    serverPreviews ?: JournalSettings(SharedPreferencesStore(context)).let { settings ->
        ServerPreviews(
            http = serverHttp(context),
            fileSystem = FileSystem.SYSTEM,
            directory = context.cacheDir.toOkioPath() / "previews",
            maxBytes = { settings.previewCacheGigabytes.toLong() shl 30 },
            options = { settings.previewOptions },
        )
    }.also { serverPreviews = it }

/** Where to stream a server original from (`GET /media/{sha256}`, with Range), and the mTLS client to do it with. */
class ServerStream(val url: String, val client: OkHttpClient)

/** The stream's client, made for an identity and a pinned server key. */
private var streamClient: Pair<Pair<String, String?>, OkHttpClient>? = null

/** The stream of [sha256]'s original; null when this device isn't registered. Blocks the first time: call off the main thread. */
fun serverStream(context: Context, sha256: String): ServerStream? {
    val settings = JournalSettings(SharedPreferencesStore(context))
    val alias = settings.identityAlias ?: return null
    val made = alias to settings.serverFingerprint
    val client = synchronized(ServerStream::class) {
        streamClient?.takeIf { it.first == made }?.second
            ?: AndroidMtlsHttpClientFactory(context).okHttpClient(alias, decodePemCertificates(SERVER_CA_PEM))
                .also { streamClient = made to it }
    }
    return ServerStream(serverPreviews(context).mediaUrl(sha256), client)
}

private var phoneStorage: PhoneStorage? = null

/**
 * Settings → Storage on this phone: previews, downloaded originals and streamed videos of server photos under one
 * limit. One per process; the app attaches its video cache.
 */
@Synchronized
fun phoneStorage(context: Context): PhoneStorage =
    phoneStorage ?: JournalSettings(SharedPreferencesStore(context)).let { settings ->
        PhoneStorage(serverPreviews(context)) { settings.previewCacheGigabytes.toLong() shl 30 }
    }.also { phoneStorage = it }

/** Originals downloaded from the server and kept in `files/originals`. One per process. */
@Synchronized
fun keptOriginals(context: Context): KeptOriginals =
    keptOriginals ?: KeptOriginals(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "originals")
        .also { keptOriginals = it }

private var backupController: BackupController? = null
private var uploadLedger: UploadLedger? = null

/** What this device read and uploaded, in `files/backup/ledger.jsonl`; shared by backup and "Free up space". */
@Synchronized
private fun uploadLedger(context: Context): UploadLedger =
    uploadLedger ?: UploadLedger(FileSystem.SYSTEM, context.filesDir.toOkioPath() / "backup", libraryDatabase(context)).also { uploadLedger = it }

/**
 * Backup to the server. One per process, shared by the screens and the background worker, so both see the same
 * progress. Hashes and uploads are remembered in `files/backup/ledger.jsonl`. [scheduler] reschedules background runs
 * when the settings change and starts one for "Back up now"; it is set once, by the first caller.
 */
@Synchronized
fun backupController(context: Context, scheduler: BackupScheduler): BackupController =
    backupController ?: BackupController(
        settings = JournalSettings(SharedPreferencesStore(context)),
        ledger = uploadLedger(context),
        library = AndroidMediaLibrary(context),
        files = AndroidMediaFiles(context),
        clientFactory = AndroidMtlsHttpClientFactory(context),
        trustedCas = decodePemCertificates(SERVER_CA_PEM),
        locate = AndroidPlaceReader(context).let { reader -> { item -> if (reader.canReadLocations) reader.location(item) else null } },
        onSettingsChanged = scheduler::settingsChanged,
        onBackUpNow = scheduler::backUpNow,
        soundFolders = AndroidSoundFolders(context),
        check = ServerVerifyApi(serverHttp(context)),
    ).also { backupController = it }

private var freeUpController: FreeUpController? = null

/**
 * "Free up space": takes originals the server has off the phone. One per process, shared by Settings and the
 * background worker. [backup] sends again what it finds changed or missing; [start] runs it in the background. Files
 * waiting for the user's confirmation are kept in `files/freeup/waiting.json`.
 */
@Synchronized
fun freeUpController(context: Context, backup: BackupController, start: () -> Unit): FreeUpController =
    freeUpController ?: FreeUpController(
        settings = JournalSettings(SharedPreferencesStore(context)),
        ledger = uploadLedger(context),
        library = AndroidMediaLibrary(context),
        files = AndroidMediaFiles(context),
        server = ServerVerifyApi(serverHttp(context)),
        soundFolders = AndroidSoundFolders(context),
        fileSystem = FileSystem.SYSTEM,
        directory = context.filesDir.toOkioPath() / "freeup",
        now = System::currentTimeMillis,
        onLedgerChanged = backup::ledgerChanged,
        onStart = start,
    ).also { freeUpController = it }

/** The user's choices on the Settings screen. */
fun createSettingsController(context: Context): SettingsController =
    SettingsController(
        JournalSettings(SharedPreferencesStore(context)),
        defaultSimilarityPercent = AndroidPhotoAnalyzer.DEFAULT_SAME_SCENE_PERCENT,
    )

/**
 * Photo notes kept on this device, in the app's private files folder (`files/journal/media`); the stories once kept
 * beside them are deleted. Photo fingerprints for Moments' sets are cached in `cache/similarity`, where photos were
 * taken and place names in `cache/places`.
 */
fun createStoriesController(context: Context, library: LibraryController, settings: SettingsController): StoriesController {
    val journal = context.filesDir.toOkioPath() / "journal"
    deleteOldStories(FileSystem.SYSTEM, journal)
    return StoriesController(
        noteStore = FilePhotoNoteStore(FileSystem.SYSTEM, journal),
        library = library,
        settings = settings,
        newId = StoryIds(JournalSettings(SharedPreferencesStore(context)), System::currentTimeMillis)::next,
        similarity = PhotoSimilarity(
            AndroidPhotoAnalyzer(context),
            FingerprintCache(FileSystem.SYSTEM, context.cacheDir.toOkioPath() / "similarity"),
        ),
        places = PhotoPlaces(AndroidPlaceReader(context), PlaceCache(FileSystem.SYSTEM, context.cacheDir.toOkioPath() / "places")),
        // Moments read from the library database a page at a time.
        feed = FeedPager(
            FeedTable(libraryDatabase(context)),
            LibraryTable(libraryDatabase(context)),
            Dispatchers.IO,
            pageSize = { settings.currentState.momentsPaging.rowsPerPage },
        ),
        shownRow = library::shownRow,
    )
}
