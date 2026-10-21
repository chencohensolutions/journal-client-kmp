package com.chencohensolution.journal.library

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * What iOS reads from a photo's file (its PHAssetResource): [MediaItem.fileName], [MediaItem.sizeBytes],
 * [MediaItem.mimeType] and [MediaItem.modifiedMillis]. Reading it takes a few milliseconds a photo, so it is cached
 * ([FileFactsCache]) and read again only when the asset's modification date moves.
 */
internal data class FileFacts(
    /** PHAsset.modificationDate when these were read. */
    val assetModifiedMillis: Long?,
    val fileName: String?,
    val sizeBytes: Long?,
    val mimeType: String?,
    /** The type of the file as it was taken, before an edit: it tells camera shots from saved images. */
    val originalMimeType: String?,
    /**
     * When the file itself last changed: the asset's modification date at the read that first saw this name, size and
     * type. iOS moves the modification date for a favorite, an album or its own photo analysis too; backup would read
     * every such photo again if [MediaItem.modifiedMillis] followed it.
     */
    val modifiedMillis: Long?,
) {
    /** Facts read again: the file keeps its [modifiedMillis] when nothing about it changed since [previous]. */
    fun after(previous: FileFacts?): FileFacts =
        if (previous != null && previous.fileName == fileName && previous.sizeBytes == sizeBytes && previous.mimeType == mimeType) {
            copy(modifiedMillis = previous.modifiedMillis)
        } else {
            this
        }
}

/**
 * The name of an edited photo's current file: the original's ("IMG_1234.HEIC") with the edit's extension (Photos
 * renders it as "FullSizeRender.jpg"), as Photos names it when it exports the current version.
 */
internal fun editedFileName(originalName: String, renderName: String): String {
    val extension = renderName.substringAfterLast('.', "")
    val stem = originalName.substringBeforeLast('.')
    return if (extension.isEmpty()) originalName else "$stem.$extension"
}

/**
 * `<dir>/files.json`: PHAsset local identifier → [FileFacts]. A cache in the platform's caches folder, rebuilt when
 * it's missing or damaged (each photo is read again once, and backup hashes again the photos whose date it lost).
 */
internal class FileFactsCache(private val fileSystem: FileSystem, private val dir: Path) {
    private val file = dir / "files.json"

    fun load(): MutableMap<String, FileFacts> = try {
        val saved = fileSystem.read(file) { json.decodeFromString<CacheFile>(readUtf8()) }
        if (saved.v != VERSION) {
            mutableMapOf()
        } else {
            saved.assets.mapValuesTo(HashMap()) { (_, it) -> FileFacts(it.a, it.n, it.s, it.t, it.o ?: it.t, it.m) }
        }
    } catch (e: Exception) {
        mutableMapOf()
    }

    fun save(facts: Map<String, FileFacts>) {
        try {
            val contents = CacheFile(
                VERSION,
                // The original's type is written only when an edit changed it.
                facts.mapValues { (_, it) -> Cached(it.assetModifiedMillis, it.fileName, it.sizeBytes, it.mimeType, it.originalMimeType.takeIf { o -> o != it.mimeType }, it.modifiedMillis) },
            )
            fileSystem.createDirectories(dir)
            val temp = dir / "files.json.tmp"
            fileSystem.write(temp) { writeUtf8(json.encodeToString(contents)) }
            fileSystem.atomicMove(temp, file)
        } catch (e: Exception) {
            // Only a cache: the photos are read again next time.
        }
    }

    @Serializable
    private class CacheFile(val v: Int, val assets: Map<String, Cached>)

    @Serializable
    private class Cached(
        val a: Long? = null,
        val n: String? = null,
        val s: Long? = null,
        val t: String? = null,
        val o: String? = null,
        val m: Long? = null,
    )

    private companion object {
        const val VERSION = 1
        val json = Json { ignoreUnknownKeys = true }
    }
}
