package com.chencohensolution.journal.local

import com.chencohensolution.journal.graphql.LocalIndexesQuery
import com.chencohensolution.journal.graphql.PhoneModelsQuery
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.firstServerError
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import io.ktor.utils.io.readFully
import io.ktor.utils.io.readUTF8Line
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.HashingSink
import okio.Path
import okio.buffer
import okio.use

// How the phone gets the server's indexes (journal-server-go docs/search.md "On the phone"): the manifest
// (`localIndexes`), each index as a stream of what was added since a cursor (`GET /index/{name}`), the people whole
// (`GET /index/people`, with an ETag) and the model files a phone runs (`GET /models/{index}/{name}`, with Range).

/** A model file an index needs on the phone: [precision] fp32 or q8 for a model, "" for one every precision needs. */
data class ModelFileInfo(val name: String, val precision: String, val bytes: Long, val sha256: String, val url: String)

/** An index the server lets this phone keep (its `localIndexes`). */
data class LocalIndexInfo(
    /** search, faces, ocr, similar, aesthetic. */
    val name: String,
    val model: String,
    val format: String,
    val dim: Int,
    /** Files read for it that this device may see. */
    val files: Int,
    /** About how much a stream from the start sends. */
    val bytes: Long,
    val models: List<ModelFileInfo>,
) {
    /** The model files this phone downloads: the 8-bit model when there is one, and the files every precision needs. */
    val phoneModels: List<ModelFileInfo>
        get() {
            val precisions = models.map { it.precision }.filter { it.isNotEmpty() }.toSet()
            val chosen = if ("q8" in precisions) "q8" else precisions.firstOrNull()
            return models.filter { it.precision.isEmpty() || it.precision == chosen }
        }
}

/** A stream's first line. */
@Serializable
internal data class StreamHead(
    val v: Int = 1,
    val index: String = "",
    val model: String = "",
    val format: String = "",
    val dim: Int = 0,
    val reset: Boolean = false,
)

/** What a stream says, frame by frame. */
internal interface StreamSink {
    fun head(head: StreamHead)
    fun record(record: ByteArray)
    fun drop(sha256: String)

    /** A cursor to go on from should the stream break after it. */
    fun checkpoint(cursor: String)
}

/** The people document: each person or group with every face of theirs, as `<sha256>:<n>`. */
@Serializable
internal data class PeopleDoc(val v: Int = 1, val model: String = "", val people: List<PeopleDocItem> = emptyList())

@Serializable
internal data class PeopleDocItem(
    val id: String,
    val name: String? = null,
    val faces: List<String> = emptyList(),
    /** For a named person: the faces the user said are them, and those they said are not (to match faces by). */
    val named: List<String> = emptyList(),
    val not: List<String> = emptyList(),
)

/** The stream broke, or the server couldn't be reached: what was applied stays, from the last checkpoint. */
internal class StreamBroke(message: String) : Exception(message)

internal interface LocalIndexApi {
    suspend fun manifest(): List<LocalIndexInfo>

    /** Streams [name] from [cursor] (null: from the start) into [sink]; the cursor for next time, from its end frame. */
    suspend fun stream(name: String, cursor: String?, sink: StreamSink): String

    /** The people document, or null when it is unchanged since [etag]; with its ETag. */
    suspend fun people(etag: String?): Pair<PeopleDoc, String?>?

    /**
     * Downloads [file] into [target], going on from what [target] already has (Range), and checks its sha256. Calls
     * [progress] with the bytes it has. False when it broke (the next try goes on), throws when the bytes are wrong.
     */
    suspend fun model(file: ModelFileInfo, fileSystem: FileSystem, target: Path, progress: (Long) -> Unit): Boolean
}

internal class HttpLocalIndexApi(private val http: ServerHttp) : LocalIndexApi, PhoneModelApi {
    override suspend fun manifest(): List<LocalIndexInfo> = http.graphql { apollo ->
        val response = apollo.query(LocalIndexesQuery()).execute()
        response.exception?.let { throw it }
        firstServerError(response.errors)?.let { throw it }
        response.data?.localIndexes.orEmpty().map { x ->
            LocalIndexInfo(
                name = x.name,
                model = x.model,
                format = x.format,
                dim = x.dim,
                files = x.files,
                bytes = x.bytes,
                models = x.models.map { ModelFileInfo(it.name, it.precision, it.bytes, it.sha256, it.url) },
            )
        }
    } ?: throw StreamBroke("This device isn't registered")

    override suspend fun phoneModels(): List<ModelFileInfo> = http.graphql { apollo ->
        val response = apollo.query(PhoneModelsQuery()).execute()
        response.exception?.let { throw it }
        firstServerError(response.errors)?.let { throw it }
        response.data?.phoneModels.orEmpty().map { ModelFileInfo(it.name, it.precision, it.bytes, it.sha256, it.url) }
    } ?: throw StreamBroke("This device isn't registered")

    override suspend fun stream(name: String, cursor: String?, sink: StreamSink): String {
        val client = http.client() ?: throw StreamBroke("This device isn't registered")
        val query = cursor?.let { "cursor=$it" }
        return client.prepareGet(http.url("/index/$name", query)).execute { response ->
            if (response.status != HttpStatusCode.OK) throw StreamBroke("The server answered ${response.status}")
            readStream(response.bodyAsChannel(), sink)
        }
    }

    override suspend fun people(etag: String?): Pair<PeopleDoc, String?>? {
        val client = http.client() ?: throw StreamBroke("This device isn't registered")
        return client.prepareGet(http.url("/index/people")) {
            etag?.let { header("If-None-Match", it) }
        }.execute { response ->
            when (response.status) {
                HttpStatusCode.NotModified -> null
                HttpStatusCode.OK -> json.decodeFromString(PeopleDoc.serializer(), response.bodyAsText()) to response.headers["ETag"]
                else -> throw StreamBroke("The server answered ${response.status}")
            }
        }
    }

    override suspend fun model(file: ModelFileInfo, fileSystem: FileSystem, target: Path, progress: (Long) -> Unit): Boolean {
        val client = http.client() ?: return false
        fileSystem.createDirectories(target.parent!!)
        val have = fileSystem.metadataOrNull(target)?.size ?: 0
        if (have < file.bytes) {
            val done = client.prepareGet(http.url(file.url)) {
                if (have > 0) header("Range", "bytes=$have-")
            }.execute { response ->
                val append = response.status == HttpStatusCode.PartialContent
                if (!append && response.status != HttpStatusCode.OK) return@execute false
                val sink = if (append) fileSystem.appendingSink(target) else fileSystem.sink(target)
                var received = if (append) have else 0
                sink.buffer().use { out ->
                    val channel = response.bodyAsChannel()
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        val n = channel.readAvailable(buffer)
                        if (n == -1) break
                        out.write(buffer, 0, n)
                        received += n
                        progress(received)
                    }
                }
                received >= file.bytes
            }
            if (!done) return false
        }
        // Checked whole: a file that doesn't match is deleted and downloaded again next time.
        val hashing = HashingSink.sha256(okio.blackholeSink())
        hashing.buffer().use { out -> fileSystem.read(target) { readAll(out) } }
        if (hashing.hash.hex() != file.sha256) {
            fileSystem.delete(target)
            throw StreamBroke("${file.name} didn't match its sha256")
        }
        return true
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}

/**
 * Reads a stream (journal-server-go localindex.ServeIndex): a line of JSON ([StreamHead]), then frames, each a kind byte,
 * the length of what follows (4 bytes, little-endian) and that many bytes. R: a record, D: a sha256 to drop, C: a
 * checkpoint cursor, E: the next look's cursor and the end. A stream without E broke ([StreamBroke]).
 */
internal suspend fun readStream(channel: ByteReadChannel, sink: StreamSink): String {
    val line = channel.readUTF8Line() ?: throw StreamBroke("The stream was empty")
    sink.head(Json { ignoreUnknownKeys = true }.decodeFromString(StreamHead.serializer(), line))
    val head = ByteArray(5)
    while (true) {
        try {
            channel.readFully(head, 0, 5)
        } catch (e: Exception) {
            throw StreamBroke("The stream broke")
        }
        val length = (head[1].toInt() and 0xFF) or ((head[2].toInt() and 0xFF) shl 8) or
            ((head[3].toInt() and 0xFF) shl 16) or ((head[4].toInt() and 0xFF) shl 24)
        val payload = ByteArray(length)
        try {
            if (length > 0) channel.readFully(payload, 0, length)
        } catch (e: Exception) {
            throw StreamBroke("The stream broke")
        }
        when (head[0].toInt().toChar()) {
            'R' -> sink.record(payload)
            'D' -> sink.drop(payload.hex())
            'C' -> sink.checkpoint(payload.decodeToString())
            'E' -> return payload.decodeToString()
        }
    }
}
