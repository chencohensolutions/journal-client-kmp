package com.chencohensolution.journal.settings

import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource

/**
 * Which sources the app shows (Settings › Show in the app, and the Moments filter bar): one choice for Moments, All
 * photos, Explore and Tags. Kept as the sources hidden, so a folder seen for the first time shows. Hiding never
 * deletes anything and doesn't change backup.
 */
data class ShownSources(
    val hidden: Set<MediaSource> = DEFAULT_HIDDEN,
    /** "+6 WhatsApp" on a day of Moments, for the photos it leaves out. */
    val sayWhatsHidden: Boolean = true,
    /** Days whose photos of a hidden source show anyway ("Always show this day"). */
    val alwaysShown: Set<DaySource> = emptySet(),
    /** The filter bar above Moments (on by default); off, the sources are chosen in Settings only (asked Oct 24, 2026). */
    val showFilterBar: Boolean = true,
    /**
     * Whether what the server's import placed shows at all ("Imported", option b2 of the UI lab, Oct 27, 2026); while
     * on, each of its kinds ([MediaSource.IMPORTED]) is shown or hidden in [hidden] like any source.
     */
    val importedShown: Boolean = true,
) {
    fun shows(item: MediaItem): Boolean = shows(item.day, item.source)

    /** Whether what [source] saved on [day] shows. */
    fun shows(day: LocalDay, source: MediaSource): Boolean = isShown(source) || DaySource(day, source) in alwaysShown

    /** Whether [source] is on; an imported one only while Imported is. */
    fun isShown(source: MediaSource): Boolean = source !in hidden && (importedShown || !source.isImported)

    /**
     * The pills Show in the app offers: the fixed sources, those with photos ([counts], photos per source) and those
     * hidden (so they can be shown again), the most photos first. The imported ones are [importedChoices].
     */
    fun choices(counts: Map<MediaSource, Int>): List<MediaSource> = MediaSource.byCount(counts, hidden)

    /** The kinds under Imported, the most photos first; none while nothing imported has photos. */
    fun importedChoices(counts: Map<MediaSource, Int>): List<MediaSource> = MediaSource.importedChoices(counts)

    /** Of the sources with photos in [counts], phones' and imported, those that are on. */
    fun shownAmong(counts: Map<MediaSource, Int>): Set<MediaSource> =
        shownOf(MediaSource.choices(counts.keys) + MediaSource.importedChoices(counts))

    /** Of [sources], those that are on. */
    fun shownOf(sources: Collection<MediaSource>): Set<MediaSource> = sources.filterTo(LinkedHashSet(), ::isShown)

    companion object {
        /** What the import placed of WhatsApp and screenshots is off at first too, as the phones' (Oct 27, 2026). */
        val DEFAULT_HIDDEN_IMPORTED: Set<MediaSource> =
            setOf(MediaSource.imported(MediaSource.WHATSAPP), MediaSource.imported(MediaSource.SCREENSHOTS))

        /** WhatsApp and screenshots are off at first (asked Oct 21, 2026). */
        val DEFAULT_HIDDEN: Set<MediaSource> = setOf(MediaSource.WHATSAPP, MediaSource.SCREENSHOTS) + DEFAULT_HIDDEN_IMPORTED
    }
}

/** One day's photos from one source. */
data class DaySource(val day: LocalDay, val source: MediaSource)
