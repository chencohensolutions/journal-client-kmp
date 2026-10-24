package com.chencohensolution.journal

import com.apollographql.apollo.ApolloClient
import com.chencohensolution.journal.backup.BackupApi
import com.chencohensolution.journal.backup.BackupServerException
import com.chencohensolution.journal.backup.InFlightPieces
import com.chencohensolution.journal.backup.PieceResponse
import com.chencohensolution.journal.backup.PieceSender
import com.chencohensolution.journal.backup.ServerBackupApi
import com.chencohensolution.journal.backup.pieceResult
import com.chencohensolution.journal.backup.BackupProgress
import com.chencohensolution.journal.backup.PieceBody
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
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

    override var pieceSize: Long? = null
    override var maxFilesInFlight = 1
    override var maxBytesInFlight = Long.MAX_VALUE
    val pieceSizes = mutableListOf<Int>()

    /** File names in the order their startUpload came. */
    val started = mutableListOf<String>()

    /** startUpload refuses the device's certificate for these file names. */
    val unauthorizedNames = mutableSetOf<String>()

    /** A piece of these file names drops the connection. */
    val droppedNames = mutableSetOf<String>()

    /** While set, pieces wait for it before they arrive: several files are then seen on their way at once. */
    var gate: CompletableDeferred<Unit>? = null

    /** Files with a piece on its way now, and the most there were at once. */
    val sending = mutableSetOf<String>()
    var mostAtOnce = 0

    /** Whether two pieces of one file were ever on their way at once. */
    var overlapped = false

    override suspend fun missing(hashes: List<String>) = hashes.filterNot { it in stored }.toSet()

    override suspend fun start(request: UploadRequest): StartResult {
        starts++
        started += request.fileName
        if (request.fileName in unauthorizedNames) throw BackupServerException("The server refused this device", unauthorized = true)
        if (request.fileName in refusedNames) throw com.chencohensolution.journal.backup.BackupServerException("time should be RFC3339Nano formatted string")
        if (request.sha256 in stored) return StartResult.Exists
        if (request.versionOf != null && request.versionOf !in stored) return StartResult.Rejected("UNKNOWN_ORIGINAL")
        requests[request.sha256] = request
        val received = partial.getOrPut(request.sha256) { Buffer() }
        return StartResult.Ready("/upload/${request.sha256}", received.size, CHUNK)
    }

    override suspend fun put(url: String, start: Long, body: PieceBody, total: Long, onSent: (Long) -> Unit): PieceResult {
        val sha = url.removePrefix("/upload/")
        if (!sending.add(sha)) overlapped = true
        mostAtOnce = maxOf(mostAtOnce, sending.size)
        try {
            gate?.await()
        } finally {
            sending.remove(sha)
        }
        val bytes = body.readByteArray()
        val received = partial[sha] ?: return PieceResult.NoUpload
        if (start != received.size) return PieceResult.WrongOffset(received.size)
        if (++pieces > dropAfterPieces) throw okio.IOException("connection reset")
        if (requests[sha]?.fileName in droppedNames) throw okio.IOException("connection reset")
        received.write(bytes)
        pieceSizes += bytes.size
        bytesReceived += bytes.size
        if (pieces > answerLostAfterPieces) throw okio.IOException("the app was ended")
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

/**
 * A transport like iOS's background session, over [server]: pieces are handed to "iOS" ([tasks]) and answered when
 * [deliver] says, also after the run that sent them is gone. [pieces] is the app's; a relaunch makes a new one.
 */
private class BackgroundApi(private val server: FakeServer) : BackupApi by server {
    class Task(val url: String, val start: Long, val bytes: ByteArray, val total: Long) {
        val range get() = "bytes $start-${start + bytes.size - 1}/$total"
    }

    val tasks = mutableListOf<Task>()
    var pieces = InFlightPieces()

    override suspend fun put(url: String, start: Long, body: PieceBody, total: Long, onSent: (Long) -> Unit): PieceResult {
        val end = start + body.size - 1
        val response = pieces.send(url, "bytes $start-$end/$total", onSent) { tasks += Task(url, start, body.readByteArray(), total) }
        return pieceResult(response, end)
    }

    /** The app was ended and launched again: what iOS still has is adopted. */
    fun relaunch() {
        pieces = InFlightPieces()
        tasks.forEach { pieces.adopt(it.url, it.range) }
    }

    /** iOS sends the first [count] tasks; [listening] when the app is there to hear the answers. */
    suspend fun deliver(count: Int = tasks.size, listening: Boolean = true) {
        repeat(minOf(count, tasks.size)) {
            val task = tasks.removeAt(0)
            val response = when (val result = server.put(task.url, task.start, PieceBody(task.bytes.size.toLong()) { it.write(task.bytes) }, task.total)) {
                is PieceResult.Accepted -> PieceResponse(if (result.complete) 201 else 200, result.offset)
                is PieceResult.WrongOffset -> PieceResponse(416, result.offset)
                PieceResult.NoUpload -> PieceResponse(404, null)
                PieceResult.HashMismatch -> PieceResponse(409, null)
            }
            if (listening) pieces.finished(task.url, task.range, response)
        }
    }
}

private fun body(bytes: ByteArray) = PieceBody(bytes.size.toLong()) { it.write(bytes) }

class BackupTest {
    private val fs = FakeFileSystem()
    private val files = FakeFiles()
    private val server = FakeServer()
    private fun ledger() = UploadLedger(fs, "/backup".toPath())

    /** Reads files on the test's own scheduler, so the files sent at once can be seen between steps. */
    private fun TestScope.engine(mediaFiles: MediaFiles, ledger: UploadLedger, api: BackupApi = server) =
        BackupEngine(mediaFiles, ledger, api, io = StandardTestDispatcher(testScheduler))

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
        val engine = engine(files, ledger)
        val names = mutableListOf<String?>()

        val result = engine.run(listOf(newer, older, whatsapp), BackupSettings(enabled = true)) { p -> p.currentName?.let { if (names.lastOrNull() != it) names += it } }

        assertEquals(2, result.uploaded)
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg"), names.distinct())
        assertContentEquals(files.bytes["2"], server.stored[sha(files.bytes.getValue("2"))])
        assertEquals("camera", server.requests.values.first().source)
        assertTrue(ledger.isUploaded(older) && ledger.isUploaded(newer))

        val again = engine(files, ledger()).run(listOf(newer, older), BackupSettings(enabled = true)) {}
        assertEquals(0, again.uploaded + again.alreadyOnServer + again.failed)
    }

    @Test
    fun aFolderUploadsOnlyWhenChosenAndTellsTheServerItsName() = runTest {
        val petcube = MediaSource.folder("Petcube")
        val camera = add(photo("1", takenAt = 1_000, size = 10), seed = 1)
        val pet = add(photo("2", takenAt = 2_000, size = 10, source = petcube), seed = 2)

        val cameraOnly = engine(files, ledger()).run(listOf(camera, pet), BackupSettings(enabled = true)) {}
        assertEquals(1, cameraOnly.uploaded)

        val withPets = engine(files, ledger()).run(listOf(camera, pet), BackupSettings(enabled = true, sources = setOf(MediaSource.CAMERA, petcube))) {}
        assertEquals(1, withPets.uploaded)
        assertEquals(setOf("camera", "folder:Petcube"), server.requests.values.map { it.source }.toSet())
    }

    @Test
    fun newestFirstWhenChosen() = runTest {
        val older = add(photo("1", takenAt = 1_000, size = 10), seed = 1)
        val newer = add(photo("2", takenAt = 2_000, size = 10), seed = 2)
        val order = mutableListOf<String>()
        engine(files, ledger()).run(listOf(older, newer), BackupSettings(enabled = true, order = UploadOrder.NEWEST_FIRST)) { p ->
            p.currentId?.let { if (it !in order) order += it }
        }
        assertEquals(listOf("2", "1"), order)
    }

    @Test
    fun filesTheServerAlreadyHasAreNotSent() = runTest {
        val item = add(photo("1", takenAt = 1_000, size = 1_000), seed = 1)
        server.stored[sha(files.bytes.getValue("1"))] = files.bytes.getValue("1")
        val result = engine(files, ledger()).run(listOf(item), BackupSettings(enabled = true)) {}
        assertEquals(1, result.alreadyOnServer)
        assertEquals(0, server.starts)
    }

    @Test
    fun aDroppedConnectionContinuesWhereTheServerStopped() = runTest {
        val video = add(photo("1", takenAt = 1_000, size = 1_000_000), seed = 7)
        server.dropAfterPieces = 2
        assertFailsWith<okio.IOException> { engine(files, ledger()).run(listOf(video), BackupSettings(enabled = true)) {} }

        server.dropAfterPieces = Int.MAX_VALUE
        val result = engine(files, ledger()).run(listOf(video), BackupSettings(enabled = true)) {}
        assertEquals(1, result.uploaded)
        assertContentEquals(files.bytes["1"], server.stored[sha(files.bytes.getValue("1"))])
    }

    @Test
    fun anEditSavedInPlaceUploadsAsANewVersion() = runTest {
        val original = add(photo("1", takenAt = 1_000, size = 500), seed = 1)
        val ledger = ledger()
        engine(files, ledger).run(listOf(original), BackupSettings(enabled = true)) {}
        val originalSha = sha(files.bytes.getValue("1"))

        val edited = add(original.copy(sizeBytes = 520, modifiedMillis = 2), seed = 9)
        val result = engine(files, ledger).run(listOf(edited), BackupSettings(enabled = true)) {}
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
        engine(files, ledger()).run(listOf(sentLater, alreadyThere), BackupSettings(enabled = true)) { seen += it }
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
        val result = engine(files, ledger).run(listOf(refused, fine), BackupSettings(enabled = true)) {}
        assertEquals(1, result.uploaded)
        assertEquals(1, result.failed)
        assertEquals("time should be RFC3339Nano formatted string", ledger.entry("1")?.rejected)
        assertEquals(BackupStatus.Failed("time should be RFC3339Nano formatted string"), BackupState(entries = ledger.all()).status(refused))
    }

    @Test
    fun theDeviceCanSendLargerPiecesThanTheServerAsks() = runTest {
        val video = add(photo("1", takenAt = 1_000, size = 1_300_000), seed = 1)
        server.pieceSize = 512L * 1024

        engine(files, ledger()).run(listOf(video), BackupSettings(enabled = true)) {}

        // 1.3 MB in 512 KB pieces rather than the server's 256 KB.
        assertEquals(listOf(524_288, 524_288, 251_424), server.pieceSizes)
        assertContentEquals(files.bytes["1"], server.stored[sha(files.bytes.getValue("1"))])
    }

    @Test
    fun aPieceFinishedInTheBackgroundIsNotSentAgain() = runTest {
        val video = add(photo("1", takenAt = 1_000, size = 1_000_000), seed = 5)
        val ledger = ledger()
        server.answerLostAfterPieces = 2
        assertFailsWith<okio.IOException> { engine(files, ledger).run(listOf(video), BackupSettings(enabled = true)) {} }
        assertTrue(!ledger.isUploaded(video))

        // Relaunched: startUpload says where the server is, past the piece whose answer never came.
        server.answerLostAfterPieces = Int.MAX_VALUE
        val result = engine(files, ledger).run(listOf(video), BackupSettings(enabled = true)) {}
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
        val result = engine(checking, ledger()).run(listOf(changed, fine), BackupSettings(enabled = true)) {}
        assertEquals(1, result.failed)
        assertEquals(1, result.uploaded)
    }

    @Test
    fun piecesGoThroughThePlatformsSenderAndItsAnswerIsRead() = runTest {
        val sent = mutableListOf<Pair<String, String>>()
        var answer = PieceResponse(200, 16)
        val sender = object : PieceSender {
            override suspend fun send(url: String, contentRange: String, body: PieceBody, onSent: (Long) -> Unit): PieceResponse {
                sent += url to contentRange
                onSent(body.readByteArray().size.toLong())
                return answer
            }
        }
        val api = ServerBackupApi(ApolloClient.Builder().serverUrl("https://journal.example:56398/query?x=1").build(), "https://journal.example:56398/query?x=1", sender)
        assertEquals(PieceResult.Accepted(16, complete = false), api.put("/upload/abc", 0, body(ByteArray(16)), 40))
        assertEquals("https://journal.example:56398/upload/abc" to "bytes 0-15/40", sent.single())
        answer = PieceResponse(201, 40)
        assertEquals(PieceResult.Accepted(40, complete = true), api.put("/upload/abc", 16, body(ByteArray(24)), 40))
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

    /** Lets the pieces waiting at the server's gate through, one round at a time, until [run] ends. */
    private suspend fun TestScope.stepThrough(run: kotlinx.coroutines.Deferred<*>, eachStep: () -> Unit = {}) {
        while (!run.isCompleted) {
            testScheduler.runCurrent()
            eachStep()
            val gate = server.gate!!
            server.gate = CompletableDeferred()
            gate.complete(Unit)
            testScheduler.runCurrent()
        }
    }

    @Test
    fun severalFilesGoAtOnceUpToTheLimitInTheQueuesOrder() = runTest {
        // Every third one is three pieces.
        val items = (1..8).map { add(photo("$it", takenAt = it * 1_000L, size = if (it % 3 == 0) 600_000 else 1_000), seed = it) }
        server.maxFilesInFlight = 3
        server.gate = CompletableDeferred()
        val ledger = ledger()
        val seen = mutableListOf<BackupProgress>()
        val run = async { engine(files, ledger).run(items.reversed(), BackupSettings(enabled = true)) { seen += it } }
        testScheduler.runCurrent()

        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg"), server.started)
        assertEquals(3, server.sending.size)
        val now = seen.last()
        assertEquals(listOf("1", "2", "3"), now.uploading.keys.toList())
        assertEquals(0, now.done)
        assertEquals("1", now.currentId)

        stepThrough(run) { assertTrue(server.sending.size <= 3) }
        val result = run.await()
        assertEquals(8, result.uploaded)
        assertEquals(3, server.mostAtOnce)
        assertTrue(!server.overlapped, "two pieces of one file at once")
        assertEquals(items.map { it.fileName }, server.started)
        assertEquals(items.sumOf { it.sizeBytes!! }, server.bytesReceived)
        // Written by completions that came close together, and read back from the file.
        val reopened = ledger()
        items.forEach { assertTrue(reopened.isUploaded(it), it.id) }
        val last = seen.last()
        assertEquals(8, last.done)
        assertTrue(last.uploading.isEmpty())
        assertEquals(1f, last.fraction)
    }

    @Test
    fun theOrderSettingDecidesWhatStartsFirst() = runTest {
        val items = (1..5).map { add(photo("$it", takenAt = it * 1_000L, size = 1_000), seed = it) }
        server.maxFilesInFlight = 3
        engine(files, ledger()).run(items, BackupSettings(enabled = true, order = UploadOrder.NEWEST_FIRST)) {}
        assertEquals(items.reversed().map { it.fileName }, server.started)
    }

    @Test
    fun filesGoAtOnceUpToABudgetOfBytesAndALargerOneAlone() = runTest {
        val sizes = listOf(400, 400, 400, 5_000, 100)
        val items = sizes.mapIndexed { i, size -> add(photo("${i + 1}", takenAt = (i + 1) * 1_000L, size = size), seed = i) }
        server.maxFilesInFlight = 10
        server.maxBytesInFlight = 1_000
        server.gate = CompletableDeferred()
        val rounds = mutableListOf<Set<String>>()
        val run = async { engine(files, ledger()).run(items, BackupSettings(enabled = true)) {} }
        stepThrough(run) { rounds += server.sending.map { sha -> items.first { sha(files.bytes.getValue(it.id)) == sha }.id }.toSet() }
        assertEquals(listOf(setOf("1", "2"), setOf("3"), setOf("4"), setOf("5")), rounds)
        assertEquals(5, run.await().uploaded)
    }

    @Test
    fun filesThatFailDontStopTheOthersSentAtOnce() = runTest {
        val items = (1..5).map { add(photo("$it", takenAt = it * 1_000L, size = 1_000), seed = it) }
        server.refusedNames += "IMG_2.jpg"
        // Changed since it was listed: shorter than the library says.
        files.bytes["4"] = content(4, 900)
        server.maxFilesInFlight = 3
        val ledger = ledger()
        val result = engine(files, ledger).run(items, BackupSettings(enabled = true)) {}
        assertEquals(3, result.uploaded)
        assertEquals(2, result.failed)
        listOf("1", "3", "5").forEach { id -> assertTrue(ledger.isUploaded(items.first { it.id == id })) }
    }

    @Test
    fun aNetworkErrorStartsNoMoreFilesAndIsThrownOnceTheOthersFinish() = runTest {
        val items = (1..6).map { add(photo("$it", takenAt = it * 1_000L, size = 1_000), seed = it) }
        server.droppedNames += "IMG_2.jpg"
        server.maxFilesInFlight = 3
        val ledger = ledger()
        val failure = runCatching { engine(files, ledger).run(items, BackupSettings(enabled = true)) {} }.exceptionOrNull()
        assertTrue(failure is okio.IOException, "$failure")
        assertTrue(ledger.isUploaded(items[0]) && ledger.isUploaded(items[2]))
        assertTrue(!ledger.isUploaded(items[1]))
        assertTrue("IMG_6.jpg" !in server.started, "${server.started}")
    }

    @Test
    fun aRefusedCertificateStopsEveryFile() = runTest {
        val items = (1..5).map { add(photo("$it", takenAt = it * 1_000L, size = 1_000), seed = it) }
        server.unauthorizedNames += "IMG_3.jpg"
        server.maxFilesInFlight = 3
        server.gate = CompletableDeferred()
        val run = async { runCatching { engine(files, ledger()).run(items, BackupSettings(enabled = true)) {} } }
        testScheduler.runCurrent()
        val failure = run.await().exceptionOrNull()
        assertTrue(failure is BackupServerException && failure.unauthorized, "$failure")
        assertEquals(listOf("IMG_1.jpg", "IMG_2.jpg", "IMG_3.jpg"), server.started)
        assertTrue(server.stored.isEmpty())
        assertTrue(server.sending.isEmpty()) // 1 and 2 were stopped mid-piece.
    }

    @Test
    fun theSameBytesUnderTwoIdsAreSentOnce() = runTest {
        val first = add(photo("1", takenAt = 1_000, size = 5_000), seed = 4)
        val copy = photo("2", takenAt = 2_000, size = 5_000).also { files.bytes["2"] = files.bytes.getValue("1") }
        server.maxFilesInFlight = 3
        val ledger = ledger()
        val result = engine(files, ledger).run(listOf(first, copy), BackupSettings(enabled = true)) {}
        assertEquals(1, result.uploaded)
        assertEquals(1, result.alreadyOnServer)
        assertEquals(1, server.starts)
        assertEquals(5_000L, server.bytesReceived)
        assertTrue(ledger.isUploaded(first) && ledger.isUploaded(copy))
    }

    @Test
    fun aRelaunchAdoptsManyPiecesAndSendsNothingTwice() = runTest {
        // The last is three pieces.
        val items = (1..6).map { add(photo("$it", takenAt = it * 1_000L, size = if (it == 6) 600_000 else 2_000), seed = it) }
        val api = BackgroundApi(server)
        server.maxFilesInFlight = 6
        val ledger = ledger()
        val first = async { runCatching { engine(files, ledger, api).run(items, BackupSettings(enabled = true)) {} } }
        testScheduler.runCurrent()
        assertEquals(6, api.tasks.size) // A piece of every file is with "iOS".

        // The app is ended; iOS sends two pieces while it is gone, then relaunches it with the other four on their way.
        first.cancel()
        testScheduler.runCurrent()
        api.deliver(count = 2, listening = false)
        api.relaunch()
        val second = async { engine(files, ledger, api).run(items, BackupSettings(enabled = true)) {} }
        testScheduler.runCurrent()
        assertEquals(4, api.tasks.size) // Awaited, not sent again.
        while (!second.isCompleted) {
            api.deliver()
            testScheduler.runCurrent()
        }
        val result = second.await()
        assertEquals(4, result.uploaded)
        assertEquals(2, result.alreadyOnServer)
        assertEquals(items.sumOf { it.sizeBytes!! }, server.bytesReceived)
        val reopened = ledger()
        items.forEach {
            assertContentEquals(files.bytes[it.id], server.stored[sha(files.bytes.getValue(it.id))])
            assertTrue(reopened.isUploaded(it), it.id)
        }
    }
}
