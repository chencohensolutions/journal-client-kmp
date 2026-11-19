package com.chencohensolution.journal.settings

import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.HiddenCollections

/**
 * Show in the app's pills, as Settings › Show in the app and the "Showing" sheets of Moments and Explore draw them
 * (option b2 of the UI lab, Oct 27, 2026): one list of sources, what the server's import placed counted as its kind
 * (there is no "Imported" since Nov 16, 2026). Each counts only what reaches this phone. What Private settings hides on every device is said, never switched here:
 * a source hidden whole is locked, one hidden in part carries a lock and the files it hides. That is known only once
 * Private settings was opened in this run of the app ([privateKnown]); Show in the app never asks the server itself.
 */
data class ShownSourcesPills(
    /** The sources, imported files in their kind; the most photos first, locked ones last. */
    val fromPhones: List<Pill>,
    /** Private settings was opened in this run, so [Pill.hiddenEverywhere] and [Pill.allHiddenEverywhere] are known. */
    val privateKnown: Boolean,
) {
    data class Pill(
        val source: MediaSource,
        /** The files that reach this phone. */
        val count: Int,
        /** On in Show in the app. */
        val shown: Boolean,
        /** Files Private settings hides on every device (screenshots of chosen apps); 0 when none or not known. */
        val hiddenEverywhere: Int = 0,
        /** Private settings hides all of it (an app such as WhatsApp): locked, a tap opens Private settings. */
        val allHiddenEverywhere: Boolean = false,
    )

    /** The sources Private settings hides whole ("WhatsApp"), in order. */
    val hiddenWhole: List<MediaSource>
        get() = fromPhones.filter { it.allHiddenEverywhere }.map { it.source.kind }.distinct().sorted()

    /** Of the sources it hides in part, how many files it hides on every device. */
    val hiddenInPart: Map<MediaSource, Int>
        get() = fromPhones.filter { !it.allHiddenEverywhere && it.hiddenEverywhere > 0 }
            .groupBy { it.source.kind }.mapValues { (_, pills) -> pills.sumOf { it.hiddenEverywhere } }
            .toList().sortedBy { it.first }.toMap()

    companion object {
        /**
         * The pills of [shown] over [counts] (photos per source, shown or not, as [com.chencohensolution.journal.stories.MomentsFeed.counts]),
         * with what [hidden] (ServerLibrary.hiddenEverywhere; null until Private settings is opened) hides on every
         * device. An app hidden whole is listed even when none of its files reach this phone.
         */
        fun of(shown: ShownSources, counts: Map<MediaSource, Int>, hidden: HiddenCollections?): ShownSourcesPills {
            val byKind = MediaSource.byKind(counts)
            fun pill(source: MediaSource): Pill {
                // What it hides of the phones' files and of the imported ones, added up.
                val phones = hidden?.hiddenOf(source)
                val imported = hidden?.hiddenOf(MediaSource.imported(source))
                val files = (phones?.files ?: 0) + (imported?.files ?: 0)
                return Pill(source, byKind[source] ?: 0, shown.isShown(source), files, phones?.whole == true)
            }
            val apps = hidden?.let { it.hiddenApps(imported = false) + it.hiddenApps(imported = true).map { app -> app.kind } }
            val sources = (shown.choices(counts) + apps.orEmpty()).distinct()
            return ShownSourcesPills(
                fromPhones = sources.map(::pill).sortedBy { it.allHiddenEverywhere },
                privateKnown = hidden != null,
            )
        }
    }
}
