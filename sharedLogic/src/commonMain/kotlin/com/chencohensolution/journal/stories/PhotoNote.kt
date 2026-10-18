package com.chencohensolution.journal.stories

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * What the user wrote about one photo or video, wherever it appears: a caption and tags, and how it is cropped for
 * looking at ([crops]; the photo itself is never changed). Like story members, it is
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
    val crops: PhotoCrops = PhotoCrops(),
    /** Merged by hand in Moments with the other photos of this set id: shown as one set you swipe. */
    val feedSet: String? = null,
    /** Taken out of a set in Moments: never put in one by itself again. */
    val apart: Boolean = false,
) {
    val isEmpty: Boolean get() = caption.isBlank() && tags.isEmpty() && crops.isEmpty && feedSet == null && !apart
}

/** Where photo notes are kept until they can be synced to the server. */
internal interface PhotoNoteStore {
    fun loadAll(): List<PhotoNote>
    fun save(note: PhotoNote)
    fun delete(deviceId: String)
}

/**
 * One small JSON file per photo, `<root>/media/<device id>.json`, like the server's sidecar files: the photo itself
 * is never modified. A note with no caption, tags or crops is deleted.
 *
 * Saving keeps what is in the file but not in [NoteFile] (a newer version's fields), so an older app on another
 * device or a sync never drops them.
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
        val previous = try {
            if (fileSystem.exists(path)) fileSystem.read(path) { json.parseToJsonElement(readUtf8()) as? JsonObject } else null
        } catch (e: Exception) {
            null
        }
        val temp = dir / "${path.name}.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(JsonObject.serializer(), merged(previous, NoteFile.of(note)))) }
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
        /** The fields this version writes; everything else in a file is kept as it was. */
        val NOTE_KEYS = setOf("v", "deviceId", "fileName", "takenAt", "caption", "tags", "crops", "set", "apart", "updatedAt")
        val CROP_KEYS = setOf("moments", "portrait", "landscape")

        /** [file] over [previous], keeping [previous]'s unknown fields, also inside "crops". */
        fun merged(previous: JsonObject?, file: NoteFile): JsonObject {
            val written = json.encodeToJsonElement(NoteFile.serializer(), file).jsonObject
            val kept = previous.orEmpty().filterKeys { it !in NOTE_KEYS }
            val unknownCrops = (previous?.get("crops") as? JsonObject).orEmpty().filterKeys { it !in CROP_KEYS }
            val crops = (written["crops"] as? JsonObject).orEmpty() + unknownCrops
            return JsonObject(kept + written.filterKeys { it != "crops" } + (if (crops.isEmpty()) emptyMap() else mapOf("crops" to JsonObject(crops))))
        }

        val json = Json {
            prettyPrint = true
            ignoreUnknownKeys = true
            encodeDefaults = true
            explicitNulls = false
        }
    }
}

/** media/<id>.json, format version 2 (version 1 had no crops, sets or apart). */
@Serializable
private data class NoteFile(
    val v: Int = 2,
    val deviceId: String,
    val fileName: String? = null,
    val takenAt: Long = 0,
    val caption: String = "",
    val tags: List<String> = emptyList(),
    val crops: CropsFile? = null,
    /** The set merged by hand in Moments; "apart": taken out of one. */
    val set: String? = null,
    val apart: Boolean? = null,
    val updatedAt: String = "",
) {
    fun toNote() = PhotoNote(deviceId, fileName, takenAt, caption, tags, updatedAt, crops?.toCrops() ?: PhotoCrops(), set, apart == true)

    companion object {
        fun of(note: PhotoNote) = NoteFile(
            deviceId = note.deviceId,
            fileName = note.fileName,
            takenAt = note.takenAtMillis,
            caption = note.caption,
            tags = note.tags,
            crops = note.crops.takeIf { !it.isEmpty }?.let(CropsFile::of),
            set = note.feedSet,
            apart = note.apart.takeIf { it },
            updatedAt = note.updatedAt,
        )
    }
}

/** "crops": {"moments": {...}, "portrait": {...}, "landscape": {...}}, each missing when not set. */
@Serializable
private data class CropsFile(val moments: RectFile? = null, val portrait: RectFile? = null, val landscape: RectFile? = null) {
    fun toCrops() = PhotoCrops(moments?.toRect(), portrait?.toRect(), landscape?.toRect())

    companion object {
        fun of(crops: PhotoCrops) = CropsFile(crops.moments?.let(RectFile::of), crops.portrait?.let(RectFile::of), crops.landscape?.let(RectFile::of))
    }
}

/** Fractions of the upright photo; "aspect" is the screen shape a viewer crop was made for. */
@Serializable
private data class RectFile(val x: Float, val y: Float, val w: Float, val h: Float, val aspect: Float? = null) {
    fun toRect() = CropRect(x, y, w, h, aspect)

    companion object {
        fun of(rect: CropRect) = RectFile(rect.x, rect.y, rect.w, rect.h, rect.aspect)
    }
}
