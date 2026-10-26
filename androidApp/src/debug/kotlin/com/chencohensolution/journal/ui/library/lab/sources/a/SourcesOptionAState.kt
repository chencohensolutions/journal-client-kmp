package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.MediaSource

/**
 * Show in the app in two groups: the sources of what phones uploaded, told by the phone's folder, and the sources of
 * what was imported into the server (uploadedBy "import"), told by the import. A WhatsApp folder on the phone and
 * WhatsApp in an import are two choices, never one.
 */
internal data class SourcesOptionAState(
    /** What phones uploaded and what is on this phone, by its folder: Camera, WhatsApp, Petcube… */
    val fromPhones: List<Source>,
    /** What the import placed, by what it found: Camera, WhatsApp, Screenshots, Other. */
    val imported: List<Source>,
) {
    data class Source(val source: MediaSource, val count: Int, val shown: Boolean)

    /** The hidden ones of both groups: the bar's "+N". */
    val hiddenCount: Int get() = (fromPhones + imported).count { !it.shown }
}

/** Taps: a source of either group shown or hidden. */
internal class SourcesOptionAActions(
    val toggleFromPhones: (MediaSource) -> Unit = {},
    val toggleImported: (MediaSource) -> Unit = {},
    val back: () -> Unit = {},
)
