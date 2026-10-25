package com.chencohensolution.journal.library

import com.chencohensolution.journal.db.LibraryDatabase

/** An item of the library as `library_item` holds it, and the server file it is ([serverSha], for scores). */
internal class LibraryRow(val item: MediaItem, val serverSha: String?)

/** What [LibraryTable.refresh] changed: rows added or different, and the ids of rows gone. */
internal class LibraryDelta(val changed: List<LibraryRow>, val removed: List<String>) {
    val isEmpty: Boolean get() = changed.isEmpty() && removed.isEmpty()
}

/**
 * The library the screens read, kept in `library_item` (docs/plans/library-on-disk.md, phase 4): the phone's items
 * and the server's photos not on the phone, made inside the database from `device_item`, `ledger` and `server_media`
 * by the same rules [mergeWithServer] follows in memory. [refresh] makes it afresh in a scratch table and writes, and
 * returns, only the rows that came out different, so a change costs the app only the rows it changes. Blocking: call
 * off the main thread.
 */
internal class LibraryTable(private val database: LibraryDatabase) {
    private val queries get() = database.libraryQueries

    /** Every row, as kept. */
    fun all(): List<LibraryRow> = queries.allLibraryItems(::libraryRow).executeAsList()

    /** The [count] newest rows, read through the day index in milliseconds: the top of the screens at start. */
    fun newest(count: Long): List<LibraryRow> = queries.newestLibraryItems(count, ::libraryRow).executeAsList()

    /** Photos and videos per source of the whole library, in [MediaSource] order, as `sourceCounts` makes them. */
    fun sourceCounts(): Map<MediaSource, Int> {
        val counts = HashMap<MediaSource, Int>()
        queries.sourceCounts().executeAsList().forEach { row ->
            val source = MediaSource.parse(row.source) ?: MediaSource.OTHER
            counts[source] = (counts[source] ?: 0) + row.count.toInt()
        }
        return counts.keys.sorted().associateWith { counts.getValue(it) }
    }

    /** The rows of [ids] that are in the library, by id. */
    fun byIds(ids: Collection<String>): Map<String, LibraryRow> = database.transactionWithResult {
        ids.distinct().chunked(CHUNK).flatMap { queries.libraryItemsWithIds(it, ::libraryRow).executeAsList() }.associateBy { it.item.id }
    }

    /**
     * The row each of [sha256s] is, as [com.chencohensolution.journal.search.libraryResolver] finds it in the whole list:
     * the row with that sha256, else a phone item backup hasn't read with the server file's name (ignoring case) and size.
     * Where several rows qualify, the one that comes last in the screens' order (newest day first, newest first, then by
     * id), as the in-memory lookup keeps the last. sha256s nothing is found for are left out.
     */
    fun bySha(sha256s: Collection<String>): Map<String, LibraryRow> {
        val wanted = sha256s.distinct()
        val candidates = HashMap<String, LibraryRow>()
        val files = HashMap<String, Pair<String, Long?>>()
        database.transaction {
            wanted.chunked(CHUNK).forEach { chunk ->
                queries.libraryItemsWithShas(chunk, ::libraryRow).executeAsList().forEach { candidates[it.item.id] = it }
                queries.libraryItemsNamedAs(chunk, ::libraryRow).executeAsList().forEach { candidates[it.item.id] = it }
                queries.serverFilesWithShas(chunk).executeAsList().forEach { files[it.sha256] = it.file_name.lowercase() to it.size }
            }
        }
        val withSha = HashMap<String, LibraryRow>()
        val byNameAndSize = HashMap<Pair<String, Long?>, LibraryRow>()
        candidates.values.sortedWith(SHOWN_ORDER).forEach { row ->
            val item = row.item
            val sha = item.sha256
            if (sha != null) withSha[sha] = row else item.fileName?.let { byNameAndSize[it.lowercase() to item.sizeBytes] = row }
        }
        val found = LinkedHashMap<String, LibraryRow>()
        wanted.forEach { sha -> (withSha[sha] ?: files[sha]?.let(byNameAndSize::get))?.let { found[sha] = it } }
        return found
    }

    /** The items with a location of their own inside the box, as (id, day, where). */
    fun located(south: Double, west: Double, north: Double, east: Double): List<Triple<String, LocalDay, GeoPoint>> =
        queries.locatedLibraryItems(south = south, north = north, west = west, east = east).executeAsList().map {
            Triple(it.id, LocalDay.fromEpochDay(it.epoch_day), GeoPoint(it.latitude!!, it.longitude!!))
        }

    /** The days of those of [ids] that have no location of their own. */
    fun daysOfUnlocated(ids: Collection<String>): Set<LocalDay> =
        ids.distinct().chunked(CHUNK).flatMapTo(HashSet()) { chunk -> queries.daysOfUnlocated(chunk).executeAsList().map(LocalDay::fromEpochDay) }

    /**
     * Makes again the items that what changed since the last refresh can change: the database's triggers note each write
     * to the phone's items, the server's list, the ledger and what is hidden (`library_dirty`), and the notes are widened
     * to the items they touch (`takeKeys`). With nothing noted it does nothing; a note of everything makes it whole.
     */
    fun refresh(): LibraryDelta = database.transactionWithResult {
        if (queries.dirtyCount().executeAsOne() == 0L) return@transactionWithResult LibraryDelta(emptyList(), emptyList())
        val everything = if (queries.everythingDirty().executeAsOne() > 0) 1L else 0L
        queries.takeKeys()
        queries.clearFresh()
        queries.freshFromDevice(everything)
        queries.freshScores()
        queries.freshFromServer(everything)
        val changed = queries.changedRows(::libraryRow).executeAsList()
        val removed = queries.removedIds(everything).executeAsList()
        removed.chunked(CHUNK).forEach { queries.deleteRemoved(it) }
        queries.applyChanged()
        // Emptied now, so the next refresh (usually a few rows) doesn't first pay for this one's rows.
        queries.clearFresh()
        LibraryDelta(changed, removed)
    }

    private companion object {
        /** SQLite takes at most 999 parameters in a statement. */
        const val CHUNK = 500

        /** The order the screens show the library in (LibraryController: days newest first, each newest first, then by id). */
        val SHOWN_ORDER = compareByDescending<LibraryRow> { it.item.day }.thenByDescending { it.item.takenAtMillis }.thenBy { it.item.id }
    }
}

@Suppress("LongParameterList")
internal fun libraryRow(
    id: String, sha256: String?, serverSha256: String?, isOnDevice: Boolean, isVideo: Boolean, takenMillis: Long, epochDay: Long,
    durationMillis: Long?, width: Long, height: Long, size: Long?, fileName: String?, burstId: String?, source: String,
    latitude: Double?, longitude: Double?, modifiedMillis: Long?, mimeType: String?, isPrivate: Boolean, aesthetic: Double?,
) = LibraryRow(
    MediaItem(
        id = id,
        type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
        takenAtMillis = takenMillis,
        day = LocalDay.fromEpochDay(epochDay),
        durationMillis = durationMillis,
        width = width.toInt(),
        height = height.toInt(),
        sizeBytes = size,
        fileName = fileName,
        backup = if (isOnDevice) BackupStatus.NotBackedUp else BackupStatus.BackedUp,
        burstId = burstId,
        source = MediaSource.parse(source) ?: MediaSource.OTHER,
        location = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null,
        modifiedMillis = modifiedMillis,
        mimeType = mimeType,
        sha256 = sha256,
        isOnDevice = isOnDevice,
        isPrivate = isPrivate,
        aesthetic = aesthetic?.toFloat(),
    ),
    serverSha256,
)

/**
 * [row]'s item as shown: the aesthetic score this phone keeps for its server file ([scores], by sha256) before the
 * server's, and for a photo only on the server the original kept here ([kept], by sha256), as [mergeWithServer] does.
 */
internal fun shown(row: LibraryRow, kept: Map<String, String>, scores: Map<String, Float>): MediaItem {
    var item = row.item
    row.serverSha?.let(scores::get)?.let { score -> if (score != item.aesthetic) item = item.copy(aesthetic = score) }
    if (!item.isOnDevice) item.sha256?.let(kept::get)?.let { item = item.copy(keptOriginal = it) }
    return item
}
