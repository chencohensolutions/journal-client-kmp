package com.chencohensolution.journal

import com.chencohensolution.journal.connection.JournalController
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

    /** The device photo library (PhotoKit). Its photos' file names, sizes and types are cached in Caches (`library`). */
    fun createLibraryController(): LibraryController =
        LibraryController(
            library = IosMediaLibrary(FileFactsCache(FileSystem.SYSTEM, cachesDir().toPath() / "library")),
            settings = JournalSettings(UserDefaultsStore()),
        )

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
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
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

    /** Imports a PKCS#12 bundle into the app Keychain; select the returned label on the controller. */
    fun importIdentity(pkcs12: NSData, password: String): IdentityImportResult =
        KeychainIdentities.importPkcs12(pkcs12, password)
}
