package com.chencohensolution.journal.settings

import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource

/**
 * Which sources the app shows (Settings › Show in the app, and the Moments filter bar): one choice for Moments, All
 * photos, Explore, Tags and the days offered to a story. Photos already in a story always show in that story. Hiding
 * never deletes anything and doesn't change backup.
 */
data class ShownSources(
    val sources: Set<MediaSource> = DEFAULT_SOURCES,
    /** "+6 WhatsApp" on a day of Moments, for the photos it leaves out. */
    val sayWhatsHidden: Boolean = true,
    /** Days whose photos of a hidden source show anyway ("Always show this day"). */
    val alwaysShown: Set<DaySource> = emptySet(),
) {
    fun shows(item: MediaItem): Boolean = item.source in sources || DaySource(item.day, item.source) in alwaysShown

    /** Whether [source] is on; for Swift, where a Kotlin set's `contains` takes `Any`. */
    fun isShown(source: MediaSource): Boolean = source in sources

    companion object {
        /** WhatsApp and screenshots are off at first (asked Oct 21, 2026). */
        val DEFAULT_SOURCES: Set<MediaSource> = setOf(MediaSource.CAMERA, MediaSource.OTHER)
    }
}

/** One day's photos from one source. */
data class DaySource(val day: LocalDay, val source: MediaSource)
