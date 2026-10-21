@file:OptIn(ExperimentalAtomicApi::class)

package com.chencohensolution.journal.backup

import kotlinx.coroutines.CompletableDeferred
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.Volatile
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pieces handed to a transport that goes on without the app (iOS's background URLSession), one per upload URL. A
 * piece asked for again while it is still on its way (the run was cancelled, or the app was relaunched mid-piece) is
 * not sent twice: its answer is awaited. Another piece of the same upload waits for the one on its way, so the server
 * gets one request per file at a time. Safe to call from the transport's callbacks and the upload at once.
 */
internal class InFlightPieces {
    private class Piece(val range: String, @Volatile var onSent: (Long) -> Unit) {
        val answer = CompletableDeferred<PieceResponse>()
    }

    private val pieces = AtomicReference<Map<String, Piece>>(emptyMap())

    /**
     * The answer to the piece [range] (a Content-Range) of [url]: the one on its way, or a new one that [start] hands
     * to the transport. [onSent] gets the bytes sent while this call waits.
     */
    suspend fun send(url: String, range: String, onSent: (Long) -> Unit, start: () -> Unit): PieceResponse {
        while (true) {
            val current = pieces.load()[url]
            if (current != null) {
                if (current.range == range) return await(current, onSent)
                // An earlier piece of this file: its answer moves the server's offset, and the upload follows that.
                try {
                    current.answer.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // Its error was for whoever sent it.
                }
                continue
            }
            val piece = Piece(range, onSent)
            if (!add(url, piece)) continue
            try {
                start()
            } catch (e: Throwable) {
                remove(url, piece)
                throw e
            }
            return await(piece, onSent)
        }
    }

    /** A piece found on its way after a relaunch; a [send] of it waits for its answer instead of sending it again. */
    fun adopt(url: String, range: String) {
        add(url, Piece(range) {})
    }

    /** [sent] bytes of the piece [range] of [url] have left. */
    fun progress(url: String, range: String, sent: Long) {
        pieces.load()[url]?.takeIf { it.range == range }?.onSent?.invoke(sent)
    }

    /** The piece [range] of [url] was answered with [response], or failed with [error]. */
    fun finished(url: String, range: String, response: PieceResponse?, error: Throwable? = null) {
        val piece = pieces.load()[url]?.takeIf { it.range == range } ?: return
        remove(url, piece)
        if (response != null) piece.answer.complete(response) else piece.answer.completeExceptionally(error ?: IllegalStateException("No answer"))
    }

    /** How many pieces are on their way. */
    val count: Int get() = pieces.load().size

    private suspend fun await(piece: Piece, onSent: (Long) -> Unit): PieceResponse {
        piece.onSent = onSent
        try {
            return piece.answer.await()
        } finally {
            // A cancelled run gets no more progress; the piece itself goes on.
            if (piece.onSent === onSent) piece.onSent = {}
        }
    }

    private fun add(url: String, piece: Piece): Boolean {
        while (true) {
            val old = pieces.load()
            if (url in old) return false
            if (pieces.compareAndSet(old, old + (url to piece))) return true
        }
    }

    private fun remove(url: String, piece: Piece) {
        while (true) {
            val old = pieces.load()
            if (old[url] !== piece) return
            if (pieces.compareAndSet(old, old - url)) return
        }
    }
}
