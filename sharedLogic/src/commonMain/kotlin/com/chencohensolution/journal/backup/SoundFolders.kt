package com.chencohensolution.journal.backup

import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import okio.Source

/**
 * The platform's access to the sound folders the user picked ([SoundFolder]). Their files never enter the photo
 * library: only backup and Free up space see them.
 */
internal interface SoundFolders {
    /**
     * The sound files in [folder] and its subfolders, as [MediaItem]s of type [MediaType.AUDIO] whose ids are
     * [SoundIds.of]; null when the app can no longer read the folder (the user took the access back, or it is gone).
     */
    suspend fun list(folder: SoundFolder): List<MediaItem>?

    /** What a folder holds, for the add dialog: how many files, their bytes, and what the names suggest. */
    suspend fun look(folder: SoundFolder): FolderLook? = list(folder)?.let { FolderLook.of(it) }
}

/** What was found in a folder the user just picked. */
data class FolderLook(val files: Int, val bytes: Long, val wavs: Int, val guess: AudioKind) {
    companion object {
        fun of(items: List<MediaItem>) = FolderLook(
            files = items.size,
            bytes = items.sumOf { it.sizeBytes ?: 0 },
            wavs = items.count { it.isWav },
            guess = guessKind(items.mapNotNull { it.fileName }),
        )
    }
}

/**
 * What the files' names suggest a folder holds: calls when most are named like Samsung's call recorder ("Call recording
 * …", "הקלטת שיחה …", "שיחה …"), voice messages when most are WhatsApp's (PTT-…, AUD-…), else recordings.
 */
fun guessKind(names: List<String>): AudioKind {
    if (names.isEmpty()) return AudioKind.RECORDING
    val calls = names.count { name -> CALL_PREFIXES.any { name.startsWith(it) } }
    val voice = names.count { it.startsWith("PTT-") || it.startsWith("AUD-") }
    return when {
        calls * 2 > names.size -> AudioKind.CALL
        voice * 2 > names.size -> AudioKind.VOICE_MESSAGE
        else -> AudioKind.RECORDING
    }
}

private val CALL_PREFIXES = listOf("Call recording ", "הקלטת שיחה ", "שיחה ")

/** Ids of files in sound folders: the folder's tree URI and the file's document, so each file knows its folder. */
internal object SoundIds {
    private const val PREFIX = "sound:"

    // Never in a URI: Android escapes it as %7C.
    private const val SEPARATOR = '|'

    fun of(folder: SoundFolder, document: String) = "$PREFIX${folder.treeUri}$SEPARATOR$document"

    fun isSound(id: String) = id.startsWith(PREFIX)

    /** The folder's tree URI, or null for an id not of a sound folder. */
    fun treeOf(id: String): String? = id.takeIf(::isSound)?.removePrefix(PREFIX)?.substringBefore(SEPARATOR)

    /** The file's document, or null for an id not of a sound folder. */
    fun documentOf(id: String): String? = id.takeIf(::isSound)?.substringAfter(SEPARATOR)
}

/** A file still being written (a call going on, a recording not stopped) is left for a later run. */
internal const val STILL_WRITING_MILLIS = 2L * 60 * 1000

/** The files of [items] that are finished at [nowMillis]: not changed in the last [STILL_WRITING_MILLIS]. */
internal fun finished(items: List<MediaItem>, nowMillis: Long): List<MediaItem> =
    items.filter { (it.modifiedMillis ?: 0) <= nowMillis - STILL_WRITING_MILLIS }

/** A WAV recording: sent as FLAC, never as it is (unless the phone can't encode it). */
internal val MediaItem.isWav: Boolean
    get() = type == MediaType.AUDIO &&
        (mimeType?.lowercase() in WAV_TYPES || fileName?.substringAfterLast('.', "")?.equals("wav", ignoreCase = true) == true)

private val WAV_TYPES = setOf("audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave")

/** The MIME type of a sound file by its extension, for files the platform doesn't name. */
internal fun audioMimeType(fileName: String?): String = when (fileName?.substringAfterLast('.', "")?.lowercase()) {
    "m4a", "mp4", "3gp" -> "audio/mp4"
    "aac" -> "audio/aac"
    "flac" -> "audio/flac"
    "ogg", "opus" -> "audio/ogg"
    "wav" -> "audio/wav"
    "mp3" -> "audio/mpeg"
    "amr" -> "audio/amr"
    else -> "application/octet-stream"
}

/** A WAV's FLAC, made on the phone to be sent in its place. */
internal class EncodedFlac(
    val fileName: String,
    val size: Long,
    val sha256: String,
    /** Its bytes from an offset. */
    val open: (offset: Long) -> Source,
    /** Deletes the FLAC made to send it (the phone keeps the WAV, or the FLAC was written into the folder). */
    val discard: () -> Unit,
)
