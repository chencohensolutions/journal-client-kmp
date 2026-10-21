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

    /** The device photo library (PhotoKit), with each photo's backup status from [backup]. */
    fun createLibraryController(backup: BackupController?): LibraryController =
        LibraryController(
            library = mediaLibrary,
            settings = JournalSettings(UserDefaultsStore()),
            backup = backup?.state,
        )

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

    private fun cachesDir() = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true).first() as String
    private fun supportDir() = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String

    /** Imports a PKCS#12 bundle into the app Keychain; select the returned label on the controller. */
    fun importIdentity(pkcs12: NSData, password: String): IdentityImportResult =
        KeychainIdentities.importPkcs12(pkcs12, password)
}
