package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.server.HiddenCollections

private typealias KindA = PrivateOptionAState.Kind
private typealias TypeA = PrivateOptionAState.Type

// The owner's library, roughly: WhatsApp hidden on all devices, Bumble's screenshots hidden, calls hidden.
private val labState = PrivateOptionAState(
    showPrivate = false,
    kinds = listOf(
        KindA(TypeA.FOLDER, "WhatsApp", 19_700, false),
        KindA(TypeA.CALLS, "", 6_395, false),
        KindA(TypeA.SCREENSHOTS, "", 2_270, true),
        KindA(TypeA.SENT_TO_YOU, "", 312, true),
        KindA(TypeA.VOICE_RECORDINGS, "", 168, true),
        KindA(TypeA.FOLDER, "סרט צילום", 84, true),
        KindA(TypeA.FOLDER, "Dine-Out", 49, true),
        KindA(TypeA.FOLDER, "Telegram", 16, true),
        KindA(TypeA.VOICE_MESSAGES, "", 14, true),
        KindA(TypeA.FOLDER, "Petcube", 13, true),
    ),
    screenshotApps = listOf(
        KindA(TypeA.SCREENSHOTS, "Bumble", 1_458, false),
        KindA(TypeA.SCREENSHOTS, "Chrome", 310, true),
        KindA(TypeA.SCREENSHOTS, "", 128, true),
        KindA(TypeA.SCREENSHOTS, "WhatsApp", 96, true),
        KindA(TypeA.SCREENSHOTS, "Instagram", 88, true),
        KindA(TypeA.SCREENSHOTS, "Maps", 41, true),
    ),
    nudity = HiddenCollections.Nudity(hidden = true, fromPercent = 50, files = 61, notScanned = 0),
)

private fun List<KindA>.with(kind: KindA, shown: Boolean) = map { if (it === kind) it.copy(shown = shown) else it }

// Fakes: one list of kinds (the server's hiddenCollections has apps, screenshot apps, recordings, calls and voice
// messages apart, and no Sent to you: it can't hide it yet); Sent to you, Voice recordings and Voice messages as kinds
// that hide; the counts (the owner's library, roughly). Nothing is sent to the server; switches live only while open.
internal val privateOptionA = LabOption("a", "One list by kind", "Every kind in one card, the most files first; screenshots as app chips") {
    var state by remember { mutableStateOf(labState) }
    PrivateScreenA(
        state,
        PrivateOptionAActions(
            setShowPrivate = { state = state.copy(showPrivate = it) },
            set = { kind, shown -> state = state.copy(kinds = state.kinds.with(kind, shown), screenshotApps = state.screenshotApps.with(kind, shown)) },
            setNudityHidden = { state = state.copy(nudity = state.nudity.copy(hidden = it)) },
            setNudityFromPercent = { state = state.copy(nudity = state.nudity.copy(fromPercent = it)) },
        ),
    )
}
