package com.chencohensolution.journal

import android.content.Context
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.network.AndroidMtlsHttpClientFactory
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.SharedPreferencesStore

/** Android entry point into the shared logic. */
fun createJournalController(context: Context): JournalController =
    JournalController(
        settings = JournalSettings(SharedPreferencesStore(context)),
        clientFactory = AndroidMtlsHttpClientFactory(context),
    )
