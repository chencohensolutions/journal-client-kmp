package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.MediaSource

// The owner's library, roughly: WhatsApp and Bumble's screenshots are hidden on all devices in Private settings, so
// they aren't listed; Screenshots and Calls are hidden on this phone with Moments' filter bar.
private val labState = KindsOptionC2State(
    kinds = listOf(
        KindsOptionC2State.Kind(MediaSource.CAMERA, 79_000, 41_200, 12, false),
        KindsOptionC2State.Kind(MediaSource.SCREENSHOTS, 812, 640, 0, true),
        KindsOptionC2State.Kind(MediaSource.RECEIVED, 312, 140, 0, false),
        KindsOptionC2State.Kind(MediaSource.folder("סרט צילום"), 84, 0, 0, false),
        KindsOptionC2State.Kind(MediaSource.folder("Dine-Out"), 49, 49, 0, false),
        KindsOptionC2State.Kind(MediaSource.folder("Telegram"), 16, 16, 0, false),
        KindsOptionC2State.Kind(MediaSource.folder("Petcube"), 13, 13, 3, false),
        KindsOptionC2State.Kind(MediaSource.OTHER, 480, 220, 0, false),
    ),
    sounds = listOf(
        KindsOptionC2State.SoundKind(KindsOptionC2State.Sound.CALLS, 6_395, 1_210, 0, true),
        KindsOptionC2State.SoundKind(KindsOptionC2State.Sound.VOICE_RECORDINGS, 168, 31, 0, false),
        KindsOptionC2State.SoundKind(KindsOptionC2State.Sound.VOICE_MESSAGES, 14, 14, 0, false),
    ),
    hiddenEverywhere = 2,
)

// Fakes: one list of kinds with Sent to you, the folders and the sound kinds; each kind's files on this phone and its
// "not backed up" count (neither the library nor the server counts them per kind); hiding sound kinds with Moments'
// filter bar (it has no sound kinds yet); the count of kinds hidden on all devices (the server leaves them out without
// counting them); the counts (the owner's library, roughly). "Not backed up ›" and "Private settings ›" do nothing.
internal val kindsOptionC2 = LabOption("c2", "A status page, nothing about hiding here", "As c, without shown or hidden on this phone") {
    KindsScreenC2(labState, KindsOptionC2Actions())
}
