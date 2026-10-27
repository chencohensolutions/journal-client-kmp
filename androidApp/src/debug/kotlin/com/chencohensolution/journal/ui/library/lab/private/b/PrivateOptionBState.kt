package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.server.HiddenCollections

/**
 * Private settings by kind in groups: apps and folders, screenshots by the app they were taken in, sound, Sent to you
 * and nudity, each kind once. On means shown, everywhere on the screen.
 */
internal data class PrivateOptionBState(
    /** "Show private media", this phone's own choice. */
    val showPrivate: Boolean,
    /** WhatsApp, Telegram, Petcube and every folder, the most files first. */
    val apps: List<Kind>,
    /** The apps screenshots were taken in, the most first; one with an empty name has none in its file name. */
    val screenshotApps: List<Kind>,
    /** Calls, Voice recordings, Voice messages. */
    val sounds: List<Kind>,
    val sentToYou: Kind,
    val nudity: HiddenCollections.Nudity,
) {
    enum class Type { FOLDER, SCREENSHOTS, SENT_TO_YOU, CALLS, VOICE_RECORDINGS, VOICE_MESSAGES }

    /** A kind: [name] for a folder or a screenshot app. */
    data class Kind(val type: Type, val name: String, val files: Int, val shown: Boolean)
}

internal class PrivateOptionBActions(
    val setShowPrivate: (Boolean) -> Unit = {},
    val set: (PrivateOptionBState.Kind, Boolean) -> Unit = { _, _ -> },
    val setNudityHidden: (Boolean) -> Unit = {},
    val setNudityFromPercent: (Int) -> Unit = {},
    val back: () -> Unit = {},
)
