package com.chencohensolution.journal.stories

import com.chencohensolution.journal.db.LibraryDatabase
import com.chencohensolution.journal.server.NoteAnswer
import com.chencohensolution.journal.server.ServerLibrary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Instant

/*
 * Notes synced with the server (asked for by the owner on Nov 8, 2026: "sync everything with the server"). A photo's
 * note (its title and paragraph with their history, tags, crops, Moments sets and the photo a set opens on, whether
 * it is on the storyline, where the viewer was zoomed) is kept on the phone as journal/media/<id>.json (PhotoNote) and
 * on the server in the sidecar of the photo's file (setNote, Media.note in the change feed). See docs/design.md
 * "Notes".
 *
 * - Out: a note changed here goes in the database's outbox (note_outbox); once its photo is on the server, its file's
 *   JSON (without the photo's id, name and time) is sent with the version last had from the server. A note the
 *   server changed meanwhile comes back instead: it is merged with this phone's ([mergeNotes]) and sent again.
 * - In: the change feed keeps each file's note in server_media; a note whose version differs from the one last
 *   brought in (note_synced) replaces this phone's note of the photo the library shows for that file, or is merged
 *   with it when this phone's changed too (still in the outbox).
 * - The first time, every note already on the phone goes in the outbox.
 */

/**
 * Where [NoteSync] reads the server: whether it keeps notes ([enabled]), when its list changed ([changed]), and its
 * setNote ([send]).
 */
class NoteSyncSource internal constructor(
    internal val database: LibraryDatabase,
    internal val enabled: Flow<Boolean>,
    internal val changed: Flow<Any?>,
    internal val send: suspend (sha256: String, note: String?, baseVersion: String?) -> NoteAnswer?,
    internal val io: CoroutineDispatcher,
) {
    companion object {
        /** The server of [server], its list kept in [database]. */
        fun of(database: LibraryDatabase, server: ServerLibrary, io: CoroutineDispatcher): NoteSyncSource = NoteSyncSource(
            database,
            enabled = server.features.map { it?.notes == true },
            changed = server.mediaVersion,
            send = server::setNote,
            io = io,
        )
    }
}

/** The photos whose notes changed here and are still to send (note_outbox). */
internal class NoteOutbox(private val database: LibraryDatabase) {
    private val _changes = MutableStateFlow(0L)

    /** Goes up with each [add], for [NoteSync] to send. */
    val changes: Flow<Long> get() = _changes

    /** Marks the notes of [ids] to send. Blocking, but small: one row each. */
    fun add(ids: Collection<String>) {
        if (ids.isEmpty()) return
        database.transaction { ids.forEach { database.notesQueries.addToOutbox(it) } }
        _changes.value++
    }
}

/**
 * Syncs [stories]' notes with the server of [source]: after a change here (a few seconds later, so a burst of changes is
 * one), and after the server's list or the library changed. Runs on [scope]; the database is read on the source's io.
 */
@OptIn(FlowPreview::class)
internal class NoteSync(
    private val source: NoteSyncSource,
    private val stories: StoriesController,
    private val outbox: NoteOutbox,
    private val scope: CoroutineScope,
    /** Library changes: rows the library shows for new server files, which incoming notes go to. */
    private val libraryChanged: Flow<Any?>,
) {
    private val queries get() = source.database.notesQueries
    private val state get() = source.database.stateQueries
    private val mutex = Mutex()
    private var enabled = false

    fun start() {
        scope.launch {
            combine(source.enabled, merge(outbox.changes, source.changed, libraryChanged)) { on, _ -> on }
                .debounce(SETTLE_MILLIS)
                .collect { on ->
                    enabled = on
                    if (on) runCatching { syncNow() }.onFailure { if (it is CancellationException) throw it }
                }
        }
    }

    /** One pass: the server's notes brought in, then this phone's sent. Offline, what is left waits for the next. */
    suspend fun syncNow() = mutex.withLock {
        seed()
        bringIn()
        sendOut()
    }

    /** The first time: every note already on this phone is to send. */
    private suspend fun seed() {
        val seeded = withContext(source.io) { state.stateValue(SEEDED).executeAsOneOrNull() != null }
        if (seeded) return
        val ids = stories.noteIds()
        withContext(source.io) {
            source.database.transaction {
                ids.forEach { queries.addToOutbox(it) }
                state.setState(SEEDED, "true")
            }
        }
    }

    private suspend fun bringIn() {
        val incoming = withContext(source.io) { queries.incoming().executeAsList() }
        for (row in incoming) {
            // Not shown by the library (yet): brought in once it is.
            val id = row.item_id ?: continue
            val pending = withContext(source.io) { queries.isInOutbox(id).executeAsOne() > 0 }
            if (pending) stories.mergeFromServer(id, row.note) else stories.noteFromServer(id, row.note)
            withContext(source.io) { queries.keepSynced(row.sha256, row.note_version, id) }
        }
    }

    private suspend fun sendOut() {
        val waiting = withContext(source.io) { queries.outbox().executeAsList() }
        for (entry in waiting) {
            // Its photo isn't on the server yet: sent once backup uploaded it.
            val sha = entry.server_sha256 ?: continue
            for (attempt in 1..MAX_ATTEMPTS) {
                val base = withContext(source.io) { queries.syncedVersion(sha).executeAsOneOrNull()?.version }
                val answer = source.send(sha, stories.syncContentOf(entry.id), base) ?: return
                withContext(source.io) { queries.keepSynced(sha, answer.version, entry.id) }
                if (answer.saved) {
                    withContext(source.io) { queries.sent(entry.id, entry.change) }
                    break
                }
                // Changed on the server since: this phone's note and the server's made one, and sent again.
                stories.mergeFromServer(entry.id, answer.note)
            }
        }
    }

    private companion object {
        /** Changes closer together than this are sent together. */
        const val SETTLE_MILLIS = 3_000L

        /** A note changed on another device again and again while this one sends: tried again at the next pass. */
        const val MAX_ATTEMPTS = 3

        const val SEEDED = "notes_seeded"
    }
}

/**
 * This phone's note [local] and the server's [server] of the same photo, both changed since they were last the same,
 * made one: nothing written is lost. The writing saved last is the photo's, and the other becomes an earlier version
 * (the histories are joined); everything else (tags, crops, sets, the storyline, zoom) is the note changed last's.
 */
internal fun mergeNotes(local: PhotoNote, server: PhotoNote): PhotoNote {
    val localNewer = later(local.updatedAt, server.updatedAt)
    val newer = if (localNewer) local else server
    val writingNewer = if (later(local.writtenAt ?: local.updatedAt, server.writtenAt ?: server.updatedAt)) local else server
    val older = if (writingNewer === local) server else local
    val sameWriting = local.title == server.title && local.caption == server.caption
    val versions = local.history + server.history +
        (if (!sameWriting && older.hasWriting) listOf(NoteVersion(older.title, older.caption, older.writtenAt ?: older.updatedAt)) else emptyList())
    val history = versions.distinct().sortedBy { runCatching { Instant.parse(it.savedAt) }.getOrNull() ?: Instant.DISTANT_PAST }
    return newer.copy(
        deviceId = local.deviceId,
        fileName = local.fileName ?: server.fileName,
        takenAtMillis = local.takenAtMillis,
        title = writingNewer.title,
        caption = writingNewer.caption,
        writtenAt = writingNewer.writtenAt,
        history = history,
        updatedAt = if (localNewer) local.updatedAt else server.updatedAt,
    )
}

/** Whether RFC 3339 time [a] is after [b] (a time that can't be read is the earliest). */
private fun later(a: String, b: String): Boolean {
    val first = runCatching { Instant.parse(a) }.getOrNull() ?: Instant.DISTANT_PAST
    val second = runCatching { Instant.parse(b) }.getOrNull() ?: Instant.DISTANT_PAST
    return first > second
}
