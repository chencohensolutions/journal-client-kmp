package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.MediaSource

/**
 * Show in the app with "Imported" as one source beside the phone's folders: on or off as a whole, and while on, its
 * kinds (what the import found: Camera, WhatsApp, Screenshots, Other) chosen under it.
 */
internal data class SourcesOptionBState(
    /** What phones uploaded and what is on this phone, by its folder. */
    val fromPhones: List<Source>,
    /** Whether anything imported shows. */
    val importedShown: Boolean,
    /** The import's kinds; they count only while [importedShown]. */
    val importedKinds: List<Source>,
) {
    data class Source(val source: MediaSource, val count: Int, val shown: Boolean)

    /** The imported files that show. */
    val importedCount: Int get() = importedKinds.filter { it.shown }.sumOf { it.count }

    /** The bar's "+N": hidden phone sources, and Imported when off. */
    val hiddenCount: Int get() = fromPhones.count { !it.shown } + if (importedShown) 0 else 1
}

internal class SourcesOptionBActions(
    val toggleFromPhones: (MediaSource) -> Unit = {},
    val toggleImported: () -> Unit = {},
    val toggleImportedKind: (MediaSource) -> Unit = {},
    val back: () -> Unit = {},
)
