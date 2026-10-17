package com.chencohensolution.journal

import android.app.Application
import android.os.Bundle
import android.security.KeyChain
import android.widget.Toast
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
import com.chencohensolution.journal.ui.JournalScreen
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
                // First launch without an identity starts with device registration.
                var showWizard by rememberSaveable { mutableStateOf(viewModel.controller.currentState.identityAlias == null) }
                if (showWizard) {
                    EnrollmentWizard(
                        controller = viewModel.enrollment,
                        onUseCertificateFile = { showWizard = false },
                        onOpenJournal = {
                            showWizard = false
                            viewModel.enrollment.reset()
                            viewModel.controller.connect()
                        },
                    )
                } else {
                    JournalScreen(
                        controller = viewModel.controller,
                        onSelectIdentity = ::chooseKeyChainAlias,
                        onRegisterDevice = {
                            viewModel.enrollment.reset()
                            showWizard = true
                        },
                    )
                }
            }
        }
    }

    /** Opens the system picker for client certificates installed in the Android KeyChain. */
    private fun chooseKeyChainAlias() {
        KeyChain.choosePrivateKeyAlias(this, { alias ->
            if (alias != null) {
                runOnUiThread {
                    viewModel.controller.selectIdentity(alias)
                    Toast.makeText(this, getString(R.string.identity_selected_toast, alias), Toast.LENGTH_SHORT).show()
                }
            }
        }, null, null, null, -1, viewModel.controller.currentState.identityAlias)
    }
}
