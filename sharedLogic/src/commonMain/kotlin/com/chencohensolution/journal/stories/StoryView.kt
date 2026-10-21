package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.similarity.PhotoLooks

// A story and its groups as they are on this device: what the screens draw.

/** A group with its photos as they are on this device. */
data class GroupView(
    val group: StoryGroup,
    /** Its photos and its subgroups' photos, oldest first. */
    val items: List<MediaItem>,
    /** What its page shows: its own photos, and each subgroup as one tile, oldest first. */
    val tiles: List<StoryTile> = items.map { StoryTile.Media(it) },
    /** Fingerprints of its own photos, for [suggestions]. */
    val looks: PhotoLooks = PhotoLooks.NONE,
    /** The story's [Story.dismissedSuggestions]. */
    val dismissed: Set<String> = emptySet(),
) {
    val id: String get() = group.id
    val title: String? get() = group.title
    val text: String get() = group.text
    val itemCount: Int get() = items.size

    /** The group this one is a tile of; null when it is a tile of the story. */
    val parentId: String? get() = group.parentId

    /** The photo on the group's tile. */
    val cover: MediaItem = items.firstOrNull { it.id == group.coverId } ?: items.first()

    /** What the viewer swipes through from the group's page: its photos, and each subgroup as its cover. */
    val viewerItems: List<MediaItem> get() = tiles.map { it.firstItem(cover = true) }

    /** The subgroup shown as [itemId] in [viewerItems], if that photo stands for one. */
    fun coverStop(itemId: String): GroupView? = tiles.coverStop(itemId)

    /**
     * Its own photos that look alike (or were taken in a burst), to become subgroups; never all of its tiles, which
     * would only wrap the group again. Worked out when first read.
     */
    val suggestions: List<BurstSuggestion> by lazy {
        val own = tiles.filterIsInstance<StoryTile.Media>().map { it.item }
        findSuggestions(own, dismissed, looks).filter { it.count < tiles.size }
    }
}

private fun List<StoryTile>.coverStop(itemId: String): GroupView? =
    filterIsInstance<StoryTile.Group>().firstOrNull { it.group.cover.id == itemId }?.group

/** What a story shows in its grid: a photo or video, or a group as one tile. */
sealed interface StoryTile {
    /** Names the tile in the story's layout: the photo's device id, or [groupTileKey]. */
    val key: String

    data class Media(val item: MediaItem) : StoryTile {
        override val key: String get() = item.id
    }

    data class Group(val group: GroupView) : StoryTile {
        override val key: String get() = groupTileKey(group.id)
    }
}

/** One day of a story's grid, packed into [bands]. A group sits on the day of its first photo. */
data class StoryDay(val day: LocalDay, val tiles: List<StoryTile>, val bands: List<StoryBand>)

/** A story with its photos and videos as they are on this device. */
data class StoryView(
    val story: Story,
    /** Members found on this device, oldest first. */
    val items: List<MediaItem>,
    /** Fingerprints of its photos worked out so far; they make [suggestions] find photos that look alike. */
    val looks: PhotoLooks = PhotoLooks.NONE,
) {
    val id: String get() = story.id
    /** "" when the story has no title. */
    val title: String get() = story.title
    val hasTitle: Boolean get() = story.title.isNotBlank()
    val intro: String get() = story.intro

    /** Day of its oldest photo on this device; null when none is. */
    val firstDay: LocalDay? = items.firstOrNull()?.day

    /** Day of its newest photo on this device; null when none is. */
    val lastDay: LocalDay? = items.lastOrNull()?.day

    /** Days from the first photo to the last; 0 without photos. */
    val dayCount: Int = if (firstDay != null && lastDay != null) (lastDay.toEpochDay() - firstDay.toEpochDay() + 1).toInt() else 0

    /** Where the story sits in the journal: its newest photo's day, or the day it was created. */
    val timelineDay: LocalDay = lastDay ?: LocalDay.parse(story.createdAt.take(10)) ?: LocalDay(1970, 1, 1)

    /** Photos days apart rather than months, like a trip: then neighboring days are worth suggesting. */
    val isShortSpan: Boolean = dayCount in 1..SHORT_SPAN_DAYS

    val itemCount: Int get() = items.size
    val notBackedUpCount: Int = items.count { it.backup != BackupStatus.BackedUp }

    /** Members that are no longer on this device (deleted, or not visible with limited access). */
    val missingCount: Int = story.members.size - items.size

    /** The story's own groups (not nested in another) with at least one photo on this device. */
    private val rootGroups: List<GroupView>

    /** Groups with at least one photo on this device, nested ones included: each followed by its subgroups. */
    val groups: List<GroupView>

    private val dismissed = story.dismissedSuggestions.toSet()

    init {
        val byId = items.associateBy { it.id }
        val known = story.groups.map { it.id }.toSet()
        val childrenOf = story.groups.groupBy { it.parentId }
        val visited = mutableSetOf<String>()
        val all = mutableListOf<GroupView?>()
        fun view(group: StoryGroup): GroupView? {
            if (!visited.add(group.id)) return null
            val slot = all.size
            all += null
            val own = group.memberIds.mapNotNull { byId[it] }.map { StoryTile.Media(it) }
            val subgroups = childrenOf[group.id].orEmpty().mapNotNull(::view).map { StoryTile.Group(it) }
            val tiles = (own + subgroups).sortedBy { it.firstItem().takenAtMillis }
            if (tiles.isEmpty()) return null
            val items = tiles.flatMap { it.allItems() }.sortedBy { it.takenAtMillis }
            return GroupView(group, items, tiles, looks.only(own.map { it.item.id }), dismissed).also { all[slot] = it }
        }
        rootGroups = story.groups.filter { it.parentId == null || it.parentId !in known }.mapNotNull(::view)
        groups = all.filterNotNull()
    }

    /**
     * The grid: loose photos, and each of the story's own groups where its first photo would be, oldest first unless
     * the owner arranged them ([Story.tileOrder]).
     */
    val tiles: List<StoryTile> = run {
        val grouped = story.groups.flatMap { it.memberIds }.toSet()
        val byTime = items.filter { it.id !in grouped }.map { StoryTile.Media(it) } + rootGroups.map { StoryTile.Group(it) }
        arrangeTiles(byTime, story.tileOrder, key = { it.key }, time = { it.firstItem().takenAtMillis })
    }

    /** [tiles] by day, oldest day first: a story reads like a story. Within a day, in the story's order. */
    val tileDays: List<StoryDay> = tiles
        .groupBy { tile -> tile.firstItem().day }
        .toList()
        .sortedBy { (day, _) -> day }
        .map { (day, dayTiles) -> StoryDay(day, dayTiles, packDay(dayTiles, story.layout)) }

    /** What the viewer swipes through: loose photos, and each group as its cover. */
    val viewerItems: List<MediaItem> = tiles.map { it.firstItem(cover = true) }

    /** The loose photos and videos: the story's own, in no group. */
    val looseItems: List<MediaItem> get() = tiles.filterIsInstance<StoryTile.Media>().map { it.item }

    /** Photos that look alike (or bursts) among the loose photos, except dismissed ones. Worked out when first read. */
    val suggestions: List<BurstSuggestion> by lazy { findSuggestions(looseItems, dismissed, looks) }

    fun group(id: String): GroupView? = groups.firstOrNull { it.id == id }

    /** The group a page of the story shows as [itemId] in [viewerItems], if that photo stands for one. */
    fun coverStop(itemId: String): GroupView? = tiles.coverStop(itemId)

    /** The groups around [groupId], the story's own first; for the group page's heading. */
    fun parentsOf(groupId: String): List<GroupView> {
        val parents = mutableListOf<GroupView>()
        var parent = group(groupId)?.parentId?.let(::group)
        while (parent != null && parent !in parents) {
            parents.add(0, parent)
            parent = parent.parentId?.let(::group)
        }
        return parents
    }
}

internal fun StoryTile.firstItem(cover: Boolean = false): MediaItem = when (this) {
    is StoryTile.Media -> item
    is StoryTile.Group -> if (cover) group.cover else group.items.first()
}

private fun StoryTile.allItems(): List<MediaItem> = when (this) {
    is StoryTile.Media -> listOf(item)
    is StoryTile.Group -> group.items
}

/** A story whose photos span at most this many days is trip-like; see [StoryView.isShortSpan]. */
internal const val SHORT_SPAN_DAYS = 14
