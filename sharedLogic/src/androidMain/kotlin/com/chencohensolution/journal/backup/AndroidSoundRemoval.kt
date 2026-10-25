package com.chencohensolution.journal.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.storage.SoundRemoval
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The one place the app writes in the sound folders the user picked (allowed by the owner on Oct 25, 2026, with
 * backup of calls and recordings): it deletes sound files the server confirmed it holds ("Free up space", after the
 * app's own dialog, and a WAV replaced by its FLAC), and writes that FLAC beside the WAV. These folders have no trash, so
 * a deleted file is gone for good. It never touches the photo library, and only files of folders the user picked
 * (their tree URIs, with the write access the picker gave). See PhotoSafetyTest.
 */
class AndroidSoundRemoval(context: Context) : SoundRemoval {
    private val resolver = context.applicationContext.contentResolver

    override suspend fun delete(items: List<MediaItem>): Set<String> = withContext(Dispatchers.IO) {
        items.filter { item -> documentOf(item)?.let { runCatching { DocumentsContract.deleteDocument(resolver, it) }.getOrDefault(false) } == true }
            .mapTo(mutableSetOf()) { it.id }
    }

    /**
     * Writes [flac] (a file in the app's cache) as [name] in the folder of the WAV [item], then deletes the WAV; false
     * when either failed (a FLAC written but not complete is deleted again, and the WAV stays).
     */
    internal suspend fun replace(item: MediaItem, flac: File, name: String): Boolean = withContext(Dispatchers.IO) {
        val wav = documentOf(item) ?: return@withContext false
        val tree = Uri.parse(SoundIds.treeOf(item.id) ?: return@withContext false)
        val parent = runCatching { DocumentsContract.findDocumentPath(resolver, wav)?.path?.dropLast(1)?.lastOrNull() }.getOrNull()
            ?: DocumentsContract.getTreeDocumentId(tree)
        val folder = DocumentsContract.buildDocumentUriUsingTree(tree, parent)
        val created = runCatching { DocumentsContract.createDocument(resolver, folder, "audio/flac", name) }.getOrNull() ?: return@withContext false
        val written = runCatching {
            resolver.openOutputStream(created, "w")!!.use { out -> flac.inputStream().use { it.copyTo(out) } }
        }.getOrNull()
        if (written != flac.length()) {
            runCatching { DocumentsContract.deleteDocument(resolver, created) }
            return@withContext false
        }
        runCatching { DocumentsContract.deleteDocument(resolver, wav) }.getOrDefault(false)
    }

    private fun documentOf(item: MediaItem): Uri? = SoundIds.documentOf(item.id)?.let(Uri::parse)
}
