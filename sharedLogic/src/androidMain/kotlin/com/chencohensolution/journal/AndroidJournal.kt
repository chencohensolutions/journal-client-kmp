package com.chencohensolution.journal

import android.content.Context
import android.os.Build
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.enrollment.AndroidDeviceKeyStore
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.network.AndroidMtlsHttpClientFactory
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.SharedPreferencesStore

/** Android entry point into the shared logic. */
fun createJournalController(context: Context): JournalController =
    JournalController(
        settings = JournalSettings(SharedPreferencesStore(context)),
        clientFactory = AndroidMtlsHttpClientFactory(context),
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
