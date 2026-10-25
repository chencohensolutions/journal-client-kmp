package com.chencohensolution.journal.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.time.Instant
import java.time.ZoneId

/**
 * Lists the sound folders the user picked with Android's folder picker, through the lasting access it gave
 * (`takePersistableUriPermission`). Read-only: nothing here writes (see [AndroidSoundRemoval] for the one place that
 * does). Files whose names start with a dot (`.nomedia`, a recorder's `.pending` file) are left out.
 */
internal class AndroidSoundFolders(context: Context) : SoundFolders {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun list(folder: SoundFolder): List<MediaItem>? = withContext(Dispatchers.IO) {
        val tree = Uri.parse(folder.treeUri)
        if (resolver.persistedUriPermissions.none { it.uri == tree && it.isReadPermission }) return@withContext null
        val items = mutableListOf<MediaItem>()
        try {
            walk(tree, DocumentsContract.getTreeDocumentId(tree), folder, items, depth = 0)
        } catch (_: SecurityException) {
            return@withContext null
        } catch (_: FileNotFoundException) {
            return@withContext null
        } catch (_: IllegalArgumentException) {
            return@withContext null
        }
        items
    }

    private fun walk(tree: Uri, parent: String, folder: SoundFolder, into: MutableList<MediaItem>, depth: Int) {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parent)
        val columns = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE, Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
        val cursor = resolver.query(children, columns, null, null, null) ?: throw FileNotFoundException("Can't list ${folder.path}")
        val folders = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                val id = it.getString(0) ?: continue
                val name = it.getString(1) ?: continue
                val mime = it.getString(2)
                if (name.startsWith(".")) continue
                if (mime == Document.MIME_TYPE_DIR) {
                    folders += id
                    continue
                }
                if (!isSound(name, mime)) continue
                val modified = it.getLong(4)
                val date = Instant.ofEpochMilli(modified).atZone(ZoneId.systemDefault()).toLocalDate()
                into += MediaItem(
                    id = SoundIds.of(folder, DocumentsContract.buildDocumentUriUsingTree(tree, id).toString()),
                    type = MediaType.AUDIO,
                    takenAtMillis = modified,
                    day = LocalDay(date.year, date.monthValue, date.dayOfMonth),
                    durationMillis = null,
                    width = 0,
                    height = 0,
                    sizeBytes = it.getLong(3),
                    fileName = name,
                    source = folder.source,
                    modifiedMillis = modified,
                    mimeType = mime?.takeIf { type -> type.startsWith("audio/") },
                )
            }
        }
        if (depth < MAX_DEPTH) folders.forEach { walk(tree, it, folder, into, depth + 1) }
    }

    private fun isSound(name: String, mime: String?): Boolean =
        mime?.startsWith("audio/") == true || name.substringAfterLast('.', "").lowercase() in EXTENSIONS

    private companion object {
        /** Subfolders deep (Samsung keeps calls in Recordings/Call, some recorders a folder per day). */
        const val MAX_DEPTH = 4
        val EXTENSIONS = setOf("m4a", "aac", "flac", "ogg", "opus", "wav", "mp3", "amr", "3gp")
    }
}
