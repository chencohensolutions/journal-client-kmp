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

private typealias KindC2 = PrivateOptionC2State.Kind
private typealias TypeC2 = PrivateOptionC2State.Type

// The owner's library, roughly: WhatsApp and Bumble's screenshots hidden on all devices (the "2 hidden"), the rest shown.
private val labState = PrivateOptionC2State(
    showPrivate = false,
    unlocked = false,
    kinds = listOf(
        KindC2(TypeC2.FOLDER, "WhatsApp", 19_700, false),
        KindC2(TypeC2.CALLS, "", 6_395, true),
        KindC2(TypeC2.SCREENSHOTS, "", 2_270, true),
        KindC2(TypeC2.SENT_TO_YOU, "", 312, true),
        KindC2(TypeC2.VOICE_RECORDINGS, "", 168, true),
        KindC2(TypeC2.FOLDER, "סרט צילום", 84, true),
        KindC2(TypeC2.FOLDER, "Dine-Out", 49, true),
        KindC2(TypeC2.FOLDER, "Telegram", 16, true),
        KindC2(TypeC2.VOICE_MESSAGES, "", 14, true),
        KindC2(TypeC2.FOLDER, "Petcube", 13, true),
    ),
    screenshotApps = listOf(
        KindC2(TypeC2.SCREENSHOTS, "Bumble", 1_458, false),
        KindC2(TypeC2.SCREENSHOTS, "Chrome", 310, true),
        KindC2(TypeC2.SCREENSHOTS, "", 128, true),
        KindC2(TypeC2.SCREENSHOTS, "WhatsApp", 96, true),
        KindC2(TypeC2.SCREENSHOTS, "Instagram", 88, true),
        KindC2(TypeC2.SCREENSHOTS, "Maps", 41, true),
    ),
    nudity = HiddenCollections.Nudity(hidden = true, fromPercent = 50, files = 61, notScanned = 0),
)

private fun List<KindC2>.with(kind: KindC2, shown: Boolean) = map { if (it === kind) it.copy(shown = shown) else it }

/** The lab's page: [askingFor] names the kind whose lock prompt is open (the phone's own prompt, faked as a dialog). */
@Composable
private fun PrivateLabC2(unlocked: Boolean, askingFor: String? = null, asksAgain: Boolean = true) {
    var state by remember { mutableStateOf(labState.copy(unlocked = unlocked)) }
    var asking by remember { mutableStateOf(askingFor?.let { name -> state.kinds.first { it.name == name } }) }
    PrivateScreenC2(
        state,
        PrivateOptionC2Actions(
            setShowPrivate = { state = state.copy(showPrivate = it) },
            set = { kind, shown ->
                if (asksAgain && shown && kind.hiddenBefore) {
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
            kind.type == TypeC2.FOLDER -> sourceName(MediaSource.folder(kind.name))
            kind.name.isNotEmpty() -> kind.name
            else -> stringResource(R.string.private_c2_no_app)
        }
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text(stringResource(R.string.private_c2_confirm_title, name)) },
            text = { Text(stringResource(R.string.private_c2_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    state = state.copy(kinds = state.kinds.with(kind, true), screenshotApps = state.screenshotApps.with(kind, true))
                    asking = null
                }) { Text(stringResource(R.string.private_c2_confirm_unlock)) }
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
// As c, but the whole page is behind the phone's lock (the app's PhoneLocked, as Private settings is today): once
// open, every kind is listed, the hidden ones with their switches off, and showing one asks nothing more.
internal val privateOptionC2 = LabOption("c2", "Choose what shows, behind the lock", "The phone's lock first; then every kind, hidden ones too") {
    PhoneLocked(androidx.compose.ui.res.stringResource(R.string.settings_hidden_title), back = {}) {
        PrivateLabC2(unlocked = true, asksAgain = false)
    }
}

// As c2 once unlocked, drawn without the lock (for screenshots on a device with no screen lock).
internal val privateOptionC2Open = LabOption("c2-open", "c2, unlocked", "What c2 shows after the phone's lock") {
    PrivateLabC2(unlocked = true, asksAgain = false)
}
