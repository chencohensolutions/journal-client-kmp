package com.chencohensolution.journal

import com.chencohensolution.journal.backup.BackgroundPieceSender
import com.chencohensolution.journal.backup.BackupController
import com.chencohensolution.journal.backup.IosMediaFiles
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.tls.SERVER_CA_PEM
import com.chencohensolution.journal.tls.decodePemCertificates
import com.chencohensolution.journal.tls.secCertificateObject
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.enrollment.IosDeviceKeyStore
import com.chencohensolution.journal.stories.StoryIds
import com.chencohensolution.journal.stories.FeedPager
import com.chencohensolution.journal.stories.FeedTable
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.deleteOldStories
import com.chencohensolution.journal.library.FileFactsCache
import com.chencohensolution.journal.library.IosMediaLibrary
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.StoredMediaLibrary
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.network.IosMtlsHttpClientFactory
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.UserDefaultsStore
import com.chencohensolution.journal.places.IosPlaceReader
import com.chencohensolution.journal.places.PhotoPlaces
import com.chencohensolution.journal.places.PlaceCache
import com.chencohensolution.journal.similarity.FingerprintCache
import com.chencohensolution.journal.similarity.IosPhotoAnalyzer
import com.chencohensolution.journal.similarity.PhotoSimilarity
import com.chencohensolution.journal.tls.IdentityImportResult
import com.chencohensolution.journal.tls.KeychainIdentities
import com.chencohensolution.journal.server.HttpIndexingApi
import com.chencohensolution.journal.server.IosServerFiles
import com.chencohensolution.journal.server.IosServerStream
import com.chencohensolution.journal.server.KeptOriginals
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.ServerIndexing
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerPreviews
import com.chencohensolution.journal.settings.PrivateMedia
import com.chencohensolution.journal.local.HttpLocalIndexApi
import com.chencohensolution.journal.local.LocalIndexes
import com.chencohensolution.journal.local.LocalSearchApi
import com.chencohensolution.journal.local.PhoneFallbackFindingsApi
import com.chencohensolution.journal.local.PhoneFallbackPeopleApi
import com.chencohensolution.journal.local.PhoneFirstSearchApi
import com.chencohensolution.journal.local.TextEncoding
import com.chencohensolution.journal.people.ApiPeopleSearch
import com.chencohensolution.journal.people.ApolloFindingsApi
import com.chencohensolution.journal.people.ApolloPeopleApi
import com.chencohensolution.journal.people.PeopleController
import com.chencohensolution.journal.people.PersonController
import com.chencohensolution.journal.people.PhotoFindingsController
import com.chencohensolution.journal.search.ApolloSearchApi
import com.chencohensolution.journal.search.ApolloSearchWarmUp
import com.chencohensolution.journal.search.SearchController
import com.chencohensolution.journal.storage.FreeUpController
import com.chencohensolution.journal.storage.IosFreeUp
import com.chencohensolution.journal.storage.PhoneStorage
import com.chencohensolution.journal.storage.ServerVerifyApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.StateFlow
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import com.chencohensolution.journal.db.LibraryDatabase
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLIsExcludedFromBackupKey
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.NSUserDomainMask
import platform.UIKit.UIDevice

/** iOS entry point into the shared logic (`IosJournal.shared` in Swift). */
object IosJournal {
    fun createController(): JournalController =
        JournalController(
            settings = JournalSettings(UserDefaultsStore()),
            clientFactory = IosMtlsHttpClientFactory(),
            http = serverHttp(),
        )

    /** The enrollment wizard; a successful enrollment switches [journal] to the new identity. */
    fun createEnrollmentController(journal: JournalController): EnrollmentController =
        EnrollmentController(
            settings = JournalSettings(UserDefaultsStore()),
            clientFactory = IosMtlsHttpClientFactory(),
            keyStore = IosDeviceKeyStore(),
            defaultDeviceName = UIDevice.currentDevice.model,
            onEnrolled = journal::useEnrolledIdentity,
        )

    /** PhotoKit, read once for the screens and backup. Its photos' file names, sizes and types are cached in Caches (`library`). */
    private val mediaLibrary by lazy { IosMediaLibrary(FileFactsCache(FileSystem.SYSTEM, cachesDir().toPath() / "library")) }

    /**
     * The device photo library (PhotoKit), with each photo's backup status from [backup], the photos that are only on
     * the server from [server] and the originals downloaded into [originals]; what [privateMedia] hides is left out.
     */
    fun createLibraryController(
        backup: BackupController?,
        server: ServerLibrary?,
        originals: KeptOriginals?,
        privateMedia: StateFlow<PrivateMedia>?,
    ): LibraryController =
        LibraryController(
            // Kept in the library database; PhotoKit is read whole each time until its change tokens are used.
            library = StoredMediaLibrary(mediaLibrary, libraryDatabase),
            table = LibraryTable(libraryDatabase),
            settings = JournalSettings(UserDefaultsStore()),
            backup = backup?.state,
            server = server,
            originals = originals,
            privateMedia = privateMedia,
            scores = localIndexes().scores,
        )

    /** Runs the search model's text half on this phone (ONNX Runtime, in Swift); set at launch, before [localIndexes]. */
    var textEncoding: TextEncoding? = null

    /** Whether the phone is on Wi-Fi (or another network that costs nothing); set by Swift (NWPathMonitor). */
    var onWifi: () -> Boolean = { true }

    private var localIndexes: LocalIndexes? = null
    private var serverIndexing: ServerIndexing? = null

    /** Settings › Search › On your server: the reading of this user's photos for the server's indexes. One per process. */
    fun serverIndexing(): ServerIndexing =
        serverIndexing ?: ServerIndexing(HttpIndexingApi(serverHttp())).also { serverIndexing = it }

    /**
     * Settings › Search › On this phone: the server's indexes kept in Application Support (`local`), out of the
     * phone's iCloud backup, to search without the server. One per process.
     */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    fun localIndexes(): LocalIndexes =
        localIndexes ?: run {
            val directory = supportDir() + "/local"
            NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
            NSURL.fileURLWithPath(directory).setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
            LocalIndexes(
                settings = JournalSettings(UserDefaultsStore()),
                api = HttpLocalIndexApi(serverHttp()),
                fileSystem = FileSystem.SYSTEM,
                directory = directory.toPath(),
                onWifi = { onWifi() },
                freeSpace = {
                    (NSFileManager.defaultManager.attributesOfFileSystemForPath(supportDir(), error = null)?.get(platform.Foundation.NSFileSystemFreeSize) as? NSNumber)
                        ?.longLongValue ?: 0
                },
                now = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
                encoding = textEncoding,
            )
        }.also { localIndexes = it }

    private fun localSearchApi(): LocalSearchApi {
        val settings = JournalSettings(UserDefaultsStore())
        return LocalSearchApi(localIndexes(), serverLibrary().media) { settings.privateMedia.show }
    }

    private fun peopleApi() = PhoneFallbackPeopleApi(ApolloPeopleApi(serverHttp()), localIndexes(), serverLibrary().media)

    /**
     * Text search on the server; its answers are shown as [library] has those photos ([server] for ones not read yet),
     * with [stories]' places for the filter's towns, a page of [settings]' size at a time.
     */
    fun createSearchController(
        library: LibraryController,
        server: ServerLibrary,
        stories: StoriesController,
        settings: SettingsController,
    ): SearchController =
        SearchController(
            library = library.state,
            api = PhoneFirstSearchApi(localSearchApi(), ApolloSearchApi(JournalSettings(UserDefaultsStore()), serverHttp())),
            server = server.media,
            stories = stories.state,
            pageSize = { settings.currentState.searchPageSize },
            warmUp = ApolloSearchWarmUp(serverHttp()),
            peopleSearch = ApiPeopleSearch(peopleApi()) { JournalSettings(UserDefaultsStore()).privateMedia.show },
        )

    private var peopleController: PeopleController? = null

    /** Explore's People: the user's people and groups of faces on the server. One per process. */
    fun peopleController(): PeopleController =
        peopleController ?: JournalSettings(UserDefaultsStore()).let { settings ->
            PeopleController(peopleApi(), includePrivate = { settings.privateMedia.show })
        }.also { peopleController = it }

    /** A person's page, or a group's ("Who's this?"): [id] from [peopleController]'s list. */
    fun createPersonController(id: String, library: LibraryController): PersonController {
        val settings = JournalSettings(UserDefaultsStore())
        return PersonController(
            id = id,
            api = peopleApi(),
            people = peopleController(),
            library = library.state,
            server = serverLibrary().media,
            includePrivate = { settings.privateMedia.show },
        )
    }

    /** What the server found in the photo the viewer shows: people, text, similar photos. */
    fun createPhotoFindingsController(library: LibraryController): PhotoFindingsController {
        val settings = JournalSettings(UserDefaultsStore())
        val server = serverLibrary()
        return PhotoFindingsController(
            api = PhoneFallbackFindingsApi(ApolloFindingsApi(serverHttp()), localIndexes()),
            people = peopleController(),
            library = library.state,
            server = server.media,
            features = server.features,
            includePrivate = { settings.privateMedia.show },
        )
    }

    private var serverLibrary: ServerLibrary? = null
    private var serverPreviews: ServerPreviews? = null
    private var keptOriginals: KeptOriginals? = null
    private var serverHttp: ServerHttp? = null
    private var phoneStorage: PhoneStorage? = null
    private var serverFiles: IosServerFiles? = null

    // One of each per process, asked for on the main thread (by the view models).
    private fun serverHttp(): ServerHttp =
        serverHttp ?: ServerHttp(JournalSettings(UserDefaultsStore()), IosMtlsHttpClientFactory(), decodePemCertificates(SERVER_CA_PEM))
            .also { serverHttp = it }

    /**
     * The library on the phone (docs/plans/library-on-disk.md), `library/library.db` in Application Support: a cache,
     * so its folder is kept out of iCloud backups. The native driver writes WAL.
     */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    private val libraryDatabase by lazy {
        val directory = supportDir() + "/library"
        NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
        NSURL.fileURLWithPath(directory).setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
        LibraryDatabase(
            NativeSqliteDriver(LibraryDatabase.Schema, "library.db", onConfiguration = { config ->
                config.copy(extendedConfig = config.extendedConfig.copy(basePath = directory))
            }),
        )
    }

    /** What the server holds, kept in the library database. */
    fun serverLibrary(): ServerLibrary =
        serverLibrary ?: ServerLibrary(
            settings = JournalSettings(UserDefaultsStore()),
            http = serverHttp(),
            fileSystem = FileSystem.SYSTEM,
            directory = supportDir().toPath() / "server",
            database = libraryDatabase,
            showPrivateAtStart = { JournalSettings(UserDefaultsStore()).privateMedia.show },
            // What this phone keeps of a file the server took off its list (deleted, or hidden by the user) goes too;
            // streamed videos stay until their cache trims them (Swift's cache attaches no removal yet).
            removed = { sha256s ->
                phoneStorage().remove(sha256s)
                sha256s.forEach { keptOriginals().delete(it) }
            },
        ).also { serverLibrary = it }

    /** Previews of server-only photos, cached in Caches (`previews`), up to the limit in Settings (10 GB by default). */
    private fun serverPreviews(): ServerPreviews =
        serverPreviews ?: JournalSettings(UserDefaultsStore()).let { settings ->
            ServerPreviews(
                http = serverHttp(),
                fileSystem = FileSystem.SYSTEM,
                directory = cachesDir().toPath() / "previews",
                maxBytes = { settings.previewCacheGigabytes.toLong() shl 30 },
                options = { settings.previewOptions },
            )
        }.also { serverPreviews = it }

    /**
     * Originals downloaded from the server ("Download original"), kept in Application Support (`originals`), never
     * trimmed, and left out of the phone's iCloud backup (the server has them).
     */
    @OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)
    fun keptOriginals(): KeptOriginals =
        keptOriginals ?: run {
            val directory = supportDir() + "/originals"
            NSFileManager.defaultManager.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
            NSURL.fileURLWithPath(directory).setResourceValue(NSNumber(bool = true), forKey = NSURLIsExcludedFromBackupKey, error = null)
            KeptOriginals(FileSystem.SYSTEM, directory.toPath())
        }.also { keptOriginals = it }

    /**
     * Settings → Storage on this phone: previews and downloaded originals of server photos, and the videos streamed
     * from the server (Swift attaches its cache), under one limit.
     */
    fun phoneStorage(): PhoneStorage =
        phoneStorage ?: JournalSettings(UserDefaultsStore()).let { settings ->
            PhoneStorage(serverPreviews()) { settings.previewCacheGigabytes.toLong() shl 30 }
        }.also { phoneStorage = it }

    /** What Swift asks of the server's files: tile and viewer images, downloads, removal checks, storage. */
    fun serverFiles(): IosServerFiles =
        serverFiles ?: IosServerFiles(serverLibrary(), serverPreviews(), keptOriginals(), phoneStorage()).also { serverFiles = it }

    /** What this device read and uploaded, in Application Support (`backup/ledger.jsonl`); shared by backup and "Free up space". */
    private val uploadLedger by lazy { UploadLedger(FileSystem.SYSTEM, supportDir().toPath() / "backup", libraryDatabase) }

    private var backupController: BackupController? = null
    private var pieceSender: BackgroundPieceSender? = null

    /**
     * Backup to the server. One per process, shared by the screens and the background launches, so both see the same
     * progress. Hashes and uploads are remembered in Application Support (`backup/ledger.jsonl`); pieces go through a
     * background URLSession (from `Caches/backup/pieces`), photos are copied out of PhotoKit to `Caches/backup/files`.
     * [scheduler] starts runs; it is set once, by the first caller. Call on the main thread.
     */
    fun backupController(scheduler: BackupScheduler): BackupController =
        backupController ?: run {
            val settings = JournalSettings(UserDefaultsStore())
            val caches = cachesDir().toPath() / "backup"
            val sender = BackgroundPieceSender(
                directory = caches / "pieces",
                identityAlias = { settings.identityAlias },
                anchors = decodePemCertificates(SERVER_CA_PEM).map(::secCertificateObject),
                wifiOnly = { settings.backup.wifiOnly },
            )
            pieceSender = sender
            BackupController(
                settings = settings,
                ledger = uploadLedger,
                library = mediaLibrary,
                files = IosMediaFiles(caches / "files"),
                clientFactory = IosMtlsHttpClientFactory(),
                trustedCas = decodePemCertificates(SERVER_CA_PEM),
                onSettingsChanged = scheduler::settingsChanged,
                onBackUpNow = scheduler::backUpNow,
                pieceSender = sender,
            )
        }.also { backupController = it }

    /**
     * iOS relaunched or woke the app for the background upload session [identifier]; false when it isn't backup's.
     * [completion] is called once the session's news is in and the next piece is on its way. Call [backupController]
     * first, so the session is connected.
     */
    fun handleBackgroundUploadEvents(identifier: String, completion: () -> Unit): Boolean {
        val sender = pieceSender
        if (identifier != BackgroundPieceSender.SESSION_ID || sender == null) return false
        sender.handleEvents(completion)
        return true
    }

    private var freeUp: IosFreeUp? = null

    /**
     * "Free up space": takes originals the server has off the phone. One per process. [backup] sends again what it
     * finds changed or missing; [start] runs it (in the app). Photos are copied to `Caches/freeup/files` to hash them;
     * files waiting for the user's confirmation are kept in Application Support (`freeup/waiting.json`).
     */
    fun freeUp(backup: BackupController, start: () -> Unit): IosFreeUp =
        freeUp ?: run {
            val files = IosMediaFiles(cachesDir().toPath() / "freeup" / "files")
            val controller = FreeUpController(
                settings = JournalSettings(UserDefaultsStore()),
                ledger = uploadLedger,
                library = mediaLibrary,
                files = files,
                server = ServerVerifyApi(serverHttp()),
                fileSystem = FileSystem.SYSTEM,
                directory = supportDir().toPath() / "freeup",
                now = { (NSDate().timeIntervalSince1970 * 1000).toLong() },
                onLedgerChanged = backup::ledgerChanged,
                onStart = start,
            )
            IosFreeUp(controller, files)
        }.also { freeUp = it }

    /** Calls [onChange] (on a background queue) when photos are added or changed, or their file facts arrive. */
    fun watchLibrary(onChange: () -> Unit): ObservationHandle {
        val stop = mediaLibrary.watch(onChange)
        return ObservationHandle { stop() }
    }

    private var serverStream: IosServerStream? = null

    /** Where Swift streams server-only videos from, and its URLSession's mTLS answers (`ServerVideos.swift`). */
    fun serverStream(): IosServerStream =
        serverStream ?: IosServerStream(JournalSettings(UserDefaultsStore()), serverPreviews(), decodePemCertificates(SERVER_CA_PEM))
            .also { serverStream = it }

    /** The user's choices on the Settings screen. */
    fun createSettingsController(): SettingsController = SettingsController(
        JournalSettings(UserDefaultsStore()),
        defaultSimilarityPercent = IosPhotoAnalyzer.DEFAULT_SAME_SCENE_PERCENT,
    )

    /**
     * Photo notes kept on this device, in Application Support (`journal/media`); the stories once kept beside them
     * are deleted. Photo fingerprints for Moments' sets are cached in Caches (`similarity`), where photos were taken
     * and place names too (`places`).
     */
    fun createStoriesController(library: LibraryController, settings: SettingsController): StoriesController {
        val support = supportDir()
        val caches = cachesDir()
        deleteOldStories(FileSystem.SYSTEM, support.toPath() / "journal")
        return StoriesController(
            noteStore = FilePhotoNoteStore(FileSystem.SYSTEM, support.toPath() / "journal"),
            library = library,
            settings = settings,
            newId = StoryIds(JournalSettings(UserDefaultsStore())) { (NSDate().timeIntervalSince1970 * 1000).toLong() }::next,
            similarity = PhotoSimilarity(IosPhotoAnalyzer(), FingerprintCache(FileSystem.SYSTEM, caches.toPath() / "similarity")),
            places = PhotoPlaces(IosPlaceReader(), PlaceCache(FileSystem.SYSTEM, caches.toPath() / "places")),
            // Moments read from the library database a page at a time.
            feed = FeedPager(FeedTable(libraryDatabase), LibraryTable(libraryDatabase), Dispatchers.IO),
            shownRow = library::shownRow,
        )
    }

    private fun supportDir() = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String

    private fun cachesDir() = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String

    /** Imports a PKCS#12 bundle into the app Keychain; select the returned label on the controller. */
    fun importIdentity(pkcs12: NSData, password: String): IdentityImportResult =
        KeychainIdentities.importPkcs12(pkcs12, password)
}
