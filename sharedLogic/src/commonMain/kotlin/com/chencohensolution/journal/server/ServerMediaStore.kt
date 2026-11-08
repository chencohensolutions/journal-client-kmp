package com.chencohensolution.journal.server

import com.chencohensolution.journal.db.LibraryDatabase
import com.chencohensolution.journal.db.Server_media
import app.cash.sqldelight.db.QueryResult

/**
 * What the phone's search and its people read of one server file ([ServerMediaStore.forEachFile]): when it was taken
 * (RFC 3339, else when it was uploaded), where, and whether it is private.
 */
internal class FileFacts(
    val sha256: String,
    val takenAt: String?,
    val uploadedAt: String,
    val latitude: Double?,
    val longitude: Double?,
    val isPrivate: Boolean,
    /** Tucked away: the phone's search leaves it out, as the server's does. */
    val isTucked: Boolean = false,
)

/**
 * A file marked private, while "Show private media" is off: only what hides this phone's own copy of it (its sha256,
 * else its name and size, as the library matches them).
 */
data class PrivateFile(
    val sha256: String,
    val fileName: String,
    val size: Long,
    /** The JPEG it was kept from, when it is a JPEG XL ([ServerMedia.encodedSha256]): the phone's copy of that JPEG is hidden too. */
    val encodedSha256: String? = null,
)

/** One page of the server's change feed, as [ServerMediaStore.apply] writes it. */
internal class FeedPage(
    /** Files added or changed, private ones included: [ServerMediaStore.apply] keeps only their keys while hidden. */
    val media: List<ServerMedia>,
    /** Files gone from the list: deleted, or hidden by the user (see [HiddenCollections]). */
    val gone: List<String>,
    /** Files the user now hides, and those shown again. */
    val hidden: List<String>,
    val shownAgain: List<String>,
    val cursor: String?,
    /**
     * Whether the page says which files are tucked away (a server with serverInfo.tuckedAway): from an older one, a
     * file keeps the flag its row had.
     */
    val knowsTucked: Boolean = true,
)

/** What [ServerMediaStore.apply] changed. */
internal class Applied(val mediaChanged: Boolean, val hiddenChanged: Boolean, val removed: List<String>)

/**
 * The server's list in the library database (docs/plans/library-on-disk.md): its files, the ones the user hides,
 * the private ones' keys while they are hidden ([PrivateFile]), and the change feed's cursor. Each page of the feed is
 * written in one transaction with its cursor, so the cursor and the rows always agree. A cache: losing it means
 * reading the feed again. Blocking: call off the main thread.
 */
internal class ServerMediaStore(private val database: LibraryDatabase) {
    private val queries get() = database.serverQueries
    private val state get() = database.stateQueries

    fun media(): List<ServerMedia> = queries.allMedia().executeAsList().map { it.toServerMedia() }

    /** The file [sha256]; null when the list has none. */
    fun file(sha256: String): ServerMedia? = queries.mediaWithSha(sha256).executeAsOneOrNull()?.toServerMedia()

    /** The file the server kept from the file [sha256] (a JPEG XL of a JPEG); null when there is none. */
    fun shaKeptFrom(sha256: String): String? = queries.shaKeptFrom(sha256).executeAsOneOrNull()

    /** The first file written with [fileName] and [size], its case aside; null when there is none. */
    fun shaNamedAs(fileName: String, size: Long): String? = queries.shaNamedAs(name = fileName, size = size).executeAsOneOrNull()

    /** The sound recordings, in the order [media] lists them. */
    fun sounds(): List<ServerMedia> = queries.audioMedia().executeAsList().map { it.toServerMedia() }

    /**
     * Gives [each] the few facts of every file, one row at a time from a single query, so the whole list is never in
     * memory at once (docs/plans/library-on-disk.md, step 6e).
     */
    fun forEachFile(each: (FileFacts) -> Unit) {
        val query = queries.mediaFacts { sha256, takenAt, uploadedAt, latitude, longitude, isPrivate, isTucked ->
            FileFacts(sha256, takenAt, uploadedAt, latitude, longitude, isPrivate, isTucked)
        }
        query.execute { cursor ->
            while (cursor.next().value) each(query.mapper(cursor))
            QueryResult.Unit
        }
    }

    fun hidden(): Set<String> = queries.allHidden().executeAsList().toSet()

    /** The server's names of the folder sources, by id ("folder:calls" to "Calls"), as last kept. */
    fun folderNames(): Map<String, String> = queries.allFolderNames().executeAsList().associate { it.id to it.name }

    /** Keeps [names] in place of the names kept before. */
    fun keepFolderNames(names: Map<String, String>) = database.transaction {
        queries.clearFolderNames()
        names.forEach { (id, name) -> queries.addFolderName(id, name) }
    }

    fun privateFiles(): List<PrivateFile> = queries.allPrivate().executeAsList().map { PrivateFile(it.sha256, it.file_name, it.size, it.encoded_sha256) }

    fun cursor(): String? = state.stateValue(CURSOR).executeAsOneOrNull()

    /** Whether the rows hold the private files ("Show private media" was on when they were written); null before [holdPrivate]. */
    fun holdsPrivate(): Boolean? = state.stateValue(HOLDS_PRIVATE).executeAsOneOrNull()?.let { it == "true" }

    /**
     * Writes one page and its cursor. A private file is kept as a row when [showPrivate], else as its key only; a file
     * that turns private while hidden leaves the rows, and so does one deleted.
     */
    fun apply(page: FeedPage, showPrivate: Boolean): Applied = database.transactionWithResult {
        var mediaChanged = false
        var hiddenChanged = false
        val removed = mutableListOf<String>()
        page.media.forEach { received ->
            val media = if (page.knowsTucked) received else received.copy(isTucked = queries.mediaWithSha(received.sha256).executeAsOneOrNull()?.is_tucked == true)
            if (media.isPrivate && !showPrivate) {
                queries.addPrivate(media.sha256, media.fileName, media.size, media.encodedSha256)
                if (queries.deleteMedia(media.sha256).value > 0) mediaChanged = true
            } else {
                queries.removePrivate(media.sha256)
                queries.upsertMedia(media.toRow())
                mediaChanged = true
            }
        }
        page.gone.forEach { sha ->
            queries.removePrivate(sha)
            if (queries.deleteMedia(sha).value > 0) {
                mediaChanged = true
                removed += sha
            }
        }
        page.hidden.forEach { if (queries.addHidden(it).value > 0) hiddenChanged = true }
        page.shownAgain.forEach { if (queries.removeHidden(it).value > 0) hiddenChanged = true }
        setCursor(page.cursor)
        Applied(mediaChanged, hiddenChanged, removed)
    }

    /**
     * "Show private media" turned off: the private files' rows become keys. Turned on: the keys are dropped and the
     * cursor too, so the next sync reads the whole feed again and brings their rows back.
     */
    fun holdPrivate(show: Boolean) = database.transaction {
        if (show) {
            queries.clearPrivate()
            state.clearState(CURSOR)
        } else {
            queries.privateMediaToKeys()
            queries.deletePrivateMedia()
        }
        state.setState(HOLDS_PRIVATE, show.toString())
    }

    /** Replaces everything with [media], [hidden] and [cursor]: the import of `server/media.json`. */
    fun replaceAll(media: List<ServerMedia>, hidden: Collection<String>, cursor: String?, showPrivate: Boolean) = database.transaction {
        queries.clearMedia()
        queries.clearHidden()
        queries.clearPrivate()
        media.forEach { item ->
            if (item.isPrivate && !showPrivate) queries.addPrivate(item.sha256, item.fileName, item.size, item.encodedSha256) else queries.upsertMedia(item.toRow())
        }
        hidden.forEach { queries.addHidden(it) }
        setCursor(cursor)
        state.setState(HOLDS_PRIVATE, showPrivate.toString())
    }

    /**
     * Keeps the made-up files ([madeUp], debug builds: MadeUpLibrary) beside the server's own: written when their number
     * changed, all deleted when there are none.
     */
    fun keepMadeUp(madeUp: List<ServerMedia>) = database.transaction {
        if (state.stateValue(MADE_UP).executeAsOneOrNull() == madeUp.size.toString()) return@transaction
        queries.deleteMadeUpMedia()
        madeUp.forEach { queries.upsertMedia(it.toRow()) }
        state.setState(MADE_UP, madeUp.size.toString())
    }

    private fun setCursor(cursor: String?) {
        if (cursor == null) state.clearState(CURSOR) else state.setState(CURSOR, cursor)
    }

    private companion object {
        const val CURSOR = "server_cursor"
        const val HOLDS_PRIVATE = "server_holds_private"
        const val MADE_UP = "server_made_up"
    }
}

private fun ServerMedia.toRow() = Server_media(
    sha256 = sha256,
    is_video = isVideo,
    is_audio = isAudio,
    file_name = fileName,
    mime_type = mimeType,
    size = size,
    taken_at = takenAt,
    taken_millis = (takenAt?.let(::parseTimestamp) ?: parseTimestamp(uploadedAt))?.millis ?: 0,
    width = width?.toLong(),
    height = height?.toLong(),
    duration_millis = durationMillis,
    latitude = latitude,
    longitude = longitude,
    source = source,
    uploaded_at = uploadedAt,
    nudity = nudity,
    is_private = isPrivate,
    aesthetic = aesthetic,
    is_call = isCall,
    call_contact = callContact,
    call_number = callNumber,
    uploaded_by = uploadedBy,
    screenshot_app = screenshotApp,
    camera_make = cameraMake,
    camera_model = cameraModel,
    encoded_sha256 = encodedSha256,
    is_tucked = isTucked,
)

private fun Server_media.toServerMedia() = ServerMedia(
    sha256 = sha256,
    isVideo = is_video,
    fileName = file_name,
    mimeType = mime_type,
    size = size,
    takenAt = taken_at,
    width = width?.toInt(),
    height = height?.toInt(),
    durationMillis = duration_millis,
    latitude = latitude,
    longitude = longitude,
    source = source,
    uploadedAt = uploaded_at,
    nudity = nudity,
    isPrivate = is_private,
    aesthetic = aesthetic,
    isAudio = is_audio,
    isCall = is_call,
    callContact = call_contact,
    callNumber = call_number,
    uploadedBy = uploaded_by,
    screenshotApp = screenshot_app,
    cameraMake = camera_make,
    cameraModel = camera_model,
    encodedSha256 = encoded_sha256,
    isTucked = is_tucked,
)
