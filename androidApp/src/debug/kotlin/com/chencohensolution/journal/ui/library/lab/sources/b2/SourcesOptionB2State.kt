package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.MediaSource

/**
 * Option b with Private settings in view: "Imported" as one source beside the phone's folders, its kinds under it, and
 * each pill counting only what reaches this phone. What Private settings (Hidden collections) hides on every device is
 * said, never switched here: a kind hidden whole is locked, one hidden in part carries a lock and a line below.
 */
internal data class SourcesOptionB2State(
    /** What phones uploaded and what is on this phone, by its folder. */
    val fromPhones: List<Source>,
    /** Whether anything imported shows. */
    val importedShown: Boolean,
    /** The import's kinds; they count only while [importedShown]. */
    val importedKinds: List<Source>,
) {
    data class Source(
        val source: MediaSource,
        /** The files that reach this phone. */
        val count: Int,
        val shown: Boolean,
        /** Files Private settings hides on every device (screenshots of chosen apps); 0 when none. */
        val hiddenEverywhere: Int = 0,
        /** Private settings hides the whole kind (WhatsApp): locked here, tapping opens Private settings. */
        val allHiddenEverywhere: Boolean = false,
    )

    /** The imported files that show. */
    val importedCount: Int get() = importedKinds.filter { it.shown && !it.allHiddenEverywhere }.sumOf { it.count }

    /** The bar's "+N": hidden phone sources, and Imported when off; what Private settings hides isn't counted. */
    val hiddenCount: Int get() = fromPhones.count { !it.shown && !it.allHiddenEverywhere } + if (importedShown) 0 else 1
}

internal class SourcesOptionB2Actions(
    val toggleFromPhones: (MediaSource) -> Unit = {},
    val toggleImported: () -> Unit = {},
    val toggleImportedKind: (MediaSource) -> Unit = {},
    /** Private settings (Hidden collections), behind the phone's lock. */
    val openPrivate: () -> Unit = {},
    val back: () -> Unit = {},
)
