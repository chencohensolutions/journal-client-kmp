package com.chencohensolution.journal.stories

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path

/** Where stories are kept until they can be synced to the server. */
internal interface StoryStore {
    fun loadAll(): List<Story>
    fun save(story: Story)
    fun delete(id: String)
}

/**
 * One folder per story, `<root>/stories/<id>/`, holding story.json and story.md in the same shape as the server's,
 * so the folder stays readable without the app. Each file is written to a temporary file and renamed over the old one.
 */
internal class FileStoryStore(private val fileSystem: FileSystem, private val root: Path) : StoryStore {
    private val storiesDir = root / "stories"

    override fun loadAll(): List<Story> {
        moveFromEvents()
        if (!fileSystem.exists(storiesDir)) return emptyList()
        return fileSystem.list(storiesDir).mapNotNull { dir ->
            val file = dir / STORY_FILE
            if (!fileSystem.exists(file)) return@mapNotNull null
            try {
                val text = (dir / TEXT_FILE).takeIf(fileSystem::exists)
                    ?.let { path -> StoryText.parse(fileSystem.read(path) { readUtf8() }) }
                fileSystem.read(file) { json.decodeFromString<StoryFile>(readUtf8()) }.toStory(text)
            } catch (e: Exception) {
                // A damaged file must not hide every other story; it stays on disk untouched.
                null
            }
        }
    }

    override fun save(story: Story) {
        val dir = storiesDir / story.id
        fileSystem.createDirectories(dir)
        writeAtomically(dir / STORY_FILE, json.encodeToString(StoryFile.of(story)))
        writeAtomically(dir / TEXT_FILE, textOf(story).toMarkdown(story.title))
    }

    override fun delete(id: String) {
        try {
            fileSystem.deleteRecursively(storiesDir / id)
        } catch (e: IOException) {
            // Already gone.
        }
    }

    /** Before stories were called events they lived in `events/<id>/event.json`; their old dates are ignored. */
    private fun moveFromEvents() {
        val eventsDir = root / "events"
        if (!fileSystem.exists(eventsDir)) return
        fileSystem.createDirectories(storiesDir)
        fileSystem.list(eventsDir).forEach { dir ->
            val target = storiesDir / dir.name
            if (fileSystem.exists(target)) return@forEach
            fileSystem.atomicMove(dir, target)
            if (fileSystem.exists(target / "event.json")) fileSystem.atomicMove(target / "event.json", target / STORY_FILE)
        }
        if (fileSystem.list(eventsDir).isEmpty()) fileSystem.delete(eventsDir)
    }

    private fun writeAtomically(path: Path, text: String) {
        val temp = path.parent!! / "${path.name}.tmp"
        fileSystem.write(temp) { writeUtf8(text) }
        fileSystem.atomicMove(temp, path)
    }

    private companion object {
        const val STORY_FILE = "story.json"
        const val TEXT_FILE = "story.md"
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/** Groups' sections in tile order, then sections whose group is gone. */
private fun textOf(story: Story) = StoryText(
    intro = story.intro,
    sections = story.groups.associate { it.id to it.text } + story.textExtras.filterKeys { id -> story.groups.none { it.id == id } },
)

/**
 * story.json, format version 1. Unknown fields (added by later versions, or the dates events used to have) are
 * ignored when reading.
 */
@Serializable
private data class StoryFile(
    val v: Int = 1,
    val id: String,
    val title: String,
    val createdAt: String,
    val updatedAt: String,
    val tiles: List<Tile>,
    val dismissedSuggestions: List<String> = emptyList(),
) {
    /**
     * A "media" tile is one photo or video; a "group" tile holds media tiles shown as one, its text in story.md
     * under `<!-- group:<id> -->`.
     */
    @Serializable
    data class Tile(
        val type: String,
        val deviceId: String? = null,
        val fileName: String? = null,
        val takenAt: Long? = null,
        val id: String? = null,
        val title: String? = null,
        val cover: String? = null,
        val tiles: List<Tile>? = null,
    ) {
        fun toMember(): StoryMember? = deviceId?.let { StoryMember(it, fileName, takenAt ?: 0) }

        companion object {
            fun media(member: StoryMember) =
                Tile(type = "media", deviceId = member.deviceId, fileName = member.fileName, takenAt = member.takenAtMillis)
        }
    }

    fun toStory(text: StoryText?): Story {
        val members = mutableListOf<StoryMember>()
        val groups = mutableListOf<StoryGroup>()
        tiles.forEach { tile ->
            when (tile.type) {
                "media" -> tile.toMember()?.let(members::add)
                "group" -> {
                    val groupMembers = tile.tiles.orEmpty().filter { it.type == "media" }.mapNotNull { it.toMember() }
                    val groupId = tile.id ?: return@forEach
                    if (groupMembers.isEmpty()) return@forEach
                    members += groupMembers
                    groups += StoryGroup(
                        id = groupId,
                        title = tile.title,
                        coverId = tile.cover?.takeIf { cover -> groupMembers.any { it.deviceId == cover } } ?: groupMembers.first().deviceId,
                        memberIds = groupMembers.map { it.deviceId },
                        text = text?.sections?.get(groupId).orEmpty(),
                    )
                }
            }
        }
        return Story(
            id = id,
            title = title,
            members = members.sortedBy { it.takenAtMillis },
            createdAt = createdAt,
            updatedAt = updatedAt,
            groups = groups,
            intro = text?.intro.orEmpty(),
            dismissedSuggestions = dismissedSuggestions,
            textExtras = text?.sections.orEmpty().filterKeys { id -> groups.none { it.id == id } },
        )
    }

    companion object {
        /** Loose members are media tiles; a group's tile sits where its first member would be. */
        fun of(story: Story): StoryFile {
            val groupOf = story.groups.flatMap { group -> group.memberIds.map { it to group } }.toMap()
            val byId = story.members.associateBy { it.deviceId }
            val written = mutableSetOf<String>()
            val tiles = story.members.mapNotNull { member ->
                val group = groupOf[member.deviceId] ?: return@mapNotNull Tile.media(member)
                if (!written.add(group.id)) return@mapNotNull null
                Tile(
                    type = "group",
                    id = group.id,
                    title = group.title,
                    cover = group.coverId,
                    tiles = group.memberIds.mapNotNull { byId[it] }.map(Tile::media),
                )
            }
            return StoryFile(
                id = story.id,
                title = story.title,
                createdAt = story.createdAt,
                updatedAt = story.updatedAt,
                tiles = tiles,
                dismissedSuggestions = story.dismissedSuggestions,
            )
        }
    }
}
