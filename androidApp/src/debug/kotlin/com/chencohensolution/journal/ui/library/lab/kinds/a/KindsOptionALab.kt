package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.library.MediaSource

// The owner's library, roughly: WhatsApp (19,700) is hidden on all devices in Private settings, so it isn't listed.
private val labState = KindsOptionAState(
    kinds = listOf(
        KindsOptionAState.Kind(MediaSource.CAMERA, 79_000, 12, true),
        KindsOptionAState.Kind(MediaSource.SCREENSHOTS, 2_270, 0, false),
        KindsOptionAState.Kind(MediaSource.OTHER, 480, 0, true),
        KindsOptionAState.Kind(MediaSource.RECEIVED, 312, 0, true),
        KindsOptionAState.Kind(MediaSource.folder("סרט צילום"), 84, 0, true),
        KindsOptionAState.Kind(MediaSource.folder("Dine-Out"), 49, 0, true),
        KindsOptionAState.Kind(MediaSource.folder("Telegram"), 16, 0, true),
        KindsOptionAState.Kind(MediaSource.folder("Petcube"), 13, 3, true),
    ),
    sounds = listOf(
        KindsOptionAState.SoundKind(KindsOptionAState.Sound.CALLS, 6_395, 0, false),
        KindsOptionAState.SoundKind(KindsOptionAState.Sound.VOICE_RECORDINGS, 168, 0, true),
        KindsOptionAState.SoundKind(KindsOptionAState.Sound.VOICE_MESSAGES, 14, 0, true),
    ),
    someHiddenEverywhere = true,
)

// Fakes: one list of kinds with Sent to you, the folders and the sound kinds (the app has sources, an Imported group and
// the sound kinds apart); each kind's "not backed up" count (neither the library nor the server counts it per kind);
// the counts (the owner's library, roughly); what Private settings hides (WhatsApp, left out, as the server would
// leave it out of the feed). "12 not backed up" and "Private settings ›" do nothing; switches live only while open.
internal val kindsOptionA = LabOption("a", "A list of kinds", "A row and a switch per kind, \"12 not backed up\" under its count") {
    var state by remember { mutableStateOf(labState) }
    KindsScreenA(
        state,
        KindsOptionAActions(
            toggle = { s -> state = state.copy(kinds = state.kinds.map { if (it.source == s) it.copy(shown = !it.shown) else it }) },
            toggleSound = { s -> state = state.copy(sounds = state.sounds.map { if (it.sound == s) it.copy(shown = !it.shown) else it }) },
            setSayWhatsHidden = { state = state.copy(sayWhatsHidden = it) },
            setShowFilterBar = { state = state.copy(showFilterBar = it) },
        ),
    )
}
