package com.chencohensolution.journal.backup

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloHttpException
import com.chencohensolution.journal.graphql.MissingMediaQuery
import com.chencohensolution.journal.graphql.StartUploadMutation
import com.chencohensolution.journal.graphql.type.CallInput
import com.chencohensolution.journal.graphql.type.EncodedFromInput
import com.chencohensolution.journal.graphql.type.GeoPointInput
import com.chencohensolution.journal.graphql.type.MediaKind
import com.chencohensolution.journal.graphql.type.StartUploadInput
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.MediaType
import io.ktor.client.HttpClient
import io.ktor.client.plugins.onUpload
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.URLBuilder
import io.ktor.http.contentType
import io.ktor.http.encodedPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import okio.Buffer
import okio.BufferedSink

/** What the device declares about a file before sending it (the server's StartUploadInput). */
internal data class UploadRequest(
    val sha256: String,
    val size: Long,
    val fileName: String,
    val mimeType: String,
    val kind: MediaType,
    /** RFC 3339 with the offset of where it was taken. */
    val takenAt: String?,
    val width: Int?,
    val height: Int?,
    val durationMillis: Long?,
    val location: GeoPoint?,
    val source: String?,
    /** Where it is on the phone ([com.chencohensolution.journal.library.MediaItem.folder]), kept by the server. */
    val folder: String? = null,
    val versionOf: String?,
    /** A sound file from a folder of calls: a call, with whoever its name says (the server reads the name). */
    val isCall: Boolean = false,
    /** For a FLAC made on the phone from a WAV: the WAV, which the server then answers as this file. */
    val encodedFrom: EncodedFrom? = null,
)

/** The WAV a FLAC was made from. */
internal data class EncodedFrom(val sha256: String, val mimeType: String, val size: Long)

internal sealed interface StartResult {
    data class Ready(val url: String, val offset: Long, val chunkSize: Long) : StartResult
    data object Exists : StartResult
    data class Rejected(val reason: String) : StartResult
}

internal sealed interface PieceResult {
    /** [offset] bytes have arrived; [complete] once the server checked and stored the file. */
    data class Accepted(val offset: Long, val complete: Boolean) : PieceResult

    /** The server has [offset] bytes; continue from there. */
    data class WrongOffset(val offset: Long) : PieceResult

    /** The upload is gone (expired, or the server lost it): start it again. */
    data object NoUpload : PieceResult

    /** The bytes didn't match the sha256: the file changed while it was read. */
    data object HashMismatch : PieceResult
}

/** The server answered something the device can't continue from; [unauthorized] means the certificate was refused. */
internal class BackupServerException(message: String, val unauthorized: Boolean = false) : Exception(message)

/**
 * One piece's [size] bytes, read from the file only when they are sent: a piece found still on its way
 * ([InFlightPieces]) is not read at all. Read once.
 */
internal class PieceBody(val size: Long, private val read: (BufferedSink) -> Unit) {
    fun writeTo(sink: BufferedSink) = read(sink)

    fun readByteArray(): ByteArray = Buffer().also(read).readByteArray()
}

/** The server's upload API (journal-server-go docs/media.md). */
internal interface BackupApi {
    /** The piece size this device sends instead of the server's chunkSize, or null to follow the server. */
    val pieceSize: Long? get() = null

    /** See [PieceSender.maxFilesInFlight]. */
    val maxFilesInFlight: Int get() = 1

    /** See [PieceSender.maxBytesInFlight]. */
    val maxBytesInFlight: Long get() = Long.MAX_VALUE

    /** Which of [hashes] the server doesn't have. */
    suspend fun missing(hashes: List<String>): Set<String>
    suspend fun start(request: UploadRequest): StartResult

    /** Sends bytes [start] until [start] + [body].size of a [total]-byte file; [onSent] gets how many of them left so far. */
    suspend fun put(url: String, start: Long, body: PieceBody, total: Long, onSent: (Long) -> Unit = {}): PieceResult
}

/** The server's answer to one piece: the HTTP status and its `Upload-Offset` header. */
internal data class PieceResponse(val status: Int, val uploadOffset: Long?)

/**
 * Sends one piece of an upload (a `PUT` of [body] with [contentRange] to [url], an absolute URL) and returns the
 * server's answer; [onSent] gets how many of its bytes left so far. The platform's transport: the app's own client on
 * Android, a background URLSession on iOS (which goes on while the app is suspended). Called for several files at
 * once (up to [maxFilesInFlight]), one piece at a time per file.
 */
internal interface PieceSender {
    /** See [BackupApi.pieceSize]. */
    val pieceSize: Long? get() = null

    /** How many files a run sends at once. */
    val maxFilesInFlight: Int get() = 1

    /** What the sizes of the files sent at once may add up to; a larger file still goes, alone. */
    val maxBytesInFlight: Long get() = Long.MAX_VALUE

    suspend fun send(url: String, contentRange: String, body: PieceBody, onSent: (Long) -> Unit): PieceResponse
}

/** Sends pieces with the app's mTLS client, in the app's process. */
internal class KtorPieceSender(private val http: HttpClient) : PieceSender {
    // A few files at once hide each one's round trips (startUpload, the answer to each piece), which is most of the
    // time a small photo takes. Each holds its piece in memory (the server's 16 MB), so 3 hold at most 48 MB.
    override val maxFilesInFlight: Int get() = 3

    override suspend fun send(url: String, contentRange: String, body: PieceBody, onSent: (Long) -> Unit): PieceResponse {
        val bytes = withContext(Dispatchers.IO) { body.readByteArray() }
        val response = http.put(url) {
            header("Content-Range", contentRange)
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
            onUpload { sent, _ -> onSent(sent) }
        }
        return PieceResponse(response.status.value, response.headers["Upload-Offset"]?.toLongOrNull())
    }
}

/** What the answer to a piece ending at byte [end] means (journal-server-go docs/media.md, `PUT /upload/{sha256}`). */
internal fun pieceResult(response: PieceResponse, end: Long): PieceResult {
    val offset = response.uploadOffset
    return when (response.status) {
        HttpStatusCode.OK.value, HttpStatusCode.Created.value ->
            PieceResult.Accepted(offset ?: (end + 1), complete = response.status == HttpStatusCode.Created.value)
        HttpStatusCode.RequestedRangeNotSatisfiable.value -> PieceResult.WrongOffset(offset ?: 0)
        HttpStatusCode.NotFound.value -> PieceResult.NoUpload
        HttpStatusCode.Conflict.value -> PieceResult.HashMismatch
        HttpStatusCode.Unauthorized.value -> throw BackupServerException("The server refused this device", unauthorized = true)
        else -> throw BackupServerException("Upload failed: HTTP ${response.status}")
    }
}

internal class ServerBackupApi(
    private val apollo: ApolloClient,
    /** The GraphQL endpoint; the upload URLs the server returns are paths on the same origin. */
    private val serverUrl: String,
    private val pieces: PieceSender,
) : BackupApi {
    override val pieceSize: Long? get() = pieces.pieceSize
    override val maxFilesInFlight: Int get() = pieces.maxFilesInFlight
    override val maxBytesInFlight: Long get() = pieces.maxBytesInFlight

    override suspend fun missing(hashes: List<String>): Set<String> {
        if (hashes.isEmpty()) return emptySet()
        val response = apollo.query(MissingMediaQuery(hashes)).execute()
        response.exception?.let { throw it.asServerException() }
        response.errors?.firstOrNull()?.let { throw BackupServerException(it.message) }
        return response.data?.missingMedia.orEmpty().toSet()
    }

    override suspend fun start(request: UploadRequest): StartResult {
        val input = StartUploadInput(
            sha256 = request.sha256,
            size = request.size,
            fileName = request.fileName,
            mimeType = request.mimeType,
            kind = when (request.kind) {
                MediaType.IMAGE -> MediaKind.PHOTO
                MediaType.VIDEO -> MediaKind.VIDEO
                MediaType.AUDIO -> MediaKind.AUDIO
            },
            takenAt = Optional.presentIfNotNull(request.takenAt),
            width = Optional.presentIfNotNull(request.width),
            height = Optional.presentIfNotNull(request.height),
            durationMs = Optional.presentIfNotNull(request.durationMillis),
            location = Optional.presentIfNotNull(request.location?.let { GeoPointInput(lat = it.latitude, lon = it.longitude) }),
            source = Optional.presentIfNotNull(request.source),
            folder = Optional.presentIfNotNull(request.folder?.take(500)),
            versionOf = Optional.presentIfNotNull(request.versionOf),
            call = if (request.isCall) Optional.present(CallInput()) else Optional.absent(),
            encodedFrom = Optional.presentIfNotNull(request.encodedFrom?.let { EncodedFromInput(sha256 = it.sha256, mimeType = it.mimeType, size = it.size) }),
        )
        val response = apollo.mutation(StartUploadMutation(input)).execute()
        response.exception?.let { throw it.asServerException() }
        response.errors?.firstOrNull()?.let { throw BackupServerException(it.message) }
        val result = response.data?.startUpload ?: throw BackupServerException("No answer to startUpload")
        result.onUploadReady?.let { return StartResult.Ready(it.url, it.offset, it.chunkSize) }
        result.onMediaExists?.let { return StartResult.Exists }
        result.onUploadRejected?.let { return StartResult.Rejected(it.reason.rawValue) }
        throw BackupServerException("Unknown answer to startUpload: ${result.__typename}")
    }

    override suspend fun put(url: String, start: Long, body: PieceBody, total: Long, onSent: (Long) -> Unit): PieceResult {
        val end = start + body.size - 1
        return pieceResult(pieces.send(resolve(url), "bytes $start-$end/$total", body, onSent), end)
    }

    private fun resolve(path: String): String = URLBuilder(serverUrl).apply {
        parameters.clear()
        fragment = ""
        encodedPath = path
    }.buildString()

    private fun Throwable.asServerException(): Throwable =
        if (this is ApolloHttpException && statusCode == 401) BackupServerException("The server refused this device", unauthorized = true) else this
}
