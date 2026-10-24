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
) {
    fun shows(item: MediaItem): Boolean = isShown(item.source) || DaySource(item.day, item.source) in alwaysShown

    /** Whether [source] is on. */
    fun isShown(source: MediaSource): Boolean = source !in hidden

    /**
     * The pills Show in the app offers: the fixed sources, those with photos ([counts], photos per source) and those
     * hidden (so they can be shown again), the most photos first.
     */
    fun choices(counts: Map<MediaSource, Int>): List<MediaSource> = MediaSource.byCount(counts, hidden)

    /** Of [sources], those that are on. */
    fun shownOf(sources: Collection<MediaSource>): Set<MediaSource> = sources.filterTo(LinkedHashSet(), ::isShown)

    companion object {
        /** WhatsApp and screenshots are off at first (asked Oct 21, 2026). */
        val DEFAULT_HIDDEN: Set<MediaSource> = setOf(MediaSource.WHATSAPP, MediaSource.SCREENSHOTS)
    }
}

/** One day's photos from one source. */
data class DaySource(val day: LocalDay, val source: MediaSource)
