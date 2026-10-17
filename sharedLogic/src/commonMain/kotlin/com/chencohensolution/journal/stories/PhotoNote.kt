package com.chencohensolution.journal.stories

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * What the user wrote about one photo or video, wherever it appears: a caption and tags. Like story members, it is
 * identified by device id until uploads exist; [fileName] and [takenAtMillis] help match it to its server copy.
 */
data class PhotoNote(
    val deviceId: String,
    val fileName: String?,
    val takenAtMillis: Long,
    val caption: String = "",
    /** As the user typed them; compared ignoring case. */
    val tags: List<String> = emptyList(),
    /** RFC 3339. */
    val updatedAt: String,
) {
    val isEmpty: Boolean get() = caption.isBlank() && tags.isEmpty()
}

/** Where photo notes are kept until they can be synced to the server. */
internal interface PhotoNoteStore {
    fun loadAll(): List<PhotoNote>
    fun save(note: PhotoNote)
    fun delete(deviceId: String)
}

/**
 * One small JSON file per photo, `<root>/media/<device id>.json`, like the server's sidecar files: the photo itself
 * is never modified. A note with no caption and no tags is deleted.
 */
internal class FilePhotoNoteStore(private val fileSystem: FileSystem, root: Path) : PhotoNoteStore {
    private val dir = root / "media"

    override fun loadAll(): List<PhotoNote> {
        if (!fileSystem.exists(dir)) return emptyList()
        return fileSystem.list(dir).filter { it.name.endsWith(".json") }.mapNotNull { path ->
            try {
                fileSystem.read(path) { json.decodeFromString<NoteFile>(readUtf8()) }.toNote()
            } catch (e: Exception) {
                // A damaged file must not hide every other note; it stays on disk untouched.
                null
            }
        }
    }

    override fun save(note: PhotoNote) {
        fileSystem.createDirectories(dir)
        val path = pathOf(note.deviceId)
        val temp = dir / "${path.name}.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(NoteFile.of(note))) }
        fileSystem.atomicMove(temp, path)
    }

    override fun delete(deviceId: String) {
        try {
            fileSystem.delete(pathOf(deviceId))
        } catch (e: IOException) {
            // Already gone.
        }
    }

    /** iOS ids contain "/", so anything but letters, digits, "-", "_" and "." becomes "_". */
    private fun pathOf(deviceId: String) = dir / "${deviceId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json"

    private companion object {
        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/** media/<id>.json, format version 1. */
@Serializable
private data class NoteFile(
    val v: Int = 1,
    val deviceId: String,
    val fileName: String? = null,
    val takenAt: Long = 0,
    val caption: String = "",
    val tags: List<String> = emptyList(),
    val updatedAt: String = "",
) {
    fun toNote() = PhotoNote(deviceId, fileName, takenAt, caption, tags, updatedAt)

    companion object {
        fun of(note: PhotoNote) = NoteFile(
            deviceId = note.deviceId,
            fileName = note.fileName,
            takenAt = note.takenAtMillis,
            caption = note.caption,
            tags = note.tags,
            updatedAt = note.updatedAt,
        )
    }
}
