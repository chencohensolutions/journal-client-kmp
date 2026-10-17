package com.chencohensolution.journal

import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.enrollment.IosDeviceKeyStore
import com.chencohensolution.journal.network.IosMtlsHttpClientFactory
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.UserDefaultsStore
import com.chencohensolution.journal.tls.IdentityImportResult
import com.chencohensolution.journal.tls.KeychainIdentities
import platform.Foundation.NSData
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

    /** Imports a PKCS#12 bundle into the app Keychain; select the returned label on the controller. */
    fun importIdentity(pkcs12: NSData, password: String): IdentityImportResult =
        KeychainIdentities.importPkcs12(pkcs12, password)
}
