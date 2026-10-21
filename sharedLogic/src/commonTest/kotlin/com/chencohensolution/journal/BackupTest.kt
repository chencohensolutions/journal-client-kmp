package com.chencohensolution.journal

import com.apollographql.apollo.ApolloClient
import com.chencohensolution.journal.backup.BackupApi
import com.chencohensolution.journal.backup.BackupServerException
import com.chencohensolution.journal.backup.InFlightPieces
import com.chencohensolution.journal.backup.PieceResponse
import com.chencohensolution.journal.backup.PieceSender
import com.chencohensolution.journal.backup.ServerBackupApi
import com.chencohensolution.journal.backup.pieceResult
import kotlinx.coroutines.async
import com.chencohensolution.journal.backup.BackupEngine
import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.backup.LedgerEntry
import com.chencohensolution.journal.backup.MediaFiles
import com.chencohensolution.journal.backup.PieceResult
import com.chencohensolution.journal.backup.StartResult
import com.chencohensolution.journal.backup.TransferRate
import com.chencohensolution.journal.backup.UploadLedger
import com.chencohensolution.journal.backup.UploadOrder
import com.chencohensolution.journal.backup.UploadRequest
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.Source
import okio.fakefilesystem.FakeFileSystem
import okio.use
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

private class FakeFiles : MediaFiles {
    val bytes = mutableMapOf<String, ByteArray>()
    override fun open(item: MediaItem, offset: Long): Source =
        Buffer().write(bytes.getValue(item.id).copyOfRange(offset.toInt(), bytes.getValue(item.id).size))

    override fun takenAt(item: MediaItem) = "2026-10-17T15:52:22+03:00"
}

/** The server's upload rules (journal-server-go media/upload.go), in memory. */
private class FakeServer : BackupApi {
    val stored = mutableMapOf<String, ByteArray>()
    val requests = mutableMapOf<String, UploadRequest>()
    private val partial = mutableMapOf<String, Buffer>()
    var starts = 0

    /** startUpload answers with a GraphQL error for these file names (as gqlgen does for a bad time). */
    val refusedNames = mutableSetOf<String>()

    /** Throws (a dropped connection) when this many pieces have arrived in total. */
    var dropAfterPieces = Int.MAX_VALUE
    private var pieces = 0

    /**
     * Stores this many pieces in total and then loses the answer to the next one, which it stores as well: a piece an
     * iOS background session finished while the app was gone.
     */
    var answerLostAfterPieces = Int.MAX_VALUE

    /** Every byte that arrived, also twice. */
    var bytesReceived = 0L

    override suspend fun missing(hashes: List<String>) = hashes.filterNot { it in stored }.toSet()

    override suspend fun start(request: UploadRequest): StartResult {
        starts++
        if (request.fileName in refusedNames) throw com.chencohensolution.journal.backup.BackupServerException("time should be RFC3339Nano formatted string")
        if (request.sha256 in stored) return StartResult.Exists
        if (request.versionOf != null && request.versionOf !in stored) return StartResult.Rejected("UNKNOWN_ORIGINAL")
        requests[request.sha256] = request
        val received = partial.getOrPut(request.sha256) { Buffer() }
        return StartResult.Ready("/upload/${request.sha256}", received.size, CHUNK)
    }

    override suspend fun put(url: String, start: Long, bytes: ByteArray, total: Long, onSent: (Long) -> Unit): PieceResult {
        val sha = url.removePrefix("/upload/")
        val received = partial[sha] ?: return PieceResult.NoUpload
        if (start != received.size) return PieceResult.WrongOffset(received.size)
        if (++pieces > dropAfterPieces) throw okio.IOException("connection reset")
        received.write(bytes)
        bytesReceived += bytes.size
        if (pieces > answerLostAfterPieces) throw kotlinx.coroutines.CancellationException("the app was suspended")
        onSent(bytes.size.toLong())
        if (received.size < total) return PieceResult.Accepted(received.size, complete = false)
        val content = received.readByteArray()
        partial.remove(sha)
        if (content.toByteString().sha256().hex() != sha) return PieceResult.HashMismatch
        stored[sha] = content
        return PieceResult.Accepted(total, complete = true)
    }

    companion object {
        const val CHUNK = 256L * 1024
    }
}

private val DAY = LocalDay(2026, 10, 17)

private fun photo(id: String, takenAt: Long, size: Int, source: MediaSource = MediaSource.CAMERA, modified: Long = 1) =
    MediaItem(id, MediaType.IMAGE, takenAt, DAY, null, 4000, 3000, size.toLong(), "IMG_$id.jpg", source = source, modifiedMillis = modified)

private fun content(seed: Int, size: Int) = ByteArray(size) { ((it * 31 + seed) % 251).toByte() }

private fun sha(bytes: ByteArray) = bytes.toByteString().sha256().hex()

class BackupTest {
    private val fs = FakeFileSystem()
    private val files = FakeFiles()
    private val server = FakeServer()
    private fun ledger() = UploadLedger(fs, "/backup".toPath())

    private fun add(item: MediaItem, seed: Int): MediaItem {
        files.bytes[item.id] = content(seed, item.sizeBytes!!.toInt())
        return item
    }

    @Test
    fun uploadsTheChosenSourcesOldestFirstInPiecesAndOnlyOnce() = runTest {
        val newer = add(photo("2", takenAt = 2_000, size = 600_000), seed = 2)
        val older = add(photo("1", takenAt = 1_000, size = 300_000), seed = 1)
        val whatsapp = add(photo("3", takenAt = 500, size = 1_000, source = MediaSource.WHATSAPP), seed = 3)
        val ledger = ledger()
        val engine = BackupEngine(files, ledger, server)
        val names = mutableListOf<String?>()

        val result = engine.run(listOf(newer, older, whatsapp), BackupSettings(enabled = true)) { p -> p.currentName?.let { if (names.lastOrNull() != it) names += it } }

        assertEquals(2, result.uploaded)
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg"), names.distinct())
        assertContentEquals(files.bytes["2"], server.stored[sha(files.bytes.getValue("2"))])
        assertEquals("camera", server.requests.values.first().source)
        assertTrue(ledger.isUploaded(older) && ledger.isUploaded(newer))

        val again = BackupEngine(files, ledger(), server).run(listOf(newer, older), BackupSettings(enabled = true)) {}
        assertEquals(0, again.uploaded + again.alreadyOnServer + again.failed)
    }

    @Test
    fun newestFirstWhenChosen() = runTest {
        val older = add(photo("1", takenAt = 1_000, size = 10), seed = 1)
        val newer = add(photo("2", takenAt = 2_000, size = 10), seed = 2)
        val order = mutableListOf<String>()
        BackupEngine(files, ledger(), server).run(listOf(older, newer), BackupSettings(enabled = true, order = UploadOrder.NEWEST_FIRST)) { p ->
            p.currentId?.let { if (it !in order) order += it }
        }
        assertEquals(listOf("2", "1"), order)
    }

    @Test
    fun filesTheServerAlreadyHasAreNotSent() = runTest {
        val item = add(photo("1", takenAt = 1_000, size = 1_000), seed = 1)
        server.stored[sha(files.bytes.getValue("1"))] = files.bytes.getValue("1")
        val result = BackupEngine(files, ledger(), server).run(listOf(item), BackupSettings(enabled = true)) {}
        assertEquals(1, result.alreadyOnServer)
        assertEquals(0, server.starts)
    }

    @Test
    fun aDroppedConnectionContinuesWhereTheServerStopped() = runTest {
        val video = add(photo("1", takenAt = 1_000, size = 1_000_000), seed = 7)
        server.dropAfterPieces = 2
        assertFailsWith<okio.IOException> { BackupEngine(files, ledger(), server).run(listOf(video), BackupSettings(enabled = true)) {} }

        server.dropAfterPieces = Int.MAX_VALUE
        val result = BackupEngine(files, ledger(), server).run(listOf(video), BackupSettings(enabled = true)) {}
        assertEquals(1, result.uploaded)
        assertContentEquals(files.bytes["1"], server.stored[sha(files.bytes.getValue("1"))])
    }

    @Test
    fun anEditSavedInPlaceUploadsAsANewVersion() = runTest {
        val original = add(photo("1", takenAt = 1_000, size = 500), seed = 1)
        val ledger = ledger()
        BackupEngine(files, ledger, server).run(listOf(original), BackupSettings(enabled = true)) {}
        val originalSha = sha(files.bytes.getValue("1"))

        val edited = add(original.copy(sizeBytes = 520, modifiedMillis = 2), seed = 9)
        val result = BackupEngine(files, ledger, server).run(listOf(edited), BackupSettings(enabled = true)) {}
        assertEquals(1, result.uploaded)
        assertEquals(originalSha, server.requests.getValue(sha(files.bytes.getValue("1"))).versionOf)
    }

    @Test
    fun statusesComeFromTheLedgerAndTheRun() = runTest {
        val item = photo("1", takenAt = 1_000, size = 500)
        val entry = LedgerEntry(id = "1", size = 500, modifiedMillis = 1, sha256 = "a".repeat(64), uploaded = true)
        assertEquals(BackupStatus.BackedUp, BackupState(entries = mapOf("1" to entry)).status(item))
        assertEquals(BackupStatus.NotBackedUp, BackupState(entries = mapOf("1" to entry)).status(item.copy(modifiedMillis = 5)))
        assertEquals(BackupStatus.Failed("NO_SPACE"), BackupState(entries = mapOf("1" to entry.copy(uploaded = false, rejected = "NO_SPACE"))).status(item))
        assertEquals(BackupStatus.Queued, BackupState(queued = setOf("1")).status(item))
        assertNull(BackupState(settings = BackupSettings(enabled = false)).countedSources)
        assertEquals(setOf(MediaSource.CAMERA), BackupState(settings = BackupSettings(enabled = true)).countedSources)
    }

    @Test
    fun theLedgerSurvivesARestartAndSkipsDamagedLines() = runTest {
        val first = ledger()
        first.record(LedgerEntry(id = "1", size = 5, modifiedMillis = 1, sha256 = "a".repeat(64)))
        first.record(LedgerEntry(id = "1", size = 5, modifiedMillis = 1, sha256 = "a".repeat(64), uploaded = true))
        fs.appendingSink("/backup/ledger.jsonl".toPath()).use { sink -> Buffer().writeUtf8("{not json\n").let { sink.write(it, it.size) } }
        first.record(LedgerEntry(id = "2", size = 7, modifiedMillis = null, sha256 = "b".repeat(64)))

        val reopened = ledger()
        assertEquals(true, reopened.entry("1")?.uploaded)
        assertEquals("b".repeat(64), reopened.entry("2")?.sha256)
        reopened.forget("2")
        assertNull(ledger().entry("2"))
    }

    @Test
    fun theRateIsOverTheLastFewSeconds() {
        val clock = TestTimeSource()
        val rate = TransferRate(clock::markNow)
        assertNull(rate.add(0))
        clock += 200.milliseconds
        assertNull(rate.add(1_000)) // too soon to tell
        repeat(10) { i ->
            clock += 1.seconds
            rate.add(2_000_000L * (i + 1))
        }
        // 2 MB a second, measured over the last 5 seconds only.
        assertEquals(2_000_000L, rate.add(20_000_000L)?.let { (it + 50_000) / 100_000 * 100_000 })
    }

    @Test
    fun progressCountsOnlyBytesSent() = runTest {
        val sentLater = add(photo("1", takenAt = 1_000, size = 600_000), seed = 1)
        val alreadyThere = add(photo("2", takenAt = 2_000, size = 400_000), seed = 2)
        server.stored[sha(files.bytes.getValue("2"))] = files.bytes.getValue("2")
        val seen = mutableListOf<com.chencohensolution.journal.backup.BackupProgress>()
        BackupEngine(files, ledger(), server).run(listOf(sentLater, alreadyThere), BackupSettings(enabled = true)) { seen += it }
        val last = seen.last()
        assertEquals(1f, last.fraction)
        assertEquals(1_000_000L, last.bytesDone)
        assertEquals(600_000L, last.bytesSent)
    }

    @Test
    fun aFileTheServerRefusesDoesntStopTheOthers() = runTest {
        val refused = add(photo("1", takenAt = 1_000, size = 500), seed = 1)
        val fine = add(photo("2", takenAt = 2_000, size = 500), seed = 2)
        server.refusedNames += "IMG_1.jpg"
        val ledger = ledger()
        val result = BackupEngine(files, ledger, server).run(listOf(refused, fine), BackupSettings(enabled = true)) {}
        assertEquals(1, result.uploaded)
        assertEquals(1, result.failed)
        assertEquals("time should be RFC3339Nano formatted string", ledger.entry("1")?.rejected)
        assertEquals(BackupStatus.Failed("time should be RFC3339Nano formatted string"), BackupState(entries = ledger.all()).status(refused))
    }

    @Test
    fun aPieceFinishedInTheBackgroundIsNotSentAgain() = runTest {
        val video = add(photo("1", takenAt = 1_000, size = 1_000_000), seed = 5)
        val ledger = ledger()
        server.answerLostAfterPieces = 2
        assertFailsWith<kotlinx.coroutines.CancellationException> { BackupEngine(files, ledger, server).run(listOf(video), BackupSettings(enabled = true)) {} }
        assertTrue(!ledger.isUploaded(video))

        // Relaunched: startUpload says where the server is, past the piece whose answer never came.
        server.answerLostAfterPieces = Int.MAX_VALUE
        val result = BackupEngine(files, ledger, server).run(listOf(video), BackupSettings(enabled = true)) {}
        assertEquals(1, result.uploaded)
        assertEquals(1_000_000L, server.bytesReceived)
        assertContentEquals(files.bytes["1"], server.stored[sha(files.bytes.getValue("1"))])
        assertTrue(ledger.isUploaded(video))
    }

    @Test
    fun aFileNotTheSizeTheLibrarySaidIsSkipped() = runTest {
        val changed = photo("1", takenAt = 1_000, size = 500)
        files.bytes["1"] = content(1, 400)
        // Like iOS, which checks the size of the file it copied out of the library.
        val checking = object : MediaFiles by files {
            override fun open(item: MediaItem, offset: Long): Source =
                if (files.bytes.getValue(item.id).size.toLong() != item.sizeBytes) throw okio.EOFException("changed") else files.open(item, offset)
        }
        val fine = add(photo("2", takenAt = 2_000, size = 500), seed = 2)
        val result = BackupEngine(checking, ledger(), server).run(listOf(changed, fine), BackupSettings(enabled = true)) {}
        assertEquals(1, result.failed)
        assertEquals(1, result.uploaded)
    }

    @Test
    fun piecesGoThroughThePlatformsSenderAndItsAnswerIsRead() = runTest {
        val sent = mutableListOf<Pair<String, String>>()
        var answer = PieceResponse(200, 16)
        val sender = object : PieceSender {
            override suspend fun send(url: String, contentRange: String, bytes: ByteArray, onSent: (Long) -> Unit): PieceResponse {
                sent += url to contentRange
                onSent(bytes.size.toLong())
                return answer
            }
        }
        val api = ServerBackupApi(ApolloClient.Builder().serverUrl("https://journal.example:56398/query?x=1").build(), "https://journal.example:56398/query?x=1", sender)
        assertEquals(PieceResult.Accepted(16, complete = false), api.put("/upload/abc", 0, ByteArray(16), 40))
        assertEquals("https://journal.example:56398/upload/abc" to "bytes 0-15/40", sent.single())
        answer = PieceResponse(201, 40)
        assertEquals(PieceResult.Accepted(40, complete = true), api.put("/upload/abc", 16, ByteArray(24), 40))
        assertEquals(PieceResult.WrongOffset(32), pieceResult(PieceResponse(416, 32), 15))
        assertEquals(PieceResult.NoUpload, pieceResult(PieceResponse(404, null), 15))
        assertEquals(PieceResult.HashMismatch, pieceResult(PieceResponse(409, null), 15))
        assertTrue(assertFailsWith<BackupServerException> { pieceResult(PieceResponse(401, null), 15) }.unauthorized)
        assertFailsWith<BackupServerException> { pieceResult(PieceResponse(500, null), 15) }
    }

    @Test
    fun aPieceOnItsWayIsAwaitedNotSentTwice() = runTest {
        val pieces = InFlightPieces()
        var started = 0
        val url = "https://journal.example/upload/abc"
        // The run that sent it is cancelled; the piece goes on in the background.
        val first = backgroundScope.async { pieces.send(url, "bytes 0-9/20", {}) { started++ } }
        testScheduler.runCurrent()
        first.cancel()
        val progress = mutableListOf<Long>()
        val again = async { pieces.send(url, "bytes 0-9/20", { progress += it }) { started++ } }
        testScheduler.runCurrent()
        pieces.progress(url, "bytes 0-9/20", 7)
        pieces.finished(url, "bytes 0-9/20", PieceResponse(200, 10))
        assertEquals(PieceResponse(200, 10), again.await())
        assertEquals(1, started)
        assertEquals(listOf(7L), progress)

        // Found on its way after a relaunch: the same piece waits for it; the next one waits until it is answered.
        pieces.adopt(url, "bytes 10-19/20")
        val next = async { runCatching { pieces.send(url, "bytes 20-29/30", {}) { started++ } } }
        testScheduler.runCurrent()
        assertEquals(1, started)
        pieces.finished(url, "bytes 10-19/20", PieceResponse(201, 20))
        testScheduler.runCurrent()
        assertEquals(2, started)
        pieces.finished(url, "bytes 20-29/30", null, okio.IOException("offline"))
        assertTrue(next.await().exceptionOrNull() is okio.IOException)
        assertEquals(0, pieces.count)
    }
}
