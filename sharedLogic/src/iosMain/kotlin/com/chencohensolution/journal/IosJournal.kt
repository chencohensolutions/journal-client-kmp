package com.chencohensolution.journal

import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.network.IosMtlsHttpClientFactory
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.UserDefaultsStore
import com.chencohensolution.journal.tls.IdentityImportResult
import com.chencohensolution.journal.tls.KeychainIdentities
import platform.Foundation.NSData

/** iOS entry point into the shared logic (`IosJournal.shared` in Swift). */
object IosJournal {
    fun createController(): JournalController =
        JournalController(
            settings = JournalSettings(UserDefaultsStore()),
            clientFactory = IosMtlsHttpClientFactory(),
        )

    /** Imports a PKCS#12 bundle into the app Keychain; select the returned label on the controller. */
    fun importIdentity(pkcs12: NSData, password: String): IdentityImportResult =
        KeychainIdentities.importPkcs12(pkcs12, password)
}
