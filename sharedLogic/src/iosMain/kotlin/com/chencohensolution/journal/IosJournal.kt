package com.chencohensolution.journal

import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.enrollment.IosDeviceKeyStore
import com.chencohensolution.journal.stories.StoryIds
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.FileStoryStore
import com.chencohensolution.journal.library.IosMediaLibrary
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.network.IosMtlsHttpClientFactory
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.UserDefaultsStore
import com.chencohensolution.journal.tls.IdentityImportResult
import com.chencohensolution.journal.tls.KeychainIdentities
import okio.FileSystem
import okio.Path.Companion.toPath
import platform.Foundation.NSApplicationSupportDirectory
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

    /** The device photo library (PhotoKit). */
    fun createLibraryController(): LibraryController =
        LibraryController(
            library = IosMediaLibrary(),
            settings = JournalSettings(UserDefaultsStore()),
        )

    /** Stories kept on this device, in Application Support (`journal/stories`). */
    fun createStoriesController(library: LibraryController): StoriesController {
        val support = NSSearchPathForDirectoriesInDomains(NSApplicationSupportDirectory, NSUserDomainMask, true).first() as String
        return StoriesController(
            store = FileStoryStore(FileSystem.SYSTEM, support.toPath() / "journal"),
            noteStore = FilePhotoNoteStore(FileSystem.SYSTEM, support.toPath() / "journal"),
            library = library,
            newId = StoryIds(JournalSettings(UserDefaultsStore())) { (NSDate().timeIntervalSince1970 * 1000).toLong() }::next,
        )
    }

    /** Imports a PKCS#12 bundle into the app Keychain; select the returned label on the controller. */
    fun importIdentity(pkcs12: NSData, password: String): IdentityImportResult =
        KeychainIdentities.importPkcs12(pkcs12, password)
}
