@file:OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)

package com.chencohensolution.journal.backup

import com.chencohensolution.journal.network.answerChallenge
import com.chencohensolution.journal.tls.KeychainIdentities
import com.chencohensolution.journal.tls.toNSData
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import platform.Foundation.writeToFile
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference

/**
 * Sends each piece with a background URLSession, which iOS carries on while the app is suspended, and even after the
 * system ended it: iOS relaunches the app in the background when the piece is answered ([handleEvents]), and the run
 * goes on from the server's offset. A piece is written to a file in [directory] first (a background session uploads
 * only from files) and removed once answered. The client certificate is the one in the Keychain under [identityAlias],
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

    private val pieces = InFlightPieces()
    private val ready = CompletableDeferred<Unit>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val eventsDone = AtomicReference<(() -> Unit)?>(null)
    private val delivered = AtomicInt(0)
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

    override suspend fun send(url: String, contentRange: String, bytes: ByteArray, onSent: (Long) -> Unit): PieceResponse {
        ready.await()
        return pieces.send(url, contentRange, onSent) {
            fileSystem.createDirectories(directory)
            val file = directory / "${url.substringAfterLast('/')}-${contentRange.substringAfter(' ').substringBefore('-')}.piece"
            if (!bytes.toNSData().writeToFile(file.toString(), atomically = true)) throw IOException("Can't write the piece to $file")
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
        }
    }

    /**
     * iOS woke the app for this session's events ([completion] from `handleEventsForBackgroundURLSession`). It is
     * called once the events are delivered and the run that resumed has handed the next piece to iOS (or after a few
     * seconds), so iOS keeps the app suspended only while a piece is on its way.
     */
    fun handleEvents(completion: () -> Unit) {
        eventsDone.value = completion
        // The session may have delivered them before iOS told the app.
        if (delivered.compareAndSet(1, 0)) eventsDelivered()
    }

    private fun eventsDelivered() {
        val completion = eventsDone.getAndSet(null)
        if (completion == null) {
            delivered.value = 1
            return
        }
        scope.launch {
            withTimeoutOrNull(NEXT_PIECE_WAIT_MILLIS) {
                while (pieces.count == 0) delay(200)
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

        /** How long a background wake waits for the run to hand iOS the next piece. */
        private const val NEXT_PIECE_WAIT_MILLIS = 15_000L
    }
}
