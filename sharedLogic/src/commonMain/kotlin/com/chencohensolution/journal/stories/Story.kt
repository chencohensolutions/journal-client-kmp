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
 * Photos of one moment shown as a single tile in their story. Every member is also in [Story.members]; within one
 * story a photo is in at most one group.
 */
data class StoryGroup(
    /** Unique within its story ("g1", "g2"…); names the group's section in story.md. */
    val id: String,
    val title: String?,
    /** Device id of the photo shown on the tile; one of [memberIds]. */
    val coverId: String,
    val memberIds: List<String>,
    /** The group's text, from its section of story.md. */
    val text: String = "",
)

/**
 * A trip, a person, a theme, anything worth naming: a title over a set of photos and videos, with its text. Stories
 * have no dates of their own; the span of their photos is shown when it helps.
 */
data class Story(
    /** UTC creation time, e.g. "2026-10-17T12-52-22.257Z"; also the folder name. Never changes. */
    val id: String,
    val title: String,
    /** Oldest first. */
    val members: List<StoryMember>,
    /** RFC 3339. */
    val createdAt: String,
    val updatedAt: String,
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
