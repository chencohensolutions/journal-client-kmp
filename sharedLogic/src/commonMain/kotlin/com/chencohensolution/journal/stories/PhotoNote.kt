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
    /**
     * Its place among its set's highlights (1, 2, 3…), what Moments shows of the set; the first is the set's cover, where
     * it opens. Null: not a highlight.
     */
    val highlight: Int? = null,
    /** Shown above the photo in Moments. */
    val title: String = "",
    /** When [title] and [caption] were saved (RFC 3339); null for a note written before titles existed. */
    val writtenAt: String? = null,
    /** Earlier versions of the title and paragraph, oldest first. */
    val history: List<NoteVersion> = emptyList(),
    /** False once "Remove from storyline" was chosen: the writing stays, and shows in Moments, but not in the storyline. */
    val inStoryline: Boolean = true,
    /**
     * Where the viewer was zoomed in on the photo when it was last left: the part on screen, as fractions of the upright
     * photo, with the screen's shape as its aspect. It opens there next time. Null: fitted.
     */
    val zoom: CropRect? = null,
    /** A moment made by hand ("Make a moment"): its name and which days show it; on its cover, or on any of its photos. */
    val setInfo: SetInfo? = null,
) {
    val isEmpty: Boolean
        get() = caption.isBlank() && title.isBlank() && history.isEmpty() && tags.isEmpty() && crops.isEmpty &&
            feedSet == null && !apart && highlight == null && zoom == null && setInfo == null

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

/** A set made into a moment by hand ([StoriesController.makeMoment]). */
data class SetInfo(val name: String, val showOn: ShowOn = ShowOn.FIRST, val description: String = "") {
    /** The name, or null for a moment made without one (saved as ""). */
    val shownName: String? get() = name.takeIf { it.isNotBlank() }

    /** A few words about the moment (Nov 10, 2026), or null without them. */
    val shownDescription: String? get() = description.takeIf { it.isNotBlank() }
}

/** Which days of a named set show it in Moments. */
enum class ShowOn(val id: String) {
    /** Where its oldest photo is. */
    FIRST("first"),

    /** Where its newest photo is. */
    LAST("last"),

    /** Every day it has: each day its own photos, with a link to the whole set. */
    EACH("each");

    companion object {
        fun of(id: String?): ShowOn = entries.firstOrNull { it.id == id } ?: FIRST
    }
}

/** An earlier title and paragraph of a note, and when they were saved (RFC 3339). */
data class NoteVersion(val title: String, val text: String, val savedAt: String)

/** Where photo notes are kept on this device; [NoteSync] sends them to the server and brings the server's in. */
internal interface PhotoNoteStore {
    fun loadAll(): List<PhotoNote>
    fun save(note: PhotoNote)
    fun delete(deviceId: String)

    /**
     * The note of [deviceId] as the server keeps it: its file's JSON object, without what only this device knows (the
     * photo's id, name and time), with the fields a newer version wrote; null when it has no note.
     */
    fun syncContent(deviceId: String): String?

    /**
     * The server's note [content] (as [syncContent] makes it) as the note of [deviceId], replacing its file, with
     * [fileName] and [takenAtMillis]; null, and nothing written, when it isn't a note this version can read.
     */
    fun saveFromServer(deviceId: String, fileName: String?, takenAtMillis: Long, content: String): PhotoNote?

    /** The server's note [content] as a note, without writing it; null when it can't be read. */
    fun parse(deviceId: String, fileName: String?, takenAtMillis: Long, content: String): PhotoNote?
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

    override fun syncContent(deviceId: String): String? {
        val path = pathOf(deviceId)
        val file = try {
            if (fileSystem.exists(path)) fileSystem.read(path) { json.parseToJsonElement(readUtf8()) as? JsonObject } else null
        } catch (e: Exception) {
            null
        } ?: return null
        return syncJson.encodeToString(JsonObject.serializer(), JsonObject(file.filterKeys { it !in DEVICE_KEYS }))
    }

    override fun saveFromServer(deviceId: String, fileName: String?, takenAtMillis: Long, content: String): PhotoNote? {
        val (file, note) = read(deviceId, fileName, takenAtMillis, content) ?: return null
        fileSystem.createDirectories(dir)
        val path = pathOf(deviceId)
        val temp = dir / "${path.name}.tmp"
        fileSystem.write(temp) { writeUtf8(json.encodeToString(JsonObject.serializer(), file)) }
        fileSystem.atomicMove(temp, path)
        return note
    }

    override fun parse(deviceId: String, fileName: String?, takenAtMillis: Long, content: String): PhotoNote? =
        read(deviceId, fileName, takenAtMillis, content)?.second

    /** The file the server's [content] makes for [deviceId], and its note. */
    private fun read(deviceId: String, fileName: String?, takenAtMillis: Long, content: String): Pair<JsonObject, PhotoNote>? = try {
        val server = json.parseToJsonElement(content) as? JsonObject ?: return null
        val device = json.encodeToJsonElement(NoteFile.serializer(), NoteFile(deviceId = deviceId, fileName = fileName, takenAt = takenAtMillis))
            .jsonObject.filterKeys { it in DEVICE_KEYS }
        val file = JsonObject(server.filterKeys { it !in DEVICE_KEYS } + device)
        file to json.decodeFromJsonElement(NoteFile.serializer(), file).toNote()
    } catch (e: Exception) {
        null
    }

    /** iOS ids contain "/", so anything but letters, digits, "-", "_" and "." becomes "_". */
    private fun pathOf(deviceId: String) = dir / "${deviceId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json"

    private companion object {
        /** The fields this version writes; everything else in a file is kept as it was. */
        val NOTE_KEYS = setOf(
            "v", "deviceId", "fileName", "takenAt", "title", "caption", "writtenAt", "history", "storyline", "tags", "crops", "set",
            "apart", "front", "highlight", "setInfo", "zoom", "updatedAt",
        )
        val CROP_KEYS = setOf("moments", "portrait", "landscape")

        /** What only this device knows of a note: never sent to the server, and the device's own kept from its notes. */
        val DEVICE_KEYS = setOf("deviceId", "fileName", "takenAt")

        /** The server keeps notes compacted. */
        val syncJson = Json { prettyPrint = false }

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
 * media/<id>.json, format version 5 (version 1 had no crops, sets, apart or front; version 2 no title, writtenAt,
 * history or storyline; version 3 no zoom; version 4 had "front", the photo a set opens on, read as highlight 1, and no
 * "highlight" or "setInfo"). "caption" is the paragraph; "storyline": false once removed from the
 * storyline; "zoom": the part of the photo the viewer showed when last left, like a crop.
 */
@Serializable
private data class NoteFile(
    val v: Int = 5,
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
    /** Format 4: the photo its set opens on; read as highlight 1, never written. */
    val front: Boolean? = null,
    /** Its place among its set's highlights, from 1. */
    val highlight: Int? = null,
    val setInfo: SetInfoFile? = null,
    val zoom: RectFile? = null,
    val updatedAt: String = "",
) {
    fun toNote() = PhotoNote(
        deviceId, fileName, takenAt, caption, tags, updatedAt, crops?.toCrops() ?: PhotoCrops(), set, apart == true,
        highlight = highlight ?: 1.takeIf { front == true },
        title = title.orEmpty(),
        writtenAt = writtenAt,
        history = history.orEmpty().map { NoteVersion(it.title.orEmpty(), it.text.orEmpty(), it.savedAt) },
        inStoryline = storyline != false,
        zoom = zoom?.toRect(),
        setInfo = setInfo?.let { SetInfo(it.name, ShowOn.of(it.showOn), it.description.orEmpty()) },
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
            highlight = note.highlight,
            setInfo = note.setInfo?.let { SetInfoFile(it.name, it.showOn.id, it.description.ifBlank { null }) },
            zoom = note.zoom?.let(RectFile::of),
            updatedAt = note.updatedAt,
        )
    }
}

/**
 * "setInfo": {"name": "Beach weekend", "showOn": "first" | "last" | "each", "description": "..."}; "description" since
 * Nov 10, 2026, left out when empty.
 */
@Serializable
private data class SetInfoFile(val name: String, val showOn: String = "first", val description: String? = null)

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
