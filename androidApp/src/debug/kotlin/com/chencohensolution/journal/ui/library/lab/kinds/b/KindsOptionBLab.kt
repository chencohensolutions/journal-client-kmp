package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.library.MediaSource

// The owner's library, roughly: WhatsApp (19,700) is hidden on all devices in Private settings, so it has no pill.
private val labState = KindsOptionBState(
    kinds = listOf(
        KindsOptionBState.Kind(MediaSource.CAMERA, 79_000, 12, true),
        KindsOptionBState.Kind(MediaSource.SCREENSHOTS, 2_270, 0, false),
        KindsOptionBState.Kind(MediaSource.OTHER, 480, 0, true),
        KindsOptionBState.Kind(MediaSource.RECEIVED, 312, 0, true),
        KindsOptionBState.Kind(MediaSource.folder("סרט צילום"), 84, 0, true),
        KindsOptionBState.Kind(MediaSource.folder("Dine-Out"), 49, 0, true),
        KindsOptionBState.Kind(MediaSource.folder("Telegram"), 16, 0, true),
        KindsOptionBState.Kind(MediaSource.folder("Petcube"), 13, 3, true),
    ),
    sounds = listOf(
        KindsOptionBState.SoundKind(KindsOptionBState.Sound.CALLS, 6_395, 0, false),
        KindsOptionBState.SoundKind(KindsOptionBState.Sound.VOICE_RECORDINGS, 168, 0, true),
        KindsOptionBState.SoundKind(KindsOptionBState.Sound.VOICE_MESSAGES, 14, 0, true),
    ),
    someHiddenEverywhere = true,
)

// Fakes: one list of kinds with Sent to you, the folders and the sound kinds (the app has sources, an Imported group and
// the sound kinds apart); each kind's "not backed up" count (neither the library nor the server counts it per kind);
// the counts (the owner's library, roughly); what Private settings hides (WhatsApp, left out, as the server would
// leave it out of the feed). The badges and "Private settings ›" do nothing; pills live only while open.
internal val kindsOptionB = LabOption("b", "Pills with a badge", "Today's pills, \"12 not backed up\" as a badge on the pill") {
    var state by remember { mutableStateOf(labState) }
    KindsScreenB(
        state,
        KindsOptionBActions(
            toggle = { s -> state = state.copy(kinds = state.kinds.map { if (it.source == s) it.copy(shown = !it.shown) else it }) },
            toggleSound = { s -> state = state.copy(sounds = state.sounds.map { if (it.sound == s) it.copy(shown = !it.shown) else it }) },
            setSayWhatsHidden = { state = state.copy(sayWhatsHidden = it) },
            setShowFilterBar = { state = state.copy(showFilterBar = it) },
        ),
    )
}
