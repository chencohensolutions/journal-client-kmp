package com.chencohensolution.journal

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.ui.EnrollmentWizard
import com.chencohensolution.journal.ui.HomeScreen
import com.chencohensolution.journal.ui.JournalTheme

/** Keeps the shared controllers alive across configuration changes. */
class JournalViewModel(application: Application) : AndroidViewModel(application) {
    val controller: JournalController = createJournalController(application)
    val enrollment: EnrollmentController = createEnrollmentController(application, controller)

    override fun onCleared() {
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
                    HomeScreen(viewModel.controller)
                }
            }
        }
    }
}
