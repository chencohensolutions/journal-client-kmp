package com.chencohensolution.journal.ui.library

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.HiddenCollections

private typealias KindC = PrivateOptionCState.Kind
private typealias TypeC = PrivateOptionCState.Type

// The owner's library, roughly: WhatsApp and Bumble's screenshots hidden on all devices (the "2 hidden"), the rest shown.
private val labState = PrivateOptionCState(
    showPrivate = false,
    unlocked = false,
    kinds = listOf(
        KindC(TypeC.FOLDER, "WhatsApp", 19_700, false),
        KindC(TypeC.CALLS, "", 6_395, true),
        KindC(TypeC.SCREENSHOTS, "", 2_270, true),
        KindC(TypeC.SENT_TO_YOU, "", 312, true),
        KindC(TypeC.VOICE_RECORDINGS, "", 168, true),
        KindC(TypeC.FOLDER, "סרט צילום", 84, true),
        KindC(TypeC.FOLDER, "Dine-Out", 49, true),
        KindC(TypeC.FOLDER, "Telegram", 16, true),
        KindC(TypeC.VOICE_MESSAGES, "", 14, true),
        KindC(TypeC.FOLDER, "Petcube", 13, true),
    ),
    screenshotApps = listOf(
        KindC(TypeC.SCREENSHOTS, "Bumble", 1_458, false),
        KindC(TypeC.SCREENSHOTS, "Chrome", 310, true),
        KindC(TypeC.SCREENSHOTS, "", 128, true),
        KindC(TypeC.SCREENSHOTS, "WhatsApp", 96, true),
        KindC(TypeC.SCREENSHOTS, "Instagram", 88, true),
        KindC(TypeC.SCREENSHOTS, "Maps", 41, true),
    ),
    nudity = HiddenCollections.Nudity(hidden = true, fromPercent = 50, files = 61, notScanned = 0),
)

private fun List<KindC>.with(kind: KindC, shown: Boolean) = map { if (it === kind) it.copy(shown = shown) else it }

/** The lab's page: [askingFor] names the kind whose lock prompt is open (the phone's own prompt, faked as a dialog). */
@Composable
private fun PrivateLabC(unlocked: Boolean, askingFor: String? = null) {
    var state by remember { mutableStateOf(labState.copy(unlocked = unlocked)) }
    var asking by remember { mutableStateOf(askingFor?.let { name -> state.kinds.first { it.name == name } }) }
    PrivateScreenC(
        state,
        PrivateOptionCActions(
            setShowPrivate = { state = state.copy(showPrivate = it) },
            set = { kind, shown ->
                if (shown && kind.hiddenBefore) {
                    asking = kind
                } else {
                    state = state.copy(kinds = state.kinds.with(kind, shown), screenshotApps = state.screenshotApps.with(kind, shown))
                }
            },
            unlock = { state = state.copy(unlocked = true) },
            setNudityHidden = { state = state.copy(nudity = state.nudity.copy(hidden = it)) },
            setNudityFromPercent = { state = state.copy(nudity = state.nudity.copy(fromPercent = it)) },
        ),
    )
    asking?.let { kind ->
        val name = when {
            kind.type == TypeC.FOLDER -> sourceName(MediaSource.folder(kind.name))
            kind.name.isNotEmpty() -> kind.name
            else -> stringResource(R.string.private_c_no_app)
        }
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.private_c_confirm_title, name)) },
            text = { Text(stringResource(R.string.private_c_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    state = state.copy(kinds = state.kinds.with(kind, true), screenshotApps = state.screenshotApps.with(kind, true))
                    asking = null
                }) { Text(stringResource(R.string.private_c_confirm_unlock)) }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
}

// Fakes: the page opening without the lock (the app asks for it before Private settings); unlocking (the row just
// names the hidden kinds; the app would ask for the phone's lock); the lock asked again to show a hidden kind (a
// dialog stands for the phone's prompt); one list of kinds with Sent to you and the sound kinds as kinds that hide
// (the server's hiddenCollections keeps them apart and can't hide Sent to you yet); the counts (the owner's library,
// roughly). Nothing is sent to the server; switches live only while open.
internal val privateOptionC = LabOption("c", "Choose what shows, no lock to hide", "Opens without the lock; hidden kinds unnamed until unlocked") {
    PrivateLabC(unlocked = false)
}

// As c, unlocked: WhatsApp and Bumble's screenshots are named, their switches off.
internal val privateOptionCUnlocked = LabOption("c-unlocked", "c, unlocked", "The hidden kinds named, switches off") {
    PrivateLabC(unlocked = true)
}

// As c, unlocked, with WhatsApp's switch turned on: the lock is asked for (a dialog stands for the phone's prompt).
internal val privateOptionCConfirm = LabOption("c-confirm", "c, showing WhatsApp", "\"Unlock to show WhatsApp on all your devices\"") {
    PrivateLabC(unlocked = true, askingFor = "WhatsApp")
}
