package com.chencohensolution.journal

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.chencohensolution.journal.backup.BackupController
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.search.SearchController
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

    init {
        // The video cache counts toward the same limit as previews from the start (Media3 reads its index here).
        Thread { ServerVideos.cache(application) }.start()
    }

    override fun onCleared() {
        search.close()
        stories.close()
        settings.close()
        library.close()
        enrollment.close()
        controller.close()
    }
}
