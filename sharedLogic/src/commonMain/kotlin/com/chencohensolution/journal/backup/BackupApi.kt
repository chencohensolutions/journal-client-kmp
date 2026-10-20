package com.chencohensolution.journal.backup

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.api.Optional
import com.apollographql.apollo.exception.ApolloHttpException
import com.chencohensolution.journal.graphql.MissingMediaQuery
import com.chencohensolution.journal.graphql.StartUploadMutation
import com.chencohensolution.journal.graphql.type.GeoPointInput
import com.chencohensolution.journal.graphql.type.MediaKind
import com.chencohensolution.journal.graphql.type.StartUploadInput
import com.chencohensolution.journal.library.GeoPoint
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

/** What the device declares about a file before sending it (the server's StartUploadInput). */
internal data class UploadRequest(
    val sha256: String,
    val size: Long,
    val fileName: String,
    val mimeType: String,
    val isVideo: Boolean,
    /** RFC 3339 with the offset of where it was taken. */
    val takenAt: String?,
    val width: Int?,
    val height: Int?,
    val durationMillis: Long?,
    val location: GeoPoint?,
    val source: String?,
    val versionOf: String?,
)

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

/** The server's upload API (journal-server-go docs/media.md). */
internal interface BackupApi {
    /** Which of [hashes] the server doesn't have. */
    suspend fun missing(hashes: List<String>): Set<String>
    suspend fun start(request: UploadRequest): StartResult

    /** Sends bytes [start] until [start] + [bytes].size of a [total]-byte file; [onSent] gets how many of them left so far. */
    suspend fun put(url: String, start: Long, bytes: ByteArray, total: Long, onSent: (Long) -> Unit = {}): PieceResult
}

internal class ServerBackupApi(
    private val apollo: ApolloClient,
    private val http: HttpClient,
    /** The GraphQL endpoint; the upload URLs the server returns are paths on the same origin. */
    private val serverUrl: String,
) : BackupApi {
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
            kind = if (request.isVideo) MediaKind.VIDEO else MediaKind.PHOTO,
            takenAt = Optional.presentIfNotNull(request.takenAt),
            width = Optional.presentIfNotNull(request.width),
            height = Optional.presentIfNotNull(request.height),
            durationMs = Optional.presentIfNotNull(request.durationMillis),
            location = Optional.presentIfNotNull(request.location?.let { GeoPointInput(lat = it.latitude, lon = it.longitude) }),
            source = Optional.presentIfNotNull(request.source),
            versionOf = Optional.presentIfNotNull(request.versionOf),
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

    override suspend fun put(url: String, start: Long, bytes: ByteArray, total: Long, onSent: (Long) -> Unit): PieceResult {
        val end = start + bytes.size - 1
        val response = http.put(resolve(url)) {
            header("Content-Range", "bytes $start-$end/$total")
            contentType(ContentType.Application.OctetStream)
            setBody(bytes)
            onUpload { sent, _ -> onSent(sent) }
        }
        val offset = response.headers["Upload-Offset"]?.toLongOrNull()
        return when (response.status) {
            HttpStatusCode.OK, HttpStatusCode.Created ->
                PieceResult.Accepted(offset ?: (end + 1), complete = response.status == HttpStatusCode.Created)
            HttpStatusCode.RequestedRangeNotSatisfiable -> PieceResult.WrongOffset(offset ?: 0)
            HttpStatusCode.NotFound -> PieceResult.NoUpload
            HttpStatusCode.Conflict -> PieceResult.HashMismatch
            HttpStatusCode.Unauthorized -> throw BackupServerException("The server refused this device", unauthorized = true)
            else -> throw BackupServerException("Upload failed: HTTP ${response.status.value}")
        }
    }

    private fun resolve(path: String): String = URLBuilder(serverUrl).apply {
        parameters.clear()
        fragment = ""
        encodedPath = path
    }.buildString()

    private fun Throwable.asServerException(): Throwable =
        if (this is ApolloHttpException && statusCode == 401) BackupServerException("The server refused this device", unauthorized = true) else this
}
