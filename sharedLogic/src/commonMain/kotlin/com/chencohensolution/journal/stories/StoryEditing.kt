package com.chencohensolution.journal.stories

// Keeping a story consistent after any edit: groups, their tree and the layout. Pure functions on [Story], used by
// StoriesController before it saves.

/**
 * Layout entries only for tiles of the story's grid: a photo that joined a group, or left, loses its own, and so
 * does a group that became a subgroup.
 */
internal fun normalizeLayout(story: Story): Story {
    val grouped = story.groups.flatMap { it.memberIds }.toSet()
    val keys = story.members.map { it.deviceId }.filter { it !in grouped }.toSet() +
        story.groups.filter { it.parentId == null }.map { groupTileKey(it.id) }
    return story.copy(
        layout = story.layout.filter { (key, layout) -> key in keys && !layout.isEmpty },
        tileOrder = story.tileOrder.filter { it in keys }.distinct(),
    )
}

/**
 * Keeps groups consistent after any change: members must be in the story, a parent must exist (and not be the
 * group itself or inside it), a group needs two tiles (photos and subgroups), the cover must be one of its photos.
 * A group that goes away is [dissolve]d, so no photo or text is lost.
 */
internal fun normalizeGroups(story: Story): Story {
    val inStory = story.members.map { it.deviceId }.toSet()
    val ids = story.groups.map { it.id }.toSet()
    val groups = story.groups.map { group ->
        group.copy(memberIds = group.memberIds.filter { it in inStory }.distinct(), parentId = group.parentId?.takeIf { it in ids })
    }.toMutableList()
    // A loop of parents would hide its groups; the first group found in one becomes the story's own.
    groups.indices.forEach { index ->
        val seen = mutableSetOf(groups[index].id)
        var parent = groups[index].parentId
        while (parent != null) {
            if (!seen.add(parent)) {
                groups[index] = groups[index].copy(parentId = null)
                break
            }
            parent = groups.first { it.id == parent }.parentId
        }
    }
    var normalized = story.copy(groups = groups)
    while (true) {
        val small = normalized.groups.firstOrNull { group ->
            group.memberIds.size + normalized.groups.count { it.parentId == group.id } < 2
        } ?: break
        normalized = dissolve(normalized, small.id)
    }
    val byTime = story.members.associate { it.deviceId to it.takenAtMillis }
    return normalized.copy(
        groups = normalized.groups.map { group ->
            val photos = normalized.photosIn(group.id)
            group.copy(coverId = group.coverId.takeIf { it in photos } ?: photos.minByOrNull { byTime[it] ?: 0 } ?: group.coverId)
        },
    )
}

/**
 * Removes group [groupId]; its photos, subgroups and text move to the group around it, or to the story (loose
 * photos, and the opening text).
 */
internal fun dissolve(story: Story, groupId: String): Story {
    val group = story.groups.firstOrNull { it.id == groupId } ?: return story
    fun joinText(a: String) = listOf(a, group.text).filter { it.isNotBlank() }.joinToString("\n\n")
    return story.copy(
        groups = story.groups.filter { it.id != groupId }.map {
            when {
                it.id == group.parentId -> it.copy(memberIds = it.memberIds + group.memberIds, text = joinText(it.text))
                it.parentId == groupId -> it.copy(parentId = group.parentId)
                else -> it
            }
        },
        intro = if (group.parentId == null) joinText(story.intro) else story.intro,
    )
}

internal fun nextGroupId(story: Story): String {
    val used = (story.groups.map { it.id } + story.textExtras.keys).mapNotNull { it.removePrefix("g").toIntOrNull() }
    return "g${(used.maxOrNull() ?: 0) + 1}"
}
