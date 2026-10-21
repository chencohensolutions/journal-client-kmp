package com.chencohensolution.journal.stories

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

/**
 * What the user wrote about one photo or video, wherever it appears: a title and a paragraph ([caption]) with their
 * earlier versions ([history]), tags, and how it is cropped for
 * looking at ([crops]; the photo itself is never changed). Like story members, it is
 * identified by device id until uploads exist; [fileName] and [takenAtMillis] help match it to its server copy.
 */
data class PhotoNote(
    val deviceId: String,
    val fileName: String?,
    val takenAtMillis: Long,
    /** The paragraph: shown under the photo in Moments (the title is above it). */
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
    /** The photo its set in Moments opens on: the one it was last turned to (never its first, where it opens anyway). */
    val front: Boolean = false,
    /** Shown above the photo in Moments. */
    val title: String = "",
    /** When [title] and [caption] were saved (RFC 3339); null for a note written before titles existed. */
    val writtenAt: String? = null,
    /** Earlier versions of the title and paragraph, oldest first. */
    val history: List<NoteVersion> = emptyList(),
    /** False once "Remove from storyline" was chosen: the writing stays, and shows in Moments, but not in the storyline. */
    val inStoryline: Boolean = true,
) {
    val isEmpty: Boolean
        get() = caption.isBlank() && title.isBlank() && history.isEmpty() && tags.isEmpty() && crops.isEmpty &&
            feedSet == null && !apart && !front

    val hasWriting: Boolean get() = title.isNotBlank() || caption.isNotBlank()

    /** Whether the photo is in the storyline: it has writing, and wasn't removed from it. */
    val onStoryline: Boolean get() = hasWriting && inStoryline

    /**
     * The note with [title] and [text] saved at [now]. What was written before becomes a version in [history], unless
     * it was saved less than [VERSION_GAP] ago (a quick fix is part of the same version) or [keepPrevious] is false
     * and it was. A restored version always keeps what it replaces ([keepPrevious] true).
     */
    fun withWriting(title: String, text: String, now: String, keepPrevious: Boolean = false): PhotoNote {
        val newTitle = title.trim()
        val newText = text.trim()
        if (newTitle == this.title && newText == caption) return this
        val previousAt = writtenAt ?: updatedAt
        val recent = writtenAt?.let { at -> runCatching { Instant.parse(now) - Instant.parse(at) < VERSION_GAP }.getOrNull() } == true
        val kept = if (hasWriting && (keepPrevious || !recent)) history + NoteVersion(this.title, caption, previousAt) else history
        return copy(title = newTitle, caption = newText, writtenAt = now, history = kept)
    }

    companion object {
        /** Saves closer together than this are one version. */
        val VERSION_GAP = 10.minutes
    }
}

/** An earlier title and paragraph of a note, and when they were saved (RFC 3339). */
data class NoteVersion(val title: String, val text: String, val savedAt: String)

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
        val NOTE_KEYS = setOf(
            "v", "deviceId", "fileName", "takenAt", "title", "caption", "writtenAt", "history", "storyline", "tags", "crops", "set",
            "apart", "front", "updatedAt",
        )
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

/**
 * media/<id>.json, format version 3 (version 1 had no crops, sets, apart or front; version 2 no title, writtenAt,
 * history or storyline). "caption" is the paragraph; "storyline": false once removed from the storyline.
 */
@Serializable
private data class NoteFile(
    val v: Int = 3,
    val deviceId: String,
    val fileName: String? = null,
    val takenAt: Long = 0,
    val title: String? = null,
    val caption: String = "",
    val writtenAt: String? = null,
    val history: List<VersionFile>? = null,
    val storyline: Boolean? = null,
    val tags: List<String> = emptyList(),
    val crops: CropsFile? = null,
    /** The set merged by hand in Moments; "apart": taken out of one. */
    val set: String? = null,
    val apart: Boolean? = null,
    /** The photo its set opens on. */
    val front: Boolean? = null,
    val updatedAt: String = "",
) {
    fun toNote() = PhotoNote(
        deviceId, fileName, takenAt, caption, tags, updatedAt, crops?.toCrops() ?: PhotoCrops(), set, apart == true, front == true,
        title = title.orEmpty(),
        writtenAt = writtenAt,
        history = history.orEmpty().map { NoteVersion(it.title.orEmpty(), it.text.orEmpty(), it.savedAt) },
        inStoryline = storyline != false,
    )

    companion object {
        fun of(note: PhotoNote) = NoteFile(
            deviceId = note.deviceId,
            fileName = note.fileName,
            takenAt = note.takenAtMillis,
            title = note.title.ifEmpty { null },
            caption = note.caption,
            writtenAt = note.writtenAt,
            history = note.history.takeIf { it.isNotEmpty() }?.map { VersionFile(it.title.ifEmpty { null }, it.text.ifEmpty { null }, it.savedAt) },
            storyline = false.takeIf { !note.inStoryline },
            tags = note.tags,
            crops = note.crops.takeIf { !it.isEmpty }?.let(CropsFile::of),
            set = note.feedSet,
            apart = note.apart.takeIf { it },
            front = note.front.takeIf { it },
            updatedAt = note.updatedAt,
        )
    }
}

/** One of "history": an earlier title and paragraph and when they were saved. */
@Serializable
private data class VersionFile(val title: String? = null, val text: String? = null, val savedAt: String)

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
