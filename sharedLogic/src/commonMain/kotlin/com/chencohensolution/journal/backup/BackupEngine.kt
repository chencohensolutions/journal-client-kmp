package com.chencohensolution.journal.backup

import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.EOFException
import okio.HashingSink
import okio.Source
import okio.blackholeSink
import okio.buffer
import okio.use
import kotlin.coroutines.coroutineContext

/** The platform's read access to the photos' bytes. */
internal interface MediaFiles {
    /** The original bytes from [offset], unredacted (Android otherwise removes GPS from what an app reads). */
    fun open(item: MediaItem, offset: Long): Source

    /** When [item] was taken, RFC 3339 with the device's UTC offset at that moment. */
    fun takenAt(item: MediaItem): String
}

/** Where a run is: [done] of [total] files, and the bytes of the files still to send. */
data class BackupProgress(
    val done: Int,
    val total: Int,
    val bytesDone: Long,
    val bytesTotal: Long,
    /** The file being read or sent. */
    val currentId: String? = null,
    val currentName: String? = null,
    /** 0..1 of the current file. */
    val currentFraction: Float = 0f,
)

data class BackupRunResult(val uploaded: Int, val alreadyOnServer: Int, val failed: Int)

/** The photos backup takes, in the order it takes them: the chosen sources, oldest or newest first. */
internal fun backupQueue(items: List<MediaItem>, settings: BackupSettings): List<MediaItem> =
    items.filter { it.source in settings.sources && (it.sizeBytes ?: 0) > 0 }
        .let { scope ->
            when (settings.order) {
                UploadOrder.OLDEST_FIRST -> scope.sortedBy { it.takenAtMillis }
                UploadOrder.NEWEST_FIRST -> scope.sortedByDescending { it.takenAtMillis }
            }
        }

/**
 * One backup run: reads the photos not known to be on the server (sha256), asks the server which it lacks, and sends
 * those in pieces, continuing from where the server says it stopped. Network errors are thrown, so the caller can
 * retry later; a refused or changed file is skipped and tried again on the next run.
 */
internal class BackupEngine(
    private val files: MediaFiles,
    private val ledger: UploadLedger,
    private val api: BackupApi,
    private val locate: suspend (MediaItem) -> GeoPoint? = { null },
) {
    suspend fun run(items: List<MediaItem>, settings: BackupSettings, onProgress: (BackupProgress) -> Unit): BackupRunResult {
        val pending = backupQueue(items, settings).filterNot { ledger.isUploaded(it) }
        var progress = BackupProgress(done = 0, total = pending.size, bytesDone = 0, bytesTotal = pending.sumOf { it.sizeBytes ?: 0 })
        onProgress(progress)
        var uploaded = 0
        var already = 0
        var failed = 0

        for (batch in pending.chunked(BATCH)) {
            val entries = batch.map { item ->
                progress = progress.copy(currentId = item.id, currentName = item.fileName, currentFraction = 0f)
                onProgress(progress)
                item to hashed(item)
            }
            val missing = api.missing(entries.map { it.second.sha256 })
            for ((item, entry) in entries) {
                val size = item.sizeBytes ?: 0
                val outcome = if (entry.sha256 !in missing) {
                    ledger.record(entry.copy(uploaded = true, rejected = null))
                    Outcome.ALREADY
                } else {
                    upload(item, entry) { sent ->
                        onProgress(progress.copy(currentId = item.id, currentName = item.fileName, bytesDone = progress.bytesDone + sent, currentFraction = sent.toFloat() / size))
                    }
                }
                when (outcome) {
                    Outcome.UPLOADED -> uploaded++
                    Outcome.ALREADY -> already++
                    Outcome.FAILED -> failed++
                }
                progress = progress.copy(done = progress.done + 1, bytesDone = progress.bytesDone + size, currentId = null, currentName = null, currentFraction = 0f)
                onProgress(progress)
            }
        }
        return BackupRunResult(uploaded, already, failed)
    }

    /** The ledger's entry for [item] as it is now, reading the file when it is new or changed. */
    private suspend fun hashed(item: MediaItem): LedgerEntry {
        val previous = ledger.entry(item.id)
        if (previous != null && previous.matches(item)) return previous
        val sha = sha256(item)
        // Same device photo, new bytes: an edit saved in place. It points at the version the server has.
        val versionOf = previous?.let { if (it.uploaded) it.sha256 else it.versionOf }?.takeIf { it != sha }
        return LedgerEntry(id = item.id, size = item.sizeBytes ?: 0, modifiedMillis = item.modifiedMillis, sha256 = sha, versionOf = versionOf)
            .also { ledger.record(it) }
    }

    private suspend fun sha256(item: MediaItem): String = withContext(Dispatchers.IO) {
        val hashing = HashingSink.sha256(blackholeSink())
        val buffer = Buffer()
        files.open(item, 0).use { source ->
            while (source.read(buffer, READ_SIZE) != -1L) {
                hashing.write(buffer, buffer.size)
                coroutineContext.ensureActive()
            }
        }
        hashing.hash.hex()
    }

    private enum class Outcome { UPLOADED, ALREADY, FAILED }

    private suspend fun upload(item: MediaItem, entry: LedgerEntry, onSent: (Long) -> Unit): Outcome {
        val size = entry.size
        repeat(MAX_STARTS) {
            val start = when (val result = api.start(request(item, entry))) {
                StartResult.Exists -> {
                    ledger.record(entry.copy(uploaded = true, rejected = null))
                    return Outcome.ALREADY
                }
                is StartResult.Rejected -> {
                    ledger.record(entry.copy(rejected = result.reason))
                    return Outcome.FAILED
                }
                is StartResult.Ready -> result
            }
            val chunk = start.chunkSize.coerceIn(MIN_CHUNK, MAX_CHUNK)
            var offset = start.offset
            if (offset >= size) return Outcome.FAILED // The server has every byte but no file; nothing left to send.
            onSent(offset)
            val piece: PieceResult = try {
                withContext(Dispatchers.IO) {
                    files.open(item, offset).buffer().use { source ->
                        var last: PieceResult
                        do {
                            coroutineContext.ensureActive()
                            val bytes = source.readByteArray(minOf(chunk, size - offset))
                            last = api.put(start.url, offset, bytes, size)
                            if (last is PieceResult.Accepted) {
                                offset = last.offset
                                onSent(offset)
                            }
                        } while (last is PieceResult.Accepted && !last.complete && offset < size)
                        last
                    }
                }
            } catch (e: EOFException) {
                // Shorter than when it was read: the file changed. Read it again next time.
                ledger.forget(item.id)
                return Outcome.FAILED
            }
            when (piece) {
                is PieceResult.Accepted -> if (piece.complete) {
                    ledger.record(entry.copy(uploaded = true, rejected = null))
                    return Outcome.UPLOADED
                }
                PieceResult.HashMismatch -> {
                    ledger.forget(item.id)
                    return Outcome.FAILED
                }
                // Continue from the server's offset (startUpload returns it), or start the upload again.
                is PieceResult.WrongOffset, PieceResult.NoUpload -> Unit
            }
        }
        return Outcome.FAILED
    }

    private suspend fun request(item: MediaItem, entry: LedgerEntry) = UploadRequest(
        sha256 = entry.sha256,
        size = entry.size,
        fileName = item.fileName ?: item.id,
        mimeType = item.mimeType ?: if (item.type == MediaType.VIDEO) "video/mp4" else "image/jpeg",
        isVideo = item.type == MediaType.VIDEO,
        takenAt = files.takenAt(item),
        width = item.width.takeIf { it > 0 },
        height = item.height.takeIf { it > 0 },
        durationMillis = item.durationMillis,
        location = item.location ?: runCatching { locate(item) }.getOrNull(),
        source = item.source.serverName,
        versionOf = entry.versionOf,
    )

    private companion object {
        /** Hashes asked about in one missingMedia call (the server takes up to 1000). */
        const val BATCH = 100
        const val READ_SIZE = 1L shl 20
        const val MIN_CHUNK = 256L shl 10
        const val MAX_CHUNK = 64L shl 20

        /** startUpload calls per file in one run (a lost upload, or a piece the server placed elsewhere). */
        const val MAX_STARTS = 3
    }
}

/** How the server's sidecars name a source. */
internal val MediaSource.serverName: String get() = name.lowercase()
