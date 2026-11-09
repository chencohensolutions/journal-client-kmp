package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.SoundRecording
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.similarity.PhotoLooks

/*
 * Moments built a window of days at a time (docs/plans/library-on-disk.md, phase 5): when items of some days change,
 * only the rows of a window of whole days around them are built again, with [buildFeed] itself, so they come out
 * exactly as building the whole library would make them.
 */

/** The library as Moments' windows read it: by days, never whole. */
internal interface FeedSource {
    /** Every item (of every source, shown or not) taken on days [first]..[last], newest first. */
    fun items(first: LocalDay, last: LocalDay): List<MediaItem>

    /** The newest item taken before [day], and the oldest taken after it; null when there is none. */
    fun newestBefore(day: LocalDay): MediaItem?
    fun oldestAfter(day: LocalDay): MediaItem?

    /** The days of these items (those still there). */
    fun daysOf(ids: Collection<String>): Set<LocalDay>
}

/** Days [first]..[last], both included. */
internal data class DayWindow(val first: LocalDay, val last: LocalDay) {
    operator fun contains(day: LocalDay): Boolean = day in first..last
}

/**
 * The windows of whole days to build again so that the [changed] days come out as building everything would make
 * them: their edges are breaks between rows.
 * - With [GroupingMode.BY_GAP] a row runs on while photos are less than the gap apart, across midnight: an edge needs
 *   a longer gap to the nearest item beyond it (counting every item, shown or not, which only ever widens it). A
 *   recording that started before the window counts as an item at its end, one that starts after it at its start.
 * - A set merged by hand ([sets]: photo id to set id) takes in the days of all its photos.
 * Changed days far apart give one window each; windows that meet are joined.
 */
internal fun windowsFor(
    changed: Set<LocalDay>,
    source: FeedSource,
    grouping: TimelineGrouping,
    sounds: List<SoundRecording>,
    sets: Map<String, String>,
): List<DayWindow> {
    if (changed.isEmpty()) return emptyList()
    val membersBySet = sets.entries.groupBy({ it.value }, { it.key })
    var windows = joined(changed.sorted().map { DayWindow(it, it) })
    while (true) {
        val widened = joined(windows.map { widen(it, source, grouping, sounds, membersBySet) })
        if (widened == windows) return windows
        windows = widened
    }
}

/** [window] widened until its edges are breaks between rows, as [windowsFor] widens the days it is given. */
internal fun widened(
    window: DayWindow,
    source: FeedSource,
    grouping: TimelineGrouping,
    sounds: List<SoundRecording>,
    sets: Map<String, String>,
): DayWindow = widen(window, source, grouping, sounds, sets.entries.groupBy({ it.value }, { it.key }))

/** [windows] sorted, those that meet or overlap joined. */
private fun joined(windows: List<DayWindow>): List<DayWindow> {
    val joined = mutableListOf<DayWindow>()
    windows.sortedBy { it.first }.forEach { window ->
        val last = joined.lastOrNull()
        if (last != null && window.first.toEpochDay() <= last.last.toEpochDay() + 1) {
            joined[joined.lastIndex] = DayWindow(last.first, maxOf(last.last, window.last))
        } else {
            joined += window
        }
    }
    return joined
}

/** [start] widened until its edges are breaks between rows (see [windowsFor]). */
private fun widen(
    start: DayWindow,
    source: FeedSource,
    grouping: TimelineGrouping,
    sounds: List<SoundRecording>,
    membersBySet: Map<String, List<String>>,
): DayWindow {
    var window = start
    while (true) {
        var first = window.first
        var last = window.last
        val items = source.items(first, last)
        // Sets merged by hand with a photo in the window: all their days.
        val ids = items.mapTo(HashSet()) { it.id }
        val setIds = membersBySet.filterValues { members -> members.any { it in ids } }.keys
        if (setIds.isNotEmpty()) {
            source.daysOf(setIds.flatMap { membersBySet.getValue(it) }).forEach { day ->
                if (day < first) first = day
                if (day > last) last = day
            }
        }
        if (grouping.mode == GroupingMode.BY_GAP) {
            // The oldest and newest moments inside: its items, and its recordings' starts and ends.
            val times = items.map { it.takenAtMillis } +
                sounds.filter { it.day in window }.flatMap { listOf(it.startMillis, it.endMillis) }
            val oldest = times.minOrNull()
            val newest = times.maxOrNull()
            // Below: the newest item before, or a recording that started before and ran on (counted at its end).
            val before = source.newestBefore(window.first)
            val ranOn = sounds.filter { it.day < window.first }.maxByOrNull { it.endMillis }
            val below: Pair<Long, LocalDay>? = when {
                ranOn != null && (before == null || ranOn.endMillis >= before.takenAtMillis) -> ranOn.endMillis to ranOn.day
                before != null -> before.takenAtMillis to before.day
                else -> null
            }
            if (below != null && (oldest == null || oldest - below.first <= grouping.gapMillis)) first = minOf(first, below.second)
            // Above: the oldest item after, or a recording that starts after.
            val after = source.oldestAfter(window.last)
            val startsAfter = sounds.filter { it.day > window.last }.minByOrNull { it.startMillis }
            val above: Pair<Long, LocalDay>? = when {
                startsAfter != null && (after == null || startsAfter.startMillis <= after.takenAtMillis) -> startsAfter.startMillis to startsAfter.day
                after != null -> after.takenAtMillis to after.day
                else -> null
            }
            if (above != null && (newest == null || above.first - newest <= grouping.gapMillis)) last = maxOf(last, above.second)
        }
        val widened = DayWindow(first, last)
        if (widened == window) return window
        window = widened
    }
}

/**
 * The rows of Moments for [window]: [buildFeed] over its items, the recordings that start in it and the sets merged by
 * hand of its photos, as the whole library's build makes them for these days.
 */
internal fun buildWindow(
    window: DayWindow,
    source: FeedSource,
    shown: ShownSources,
    grouping: TimelineGrouping,
    places: Places,
    looks: PhotoLooks,
    alone: Set<String>,
    sets: Map<String, String>,
    revealed: Set<FeedReveal>,
    sounds: List<SoundRecording>,
    tuckedShown: Set<LocalDay> = emptySet(),
    placements: Map<String, ShowOn> = emptyMap(),
): List<FeedSection> =
    buildFeed(source.items(window.first, window.last), shown, grouping, places, looks, alone, sets, revealed, sounds.filter { it.day in window }, tuckedShown, placements)
        .entries.mapNotNull { (it as? FeedEntry.Section)?.section }
