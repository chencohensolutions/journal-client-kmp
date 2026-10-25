@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.chencohensolution.journal.backup

import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.storage.SoundRemoval
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSDirectoryEnumerationSkipsHiddenFiles
import platform.Foundation.NSDirectoryEnumerator
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSURL
import platform.Foundation.NSURLBookmarkCreationMinimalBookmark
import platform.Foundation.NSURLContentModificationDateKey
import platform.Foundation.NSURLFileSizeKey
import platform.Foundation.NSURLIsDirectoryKey
import platform.Foundation.NSURLLocalizedNameKey
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/**
 * The sound folders the user picked with the Files folder picker (Settings › Backup › Add a folder), through the lasting
 * access it gave: each folder's bookmark is kept in [directory] (Application Support, `soundFolders/<key>`), and its
 * [SoundFolder.treeUri] is "folder:<key>". A file's id is its folder's and its path inside it ([SoundIds]). Files whose
 * names start with a dot are left out. An iPhone keeps call recordings in Notes, and Voice Memos and WhatsApp's voice
 * notes inside their apps: only recordings saved into Files can be picked.
 *
 * Read-only but for [delete] and [replace], the one place the app writes in these folders (backup of calls and
 * recordings, asked for by the owner on Oct 25, 2026): sound files the server confirmed it holds are deleted ("Free up
 * space", after the app's own dialog, and a WAV replaced by its FLAC). These folders have no trash: deleted is gone for
 * good. It never touches the photo library. See PhotoSafetyTest.
 */
class IosSoundFolders internal constructor(private val directory: String) : SoundFolders, SoundRemoval {
    private val files = NSFileManager.defaultManager

    /**
     * Keeps lasting access to [url], a folder the picker just returned, as a folder of recordings until the user says
     * what it holds; null when iOS refused it.
     */
    fun add(url: NSURL): SoundFolder? {
        val started = url.startAccessingSecurityScopedResource()
        try {
            val bookmark = url.bookmarkDataWithOptions(NSURLBookmarkCreationMinimalBookmark, includingResourceValuesForKeys = null, relativeToURL = null, error = null)
                ?: return null
            files.createDirectoryAtPath(directory, withIntermediateDirectories = true, attributes = null, error = null)
            val key = NSUUID().UUIDString
            if (!bookmark.writeToFile("$directory/$key", atomically = true)) return null
            val name = url.lastPathComponent ?: return null
            // As Files names it ("On My iPhone", not its folder's own name).
            val parentUrl = url.URLByDeletingLastPathComponent
            val parent = (parentUrl?.resourceValuesForKeys(listOf(NSURLLocalizedNameKey), error = null)?.get(NSURLLocalizedNameKey) as? String)
                ?: parentUrl?.lastPathComponent
            return SoundFolder(
                treeUri = PREFIX + key,
                name = name,
                path = if (parent.isNullOrEmpty() || parent == "/") name else "$parent › $name",
                kind = AudioKind.RECORDING,
            )
        } finally {
            if (started) url.stopAccessingSecurityScopedResource()
        }
    }

    override suspend fun list(folder: SoundFolder): List<MediaItem>? = withContext(Dispatchers.IO) {
        accessing(folder.treeUri) { root -> walk(root, folder) }
    }

    /** Copies the file [item] to [to] (a path in the app's caches), to read it there; false when it can't be read. */
    internal fun copy(item: MediaItem, to: String): Boolean {
        val tree = SoundIds.treeOf(item.id) ?: return false
        val document = SoundIds.documentOf(item.id) ?: return false
        return accessing(tree) { root ->
            files.removeItemAtPath(to, error = null)
            root.URLByAppendingPathComponent(document)?.let { files.copyItemAtURL(it, NSURL.fileURLWithPath(to), error = null) } == true
        } == true
    }

    /** Deletes [items], files of the picked folders the server confirmed it holds; the ids of those that are gone. */
    override suspend fun delete(items: List<MediaItem>): Set<String> = withContext(Dispatchers.IO) {
        items.filter { item ->
            val tree = SoundIds.treeOf(item.id) ?: return@filter false
            val document = SoundIds.documentOf(item.id) ?: return@filter false
            accessing(tree) { root -> root.URLByAppendingPathComponent(document)?.let { files.removeItemAtURL(it, error = null) } == true } == true
        }.mapTo(HashSet()) { it.id }
    }

    /**
     * Writes [flac] (a file in the app's caches) as [name] beside the WAV [item], then deletes the WAV; false when either
     * failed (a FLAC written but not complete is deleted again, and the WAV stays).
     */
    internal suspend fun replace(item: MediaItem, flac: String, name: String): Boolean = withContext(Dispatchers.IO) {
        val tree = SoundIds.treeOf(item.id) ?: return@withContext false
        val document = SoundIds.documentOf(item.id) ?: return@withContext false
        accessing(tree) { root ->
            val wav = root.URLByAppendingPathComponent(document) ?: return@accessing false
            val target = wav.URLByDeletingLastPathComponent?.URLByAppendingPathComponent(name) ?: return@accessing false
            if (files.fileExistsAtPath(target.path ?: "")) return@accessing false
            val copied = files.copyItemAtURL(NSURL.fileURLWithPath(flac), target, error = null)
            val size = { path: String? -> (path?.let { files.attributesOfItemAtPath(it, error = null) }?.get("NSFileSize") as? NSNumber)?.longLongValue }
            if (!copied || size(target.path) != size(flac)) {
                files.removeItemAtURL(target, error = null)
                return@accessing false
            }
            files.removeItemAtURL(wav, error = null)
        } == true
    }

    /** [block] on the folder [treeUri] while its access is held; null when it can't be read any more. */
    private fun <T> accessing(treeUri: String, block: (NSURL) -> T): T? {
        val key = treeUri.removePrefix(PREFIX).takeIf { treeUri.startsWith(PREFIX) } ?: return null
        val bookmark = NSData.dataWithContentsOfFile("$directory/$key") ?: return null
        val url = NSURL.URLByResolvingBookmarkData(bookmark, options = 0u, relativeToURL = null, bookmarkDataIsStale = null, error = null)
            ?: return null
        // False for a folder that needs no lasting access (one inside the app's own); reading then tells.
        val started = url.startAccessingSecurityScopedResource()
        try {
            return block(url)
        } finally {
            if (started) url.stopAccessingSecurityScopedResource()
        }
    }

    private fun walk(root: NSURL, folder: SoundFolder): List<MediaItem>? {
        val keys = listOf(NSURLIsDirectoryKey, NSURLFileSizeKey, NSURLContentModificationDateKey)
        val walker: NSDirectoryEnumerator = files.enumeratorAtURL(root, keys, NSDirectoryEnumerationSkipsHiddenFiles, errorHandler = null) ?: return null
        val rootPath = root.path?.trimEnd('/') ?: return null
        val calendar = NSCalendar.currentCalendar
        val items = mutableListOf<MediaItem>()
        while (true) {
            val url = walker.nextObject() as? NSURL ?: break
            val values = url.resourceValuesForKeys(keys, error = null) ?: continue
            if ((values[NSURLIsDirectoryKey] as? NSNumber)?.boolValue == true) {
                // Subfolders deep (a recorder's folder per day, Calls under Recordings).
                if (walker.level.toInt() >= MAX_DEPTH) walker.skipDescendants()
                continue
            }
            val name = url.lastPathComponent ?: continue
            if (name.startsWith(".") || name.substringAfterLast('.', "").lowercase() !in EXTENSIONS) continue
            val path = url.path ?: continue
            val document = path.removePrefix(rootPath).trimStart('/')
            val modified = values[NSURLContentModificationDateKey] as? NSDate ?: NSDate()
            val millis = (modified.timeIntervalSince1970 * 1000).toLong()
            val parts = calendar.components(NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay, modified)
            items += MediaItem(
                id = SoundIds.of(folder, document),
                type = MediaType.AUDIO,
                takenAtMillis = millis,
                day = LocalDay(parts.year.toInt(), parts.month.toInt(), parts.day.toInt()),
                durationMillis = null,
                width = 0,
                height = 0,
                sizeBytes = (values[NSURLFileSizeKey] as? NSNumber)?.longLongValue,
                fileName = name,
                source = folder.source,
                modifiedMillis = millis,
                mimeType = audioMimeType(name).takeIf { it.startsWith("audio/") },
            )
        }
        return items
    }

    private companion object {
        const val PREFIX = "folder:"
        const val MAX_DEPTH = 4
        val EXTENSIONS = setOf("m4a", "aac", "flac", "ogg", "opus", "wav", "mp3", "amr", "3gp", "caf")
    }
}
