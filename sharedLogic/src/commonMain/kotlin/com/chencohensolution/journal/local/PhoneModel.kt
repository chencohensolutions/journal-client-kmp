package com.chencohensolution.journal.local

import kotlinx.coroutines.CancellationException
import okio.FileSystem
import okio.Path

/**
 * Where the phone gets the model files it runs by itself, not as part of an index (journal-server-go `phoneModels`,
 * served at `/models/phone/{name}` with Range): MediaPipe's photo embedder on Android.
 */
internal interface PhoneModelApi {
    /** The files the server offers; empty when it doesn't have them (or is still hashing them after a start). */
    suspend fun phoneModels(): List<ModelFileInfo>

    /** The same download as [LocalIndexApi.model]: from what [target] has (Range), sha256 checked. */
    suspend fun model(file: ModelFileInfo, fileSystem: FileSystem, target: Path, progress: (Long) -> Unit): Boolean
}

/**
 * One model file the phone downloads from the server on first use, instead of carrying it in the app: kept as
 * `<directory>/<name>`, with `<name>.ok` (its sha256) beside it once downloaded whole and checked. A broken download
 * goes on from where it broke next time; a file that doesn't match its sha256 is deleted and downloaded again.
 */
internal class PhoneModel(
    private val name: String,
    private val api: PhoneModelApi,
    private val fileSystem: FileSystem,
    private val directory: Path,
) {
    private val file = directory / name
    private val ok = directory / "$name.ok"

    /** The model's file when it is here whole; reads nothing from the server. */
    fun path(): Path? = if (fileSystem.exists(ok) && fileSystem.exists(file)) file else null

    /**
     * The model's file, downloaded first if it isn't here. Null when the server doesn't offer it, can't be reached or
     * the download broke: the next call tries again.
     */
    suspend fun fetch(): Path? {
        path()?.let { return it }
        return try {
            val info = api.phoneModels().firstOrNull { it.name == name } ?: return null
            if (!api.model(info, fileSystem, file) {}) return null
            fileSystem.write(ok) { writeUtf8(info.sha256) }
            file
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
