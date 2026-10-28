@file:OptIn(ExperimentalAtomicApi::class)

package com.chencohensolution.journal.backup

import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.MadeUpLibrary
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.storage.ServerCheck
import com.chencohensolution.journal.storage.ServerVerdict
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSource
import okio.EOFException
import okio.HashingSink
import okio.Source
import okio.blackholeSink
import okio.buffer
import okio.use
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.coroutines.ContinuationInterceptor
import kotlin.coroutines.cancellation.CancellationException

/** The platform's read access to the photos' bytes. */
internal interface MediaFiles {
    /** The original bytes from [offset], unredacted (Android otherwise removes GPS from what an app reads). */
    fun open(item: MediaItem, offset: Long): Source

    /** When [item] was taken, RFC 3339 with the device's UTC offset at that moment. */
    fun takenAt(item: MediaItem): String

    /** The run is done reading [item] for now (hashed it, or its upload ended): what was copied to read it can go. */
    fun release(item: MediaItem) {}

    /** A run ended: what was copied out of the library to read it can go. */
    fun runEnded() {}

    /**
     * A FLAC of the WAV [item], finished being written, made to be sent in its place; null when the phone can't encode
     * it (the WAV is then sent as it is, and the server keeps it as FLAC).
     */
    suspend fun encodeFlac(item: MediaItem): EncodedFile? = null

    /**
     * Writes [flac] beside the WAV [item] in its folder and deletes the WAV, once the server confirmed the FLAC
     * ([KeepOnPhone.FLAC]); false when it couldn't (the WAV stays).
     */
    suspend fun replaceWithFlac(item: MediaItem, flac: EncodedFile): Boolean = false

    /**
     * A JPEG XL of the JPEG photo [item], packed losslessly and checked to make the very same JPEG again, made to be
     * sent in its place; null when the phone can't pack it, or packing saves nothing (the JPEG is then sent as it is,
     * and the server packs it).
     */
    suspend fun encodeJxl(item: MediaItem): EncodedFile? = null

    /**
     * Writes [jxl] into the JPEG [item]'s folder and moves the JPEG to the gallery's trash, once the server confirmed it
     * holds that very JPEG XL ([BackupSettings.keepJpegXlOnPhone]); false when it couldn't (the JPEG stays).
     */
    suspend fun replaceWithJxl(item: MediaItem, jxl: EncodedFile): Boolean = false
}

/**
 * Where a run is: [done] of [total] files finished, and the bytes of the files still to send. Several files are sent
 * at once ([uploading]); [currentId] is the earliest of them still on its way (or the file being read before any is).
 */
data class BackupProgress(
    val done: Int,
    val total: Int,
    /** Bytes of the finished files, and what has arrived of the ones on their way. */
    val bytesDone: Long,
    val bytesTotal: Long,
    /** The earliest file still on its way, else the file being read. */
    val currentId: String? = null,
    val currentName: String? = null,
    /** 0..1 of the current file. */
    val currentFraction: Float = 0f,
    /** Bytes actually sent this run (files the server already had count in [bytesDone], not here). */
    val bytesSent: Long = 0,
    /** How fast bytes leave, over the last few seconds; null until it is known. */
    val bytesPerSecond: Long? = null,
    /** The files on their way, by device id, in the order they started: 0..1 of each. */
    val uploading: Map<String, Float> = emptyMap(),
) {
    /** 0..1 of the whole run, by bytes. */
    val fraction: Float get() = if (bytesTotal > 0) (bytesDone.toFloat() / bytesTotal).coerceIn(0f, 1f) else 0f
}

data class BackupRunResult(val uploaded: Int, val alreadyOnServer: Int, val failed: Int)

/** A JPEG photo, which backup sends as JPEG XL (see [MediaFiles.encodeJxl]). */
internal val MediaItem.isJpegPhoto: Boolean
    get() = type == MediaType.IMAGE && when (val mime = mimeType?.lowercase()) {
        null -> fileName?.substringAfterLast('.', "")?.lowercase() in setOf("jpg", "jpeg")
        else -> mime == "image/jpeg" || mime == "image/jpg"
    }

/**
 * The files backup takes, in the order it takes them: the chosen sources' photos and the sound folders' files (listed
 * only for the folders backed up), oldest or newest first.
 */
internal fun backupQueue(items: List<MediaItem>, settings: BackupSettings): List<MediaItem> =
    items.filter { (it.source in settings.sources || SoundIds.isSound(it.id)) && (it.sizeBytes ?: 0) > 0 && !MadeUpLibrary.isMadeUp(it) }
        .let { scope ->
            when (settings.order) {
                UploadOrder.OLDEST_FIRST -> scope.sortedBy { it.takenAtMillis }
                UploadOrder.NEWEST_FIRST -> scope.sortedByDescending { it.takenAtMillis }
            }
        }

/**
 * One backup run: reads the photos not known to be on the server (sha256), asks the server which it lacks, and sends
 * those in pieces, continuing from where the server says it stopped. Several files go at once, as many as the
 * platform's sender takes ([BackupApi.maxFilesInFlight], [BackupApi.maxBytesInFlight]); they start in the queue's
 * order, and each file's pieces go one after another. A refused or changed file is skipped and tried again on the
 * next run. A network error starts no more files: the ones on their way finish, then it is thrown, so the caller can
 * retry later. A refused certificate stops everything at once.
 */
internal class BackupEngine(
    private val files: MediaFiles,
    private val ledger: UploadLedger,
    private val api: BackupApi,
    private val locate: suspend (MediaItem) -> GeoPoint? = { null },
    /** The server's check of a file it has, before a WAV kept as FLAC is deleted ([KeepOnPhone.FLAC]). */
    private val check: ServerCheck? = null,
    /**
     * JPEG photos go as the JPEG XL the phone packs of them ([MediaFiles.encodeJxl]): only to a server that checks such
     * a file makes the JPEG again ([com.chencohensolution.journal.server.ServerFeatures.keepsJpegAsJxl]).
     */
    private val packJpegs: Boolean = false,
    /** Where files are read. */
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    suspend fun run(items: List<MediaItem>, settings: BackupSettings, onProgress: (BackupProgress) -> Unit): BackupRunResult {
        val pending = backupQueue(items, settings).filterNot { ledger.isUploaded(it) }
        // The run's own bookkeeping happens on one thread at a time; the reading and sending go on elsewhere.
        val caller = currentCoroutineContext()[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.Default
        return withContext(caller.limitedParallelism(1)) { Run(pending, settings, onProgress).go() }
    }

    /** A file on its way: how far the server is, and how many bytes this run moved (not what it had before). */
    private class Sending(val item: MediaItem) {
        val offset = AtomicLong(0)
        val sent = AtomicLong(0)
        private var seen = -1L

        /** Called from the transport's callbacks, one piece at a time. */
        fun moved(to: Long) {
            if (seen < 0) seen = to
            if (to > seen) {
                sent.addAndFetch(to - seen)
                seen = to
            }
            offset.store(to)
        }

        val fraction: Float get() = offset.load().toFloat() / (item.sizeBytes ?: 1).coerceAtLeast(1)
    }

    private inner class Run(
        private val pending: List<MediaItem>,
        private val settings: BackupSettings,
        private val onProgress: (BackupProgress) -> Unit,
    ) {
        private val bytesTotal = pending.sumOf { it.sizeBytes ?: 0 }
        private var done = 0
        private var finishedBytes = 0L
        private var finishedSent = 0L
        private var uploaded = 0
        private var already = 0
        private var failed = 0
        private var reading: MediaItem? = null
        private val sending = LinkedHashMap<String, Sending>()
        private var sendingBytes = 0L

        /** Files of this run by sha256: the same bytes under two device ids are sent once. */
        private val bySha = mutableMapOf<String, Deferred<Outcome?>>()

        /** The first network or server error; no file starts after it. */
        private var error: Throwable? = null
        private val slotFreed = Channel<Unit>(Channel.CONFLATED)
        private val progressQueued = AtomicBoolean(false)
        private lateinit var scope: CoroutineScope

        suspend fun go(): BackupRunResult {
            emit()
            coroutineScope {
                scope = this
                try {
                    produce()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    stopStarting(e)
                }
            }
            error?.let { throw it }
            return BackupRunResult(uploaded, already, failed)
        }

        /** Reads the queue in batches of [BATCH] and starts the files the server lacks, in order, as slots free up. */
        private suspend fun produce() {
            for (batch in pending.chunked(BATCH)) {
                val entries = batch.mapNotNull { item ->
                    if (error != null) return
                    read(item)?.let { item to it }
                }
                reading = null
                val missing = api.missing(entries.map { it.second.sha256 }.filter { it !in bySha }.distinct())
                for ((item, entry) in entries) {
                    if (error != null) return
                    val earlier = bySha[entry.sha256]
                    when {
                        earlier != null -> sameBytes(item, entry, earlier)
                        entry.sha256 !in missing -> {
                            ledger.record(entry.copy(uploaded = true, rejected = null))
                            finish(item, Outcome.ALREADY, null)
                        }
                        else -> {
                            awaitSlot(item.sizeBytes ?: 0)
                            if (error != null) return
                            start(item, entry)
                        }
                    }
                }
            }
        }

        /** The ledger's entry for [item], or null when it isn't the size the library said (counted as failed). */
        private suspend fun read(item: MediaItem): LedgerEntry? {
            reading = item
            emit()
            return try {
                hashed(item)
            } catch (e: EOFException) {
                // Not the size the library reported: changed since it was listed. Read again next time.
                ledger.forget(item.id)
                finish(item, Outcome.FAILED, null)
                null
            } finally {
                files.release(item)
            }
        }

        private suspend fun awaitSlot(size: Long) {
            while (sending.size >= api.maxFilesInFlight || (sending.isNotEmpty() && sendingBytes + size > api.maxBytesInFlight)) {
                slotFreed.receive()
                if (error != null) return
            }
        }

        private fun start(item: MediaItem, entry: LedgerEntry) {
            val file = Sending(item)
            val size = item.sizeBytes ?: 0
            sending[item.id] = file
            sendingBytes += size
            bySha[entry.sha256] = scope.async {
                val outcome = try {
                    upload(item, entry, settings) { offset ->
                        file.moved(offset)
                        progressChanged()
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: BackupServerException) {
                    if (e.unauthorized) throw e // Fails the run, which cancels the other files.
                    stopStarting(e)
                    null
                } catch (e: Exception) {
                    stopStarting(e)
                    null
                } finally {
                    files.release(item)
                }
                sending.remove(item.id)
                sendingBytes -= size
                slotFreed.trySend(Unit)
                finish(item, outcome, file)
                outcome
            }
            emit()
        }

        /** [item] has the bytes of a file this run already sends: it is on the server once that one is. */
        private fun sameBytes(item: MediaItem, entry: LedgerEntry, earlier: Deferred<Outcome?>) {
            scope.launch {
                val outcome = when (earlier.await()) {
                    Outcome.UPLOADED, Outcome.ALREADY -> Outcome.ALREADY.also { ledger.record(entry.copy(uploaded = true, rejected = null)) }
                    Outcome.FAILED -> Outcome.FAILED
                    null -> null
                }
                finish(item, outcome, null)
            }
        }

        /** [outcome] is null when the file stopped on a network error: it doesn't count as done. */
        private fun finish(item: MediaItem, outcome: Outcome?, file: Sending?) {
            finishedSent += file?.sent?.load() ?: 0
            when (outcome) {
                Outcome.UPLOADED -> uploaded++
                Outcome.ALREADY -> already++
                Outcome.FAILED -> failed++
                null -> Unit
            }
            if (outcome != null) {
                done++
                finishedBytes += item.sizeBytes ?: 0
            }
            emit()
        }

        private fun stopStarting(e: Throwable) {
            if (error == null) error = e
            slotFreed.trySend(Unit)
        }

        /** From the transport's threads: reports progress once the run's thread is free, many callbacks in one. */
        private fun progressChanged() {
            if (progressQueued.compareAndSet(false, true)) {
                scope.launch {
                    progressQueued.store(false)
                    emit()
                }
            }
        }

        private fun emit() {
            val first = sending.values.firstOrNull()
            val current = first?.item ?: reading
            onProgress(
                BackupProgress(
                    done = done,
                    total = pending.size,
                    bytesDone = finishedBytes + sending.values.sumOf { it.offset.load() },
                    bytesTotal = bytesTotal,
                    currentId = current?.id,
                    currentName = current?.fileName,
                    currentFraction = first?.fraction ?: 0f,
                    bytesSent = finishedSent + sending.values.sumOf { it.sent.load() },
                    uploading = sending.mapValues { it.value.fraction },
                ),
            )
        }
    }

    /** The ledger's entry for [item] as it is now, reading the file when it is new or changed. */
    private suspend fun hashed(item: MediaItem): LedgerEntry {
        val previous = ledger.entry(item.id)
        if (previous != null && previous.matches(item)) return previous
        val sha = files.sha256(item, io)
        // Same device photo, new bytes: an edit saved in place. It points at the version the server has.
        val versionOf = previous?.let { if (it.uploaded) it.sha256 else it.versionOf }?.takeIf { it != sha }
        return LedgerEntry(id = item.id, size = item.sizeBytes ?: 0, modifiedMillis = item.modifiedMillis, sha256 = sha, versionOf = versionOf)
            .also { ledger.record(it) }
    }

    private enum class Outcome { UPLOADED, ALREADY, FAILED }

    /** What a run sends for a file: the file as it is, or a WAV's FLAC. */
    private class Payload(
        val sha256: String,
        val size: Long,
        val fileName: String,
        val mimeType: String,
        val open: (offset: Long) -> Source,
        val encodedFrom: EncodedFrom? = null,
    )

    private fun asItIs(item: MediaItem, entry: LedgerEntry) = Payload(
        sha256 = entry.sha256,
        size = entry.size,
        fileName = item.fileName ?: item.id,
        mimeType = item.mimeType ?: when (item.type) {
            MediaType.VIDEO -> "video/mp4"
            MediaType.AUDIO -> audioMimeType(item.fileName)
            MediaType.IMAGE -> "image/jpeg"
        },
        open = { offset -> files.open(item, offset) },
    )

    /**
     * Sends [item], which the server lacks. A WAV goes as the FLAC the phone makes of it, with the WAV's sha256 as
     * `encodedFrom` (the server never keeps a WAV); then, with [KeepOnPhone.FLAC], the FLAC replaces the WAV on the phone
     * once the server confirms it.
     */
    private suspend fun upload(item: MediaItem, entry: LedgerEntry, settings: BackupSettings, onSent: (Long) -> Unit): Outcome {
        if (packJpegs && item.isJpegPhoto) return uploadJpeg(item, entry, settings, onSent)
        if (!item.isWav) return send(item, entry, asItIs(item, entry), settings, onSent)
        val flac = try {
            withContext(io) { files.encodeFlac(item) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ledger.record(entry.copy(rejected = "Couldn't make a FLAC of it: ${e.message}"))
            return Outcome.FAILED
        } ?: return send(item, entry, asItIs(item, entry), settings, onSent)
        try {
            val wav = asItIs(item, entry)
            val payload = Payload(
                sha256 = flac.sha256,
                size = flac.size,
                fileName = flac.fileName,
                mimeType = "audio/flac",
                open = flac.open,
                encodedFrom = EncodedFrom(sha256 = entry.sha256, mimeType = wav.mimeType, size = entry.size),
            )
            val outcome = send(item, entry, payload, settings, onSent)
            if (outcome != Outcome.FAILED && settings.keepOnPhone == KeepOnPhone.FLAC) {
                // A check that can't be made leaves the WAV: never deleted without the server's word.
                val verdict = runCatching { check?.verify(flac.sha256) }.getOrNull()
                if (verdict == ServerVerdict.OK) files.replaceWithFlac(item, flac)
            }
            return outcome
        } finally {
            flac.discard()
        }
    }

    /**
     * Sends the JPEG photo [item] as the JPEG XL the phone packs of it, with the JPEG's sha256, type, size and name as
     * `encodedFrom`: the server rebuilds the JPEG from it and takes it only when that is the very same JPEG, and keeps
     * the JPEG's sha256 so the phone's JPEG and the server's JPEG XL are known as one photo. The ledger keeps the JPEG's
     * sha256, as for any file. When the phone can't pack it, or the server refuses the JPEG XL, the JPEG goes as it is
     * (the server packs it itself).
     */
    private suspend fun uploadJpeg(item: MediaItem, entry: LedgerEntry, settings: BackupSettings, onSent: (Long) -> Unit): Outcome {
        val jpeg = asItIs(item, entry)
        val jxl = try {
            withContext(io) { files.encodeJxl(item) }
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return send(item, entry, jpeg, settings, onSent)
        try {
            val payload = Payload(
                sha256 = jxl.sha256,
                size = jxl.size,
                fileName = jxl.fileName,
                mimeType = "image/jxl",
                open = jxl.open,
                encodedFrom = EncodedFrom(sha256 = entry.sha256, mimeType = jpeg.mimeType, size = entry.size, fileName = jpeg.fileName),
            )
            // A JPEG XL the server refused (its start, or 409 at the end when it doesn't make the JPEG) goes again as
            // the JPEG, now: it was hashed this run, and only the JPEG XL was read since.
            val outcome = send(item, entry, payload, settings, onSent)
            // Kept on the phone only when the server took these very bytes (one it packed itself may differ), and
            // confirms it holds them: a check that can't be made leaves the JPEG.
            if (outcome == Outcome.UPLOADED && settings.keepJpegXlOnPhone) {
                val verdict = runCatching { check?.verify(jxl.sha256) }.getOrNull()
                if (verdict == ServerVerdict.OK) files.replaceWithJxl(item, jxl)
            }
            if (outcome != Outcome.FAILED) return outcome
        } finally {
            jxl.discard()
        }
        return send(item, entry, jpeg, settings, onSent)
    }

    private suspend fun send(item: MediaItem, entry: LedgerEntry, payload: Payload, settings: BackupSettings, onSent: (Long) -> Unit): Outcome {
        val size = payload.size
        repeat(MAX_STARTS) {
            val answer = try {
                api.start(request(item, entry, payload, settings))
            } catch (e: BackupServerException) {
                if (e.unauthorized) throw e
                // The server refused this file's details (not the connection): skip it, the others go on.
                ledger.record(entry.copy(rejected = e.message ?: "refused"))
                return Outcome.FAILED
            }
            val start = when (val result = answer) {
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
            val chunk = (api.pieceSize ?: start.chunkSize).coerceIn(MIN_CHUNK, MAX_CHUNK)
            var offset = start.offset
            if (offset >= size) return Outcome.FAILED // The server has every byte but no file; nothing left to send.
            onSent(offset)
            val piece: PieceResult = try {
                withContext(io) {
                    // Read on from one source; opened again where the server is when a piece wasn't read from it (one
                    // already on its way) or the server is elsewhere.
                    var source: BufferedSource? = null
                    var position = -1L
                    try {
                        var last: PieceResult
                        do {
                            currentCoroutineContext().ensureActive()
                            if (source == null || position != offset) {
                                source?.close()
                                source = payload.open(offset).buffer()
                                position = offset
                            }
                            val from: BufferedSource = source
                            val length = minOf(chunk, size - offset)
                            val body = PieceBody(length) { sink ->
                                sink.write(from, length)
                                position += length
                            }
                            val pieceStart = offset
                            last = api.put(start.url, offset, body, size) { inPiece -> onSent(pieceStart + inPiece) }
                            if (last is PieceResult.Accepted) {
                                offset = last.offset
                                onSent(offset)
                            }
                        } while (last is PieceResult.Accepted && !last.complete && offset < size)
                        last
                    } finally {
                        source?.close()
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

    private suspend fun request(item: MediaItem, entry: LedgerEntry, payload: Payload, settings: BackupSettings): UploadRequest {
        val soundFolder = settings.soundFolders.firstOrNull { it.treeUri == SoundIds.treeOf(item.id) }
        return request(item, entry, payload, soundFolder)
    }

    /**
     * A sound folder's file ([soundFolder]) is sent with the folder's name as the phone shows it ("Call", as in Backup ›
     * sound folders) as its folder, so the server's Settings › Sound folders lists the phone's folders beside the
     * imported ones, and with its kind as the source (call, recording) until the user merges the folder otherwise.
     */
    private suspend fun request(item: MediaItem, entry: LedgerEntry, payload: Payload, soundFolder: SoundFolder?) = UploadRequest(
        sha256 = payload.sha256,
        size = payload.size,
        fileName = payload.fileName,
        mimeType = payload.mimeType,
        kind = item.type,
        takenAt = files.takenAt(item),
        width = item.width.takeIf { it > 0 },
        height = item.height.takeIf { it > 0 },
        durationMillis = item.durationMillis,
        location = item.location ?: runCatching { locate(item) }.getOrNull(),
        source = item.source.serverName,
        folder = soundFolder?.name?.takeIf { it.isNotBlank() } ?: item.folder,
        // A sound file changed in place is a new recording, not an edit.
        versionOf = entry.versionOf.takeIf { item.type != MediaType.AUDIO },
        isCall = soundFolder?.kind == AudioKind.CALL,
        encodedFrom = payload.encodedFrom,
    )

    private companion object {
        /** Hashes asked about in one missingMedia call: as many as the server takes (journal-server-go maxHashesPerQuery). */
        const val BATCH = 1000
        const val MIN_CHUNK = 256L shl 10
        /** The largest piece the server takes (journal-server-go media/upload.go MaxChunkSize). */
        const val MAX_CHUNK = 64L shl 20

        /** startUpload calls per file in one run (a lost upload, or a piece the server placed elsewhere). */
        const val MAX_STARTS = 3
    }
}

/** The sha256 of [item]'s bytes as they are now, read from the start on [io]. */
internal suspend fun MediaFiles.sha256(item: MediaItem, io: CoroutineDispatcher = Dispatchers.IO): String = withContext(io) {
    val hashing = HashingSink.sha256(blackholeSink())
    val buffer = Buffer()
    open(item, 0).use { source ->
        while (source.read(buffer, READ_SIZE) != -1L) {
            hashing.write(buffer, buffer.size)
            currentCoroutineContext().ensureActive()
        }
    }
    hashing.hash.hex()
}

private const val READ_SIZE = 1L shl 20

/**
 * How the server is told a source: a folder spelled as the phone saw it ("folder:Petcube"), which the server reads in
 * lower case and counts towards the name it shows the folder by.
 */
internal val MediaSource.serverName: String get() = spelledId
