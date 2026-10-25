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

    fun refresh(): LibraryDelta = database.transactionWithResult {
        queries.clearFresh()
        queries.freshFromDevice()
        queries.freshScores()
        queries.freshFromServer()
        val changed = queries.changedRows(::libraryRow).executeAsList()
        val removed = queries.removedIds().executeAsList()
        queries.deleteRemoved()
        queries.applyChanged()
        LibraryDelta(changed, removed)
    }

    /** Whether a change of the ledger rows of [ids] (now or before of [shas]) can change the library. */
    fun affects(ids: Collection<String>, shas: Collection<String>): Boolean =
        ids.chunked(CHUNK).any { queries.deviceItemCount(it).executeAsOne() > 0 } ||
            shas.chunked(CHUNK).any { queries.serverPhotoCount(it).executeAsOne() > 0 }

    private companion object {
        /** SQLite takes at most 999 parameters in a statement. */
        const val CHUNK = 500
    }
}

@Suppress("LongParameterList")
private fun libraryRow(
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
