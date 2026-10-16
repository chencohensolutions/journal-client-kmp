package com.chencohensolution.journal

import android.app.Application
import android.os.Bundle
import android.security.KeyChain
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.AndroidViewModel
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.ui.JournalScreen
import com.chencohensolution.journal.ui.JournalTheme

/** Keeps the shared controller alive across configuration changes. */
class JournalViewModel(application: Application) : AndroidViewModel(application) {
    val controller: JournalController = createJournalController(application)

    override fun onCleared() {
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
                JournalScreen(
                    controller = viewModel.controller,
                    onSelectIdentity = ::chooseKeyChainAlias,
                )
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
