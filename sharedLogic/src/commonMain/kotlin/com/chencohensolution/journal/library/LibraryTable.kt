package com.chencohensolution.journal.library

import com.chencohensolution.journal.backup.BackupActivity
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.db.LibraryDatabase
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.stories.GeoArea

/** An item of the library as `library_item` holds it, and the server file it is ([serverSha], for scores). */
internal class LibraryRow(val item: MediaItem, val serverSha: String?)

/**
 * Where a row is in the screens' order: newest day first, newest first, then by id (LibraryController). Ordered that
 * way; a batch read goes on after one (step 6b).
 */
internal data class LibraryKey(val epochDay: Long, val takenMillis: Long, val id: String) : Comparable<LibraryKey> {
    override fun compareTo(other: LibraryKey): Int = when {
        epochDay != other.epochDay -> other.epochDay.compareTo(epochDay)
        takenMillis != other.takenMillis -> other.takenMillis.compareTo(takenMillis)
        else -> id.compareTo(other.id)
    }

    val day: LocalDay get() = LocalDay.fromEpochDay(epochDay)

    companion object {
        /** Before every row: a read from the newest. */
        val FIRST = LibraryKey(Long.MAX_VALUE, Long.MAX_VALUE, "")
    }
}

/** [this]'s place in the screens' order. */
internal val MediaItem.key: LibraryKey get() = LibraryKey(day.toEpochDay(), takenAtMillis, id)

/** Items with a location of their own in a 0.1° [square] from [source], on [day] when counted per day. */
internal class SquareCount(val source: MediaSource, val square: Pair<Long, Long>, val day: LocalDay?, val count: Int)

/** A row's place in the screens' order, its source and where it was taken (when known). */
internal class LocatedRow(val key: LibraryKey, val source: MediaSource, val point: GeoPoint?)

/**
 * A row as Explore's summaries read it (step 6c): its place in the screens' order, type, source, its own location, and
 * the server file it is with the server's score (this phone's score for that file goes first, see [shown]).
 */
internal class ExploreRow(
    val key: LibraryKey,
    val isVideo: Boolean,
    val source: MediaSource,
    val point: GeoPoint?,
    val serverSha: String?,
    val aesthetic: Float?,
)

/** How many items of one type [source] saved on [day]. */
internal class DayCount(val day: LocalDay, val source: MediaSource, val isVideo: Boolean, val count: Int)

/** The box around the items with a location of their own that [source] saved on [day]. */
internal class DayBox(val day: LocalDay, val source: MediaSource, val box: GeoArea)

/**
 * The shown items with a location of their own in one square of the map ([lat], [lon]: floor(degrees / size)): how many,
 * the sums of their latitudes and longitudes, and the id of the only one when there is one.
 */
internal class CellCount(val lat: Long, val lon: Long, val count: Int, val latSum: Double, val lonSum: Double, val onlyId: String?)

/**
 * One source's counts in the database (step 6d): its [items], the phone's items among them and their bytes, and those
 * the ledger doesn't hold as uploaded as they are now ([waiting]) and their bytes (an unknown size counts 0).
 */
internal class SourceCounts(
    val source: MediaSource,
    val items: Int,
    val onDevice: Int,
    val onDeviceBytes: Long,
    val waiting: Int,
    val waitingBytes: Long,
)

/**
 * The library's counts as [LibraryState] shows them, from the database's [counts] per source and the status each photo
 * has in [backup] (BackupState.status): a photo waits unless the ledger holds it as uploaded, and one on its way
 * ([uploadingBackedUp]: those of the files uploading now the ledger holds as uploaded, from
 * [LibraryTable.backedUpAmong]) waits too. Only the sources backup takes count; without [backup] every phone item waits.
 * Exactly what LibraryState counts over its days.
 */
internal fun libraryTotals(counts: List<SourceCounts>, uploadingBackedUp: List<Pair<MediaSource, Long?>>, backup: BackupState?): LibraryTotals {
    val sources = backup?.countedSources
    fun counted(source: MediaSource) = sources == null || source in sources
    val taken = counts.filter { counted(it.source) }
    val again = uploadingBackedUp.filter { counted(it.first) }
    return if (backup == null) {
        LibraryTotals(counts.sumOf { it.items }, taken.sumOf { it.onDevice }, taken.sumOf { it.onDeviceBytes })
    } else {
        LibraryTotals(
            counts.sumOf { it.items },
            taken.sumOf { it.waiting } + again.size,
            taken.sumOf { it.waitingBytes } + again.sumOf { it.second ?: 0L },
        )
    }
}

/** The ids of the files [backup] has on their way now: their status is Uploading, whatever the ledger says. */
internal fun uploadingIds(backup: BackupState?): Set<String> {
    val running = (backup?.activity as? BackupActivity.Running)?.progress ?: return emptySet()
    return running.uploading.keys + listOfNotNull(running.currentId)
}

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

    /**
     * Per source as the database spells it: the items, the phone's items and their bytes, and those the ledger doesn't
     * hold as uploaded as they are now (step 6d). [libraryTotals] makes backup's counts from them.
     */
    fun backupCounts(): List<SourceCounts> = queries.backupCounts { source, items, onDevice, onDeviceBytes, waiting, waitingBytes ->
        SourceCounts(MediaSource.parse(source) ?: MediaSource.OTHER, items.toInt(), onDevice.toInt(), onDeviceBytes, waiting.toInt(), waitingBytes)
    }.executeAsList()

    /** Those of [ids] that are phone items the ledger holds as uploaded as they are now, with their source and size. */
    fun backedUpAmong(ids: Collection<String>): List<Pair<MediaSource, Long?>> = database.transactionWithResult {
        ids.distinct().chunked(CHUNK).flatMap { chunk ->
            queries.backedUpAmong(chunk) { _, source, size -> (MediaSource.parse(source) ?: MediaSource.OTHER) to size }.executeAsList()
        }
    }

    /** Whether any of [ids] is an item of the library. */
    fun hasAny(ids: Collection<String>): Boolean = database.transactionWithResult {
        ids.distinct().chunked(CHUNK).any { queries.libraryIdsAmong(it).executeAsOne() > 0 }
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
     * Up to [limit] of the phone's photos (not videos) in the screens' order after the row [after] (null: from the newest):
     * what fingerprints are read from, a batch at a time (step 6b).
     */
    fun deviceImages(after: LibraryKey?, limit: Int): List<MediaItem> = (after ?: LibraryKey.FIRST).let { key ->
        queries.deviceImagesAfter(day = key.epochDay, taken = key.takenMillis, id = key.id, limit = limit.toLong(), mapper = ::libraryRow)
            .executeAsList().map { it.item }
    }

    /** Up to [limit] of the phone's photos and videos without a location of their own, in the screens' order after [after]. */
    fun deviceUnlocated(after: LibraryKey?, limit: Int): List<MediaItem> = (after ?: LibraryKey.FIRST).let { key ->
        queries.deviceUnlocatedAfter(day = key.epochDay, taken = key.takenMillis, id = key.id, limit = limit.toLong(), mapper = ::libraryRow)
            .executeAsList().map { it.item }
    }

    /** Up to [limit] items in the screens' order after [after], each with its own location (null without): places to name. */
    fun points(after: LibraryKey?, limit: Int): List<Pair<LibraryKey, GeoPoint?>> = (after ?: LibraryKey.FIRST).let { key ->
        queries.pointsAfter(day = key.epochDay, taken = key.takenMillis, id = key.id, limit = limit.toLong()) { id, epochDay, takenMillis, latitude, longitude ->
            LibraryKey(epochDay, takenMillis, id) to if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null
        }.executeAsList()
    }

    /**
     * The items with a location of their own counted per 0.1° square (truncated toward zero, as Explore's home does) and
     * source; those on [days] (days a hidden source is always shown on) counted per day too.
     */
    fun squareCounts(days: Collection<LocalDay>): List<SquareCount> =
        queries.homeSquares(days.map { it.toEpochDay() }.distinct()) { source, lat10, lon10, onDay, anyDay, photos ->
            // Never null: only rows with a location are counted.
            SquareCount(MediaSource.parse(source) ?: MediaSource.OTHER, lat10!! to lon10!!, if (onDay) LocalDay.fromEpochDay(anyDay) else null, photos.toInt())
        }.executeAsList()

    /** The items with a location of their own in the 0.1° [square], in the screens' order. */
    fun inSquare(square: Pair<Long, Long>): List<LocatedRow> =
        queries.homeSquareRows(lat10 = square.first, lon10 = square.second) { id, epochDay, takenMillis, source, latitude, longitude ->
            LocatedRow(LibraryKey(epochDay, takenMillis, id), MediaSource.parse(source) ?: MediaSource.OTHER, GeoPoint(latitude, longitude))
        }.executeAsList()

    /** Those of [ids] without a location of their own (where a place found for them counts), with no point. */
    fun unlocated(ids: Collection<String>): List<LocatedRow> = database.transactionWithResult {
        ids.distinct().chunked(CHUNK).flatMap { chunk ->
            queries.unlocatedLibraryRows(chunk) { id, epochDay, takenMillis, source ->
                LocatedRow(LibraryKey(epochDay, takenMillis, id), MediaSource.parse(source) ?: MediaSource.OTHER, null)
            }.executeAsList()
        }
    }

    /** Up to [limit] rows in the screens' order after [after] (null: from the newest), as Explore's summaries read them. */
    fun exploreRows(after: LibraryKey?, limit: Int): List<ExploreRow> = (after ?: LibraryKey.FIRST).let { key ->
        queries.exploreRowsAfter(day = key.epochDay, taken = key.takenMillis, id = key.id, limit = limit.toLong()) {
                id, epochDay, takenMillis, isVideo, source, latitude, longitude, serverSha, aesthetic ->
            ExploreRow(
                LibraryKey(epochDay, takenMillis, id), isVideo, MediaSource.parse(source) ?: MediaSource.OTHER,
                if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null, serverSha, aesthetic?.toFloat(),
            )
        }.executeAsList()
    }

    /** Photos and videos per day, source and type, of the whole library. */
    fun dayCounts(): List<DayCount> = queries.dayCounts { epochDay, source, isVideo, items ->
        DayCount(LocalDay.fromEpochDay(epochDay), MediaSource.parse(source) ?: MediaSource.OTHER, isVideo, items.toInt())
    }.executeAsList()

    /** The box around the items with a location of their own, per day and source. */
    fun locatedDays(): List<DayBox> = queries.locatedDays { epochDay, source, south, west, north, east ->
        // Never null: only rows with a location are grouped.
        DayBox(LocalDay.fromEpochDay(epochDay), MediaSource.parse(source) ?: MediaSource.OTHER, GeoArea(south!!, west!!, north!!, east!!))
    }.executeAsList()

    /** The rows from [first] to [last] (days), in the screens' order. */
    fun inDays(first: Long, last: Long): List<LibraryRow> = queries.libraryItemsInDays(first = first, last = last, mapper = ::libraryRow).executeAsList()

    /** The ids and places of the items with a location of their own in [box] (as [GeoArea.contains] takes it), from [first] to [last]. */
    fun locatedIn(box: GeoArea, first: Long, last: Long): List<Pair<String, GeoPoint>> =
        queries.locatedInBox(
            first = first, last = last, south = box.south, north = box.north, wraps = box.wraps, west = box.west, east = box.east,
        ) { id, latitude, longitude -> id to GeoPoint(latitude, longitude) }.executeAsList()

    /** The box around the items with a location of their own in each place square that has some, by [cellKey]. */
    fun cellBoxes(): Map<String, GeoArea> = queries.cellBoxes { latCell, lonCell, south, west, north, east ->
        "$latCell,$lonCell" to GeoArea(south!!, west!!, north!!, east!!)
    }.executeAsList().toMap()

    /**
     * The items [shown] shows with a location of their own in [box], from [first] to [last], counted per map square of
     * [size] degrees.
     */
    fun mapCells(size: Double, box: GeoArea, first: Long, last: Long, shown: ShownSources): List<CellCount> {
        val (hidden, always) = sourcesFilter(shown)
        return queries.mapCells(
            size = size, first = first, last = last, south = box.south, north = box.north, wraps = box.wraps, west = box.west,
            east = box.east, hidden = hidden, always = always,
        ) { latCell, lonCell, photos, latSum, lonSum, onlyId ->
            CellCount(latCell, lonCell, photos.toInt(), latSum!!, lonSum!!, onlyId)
        }.executeAsList()
    }

    /** How many of the items [shown] shows with a location of their own are in [box], from [first] to [last]. */
    fun mapCount(box: GeoArea, first: Long, last: Long, shown: ShownSources): Int {
        val (hidden, always) = sourcesFilter(shown)
        return queries.mapCount(
            first = first, last = last, south = box.south, north = box.north, wraps = box.wraps, west = box.west, east = box.east,
            hidden = hidden, always = always,
        ).executeAsOne().toInt()
    }

    /**
     * [shown] as the database's source column holds the sources (several spellings can be one source): those hidden,
     * and the days some of them show on anyway, as "epoch day:source".
     */
    private fun sourcesFilter(shown: ShownSources): Pair<List<String>, List<String>> {
        val spellings = queries.sourceCounts().executeAsList().map { it.source }.groupBy { MediaSource.parse(it) ?: MediaSource.OTHER }
        val hidden = spellings.filterKeys { !shown.isShown(it) }.values.flatten()
        val always = shown.alwaysShown.flatMap { daySource ->
            spellings[daySource.source].orEmpty().map { "${daySource.day.toEpochDay()}:$it" }
        }
        return hidden to always
    }

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

/**
 * A walk through the library read [limit] rows at a time in the screens' order ([read] gives those after a key, null for
 * the newest), so a background reader never holds the whole list (step 6b). [next] gives the next row [accept] takes.
 */
internal class BatchWalk<T>(
    private val key: (T) -> LibraryKey,
    private val limit: Int,
    private val read: suspend (after: LibraryKey?, limit: Int) -> List<T>,
) {
    private val batch = ArrayDeque<T>()
    private var after: LibraryKey? = null
    private var end = false

    suspend fun next(accept: (T) -> Boolean): T? {
        while (true) {
            while (batch.isNotEmpty()) batch.removeFirst().let { if (accept(it)) return it }
            if (end) return null
            val rows = read(after, limit)
            end = rows.size < limit
            rows.lastOrNull()?.let { after = key(it) }
            batch.addAll(rows)
        }
    }
}

/** 1 when [this] runs across the date line (its west edge east of its east edge), as the database's box queries take it. */
private val GeoArea.wraps: Long get() = if (west <= east) 0L else 1L
