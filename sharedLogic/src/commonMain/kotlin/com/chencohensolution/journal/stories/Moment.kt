package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.TimelineGrouping

/**
 * One row of the Moments tab, newest first: one day's photos, or with [GroupingMode.BY_GAP] a run of photos without
 * a long gap between them, which can cross midnight.
 */
data class Moment(val items: List<MediaItem>, val mode: GroupingMode) {
    /** Its oldest item's id: stays the same when newer photos join it. */
    val key: String get() = items.last().id
    val newestDay: LocalDay get() = items.first().day
    val oldestDay: LocalDay get() = items.last().day
    val startMillis: Long get() = items.last().takenAtMillis
    val endMillis: Long get() = items.first().takenAtMillis
}

/**
 * Splits [items] (newest first) into moments: by day, or wherever more than [TimelineGrouping.gapHours] pass
 * without a photo.
 */
internal fun groupMoments(items: List<MediaItem>, grouping: TimelineGrouping): List<Moment> = when (grouping.mode) {
    GroupingMode.BY_DAY -> items.groupBy { it.day }.values.map { Moment(it, grouping.mode) }
    GroupingMode.BY_GAP -> {
        val runs = mutableListOf<MutableList<MediaItem>>()
        items.forEach { item ->
            val newer = runs.lastOrNull()?.last()
            if (newer != null && newer.takenAtMillis - item.takenAtMillis <= grouping.gapMillis) runs.last() += item else runs += mutableListOf(item)
        }
        runs.map { Moment(it, grouping.mode) }
    }
}
