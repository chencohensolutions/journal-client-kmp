package com.chencohensolution.journal.backup

import com.chencohensolution.journal.db.Ledger
import com.chencohensolution.journal.db.LibraryDatabase
import com.chencohensolution.journal.library.Measure
import com.chencohensolution.journal.library.MediaItem
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path

/**
 * What this device knows about one photo it read for backup: the sha256 of its bytes for the file as it was
 * ([size], [modifiedMillis]), and whether the server has those bytes.
 */
@Serializable
data class LedgerEntry(
    /** Device id (MediaStore id / PHAsset local identifier). */
    val id: String,
    val size: Long,
    val modifiedMillis: Long?,
    val sha256: String,
    val uploaded: Boolean = false,
    /** An earlier upload of this device photo, when the photo was edited in place since: the server's original. */
    val versionOf: String? = null,
    /** Why the server refused it (UploadRejected reason); retried on the next run. */
    val rejected: String? = null,
) {
    /** Whether this entry still describes [item]: the same file, not changed since it was read. */
    fun matches(item: MediaItem): Boolean = item.sizeBytes == size && item.modifiedMillis == modifiedMillis
}

/**
 * The device's record of hashes and uploads, so a photo is read once and never sent twice. Per device, never synced:
 * losing it only means reading the photos again (the server still deduplicates).
 *
 * Kept in the library database's `ledger` table (docs/plans/library-on-disk.md, phase 3), read into memory at first
 * use and written through on each change. Until Oct 25, 2026 it was JSON lines in `ledger.jsonl` (the last line for
 * an id won, a damaged line was skipped), which is read into the table once and deleted. Safe to use from the upload
 * worker and the screens at once.
 */
internal class UploadLedger(
    private val fileSystem: FileSystem,
    /** Where `ledger.jsonl` was kept. */
    directory: Path,
    private val database: LibraryDatabase,
    /** Where the database is read and written. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val file = directory / "ledger.jsonl"
    private val tempFile = directory / "ledger.jsonl.tmp"
    private val queries get() = database.ledgerQueries
    private val entries = LinkedHashMap<String, LedgerEntry>()
    private var loaded = false
    private val mutex = Mutex()

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(io) {
            if (fileSystem.exists(file)) importFile()
            queries.allLedger().executeAsList().forEach { entries[it.id] = it.toEntry() }
        }
        loaded = true
    }

    /** Reads `ledger.jsonl` into the table, then deletes it (and a temp file a compaction left). */
    private fun importFile() {
        val read = LinkedHashMap<String, LedgerEntry>()
        fileSystem.read(file) {
            while (true) {
                val line = readUtf8Line() ?: break
                if (line.isBlank()) continue
                val entry = runCatching { json.decodeFromString<LedgerEntry>(line) }.getOrNull() ?: continue
                read[entry.id] = entry
            }
        }
        database.transaction { read.values.forEach { queries.upsertLedger(it.toRow()) } }
        Measure.log?.invoke("Ledger: ${read.size} entries read in from ledger.jsonl")
        fileSystem.delete(file)
        fileSystem.delete(tempFile, mustExist = false)
    }

    suspend fun entry(id: String): LedgerEntry? = mutex.withLock {
        ensureLoaded()
        entries[id]
    }

    suspend fun all(): Map<String, LedgerEntry> = mutex.withLock {
        ensureLoaded()
        entries.toMap()
    }

    /** Whether the server has [item] as it is now. */
    suspend fun isUploaded(item: MediaItem): Boolean = entry(item.id)?.let { it.uploaded && it.matches(item) } == true

    suspend fun record(entry: LedgerEntry): Unit = mutex.withLock {
        ensureLoaded()
        if (entries[entry.id] == entry) return@withLock
        withContext(io) { queries.upsertLedger(entry.toRow()) }
        entries[entry.id] = entry
    }

    suspend fun forget(id: String): Unit = mutex.withLock {
        ensureLoaded()
        if (entries.remove(id) != null) withContext(io) { queries.deleteLedger(id) }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

private fun LedgerEntry.toRow() = Ledger(id, size, modifiedMillis, sha256, uploaded, versionOf, rejected)

private fun Ledger.toEntry() = LedgerEntry(id, size, modified_millis, sha256, uploaded, version_of, rejected)
