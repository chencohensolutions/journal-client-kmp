package com.chencohensolution.journal

import android.app.Application
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.chencohensolution.journal.backup.BackupController
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.enrollment.CertificateRenewal
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.enrollment.RenewalResult
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.local.LocalIndexes
import com.chencohensolution.journal.people.PeopleController
import com.chencohensolution.journal.people.PhotoFindingsController
import com.chencohensolution.journal.search.SearchController
import com.chencohensolution.journal.server.ServerCopies
import com.chencohensolution.journal.swap.SwapController
import com.chencohensolution.journal.server.ServerIndexing
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.ui.library.ServerFiles
import com.chencohensolution.journal.ui.library.ServerVideos
import com.chencohensolution.journal.storage.FreeUpController
import com.chencohensolution.journal.work.WorkBackupScheduler
import com.chencohensolution.journal.work.freeUp

/** Keeps the shared controllers alive across configuration changes. */
class JournalViewModel(application: Application) : AndroidViewModel(application) {
    val controller: JournalController = createJournalController(application)
    val enrollment: EnrollmentController = createEnrollmentController(application, controller)
    val backup: BackupController = backupController(application, WorkBackupScheduler(application)).also {
        // Makes sure WorkManager has the runs the settings ask for (also after an update of the app).
        val scheduler = WorkBackupScheduler(application)
        scheduler.settingsChanged(it.currentState.settings)
        Thread { runCatching { scheduler.clearRetryDelays(it.currentState.settings) } }.start()
    }
    val server = ServerFiles(serverLibrary(application), serverPreviews(application), keptOriginals(application), phoneStorage(application))
    val settings: SettingsController = createSettingsController(application)
    val library: LibraryController = createLibraryController(application, backup, server.library, server.originals, settings.privateMedia)
    val stories: StoriesController = createStoriesController(application, library, settings)
    val freeUp: FreeUpController = freeUp(application)
    val search: SearchController = createSearchController(application, library, server.library, stories, settings)
    val people: PeopleController = peopleController(application)
    val findings: PhotoFindingsController = createPhotoFindingsController(application, library)
    val localIndexes: LocalIndexes = localIndexes(application)
    val serverIndexing: ServerIndexing = serverIndexing(application)
    val serverCopies: ServerCopies = serverCopies(application)
    val swaps: SwapController = swaps(application)
    val renewal: CertificateRenewal = createCertificateRenewal(application, controller)

    init {
        // The video cache counts toward the same limit as previews from the start (Media3 reads its index here).
        Thread { ServerVideos.cache(application) }.start()
        // In its last 30 days the certificate is renewed; offline, it is tried again at the next start.
        renewal.renewIfDue { if (it is RenewalResult.Failed) Log.w("Journal", "Renewing the certificate: ${it.message}") }
    }

    override fun onCleared() {
        findings.close()
        search.close()
        stories.close()
        settings.close()
        library.close()
        enrollment.close()
        renewal.close()
        controller.close()
    }
}
