package com.chencohensolution.journal.settings

import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.HiddenCollections

/**
 * Show in the app's pills, as Settings › Show in the app and the "Showing" sheets of Moments and Explore draw them
 * (option b2 of the UI lab, Oct 27, 2026): the phones' sources, then one "Imported" with its kinds under it while on.
 * Each counts only what reaches this phone. What Private settings hides on every device is said, never switched here:
 * a source hidden whole is locked, one hidden in part carries a lock and the files it hides. That is known only once
 * Private settings was opened in this run of the app ([privateKnown]); Show in the app never asks the server itself.
 */
data class ShownSourcesPills(
    /** What phones uploaded and what is on this phone, by its folder; the most photos first, locked ones last. */
    val fromPhones: List<Pill>,
    /** Whether anything imported shows ([ShownSources.importedShown]). */
    val importedShown: Boolean,
    /** The import's kinds, the most photos first; empty while nothing imported has photos. */
    val importedKinds: List<Pill>,
    /** Private settings was opened in this run, so [Pill.hiddenEverywhere] and [Pill.allHiddenEverywhere] are known. */
    val privateKnown: Boolean,
) {
    data class Pill(
        val source: MediaSource,
        /** The files that reach this phone. */
        val count: Int,
        /** On in Show in the app; an imported kind regardless of Imported itself. */
        val shown: Boolean,
        /** Files Private settings hides on every device (screenshots of chosen apps); 0 when none or not known. */
        val hiddenEverywhere: Int = 0,
        /** Private settings hides all of it (an app such as WhatsApp): locked, a tap opens Private settings. */
        val allHiddenEverywhere: Boolean = false,
    )

    /** Whether anything imported has photos, or is hidden on every device: Imported is offered. */
    val hasImported: Boolean get() = importedKinds.isNotEmpty()

    /** The "Imported" pill's count: what was imported and reaches this phone, shown or not. */
    val importedCount: Int get() = importedKinds.filter { !it.allHiddenEverywhere }.sumOf { it.count }

    /** The sources Private settings hides whole, phones' and imported as one ("WhatsApp"), in order. */
    val hiddenWhole: List<MediaSource>
        get() = (fromPhones + importedKinds).filter { it.allHiddenEverywhere }.map { it.source.kind }.distinct().sorted()

    /** Of the sources it hides in part, how many files it hides on every device, phones' and imported added up. */
    val hiddenInPart: Map<MediaSource, Int>
        get() = (fromPhones + importedKinds).filter { !it.allHiddenEverywhere && it.hiddenEverywhere > 0 }
            .groupBy { it.source.kind }.mapValues { (_, pills) -> pills.sumOf { it.hiddenEverywhere } }
            .toList().sortedBy { it.first }.toMap()

    companion object {
        /**
         * The pills of [shown] over [counts] (photos per source, shown or not, as [com.chencohensolution.journal.stories.MomentsFeed.counts]),
         * with what [hidden] (ServerLibrary.hiddenEverywhere; null until Private settings is opened) hides on every
         * device. An app hidden whole is listed even when none of its files reach this phone.
         */
        fun of(shown: ShownSources, counts: Map<MediaSource, Int>, hidden: HiddenCollections?): ShownSourcesPills {
            fun pill(source: MediaSource, on: Boolean): Pill {
                val of = hidden?.hiddenOf(source)
                return Pill(source, counts[source] ?: 0, on, of?.files ?: 0, of?.whole == true)
            }
            fun ordered(sources: List<MediaSource>, on: (MediaSource) -> Boolean) =
                sources.distinct().map { pill(it, on(it)) }.sortedBy { it.allHiddenEverywhere }
            val phones = shown.choices(counts) + hidden?.hiddenApps(imported = false).orEmpty()
            val imported = shown.importedChoices(counts) + hidden?.hiddenApps(imported = true).orEmpty()
            return ShownSourcesPills(
                fromPhones = ordered(phones, shown::isShown),
                importedShown = shown.importedShown,
                importedKinds = ordered(imported) { it !in shown.hidden },
                privateKnown = hidden != null,
            )
        }
    }
}
