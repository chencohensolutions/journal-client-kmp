package com.chencohensolution.journal

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
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
import com.chencohensolution.journal.enrollment.parseEnrollmentLink
import com.chencohensolution.journal.ui.EnrollmentWizard
import com.chencohensolution.journal.swap.parseSwapLink
import com.chencohensolution.journal.ui.JournalTheme
import com.chencohensolution.journal.ui.library.JournalCast
import com.chencohensolution.journal.ui.library.LibraryApp
import com.chencohensolution.journal.work.freeUp

class MainActivity : ComponentActivity() {
    private val viewModel: JournalViewModel by viewModels()

    /** Set when an enrollment link arrives, to open the wizard on it. */
    private var linkOpened by mutableStateOf(false)

    /** A swap link opened from a friend's message (journal://swap?…), for Swap with a friend. */
    private var swapLink by mutableStateOf<String?>(null)

    // Casting, the volume buttons change the TV's volume (asked Nov 10, 2026); otherwise the phone's, as usual.
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val up = when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> true
            KeyEvent.KEYCODE_VOLUME_DOWN -> false
            else -> return super.dispatchKeyEvent(event)
        }
        if (event.action == KeyEvent.ACTION_DOWN) {
            if (JournalCast.stepVolume(up)) return true
        } else if (JournalCast.casting()) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        debugStart(this, intent.takeIf { savedInstanceState == null })
        // Only on a fresh start: after a rotation the link was handled already.
        if (savedInstanceState == null) openLink(intent)

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
                // An enrollment QR code scanned with the camera: register (again) with its code.
                LaunchedEffect(linkOpened) {
                    if (linkOpened) {
                        showWizard = true
                        linkOpened = false
                    }
                }
                if (showWizard) {
                    EnrollmentWizard(
                        controller = viewModel.enrollment,
                        onOpenJournal = {
                            // Models on this phone shows after photo access.
                            viewModel.onboarding.enrolled()
                            showWizard = false
                            viewModel.enrollment.reset()
                        },
                    )
                } else {
                    LibraryApp(
                        journal = viewModel.controller,
                        library = viewModel.library,
                        stories = viewModel.stories,
                        settings = viewModel.settings,
                        backup = viewModel.backup,
                        server = viewModel.server,
                        freeUp = viewModel.freeUp,
                        search = viewModel.search,
                        people = viewModel.people,
                        findings = viewModel.findings,
                        localIndexes = viewModel.localIndexes,
                        models = viewModel.models,
                        onboarding = viewModel.onboarding,
                        serverIndexing = viewModel.serverIndexing,
                        serverCopies = viewModel.serverCopies,
                        swaps = viewModel.swaps,
                        onRegisterAgain = {
                            viewModel.enrollment.reset()
                            showWizard = true
                        },
                        swapLink = swapLink,
                        swapLinkOpened = { swapLink = null },
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        openLink(intent)
    }

    private fun openLink(intent: Intent?) {
        val data = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        if (parseSwapLink(data) != null) {
            swapLink = data
            return
        }
        val link = parseEnrollmentLink(data) ?: return
        viewModel.enrollment.openLink(link)
        linkOpened = true
    }
}

/** `Manifest.permission.ACCESS_LOCAL_NETWORK`, API 37. */
private const val LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"
