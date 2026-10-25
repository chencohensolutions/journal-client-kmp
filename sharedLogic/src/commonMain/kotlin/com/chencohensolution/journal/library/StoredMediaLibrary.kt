package com.chencohensolution.journal.library

import com.chencohensolution.journal.db.Device_item
import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.db.LibraryDatabase
import com.chencohensolution.journal.server.PrivateFile
import com.chencohensolution.journal.settings.PrivateMedia
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Where a [ChangingLibrary] was last read up to; its own format, kept as text. Null: never read. */
internal class LibraryChanges(
    /** What to pass next time. */
    val mark: String,
    /** Whether [changed] is every item (the platform couldn't tell what changed): the rest are gone. */
    val full: Boolean,
    /** Items added or changed since the mark passed (with [full], all of them). */
    val changed: List<MediaItem>,
    /** The ids of every item there now, so those gone are deleted. */
    val present: Set<String>,
)

/** A platform library that can say what changed since it was last read (Android: MediaStore's generations). */
internal interface ChangingLibrary : MediaLibrary {
    /** What changed since [mark] (null: everything). Called off the main thread by the implementation itself. */
    suspend fun changesSince(mark: String?): LibraryChanges

    /** The items with these ids, those still there. */
    suspend fun items(ids: Collection<String>): List<MediaItem>
}

/** A library that keeps out what the user hides (see [StoredMediaLibrary.leaveOut]). */
internal interface LeavesOut {
    suspend fun leaveOut(hides: (MediaItem) -> Boolean)
}

/**
 * What the phone's library leaves out: its copies of what the server hides ([hiddenOnServer]), and of the private files
 * while [privateMedia] hides them ([privateFiles]); matched by sha256 once backup read it ([entries]), else by name and
 * size, as [mergeWithServer] does.
 */
internal fun hiddenOnPhone(
    entries: Map<String, LedgerEntry>,
    hiddenOnServer: Set<String>,
    privateFiles: List<PrivateFile>,
    privateMedia: PrivateMedia,
): (MediaItem) -> Boolean {
    val leftOut = if (privateMedia.hides(true)) privateFiles else emptyList()
    val privateShas = leftOut.mapTo(HashSet()) { it.sha256 }
    val privateNamesAndSizes = leftOut.mapTo(HashSet<Pair<String?, Long?>>()) { it.fileName.lowercase() to it.size }
    return { item ->
        val sha = entries[item.id]?.takeIf { it.matches(item) }?.sha256
        if (sha != null) sha in hiddenOnServer || sha in privateShas else (item.fileName?.lowercase() to item.sizeBytes) in privateNamesAndSizes
    }
}

/**
 * The phone's library kept in the library database (docs/plans/library-on-disk.md, phase 2): each read asks
 * [platform] only for what changed since the last one when it can ([ChangingLibrary]), writes that to `device_item`, and
 * answers from the table. What the user hides ([leaveOut]) gets no row, only its id in `device_left_out`, so it is
 * read again once shown. A cache: losing it means reading the platform's library again.
 */
internal class StoredMediaLibrary(
    private val platform: MediaLibrary,
    private val database: LibraryDatabase,
    /** Where the database is read and written. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : MediaLibrary, LeavesOut {
    private val queries get() = database.deviceQueries
    private val state get() = database.stateQueries
    private val mutex = Mutex()

    /** What the user hides on this phone, as [leaveOut] last set it. */
    private var hides: (MediaItem) -> Boolean = { false }

    override fun access(): PhotoAccess = platform.access()

    override fun watch(onChange: () -> Unit): () -> Unit = platform.watch(onChange)

    override suspend fun loadItems(): List<MediaItem> = mutex.withLock {
        val changes = (platform as? ChangingLibrary)?.changesSince(withContext(io) { state.stateValue(MARK).executeAsOneOrNull() })
        val all = if (changes == null) platform.loadItems() else null
        withContext(io) {
            Measure.time({ "Phone's library: ${changes?.let { if (it.full) "read whole, ${it.changed.size}" else "${it.changed.size} changed" } ?: "read whole"}" }) {
                database.transaction {
                    if (changes != null) {
                        apply(changes.changed, changes.present, changes.full)
                        state.setState(MARK, changes.mark)
                    } else {
                        apply(all!!, all.mapTo(HashSet()) { it.id }, full = true)
                    }
                }
            }
            items()
        }
    }

    /**
     * What the user hides changed (or is first known): items it now hides leave the table, and those it hides no more
     * are read again from the platform. A platform that can't read items by id is read whole.
     */
    override suspend fun leaveOut(hides: (MediaItem) -> Boolean) {
        mutex.withLock {
            this.hides = hides
            val gone = withContext(io) { queries.leftOutIds().executeAsList() }
            val back = when {
                gone.isEmpty() -> emptyList()
                platform is ChangingLibrary -> platform.items(gone).filterNot(hides)
                else -> platform.loadItems().filter { it.id in gone.toSet() && !hides(it) }
            }
            withContext(io) {
                database.transaction {
                    items().filter(hides).forEach { item ->
                        queries.deleteDeviceItem(item.id)
                        queries.addLeftOut(item.id)
                    }
                    back.forEach { item ->
                        queries.removeLeftOut(item.id)
                        queries.upsertDeviceItem(item.toRow())
                    }
                }
            }
        }
    }

    private fun items(): List<MediaItem> = queries.allDeviceItems().executeAsList().map { it.toMediaItem() }

    /** Writes [changed] (all items when [full]) and deletes what isn't [present]; inside a transaction. */
    private fun apply(changed: List<MediaItem>, present: Set<String>, full: Boolean) {
        val leftOut = queries.leftOutIds().executeAsList().toHashSet()
        changed.forEach { item ->
            if (hides(item)) {
                queries.deleteDeviceItem(item.id)
                queries.addLeftOut(item.id)
            } else {
                if (item.id in leftOut) queries.removeLeftOut(item.id)
                queries.upsertDeviceItem(item.toRow())
            }
        }
        val written = if (full) changed.mapTo(HashSet()) { it.id } else null
        queries.deviceIds().executeAsList().forEach { id ->
            if (id !in present || (written != null && id !in written)) queries.deleteDeviceItem(id)
        }
        leftOut.forEach { id -> if (id !in present) queries.removeLeftOut(id) }
    }

    private companion object {
        const val MARK = "device_mark"
    }
}

private fun MediaItem.toRow() = Device_item(
    id = id,
    is_video = type == MediaType.VIDEO,
    taken_millis = takenAtMillis,
    epoch_day = day.toEpochDay(),
    duration_millis = durationMillis,
    width = width.toLong(),
    height = height.toLong(),
    size = sizeBytes,
    file_name = fileName,
    burst_id = burstId,
    source = source.id,
    latitude = location?.latitude,
    longitude = location?.longitude,
    modified_millis = modifiedMillis,
    mime_type = mimeType,
)

private fun Device_item.toMediaItem() = MediaItem(
    id = id,
    type = if (is_video) MediaType.VIDEO else MediaType.IMAGE,
    takenAtMillis = taken_millis,
    day = LocalDay.fromEpochDay(epoch_day),
    durationMillis = duration_millis,
    width = width.toInt(),
    height = height.toInt(),
    sizeBytes = size,
    fileName = file_name,
    burstId = burst_id,
    source = MediaSource.parse(source) ?: MediaSource.OTHER,
    location = if (latitude != null && longitude != null) GeoPoint(latitude, longitude) else null,
    modifiedMillis = modified_millis,
    mimeType = mime_type,
)
