package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.MediaSource

/**
 * Show in the app by kind, as pills: every kind the user has (Camera, WhatsApp, Screenshots, Sent to you, a folder,
 * Other) and the sound kinds, each with its files and how many of this phone's aren't on the server yet. A kind
 * Private settings hides on every device isn't among them; [someHiddenEverywhere] says only that there are some.
 */
internal data class KindsOptionBState(
    /** Photo and video kinds, the most files first. */
    val kinds: List<Kind>,
    /** Calls, Voice recordings, Voice messages, those that aren't hidden on every device. */
    val sounds: List<SoundKind>,
    val someHiddenEverywhere: Boolean,
    val sayWhatsHidden: Boolean = true,
    val showFilterBar: Boolean = true,
) {
    data class Kind(val source: MediaSource, val files: Int, val notBackedUp: Int, val shown: Boolean)

    enum class Sound { CALLS, VOICE_RECORDINGS, VOICE_MESSAGES }

    data class SoundKind(val sound: Sound, val files: Int, val notBackedUp: Int, val shown: Boolean)
}

internal class KindsOptionBActions(
    val toggle: (MediaSource) -> Unit = {},
    val toggleSound: (KindsOptionBState.Sound) -> Unit = {},
    /** Settings › Backup, from a pill's "not backed up" badge. */
    val openBackup: () -> Unit = {},
    val openPrivate: () -> Unit = {},
    val setSayWhatsHidden: (Boolean) -> Unit = {},
    val setShowFilterBar: (Boolean) -> Unit = {},
    val back: () -> Unit = {},
)
