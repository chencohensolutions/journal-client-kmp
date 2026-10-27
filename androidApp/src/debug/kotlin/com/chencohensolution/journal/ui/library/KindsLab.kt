package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.HiddenCollections
import com.chencohensolution.journal.server.HiddenCollectionsState
import com.chencohensolution.journal.settings.KindsStatus

// Made-up data for Settings › Show in the app and Private settings in the UI lab (LabScreens' "kinds" and "private"
// screens, and Settings' row). Fakes: the counts (the owner's library, roughly); the server isn't asked, and Private
// settings' switches live only while the page is open. WhatsApp and Bumble's screenshots are hidden on all devices.

/** Show in the app: the owner's kinds, imported files folded in, 15 not backed up; two kinds hidden on all devices. */
internal val labKinds = KindsStatus(
    kinds = listOf(
        KindsStatus.Kind(MediaSource.CAMERA, 79_000, 12),
        KindsStatus.Kind(MediaSource.SCREENSHOTS, 812, 0),
        KindsStatus.Kind(MediaSource.RECEIVED, 312, 0),
        KindsStatus.Kind(MediaSource.folder("סרט צילום"), 84, 0),
        KindsStatus.Kind(MediaSource.folder("Dine-Out"), 49, 0),
        KindsStatus.Kind(MediaSource.folder("Telegram"), 16, 0),
        KindsStatus.Kind(MediaSource.folder("Petcube"), 13, 3),
        KindsStatus.Kind(MediaSource.OTHER, 480, 0),
    ),
    sounds = listOf(
        KindsStatus.SoundKind(AudioKind.CALL, 6_395, 0),
        KindsStatus.SoundKind(AudioKind.RECORDING, 168, 0),
        KindsStatus.SoundKind(AudioKind.VOICE_MESSAGE, 14, 0),
    ),
    hiddenEverywhere = 2,
)

/** Private settings: what the server would answer for the owner. */
internal val labHidden = HiddenCollections(
    apps = listOf("WhatsApp" to 19_700, "סרט צילום" to 84, "Dine-Out" to 49, "Telegram" to 16, "Petcube" to 13)
        .mapIndexed { i, (name, n) -> HiddenCollections.Collection(name, n, hidden = i == 0) },
    screenshotsWithoutApp = HiddenCollections.Collection("", 128, false),
    screenshotApps = listOf("Bumble" to 1_458, "Chrome" to 310, "WhatsApp" to 96, "Instagram" to 88, "Maps" to 41)
        .mapIndexed { i, (name, n) -> HiddenCollections.Collection(name, n, hidden = i == 0) },
    nudity = HiddenCollections.Nudity(true, 50, 61, 0),
    recordings = HiddenCollections.Collection("recordings", 168, false),
    calls = HiddenCollections.Collection("calls", 6_395, false),
    voiceMessages = HiddenCollections.Collection("voiceMessages", 14, false),
    sentToYou = HiddenCollections.Collection("sentToYou", 312, false),
)

/** Private settings once unlocked (the emulator has no screen lock), its switches and slider working on [labHidden]. */
@Composable
internal fun PrivateSettingsLab() {
    var collections by remember { mutableStateOf(labHidden) }
    var showPrivate by remember { mutableStateOf(false) }
    val state = HiddenCollectionsState.Ready(collections, "", collections.screenshotApps, saving = false, notChanged = false)
    HiddenContent(
        stringResource(R.string.settings_hidden_title),
        state,
        HiddenActions(
            show = { kind, shown -> kind.collection?.let { collections = collections.with(it, hidden = !shown) } },
            setNudityHidden = { collections = collections.withNudity(it) },
            setNudityFromPercent = { collections = collections.withNudity(true, it) },
            setShowPrivate = { showPrivate = it },
        ),
        back = {},
        showPrivate = showPrivate,
    )
}
