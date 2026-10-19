package com.chencohensolution.journal.backup

import com.chencohensolution.journal.library.MediaItem
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

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
 * The device's record of hashes and uploads, so a photo is read once and never sent twice. A per-device cache, never
 * synced: losing it only means reading the photos again (the server still deduplicates).
 *
 * Kept as JSON lines appended to `ledger.jsonl`; the last line for an id wins. Rewritten (through a temp file) when it
 * holds many outdated lines. A damaged line is skipped. Safe to use from the upload worker and the screens at once.
 */
internal class UploadLedger(private val fileSystem: FileSystem, directory: Path) {
    private val file = directory / "ledger.jsonl"
    private val tempFile = directory / "ledger.jsonl.tmp"
    private val entries = LinkedHashMap<String, LedgerEntry>()
    private var lines = 0
    private var loaded = false
    private val mutex = Mutex()

    private fun ensureLoaded() {
        if (loaded) return
        loaded = true
        fileSystem.createDirectories(file.parent!!)
        if (!fileSystem.exists(file)) return
        fileSystem.read(file) {
            while (true) {
                val line = readUtf8Line() ?: break
                if (line.isBlank()) continue
                lines++
                val entry = runCatching { json.decodeFromString<LedgerEntry>(line) }.getOrNull() ?: continue
                entries[entry.id] = entry
            }
        }
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
        entries[entry.id] = entry
        if (lines > COMPACT_AFTER && lines > entries.size * 2) {
            compact()
        } else {
            fileSystem.appendingSink(file).buffer().use { it.writeUtf8(json.encodeToString(LedgerEntry.serializer(), entry)).writeUtf8("\n") }
            lines++
        }
    }

    suspend fun forget(id: String): Unit = mutex.withLock {
        ensureLoaded()
        if (entries.remove(id) != null) compact()
    }

    private fun compact() {
        fileSystem.write(tempFile) {
            entries.values.forEach { writeUtf8(json.encodeToString(LedgerEntry.serializer(), it)).writeUtf8("\n") }
        }
        fileSystem.atomicMove(tempFile, file)
        lines = entries.size
    }

    private companion object {
        const val COMPACT_AFTER = 1_000
        val json = Json { ignoreUnknownKeys = true }
    }
}
