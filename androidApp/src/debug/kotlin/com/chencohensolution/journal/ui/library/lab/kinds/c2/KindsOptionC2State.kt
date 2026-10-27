package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.MediaSource

/**
 * Show in the app as a status page: every kind of file the user has, each with its files, how many are on this phone,
 * how many of those aren't on the server yet, and whether this phone hides it (with the filter bar in Moments, a saved
 * choice of this phone). Nothing here changes: hiding on this phone is done in Moments, hiding on every device in
 * Private settings. A kind Private settings hides on every device isn't listed; [hiddenEverywhere] only counts them.
 */
internal data class KindsOptionC2State(
    /** Photo and video kinds, the most files first, Other last. */
    val kinds: List<Kind>,
    /** Calls, Voice recordings, Voice messages, those that aren't hidden on every device. */
    val sounds: List<SoundKind>,
    /** How many kinds (screenshot apps among them) Private settings hides on every device. */
    val hiddenEverywhere: Int,
) {
    /** Every listed kind's files, for the line at the top. */
    val files: Int get() = kinds.sumOf { it.files } + sounds.sumOf { it.files }

    /** Every listed kind's files on this phone that aren't on the server yet. */
    val notBackedUp: Int get() = kinds.sumOf { it.notBackedUp } + sounds.sumOf { it.notBackedUp }

    data class Kind(
        val source: MediaSource,
        /** Its files, on the phone and the server alike. */
        val files: Int,
        /** Its files on this phone. */
        val onPhone: Int,
        /** Its files on this phone that aren't on the server yet; 0 when all are. */
        val notBackedUp: Int,
        /** Hidden on this phone with Moments' filter bar. */
        val hiddenHere: Boolean,
    )

    enum class Sound { CALLS, VOICE_RECORDINGS, VOICE_MESSAGES }

    data class SoundKind(val sound: Sound, val files: Int, val onPhone: Int, val notBackedUp: Int, val hiddenHere: Boolean)
}

internal class KindsOptionC2Actions(
    /** Settings › Backup, from a "not backed up" note. */
    val openBackup: () -> Unit = {},
    /** Private settings. */
    val openPrivate: () -> Unit = {},
    val back: () -> Unit = {},
)
