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
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.FileStoryStore
import com.chencohensolution.journal.library.FileFactsCache
import com.chencohensolution.journal.library.IosMediaLibrary
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
import com.chencohensolution.journal.server.IosServerFiles
import com.chencohensolution.journal.server.KeptOriginals
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerPreviews
import com.chencohensolution.journal.settings.PrivateMedia
import com.chencohensolution.journal.storage.PhoneStorage
import kotlinx.coroutines.flow.StateFlow
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
            library = mediaLibrary,
            settings = JournalSettings(UserDefaultsStore()),
            backup = backup?.state,
            server = server,
            originals = originals,
            privateMedia = privateMedia,
        )

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

    /** What the server holds, kept in Application Support (`server/media.json`). */
    fun serverLibrary(): ServerLibrary =
        serverLibrary ?: ServerLibrary(
            settings = JournalSettings(UserDefaultsStore()),
            clientFactory = IosMtlsHttpClientFactory(),
            trustedCas = decodePemCertificates(SERVER_CA_PEM),
            fileSystem = FileSystem.SYSTEM,
            directory = supportDir().toPath() / "server",
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
            KeptOriginals(serverHttp(), FileSystem.SYSTEM, directory.toPath())
        }.also { keptOriginals = it }

    /** Settings → Storage on this phone: previews and downloaded originals of server photos under one limit. */
    fun phoneStorage(): PhoneStorage =
        phoneStorage ?: JournalSettings(UserDefaultsStore()).let { settings ->
            PhoneStorage(serverPreviews()) { settings.previewCacheGigabytes.toLong() shl 30 }
        }.also { phoneStorage = it }

    /** What Swift asks of the server's files: tile and viewer images, downloads, removal checks, storage. */
    fun serverFiles(): IosServerFiles =
        serverFiles ?: IosServerFiles(serverLibrary(), serverPreviews(), keptOriginals(), phoneStorage()).also { serverFiles = it }

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
                ledger = UploadLedger(FileSystem.SYSTEM, supportDir().toPath() / "backup"),
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

    /** Calls [onChange] (on a background queue) when photos are added or changed, or their file facts arrive. */
    fun watchLibrary(onChange: () -> Unit): ObservationHandle {
        val stop = mediaLibrary.watch(onChange)
        return ObservationHandle { stop() }
    }

    /** The user's choices on the Settings screen. */
    fun createSettingsController(): SettingsController = SettingsController(
        JournalSettings(UserDefaultsStore()),
        defaultSimilarityPercent = IosPhotoAnalyzer.DEFAULT_SAME_SCENE_PERCENT,
    )

    /**
     * Stories kept on this device, in Application Support (`journal/stories`). Photo fingerprints for burst
     * suggestions are cached in Caches (`similarity`), where photos were taken and place names too (`places`).
     */
    fun createStoriesController(library: LibraryController, settings: SettingsController): StoriesController {
        val support = supportDir()
        val caches = cachesDir()
        return StoriesController(
            store = FileStoryStore(FileSystem.SYSTEM, support.toPath() / "journal"),
            noteStore = FilePhotoNoteStore(FileSystem.SYSTEM, support.toPath() / "journal"),
            library = library,
            settings = settings,
            newId = StoryIds(JournalSettings(UserDefaultsStore())) { (NSDate().timeIntervalSince1970 * 1000).toLong() }::next,
            similarity = PhotoSimilarity(IosPhotoAnalyzer(), FingerprintCache(FileSystem.SYSTEM, caches.toPath() / "similarity")),
            places = PhotoPlaces(IosPlaceReader(), PlaceCache(FileSystem.SYSTEM, caches.toPath() / "places")),
        )
    }

    private fun supportDir() = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String

    private fun cachesDir() = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String

    /** Imports a PKCS#12 bundle into the app Keychain; select the returned label on the controller. */
    fun importIdentity(pkcs12: NSData, password: String): IdentityImportResult =
        KeychainIdentities.importPkcs12(pkcs12, password)
}
