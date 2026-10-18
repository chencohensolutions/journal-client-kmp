package com.chencohensolution.journal.stories

/**
 * A photo or video in a story. Uploads don't exist yet, so it is identified by its device id; [fileName] and
 * [takenAtMillis] are kept so it can be matched to its server copy later.
 */
data class StoryMember(
    val deviceId: String,
    val fileName: String?,
    val takenAtMillis: Long,
)

/**
 * Photos of one moment shown as a single tile in their story, or in the group around it ([parentId]): groups nest.
 * Every member is also in [Story.members]; within one story a photo is directly in at most one group.
 */
data class StoryGroup(
    /** Unique within its story ("g1", "g2"…); names the group's section in story.md. */
    val id: String,
    val title: String?,
    /** Device id of the photo shown on the tile; any photo in the group or in its subgroups. */
    val coverId: String,
    /** The photos directly in this group, not those of its subgroups. */
    val memberIds: List<String>,
    /** The group's text, from its section of story.md. */
    val text: String = "",
    /** The group this one is a tile of; null for a tile of the story itself. */
    val parentId: String? = null,
)

/** Groups each followed by its subgroups, the story's own first: the order of their sections in story.md. */
internal fun List<StoryGroup>.inTreeOrder(): List<StoryGroup> {
    val ids = map { it.id }.toSet()
    val childrenOf = groupBy { it.parentId }
    val visited = mutableSetOf<String>()
    fun visit(group: StoryGroup): List<StoryGroup> =
        if (visited.add(group.id)) listOf(group) + childrenOf[group.id].orEmpty().flatMap(::visit) else emptyList()
    return filter { it.parentId == null || it.parentId !in ids }.flatMap(::visit)
}

/** The photos of group [groupId] and of its subgroups, at any depth. */
internal fun Story.photosIn(groupId: String): Set<String> {
    val childrenOf = groups.groupBy { it.parentId }
    val photos = mutableSetOf<String>()
    val visited = mutableSetOf<String>()
    fun visit(id: String) {
        if (!visited.add(id)) return
        groups.firstOrNull { it.id == id }?.let { photos += it.memberIds }
        childrenOf[id].orEmpty().forEach { visit(it.id) }
    }
    visit(groupId)
    return photos
}

/**
 * A trip, a person, a theme, anything worth naming: a title over a set of photos and videos, with its text. Stories
 * have no dates of their own; the span of their photos is shown when it helps.
 */
data class Story(
    /** UTC creation time, e.g. "2026-10-17T12-52-22.257Z"; also the folder name. Never changes. */
    val id: String,
    /** "" when it has none: titles are optional. */
    val title: String,
    /** Oldest first. */
    val members: List<StoryMember>,
    /** RFC 3339. */
    val createdAt: String,
    val updatedAt: String,
    /** Every group, nested ones included. */
    val groups: List<StoryGroup> = emptyList(),
    /** The story's opening text: story.md before the first group's section. */
    val intro: String = "",
    /** First photo of each burst suggestion the user answered "Not now" to. */
    val dismissedSuggestions: List<String> = emptyList(),
    /** Sections of story.md whose group no longer exists, kept so no text is lost. */
    val textExtras: Map<String, String> = emptyMap(),
    /** Sizes and focus points the owner chose, by tile key (device id, or [groupTileKey]). */
    val layout: Map<String, TileLayout> = emptyMap(),
    /** Tile keys in the order the owner arranged them; tiles not listed fall in by time. Empty: by time. */
    val tileOrder: List<String> = emptyList(),
)
