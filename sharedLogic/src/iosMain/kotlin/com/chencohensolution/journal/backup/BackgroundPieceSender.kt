@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.chencohensolution.journal.backup

import com.chencohensolution.journal.network.answerChallenge
import com.chencohensolution.journal.tls.KeychainIdentities
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.IOException
import okio.Path
import platform.Foundation.NSError
import platform.Foundation.NSHTTPURLResponse
import platform.Foundation.NSMutableURLRequest
import platform.Foundation.NSOperationQueue
import platform.Foundation.NSURL
import platform.Foundation.NSURLAuthenticationChallenge
import platform.Foundation.NSURLCredential
import platform.Foundation.NSURLSession
import platform.Foundation.NSURLSessionAuthChallengeDisposition
import platform.Foundation.NSURLSessionConfiguration
import platform.Foundation.NSURLSessionTask
import platform.Foundation.NSURLSessionTaskDelegateProtocol
import platform.Foundation.NSURLSessionUploadTask
import platform.Foundation.setHTTPMethod
import platform.Foundation.setValue
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference
import kotlin.time.TimeSource

/**
 * Sends each piece with a background URLSession, which iOS carries on while the app is suspended, and even after the
 * system ended it: iOS relaunches the app in the background when pieces are answered ([handleEvents]), and the run
 * goes on from the server's offset. Many files are with iOS at once ([MAX_FILES], [MAX_BYTES]), so a closed app's
 * wake sends a batch rather than one piece. A piece is written to a file in [directory] first (a background session
 * uploads only from files) and removed once answered. The client certificate is the one in the Keychain under [identityAlias],
 * the server's must chain to [anchors]; Wi-Fi only ([wifiOnly]) is left to iOS, so a piece waits for Wi-Fi rather than
 * use mobile data or a hotspot.
 */
internal class BackgroundPieceSender(
    private val directory: Path,
    private val identityAlias: () -> String?,
    private val anchors: List<Any>,
    private val wifiOnly: () -> Boolean,
    private val fileSystem: FileSystem = FileSystem.SYSTEM,
) : PieceSender {
    // iOS wakes a closed app about once per finished piece, so the largest piece the server takes means the fewest waits.
    override val pieceSize: Long get() = PIECE_BYTES

    // Many files at once, so iOS has a batch to send while the app is closed (see MAX_FILES).
    override val maxFilesInFlight: Int get() = MAX_FILES
    override val maxBytesInFlight: Long get() = MAX_BYTES

    private val pieces = InFlightPieces()
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val eventsDone = AtomicReference<(() -> Unit)?>(null)

    /** [handedOver] when the session delivered its events before iOS told the app; -1 otherwise. */
    private val deliveredAt = AtomicInt(-1)

    /** Pieces handed to iOS since launch. */
    private val handedOver = AtomicInt(0)
    private val delegate = Delegate()
    private val queue = NSOperationQueue().apply { maxConcurrentOperationCount = 1 }
    private val session: NSURLSession

    init {
        val configuration = NSURLSessionConfiguration.backgroundSessionConfigurationWithIdentifier(SESSION_ID).apply {
            sessionSendsLaunchEvents = true
            discretionary = false
        }
        // Reconnects to the pieces still on their way from before a relaunch.
        session = NSURLSession.sessionWithConfiguration(configuration, delegate, queue)
        session.getTasksWithCompletionHandler { _, uploads, _ ->
            val kept = mutableSetOf<String>()
            uploads.orEmpty().forEach { task ->
                val piece = (task as NSURLSessionTask).piece() ?: return@forEach
                pieces.adopt(piece.url, piece.range)
                kept += piece.file
            }
            // Files of pieces that were answered while nobody listened.
            runCatching { fileSystem.list(directory).filterNot { it.name in kept }.forEach(fileSystem::delete) }
            ready.complete(Unit)
        }
    }

    override suspend fun send(url: String, contentRange: String, body: PieceBody, onSent: (Long) -> Unit): PieceResponse {
        ready.await()
        return pieces.send(url, contentRange, onSent) {
            val file = directory / "${url.substringAfterLast('/')}-${contentRange.substringAfter(' ').substringBefore('-')}.piece"
            // Streamed from the photo's file to the piece's: many pieces wait with iOS, none of them in memory.
            withContext(Dispatchers.IO) {
                fileSystem.createDirectories(directory)
                try {
                    fileSystem.write(file) { body.writeTo(this) }
                } catch (e: Throwable) {
                    runCatching { fileSystem.delete(file) }
                    throw e
                }
            }
            val request = NSMutableURLRequest.requestWithURL(NSURL(string = url)).apply {
                setHTTPMethod("PUT")
                setValue(contentRange, forHTTPHeaderField = "Content-Range")
                setValue("application/octet-stream", forHTTPHeaderField = "Content-Type")
                val anyNetwork = !wifiOnly()
                setAllowsCellularAccess(anyNetwork)
                setAllowsExpensiveNetworkAccess(anyNetwork)
            }
            val task: NSURLSessionUploadTask = session.uploadTaskWithRequest(request, fromFile = NSURL.fileURLWithPath(file.toString()))
            task.taskDescription = listOf(url, contentRange, file.name).joinToString("\n")
            task.resume()
            handedOver.incrementAndGet()
        }
    }

    /**
     * iOS woke the app for this session's events ([completion] from `handleEventsForBackgroundURLSession`). It is
     * called once the events are delivered and the run that resumed has topped iOS's queue back up: it handed iOS new
     * pieces and none for [QUIET_MILLIS], or the queue is full (or after [TOP_UP_WAIT_MILLIS]). iOS then suspends the
     * app and sends the queue on its own.
     */
    fun handleEvents(completion: () -> Unit) {
        eventsDone.value = completion
        // The session may have delivered them before iOS told the app.
        val at = deliveredAt.getAndSet(-1)
        if (at >= 0) topUp(at)
    }

    private fun eventsDelivered() {
        val at = handedOver.value
        if (eventsDone.value == null) deliveredAt.value = at else topUp(at)
    }

    /** Calls the events' completion once pieces handed over since [handedAt] (that count) have settled. */
    private fun topUp(handedAt: Int) {
        val completion = eventsDone.getAndSet(null) ?: return
        scope.launch {
            withTimeoutOrNull(TOP_UP_WAIT_MILLIS) {
                var last = handedOver.value
                var quietSince = TimeSource.Monotonic.markNow()
                while (true) {
                    delay(200)
                    val handed = handedOver.value
                    if (handed != last) {
                        last = handed
                        quietSince = TimeSource.Monotonic.markNow()
                    }
                    if (pieces.handingOver) continue
                    if (pieces.count >= MAX_FILES) break
                    if (handed != handedAt && quietSince.elapsedNow().inWholeMilliseconds >= QUIET_MILLIS) break
                }
            }
            completion()
        }
    }

    /** What a task carries in its description: the upload URL, the Content-Range, and the file name in [directory]. */
    private class Piece(val url: String, val range: String, val file: String)

    private fun NSURLSessionTask.piece(): Piece? =
        taskDescription?.split("\n")?.takeIf { it.size == 3 }?.let { Piece(it[0], it[1], it[2]) }

    private inner class Delegate : NSObject(), NSURLSessionTaskDelegateProtocol {
        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didSendBodyData: Long,
            totalBytesSent: Long,
            totalBytesExpectedToSend: Long,
        ) {
            val piece = task.piece() ?: return
            pieces.progress(piece.url, piece.range, totalBytesSent)
        }

        override fun URLSession(session: NSURLSession, task: NSURLSessionTask, didCompleteWithError: NSError?) {
            val piece = task.piece() ?: return
            runCatching { fileSystem.delete(directory / piece.file) }
            val response = task.response as? NSHTTPURLResponse
            if (didCompleteWithError != null || response == null) {
                pieces.finished(piece.url, piece.range, null, IOException(didCompleteWithError?.localizedDescription ?: "No answer from the server"))
            } else {
                val offset = response.valueForHTTPHeaderField("Upload-Offset")?.toLongOrNull()
                pieces.finished(piece.url, piece.range, PieceResponse(response.statusCode.toInt(), offset))
            }
        }

        override fun URLSession(
            session: NSURLSession,
            task: NSURLSessionTask,
            didReceiveChallenge: NSURLAuthenticationChallenge,
            completionHandler: (NSURLSessionAuthChallengeDisposition, NSURLCredential?) -> Unit,
        ) {
            // Read each time: the certificate changes when the device registers again.
            val identity = identityAlias()?.let(KeychainIdentities::find)
            answerChallenge(didReceiveChallenge, identity, anchors, completionHandler)
        }

        override fun URLSessionDidFinishEventsForBackgroundURLSession(session: NSURLSession) {
            dispatch_async(dispatch_get_main_queue()) { eventsDelivered() }
        }
    }

    companion object {
        const val SESSION_ID = "com.chencohensolution.journal.backup"

        /** 64 MB, the server's limit (journal-server-go media/upload.go MaxChunkSize); it asks for 16 MB. */
        private const val PIECE_BYTES = 64L shl 20

        /**
         * Files with iOS at once. A closed app is woken about once per finished task, and iOS spaces the wakes out
         * (about a minute apart in a test), so the queue is what moves while the app is closed: 50 photos per wake
         * rather than one. Far below the thousands of tasks Apple warns slow a background session down, and few enough
         * to top up within one wake (each needs a PhotoKit copy and a startUpload).
         */
        private const val MAX_FILES = 50

        /**
         * What the files with iOS at once may add up to. Each has a full copy in `Caches/backup/files` and one piece
         * (up to 64 MB) in `Caches/backup/pieces`, so backup holds about 1 GB of Caches at most (more only for a single
         * larger video, which goes alone): 50 photos, or 8 videos of 64 MB.
         */
        private const val MAX_BYTES = 512L shl 20

        /** The longest a background wake waits for the run to top the queue back up (iOS allows about 30 s). */
        private const val TOP_UP_WAIT_MILLIS = 20_000L

        /** No new piece for this long after some were handed over: the run has topped the queue up. */
        private const val QUIET_MILLIS = 2_000L
    }
}
