package com.chencohensolution.journal

import android.app.Application
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.ui.EnrollmentWizard
import com.chencohensolution.journal.ui.JournalTheme
import com.chencohensolution.journal.ui.library.LibraryApp

/** Keeps the shared controllers alive across configuration changes. */
class JournalViewModel(application: Application) : AndroidViewModel(application) {
    val controller: JournalController = createJournalController(application)
    val enrollment: EnrollmentController = createEnrollmentController(application, controller)
    val library: LibraryController = createLibraryController(application)
    val stories: StoriesController = createStoriesController(application, library)

    override fun onCleared() {
        stories.close()
        library.close()
        enrollment.close()
        controller.close()
    }
}

class MainActivity : ComponentActivity() {
    private val viewModel: JournalViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            JournalTheme {
                // Android 17 blocks connections to local network addresses (the journal server) without this.
                val localNetwork = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
                LaunchedEffect(Unit) {
                    if (Build.VERSION.SDK_INT >= 37 && checkSelfPermission(LOCAL_NETWORK) != PackageManager.PERMISSION_GRANTED) {
                        localNetwork.launch(LOCAL_NETWORK)
                    }
                }
                // Without an identity the device has to be registered first.
                var showWizard by rememberSaveable { mutableStateOf(viewModel.controller.currentState.identityAlias == null) }
                if (showWizard) {
                    EnrollmentWizard(
                        controller = viewModel.enrollment,
                        onOpenJournal = {
                            showWizard = false
                            viewModel.enrollment.reset()
                        },
                    )
                } else {
                    LibraryApp(
                        journal = viewModel.controller,
                        library = viewModel.library,
                        stories = viewModel.stories,
                        onRegisterAgain = {
                            viewModel.enrollment.reset()
                            showWizard = true
                        },
                    )
                }
            }
        }
    }
}

/** `Manifest.permission.ACCESS_LOCAL_NETWORK`, API 37. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
