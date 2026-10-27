package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.server.HiddenCollections

/**
 * Private settings as the place to choose what shows on all devices. It opens without the phone's lock: every kind
 * that is shown has a Show switch, and hiding needs no lock. Kinds already hidden aren't named until the user unlocks
 * ([unlocked]); then they appear with their switches off, and showing one again asks for the lock.
 */
internal data class PrivateOptionC2State(
    /** "Show private media", this phone's own choice. */
    val showPrivate: Boolean,
    /** Whether the user unlocked this visit, so the kinds hidden before it are named. */
    val unlocked: Boolean,
    /** Every kind but nudity, the most files first; Screenshots among them, its apps in [screenshotApps]. */
    val kinds: List<Kind>,
    /** The apps screenshots were taken in, the most first; one with an empty name has none in its file name. */
    val screenshotApps: List<Kind>,
    val nudity: HiddenCollections.Nudity,
) {
    /** Kinds and screenshot apps hidden before this visit, which the locked page counts without naming. */
    val hiddenBefore: Int get() = (kinds + screenshotApps).count { it.hiddenBefore }

    enum class Type { FOLDER, SCREENSHOTS, SENT_TO_YOU, CALLS, VOICE_RECORDINGS, VOICE_MESSAGES }

    /**
     * A kind: [name] for a folder or a screenshot app (an app or folder as the server names it). [hiddenBefore] is
     * whether it was hidden when the page opened: one hidden now stays named, so the user can turn it back on.
     */
    data class Kind(val type: Type, val name: String, val files: Int, val shown: Boolean, val hiddenBefore: Boolean = !shown)
}

internal class PrivateOptionC2Actions(
    val setShowPrivate: (Boolean) -> Unit = {},
    /** A kind, or a screenshot app, shown or hidden on every device; showing one hidden before asks for the lock. */
    val set: (PrivateOptionC2State.Kind, Boolean) -> Unit = { _, _ -> },
    /** Asks for the phone's lock, then names the hidden kinds. */
    val unlock: () -> Unit = {},
    val setNudityHidden: (Boolean) -> Unit = {},
    val setNudityFromPercent: (Int) -> Unit = {},
    val back: () -> Unit = {},
)
