package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.MediaSource

/**
 * Show in the app by kind: every kind of file the user has (Camera, WhatsApp, Screenshots, Sent to you, a folder such as
 * Telegram or Petcube, Other) and the sound kinds, each with its files and how many of this phone's aren't on the
 * server yet. A kind Private settings hides on every device isn't listed at all; [someHiddenEverywhere] says only that
 * there are some, never which.
 */
internal data class KindsOptionAState(
    /** Photo and video kinds, the most files first. */
    val kinds: List<Kind>,
    /** Calls, Voice recordings, Voice messages, those that aren't hidden on every device. */
    val sounds: List<SoundKind>,
    /** Whether Private settings hides some kinds on every device. */
    val someHiddenEverywhere: Boolean,
    val sayWhatsHidden: Boolean = true,
    val showFilterBar: Boolean = true,
) {
    data class Kind(
        val source: MediaSource,
        /** Its files, on the phone and the server alike. */
        val files: Int,
        /** Its files on this phone that aren't on the server yet; 0 when all are. */
        val notBackedUp: Int,
        val shown: Boolean,
    )

    enum class Sound { CALLS, VOICE_RECORDINGS, VOICE_MESSAGES }

    data class SoundKind(val sound: Sound, val files: Int, val notBackedUp: Int, val shown: Boolean)
}

internal class KindsOptionAActions(
    val toggle: (MediaSource) -> Unit = {},
    val toggleSound: (KindsOptionAState.Sound) -> Unit = {},
    /** Settings › Backup, from a "not backed up" note. */
    val openBackup: () -> Unit = {},
    /** Private settings, behind the phone's lock. */
    val openPrivate: () -> Unit = {},
    val setSayWhatsHidden: (Boolean) -> Unit = {},
    val setShowFilterBar: (Boolean) -> Unit = {},
    val back: () -> Unit = {},
)
