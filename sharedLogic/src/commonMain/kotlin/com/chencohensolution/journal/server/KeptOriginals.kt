package com.chencohensolution.journal.server

import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.buffer
import okio.use

/**
 * Originals downloaded from the server on request ("Download original") and kept in the app's own storage
 * (`files/originals/<sha256>.<ext>`), outside the preview cache, so trimming never removes them. Never written to the
 * photo library.
 */
class KeptOriginals internal constructor(
    private val http: ServerHttp,
    private val fileSystem: FileSystem,
    private val directory: Path,
) {
    private val _kept = MutableStateFlow<Map<String, Path>>(emptyMap())

    /** Kept originals by sha256. */
    val kept: StateFlow<Map<String, Path>> = _kept.asStateFlow()

    /** Reads what is kept; call once at start. */
    suspend fun load() = withContext(Dispatchers.IO) {
        if (!fileSystem.exists(directory)) return@withContext
        markNoMedia(fileSystem, directory)
        _kept.value = fileSystem.list(directory)
            .filter { !it.name.endsWith(".tmp") && !it.name.startsWith(".") }
            .associateBy { it.name.substringBefore('.') }
    }

    /**
     * Downloads [sha256] and keeps it, checking its bytes against the sha256 first. [onProgress] gets 0..1. False when
     * it can't be had now (offline, or the server doesn't have it).
     */
    suspend fun download(sha256: String, fileName: String, onProgress: (Float) -> Unit = {}): Boolean = withContext(Dispatchers.IO) {
        val client = http.client() ?: return@withContext false
        fileSystem.createDirectories(directory)
        markNoMedia(fileSystem, directory)
        val extension = fileName.substringAfterLast('.', "").lowercase().filter { it.isLetterOrDigit() }.take(8)
        val target = directory / (if (extension.isEmpty()) sha256 else "$sha256.$extension")
        val temp = directory / "$sha256.tmp"
        val ok = client.prepareGet(http.url("/media/$sha256")).execute { response ->
            if (response.status != HttpStatusCode.OK) return@execute false
            val total = response.headers["Content-Length"]?.toLongOrNull()
            val hashing = HashingSink.sha256(fileSystem.sink(temp))
            hashing.buffer().use { sink ->
                val channel = response.bodyAsChannel()
                val buffer = ByteArray(64 * 1024)
                var received = 0L
                while (true) {
                    val n = channel.readAvailable(buffer)
                    if (n == -1) break
                    sink.write(buffer, 0, n)
                    received += n
                    if (total != null && total > 0) onProgress(received.toFloat() / total)
                }
            }
            hashing.hash.hex() == sha256
        }
        if (!ok) {
            fileSystem.delete(temp, mustExist = false)
            return@withContext false
        }
        fileSystem.atomicMove(temp, target)
        _kept.value = _kept.value + (sha256 to target)
        true
    }

    /** Removes the kept original of [sha256]; the server keeps its own. */
    suspend fun delete(sha256: String) = withContext(Dispatchers.IO) {
        _kept.value[sha256]?.let { fileSystem.delete(it, mustExist = false) }
        _kept.value = _kept.value - sha256
    }
}
