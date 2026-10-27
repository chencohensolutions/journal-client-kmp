package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.server.HiddenCollections

private typealias KindB = PrivateOptionBState.Kind
private typealias TypeB = PrivateOptionBState.Type

// The owner's library, roughly: WhatsApp hidden on all devices, Bumble's screenshots hidden, calls hidden.
private val labState = PrivateOptionBState(
    showPrivate = false,
    apps = listOf(
        KindB(TypeB.FOLDER, "WhatsApp", 19_700, false),
        KindB(TypeB.FOLDER, "סרט צילום", 84, true),
        KindB(TypeB.FOLDER, "Dine-Out", 49, true),
        KindB(TypeB.FOLDER, "Telegram", 16, true),
        KindB(TypeB.FOLDER, "Petcube", 13, true),
    ),
    screenshotApps = listOf(
        KindB(TypeB.SCREENSHOTS, "Bumble", 1_458, false),
        KindB(TypeB.SCREENSHOTS, "Chrome", 310, true),
        KindB(TypeB.SCREENSHOTS, "", 128, true),
        KindB(TypeB.SCREENSHOTS, "WhatsApp", 96, true),
        KindB(TypeB.SCREENSHOTS, "Instagram", 88, true),
        KindB(TypeB.SCREENSHOTS, "Maps", 41, true),
    ),
    sounds = listOf(
        KindB(TypeB.CALLS, "", 6_395, false),
        KindB(TypeB.VOICE_RECORDINGS, "", 168, true),
        KindB(TypeB.VOICE_MESSAGES, "", 14, true),
    ),
    sentToYou = KindB(TypeB.SENT_TO_YOU, "", 312, true),
    nudity = HiddenCollections.Nudity(hidden = true, fromPercent = 50, files = 61, notScanned = 0),
)

private fun List<KindB>.with(kind: KindB, shown: Boolean) = map { if (it === kind) it.copy(shown = shown) else it }

// Fakes: the groups as kinds (the server's hiddenCollections has no Sent to you, and calls, recordings and voice
// messages as three switches beside WhatsApp's voice messages); Sent to you as a kind that hides; the counts (the owner's
// library, roughly). Nothing is sent to the server; switches and folds live only while open.
internal val privateOptionB = LabOption("b", "Groups that fold", "Apps and folders, Screenshots, Sound, Sent to you, Nudity") {
    var state by remember { mutableStateOf(labState) }
    PrivateScreenB(
        state,
        PrivateOptionBActions(
            setShowPrivate = { state = state.copy(showPrivate = it) },
            set = { kind, shown ->
                state = state.copy(
                    apps = state.apps.with(kind, shown),
                    screenshotApps = state.screenshotApps.with(kind, shown),
                    sounds = state.sounds.with(kind, shown),
                    sentToYou = if (state.sentToYou === kind) kind.copy(shown = shown) else state.sentToYou,
                )
            },
            setNudityHidden = { state = state.copy(nudity = state.nudity.copy(hidden = it)) },
            setNudityFromPercent = { state = state.copy(nudity = state.nudity.copy(fromPercent = it)) },
        ),
    )
}
