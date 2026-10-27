package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.server.HiddenCollections

/**
 * Private settings by kind, one place to hide on every device and no kind twice: apps and folders, screenshots by the
 * app they were taken in, the sound kinds, Sent to you and nudity. On means shown, everywhere on the screen.
 */
internal data class PrivateOptionAState(
    /** "Show private media", this phone's own choice. */
    val showPrivate: Boolean,
    /** Every kind but nudity, the most files first; Screenshots among them, its apps in [screenshotApps]. */
    val kinds: List<Kind>,
    /** The apps screenshots were taken in, the most first; one with an empty name has none in its file name. */
    val screenshotApps: List<Kind>,
    val nudity: HiddenCollections.Nudity,
) {
    enum class Type { FOLDER, SCREENSHOTS, SENT_TO_YOU, CALLS, VOICE_RECORDINGS, VOICE_MESSAGES }

    /** A kind: [name] for a folder or a screenshot app (an app or folder as the server names it). */
    data class Kind(val type: Type, val name: String, val files: Int, val shown: Boolean)
}

internal class PrivateOptionAActions(
    val setShowPrivate: (Boolean) -> Unit = {},
    /** A kind, or a screenshot app, shown or hidden on every device. */
    val set: (PrivateOptionAState.Kind, Boolean) -> Unit = { _, _ -> },
    val setNudityHidden: (Boolean) -> Unit = {},
    val setNudityFromPercent: (Int) -> Unit = {},
    val back: () -> Unit = {},
)
