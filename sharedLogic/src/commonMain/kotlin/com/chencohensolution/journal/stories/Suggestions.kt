package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.similarity.PhotoLooks

// Photos worth grouping: ones that look alike, or a burst taken in quick succession.

/**
 * Photos that could become a group: ones that look alike, taken at any time ([byLooks]), or, for photos not read
 * yet, ones taken in quick succession.
 */
data class BurstSuggestion(val items: List<MediaItem>, val byLooks: Boolean = false) {
    /** The first photo's id; remembered when the suggestion is dismissed. */
    val key: String get() = items.first().id
    val count: Int get() = items.size
    val startMillis: Long get() = items.first().takenAtMillis
    val seconds: Long get() = (items.last().takenAtMillis - items.first().takenAtMillis) / 1000
    val sameDay: Boolean get() = items.first().day == items.last().day
}

/** A new burst starts after a gap longer than this between two photos. */
internal const val BURST_GAP_MILLIS = 10_000L

/** Fewer photos than this taken in quick succession aren't worth a suggestion; two that look alike are. */
internal const val BURST_MIN_PHOTOS = 3

/**
 * What to suggest grouping among [items] (oldest first). Photos with a fingerprint go by looks, whenever they were
 * taken: each joins the first set where it looks like ([PhotoLooks.similar], or the same camera burst) more than half
 * of the photos (so sets don't drift through chains of look-alikes), and sets of two or more are suggested. Photos not read yet go by time ([findBursts]). Videos and
 * sets holding a dismissed photo are left out. Oldest first.
 */
internal fun findSuggestions(items: List<MediaItem>, dismissed: Set<String>, looks: PhotoLooks = PhotoLooks.NONE): List<BurstSuggestion> {
    val (read, unread) = items.filter { it.type == MediaType.IMAGE }.partition(looks::canCompare)
    val sets = mutableListOf<MutableList<MediaItem>>()
    read.forEach { photo ->
        val set = sets.firstOrNull { set -> set.count { looks.similar(it, photo) || sameBurst(it, photo) } * 2 > set.size }
        if (set != null) set += photo else sets += mutableListOf(photo)
    }
    val byLooks = sets.filter { it.size >= 2 && it.none { photo -> photo.id in dismissed } }.map { BurstSuggestion(it, byLooks = true) }
    return (byLooks + findBursts(unread, dismissed)).sortedBy { it.startMillis }
}

private fun sameBurst(a: MediaItem, b: MediaItem) = a.burstId != null && a.burstId == b.burstId

/**
 * Splits [items] (oldest first) into runs of photos taken within [BURST_GAP_MILLIS] of each other, or sharing the
 * camera's burst id, and suggests the runs of at least [BURST_MIN_PHOTOS]. Videos and runs holding a dismissed photo
 * are left out.
 */
internal fun findBursts(items: List<MediaItem>, dismissed: Set<String>): List<BurstSuggestion> {
    val runs = mutableListOf<MutableList<MediaItem>>()
    items.filter { it.type == MediaType.IMAGE }.forEach { item ->
        val previous = runs.lastOrNull()?.last()
        if (previous != null && (item.takenAtMillis - previous.takenAtMillis <= BURST_GAP_MILLIS || sameBurst(previous, item))) {
            runs.last() += item
        } else {
            runs += mutableListOf(item)
        }
    }
    return runs.filter { run -> run.size >= BURST_MIN_PHOTOS && run.none { it.id in dismissed } }.map(::BurstSuggestion)
}
