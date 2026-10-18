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
        val dir = dirOf(story.id)
        fileSystem.createDirectories(dir)
        writeAtomically(dir / STORY_FILE, json.encodeToString(StoryFile.of(story)))
        writeAtomically(dir / TEXT_FILE, textOf(story).toMarkdown(story.title))
    }

    override fun delete(id: String) {
        try {
            fileSystem.deleteRecursively(dirOf(id))
        } catch (e: IOException) {
            // Already gone.
        }
    }

    /**
     * The story's folder. Ids come from files and routes, so they are checked first: only a plain name can reach
     * the file system, never "..", "/" or an empty name that would point at the stories folder itself or above it.
     */
    private fun dirOf(id: String): Path {
        require(SAFE_ID.matches(id) && ".." !in id) { "Not a story id: $id" }
        return storiesDir / id
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

        /** Timestamp ids ("2026-10-17T12-52-22.257Z") and the UUIDs of older stories. */
        val SAFE_ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/** Groups' sections, each followed by its subgroups', then sections whose group is gone. */
private fun textOf(story: Story) = StoryText(
    intro = story.intro,
    sections = story.groups.inTreeOrder().associate { it.id to it.text } +
        story.textExtras.filterKeys { id -> story.groups.none { it.id == id } },
)

/**
 * story.json, format version 1. Unknown fields (added by later versions, or the dates events used to have) are
 * ignored when reading. Tiles are listed in the story's order; a tile's optional `size` and `focus` are how the
 * owner arranged it.
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
     * A "media" tile is one photo or video; a "group" tile holds media tiles, and the group tiles of its subgroups,
     * shown as one, its text in story.md under `<!-- group:<id> -->`. On the story's grid a tile is [size]
     * ([TileSize.fileName]; automatic when missing), cropped around [focus] (a group: its cover), [zoom] times
     * closer than filling the tile. Inside a group the tiles carry none of these.
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
        val size: String? = null,
        val focus: Focus? = null,
        val zoom: Float? = null,
    ) {
        fun toMember(): StoryMember? = deviceId?.let { StoryMember(it, fileName, takenAt ?: 0) }

        /** The key of this tile in [Story.layout]; null for a tile that can't be read. */
        val key: String?
            get() = when (type) {
                "media" -> deviceId
                "group" -> id?.let(::groupTileKey)
                else -> null
            }

        val layout: TileLayout
            get() = TileLayout(TileSize.parse(size), focus?.let { FocusPoint.of(it.x, it.y) }, cleanZoom(zoom))

        /** When its photo was taken; a group's oldest photo, subgroups included. */
        val time: Long get() = takenAt ?: tiles.orEmpty().minOfOrNull { it.time } ?: 0

        fun withLayout(layout: TileLayout?) =
            copy(size = layout?.size?.fileName, focus = layout?.focus?.let { Focus(it.x, it.y) }, zoom = layout?.zoom)

        companion object {
            fun media(member: StoryMember) =
                Tile(type = "media", deviceId = member.deviceId, fileName = member.fileName, takenAt = member.takenAtMillis)
        }
    }

    /** 0..1 from the left and from the top of the photo. */
    @Serializable
    data class Focus(val x: Float, val y: Float)

    fun toStory(text: StoryText?): Story {
        val members = mutableListOf<StoryMember>()
        val groups = mutableListOf<StoryGroup>()

        /** Adds the group and its subgroups (after it); returns all their photos. */
        fun readGroup(tile: Tile, parentId: String?): List<StoryMember> {
            val groupId = tile.id ?: return emptyList()
            val own = tile.tiles.orEmpty().filter { it.type == "media" }.mapNotNull { it.toMember() }
            members += own
            val slot = groups.size
            groups += StoryGroup(groupId, tile.title, "", own.map { it.deviceId }, text?.sections?.get(groupId).orEmpty(), parentId)
            val all = own + tile.tiles.orEmpty().filter { it.type == "group" }.flatMap { readGroup(it, groupId) }
            val cover = tile.cover?.takeIf { cover -> all.any { it.deviceId == cover } } ?: all.minByOrNull { it.takenAtMillis }?.deviceId
            groups[slot] = groups[slot].copy(coverId = cover.orEmpty())
            return all
        }

        tiles.forEach { tile ->
            when (tile.type) {
                "media" -> tile.toMember()?.let(members::add)
                "group" -> readGroup(tile, null)
            }
        }
        // A group without photos (a damaged file) would have nothing to show.
        groups.removeAll { group -> group.coverId.isEmpty() }
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
            layout = tiles.mapNotNull { tile -> tile.key?.let { it to tile.layout } }.filter { !it.second.isEmpty }.toMap(),
            tileOrder = tiles.mapNotNull { it.key },
        )
    }

    companion object {
        /**
         * Loose members are media tiles; a group's tile sits where its first photo would be, unless the owner
         * arranged the tiles. Inside a group, its photos and subgroups are in time order.
         */
        fun of(story: Story): StoryFile {
            val byId = story.members.associateBy { it.deviceId }
            val childrenOf = story.groups.groupBy { it.parentId }
            val visited = mutableSetOf<String>()
            fun groupTile(group: StoryGroup): Tile? {
                if (!visited.add(group.id)) return null
                val inner = group.memberIds.mapNotNull { byId[it] }.map(Tile::media) + childrenOf[group.id].orEmpty().mapNotNull(::groupTile)
                return Tile(type = "group", id = group.id, title = group.title, cover = group.coverId, tiles = inner.sortedBy { it.time })
            }
            val grouped = story.groups.flatMap { it.memberIds }.toSet()
            val roots = story.groups.inTreeOrder().filter { group -> story.groups.none { it.id == group.parentId } }
            val byTime = story.members.filter { it.deviceId !in grouped }.map(Tile::media) + roots.mapNotNull(::groupTile)
            val tiles = arrangeTiles(byTime, story.tileOrder, key = { it.key.orEmpty() }, time = { it.time })
                .map { tile -> tile.withLayout(story.layout[tile.key]) }
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
