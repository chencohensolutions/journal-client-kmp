package com.chencohensolution.journal.stories

import com.chencohensolution.journal.server.AppDataAnswer
import com.chencohensolution.journal.server.AppDataContent
import com.chencohensolution.journal.server.ServerLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import okio.FileSystem
import okio.Path

/*
 * Tucked-away tags (Nov 19, 2026): the list of tags the user tucked away, kept on the phone as journal/tags.json and on
 * the server as the app's data called "tags" ({"v": 1, "tucked": ["Dog"], "updatedAt": "…"}), so every phone tucks the
 * same tags. See docs/screens/tags.md "Tucked-away tags".
 */

/** Where [TuckedTags] reads and writes the server's app data: whether it has app data ([enabled]), when to ask again ([changed]). */
class TagsSyncSource internal constructor(
    internal val enabled: Flow<Boolean>,
    internal val changed: Flow<Any?>,
    /** appData("tags"); null when the server can't be reached. */
    internal val read: suspend () -> AppDataContent?,
    /** setAppData("tags", content, baseVersion); null when the server can't be reached. */
    internal val write: suspend (content: String?, baseVersion: String?) -> AppDataAnswer?,
) {
    companion object {
        /** The app data's name on the server. */
        const val NAME = "tags"

        /** The server of [server]: once it reports app data (serverInfo.appNotes). */
        fun of(server: ServerLibrary): TagsSyncSource = TagsSyncSource(
            enabled = server.features.map { it?.appData == true },
            changed = server.mediaVersion,
            read = { server.appData(NAME) },
            write = { content, base -> server.setAppData(NAME, content, base) },
        )
    }
}

/**
 * The tucked-away tags, kept in [dir]/tags.json and synced with [source] on [scope]. Without [source] (tests, no server)
 * they stay on this phone. A damaged file is never fatal: the list starts empty and the file is replaced on the next
 * change.
 *
 * The file: `{"v": 1, "content": {what the server keeps}, "synced": {"version": "…", "tucked": [...]}, "pending": true}`,
 * where "synced" is what the server had when last in step (the base of a merge) and "pending" a change still to send.
 */
@OptIn(FlowPreview::class)
class TuckedTags internal constructor(
    private val fileSystem: FileSystem?,
    private val dir: Path?,
    private val source: TagsSyncSource? = null,
    private val now: () -> String = { "" },
) {
    private var file: TagsFile = load()

    private val _tucked = MutableStateFlow(file.tucked)

    /** The tags tucked away, as saved (compared ignoring case). */
    val tucked: StateFlow<List<String>> = _tucked.asStateFlow()

    private val localChanges = MutableStateFlow(0)
    private val lock = Mutex()

    /** Whether [tag] is tucked away (ignoring case). */
    fun isTucked(tag: String): Boolean = file.tucked.any { it.equals(tag, ignoreCase = true) }

    /** Tucks [tag] away ([on]) or brings it back, saved at once and sent when the server can be reached. */
    fun set(tag: String, on: Boolean) {
        val name = tag.trim()
        if (name.isEmpty() || isTucked(name) == on) return
        val list = if (on) file.tucked + name else file.tucked.filterNot { it.equals(name, ignoreCase = true) }
        change(file.withTucked(list, now()).copy(pending = true))
        localChanges.value++
    }

    /** Syncs on [scope]: at start, after a change here (a second later) and whenever [TagsSyncSource.changed] says. */
    internal fun start(scope: CoroutineScope) {
        val sync = source ?: return
        scope.launch {
            combine(sync.enabled, sync.changed, localChanges.debounce(1_000)) { enabled, _, _ -> enabled }
                .collect { enabled -> if (enabled) syncNow() }
        }
    }

    /** Sends what changed here, or brings in what changed on the server; nothing when it can't be reached. */
    internal suspend fun syncNow() = lock.withLock {
        val sync = source ?: return@withLock
        try {
            repeat(MAX_TRIES) {
                if (file.pending) {
                    val sent = file
                    val answer = sync.write(sent.contentText(), sent.syncedVersion) ?: return@withLock
                    if (answer.saved) {
                        // A change made while this was sent stays to send, on top of what the server now has.
                        val synced = Synced(answer.data.version, sent.tucked)
                        change(file.copy(synced = synced, pending = file !== sent && file.tucked != sent.tucked))
                        if (!file.pending) return@withLock
                    } else {
                        val server = TagsContent.parse(answer.data.content)
                        val merged = mergeTucked(base = file.synced?.tucked.orEmpty(), local = file.tucked, server = server.tucked)
                        change(
                            TagsFile(server.withTucked(merged, now()), Synced(answer.data.version, server.tucked), pending = true),
                        )
                    }
                } else {
                    val there = sync.read() ?: return@withLock
                    if (there.version == file.syncedVersion) return@withLock
                    val server = TagsContent.parse(there.content)
                    change(TagsFile(server, Synced(there.version, server.tucked), pending = false))
                    return@withLock
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or refused: tried again on the next change or sync.
        }
    }

    private fun change(next: TagsFile) {
        file = next
        save(next)
        _tucked.value = next.tucked
    }

    private fun load(): TagsFile {
        val fs = fileSystem ?: return TagsFile()
        val path = dir?.let { it / FILE } ?: return TagsFile()
        return try {
            if (!fs.exists(path)) TagsFile() else TagsFile.parse(fs.read(path) { readUtf8() })
        } catch (e: Exception) {
            // A damaged file is never fatal.
            TagsFile()
        }
    }

    private fun save(next: TagsFile) {
        val fs = fileSystem ?: return
        val folder = dir ?: return
        try {
            fs.createDirectories(folder)
            val temp = folder / "$FILE.tmp"
            fs.write(temp) { writeUtf8(next.fileText()) }
            fs.atomicMove(temp, folder / FILE)
        } catch (e: Exception) {
            // Kept in memory; written with the next change.
        }
    }

    internal companion object {
        const val FILE = "tags.json"
        private const val MAX_TRIES = 4

        /** On this phone only, nothing saved: tests and previews. */
        fun inMemory(): TuckedTags = TuckedTags(null, null)
    }
}

/**
 * A conflict's merge: this phone's changes since the last sync ([base] to [local]) laid over the server's list: tags
 * tucked here are added, tags brought back here are removed, everything else is as the server has it (ignoring case).
 */
internal fun mergeTucked(base: List<String>, local: List<String>, server: List<String>): List<String> {
    fun List<String>.has(tag: String) = any { it.equals(tag, ignoreCase = true) }
    val added = local.filterNot { base.has(it) }
    val removed = base.filterNot { local.has(it) }
    val kept = server.filterNot { removed.has(it) }
    return kept + added.filterNot { kept.has(it) }
}

/** What the server was at when last in step: its version and list. */
internal data class Synced(val version: String?, val tucked: List<String>)

/** The server's object, unknown fields kept. */
internal class TagsContent(val fields: JsonObject) {
    val tucked: List<String> get() = (fields["tucked"] as? JsonArray).orEmpty()
        .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.ifEmpty { null } }
        .distinctBy { it.lowercase() }

    fun withTucked(list: List<String>, updatedAt: String) = TagsContent(
        JsonObject(fields + mapOf("v" to JsonPrimitive(1), "tucked" to JsonArray(list.map(::JsonPrimitive)), "updatedAt" to JsonPrimitive(updatedAt))),
    )

    companion object {
        fun parse(text: String?): TagsContent = TagsContent(
            text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() } ?: JsonObject(emptyMap()),
        )
    }
}

internal data class TagsFile(
    val content: TagsContent = TagsContent(JsonObject(emptyMap())),
    val synced: Synced? = null,
    val pending: Boolean = false,
) {
    val tucked: List<String> get() = content.tucked
    val syncedVersion: String? get() = synced?.version

    fun withTucked(list: List<String>, updatedAt: String) = copy(content = content.withTucked(list, updatedAt))

    fun contentText(): String = compact.encodeToString(JsonObject.serializer(), content.fields)

    fun fileText(): String {
        val synced: JsonElement = synced?.let {
            JsonObject(mapOf("version" to (it.version?.let(::JsonPrimitive) ?: JsonNull), "tucked" to JsonArray(it.tucked.map(::JsonPrimitive))))
        } ?: JsonNull
        return json.encodeToString(
            JsonObject.serializer(),
            JsonObject(mapOf("v" to JsonPrimitive(1), "content" to content.fields, "synced" to synced, "pending" to JsonPrimitive(pending))),
        )
    }

    companion object {
        fun parse(text: String): TagsFile {
            val obj = json.parseToJsonElement(text) as? JsonObject ?: return TagsFile()
            val content = TagsContent(obj["content"] as? JsonObject ?: JsonObject(emptyMap()))
            val synced = (obj["synced"] as? JsonObject)?.let { s ->
                Synced(
                    (s["version"] as? JsonPrimitive)?.contentOrNull,
                    (s["tucked"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull },
                )
            }
            val pending = (obj["pending"] as? JsonPrimitive)?.jsonPrimitive?.contentOrNull == "true"
            return TagsFile(content, synced, pending)
        }
    }
}

private val json = Json { prettyPrint = true }
private val compact = Json { prettyPrint = false }
