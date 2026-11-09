package com.chencohensolution.journal

import com.chencohensolution.journal.local.HttpLocalIndexApi
import com.chencohensolution.journal.local.ModelFileInfo
import com.chencohensolution.journal.local.PhoneModel
import com.chencohensolution.journal.local.PhoneModelApi
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpStatusCode
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.encodeUtf8
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val CONTENT = "the model's bytes"
private val SHA = CONTENT.encodeUtf8().sha256().hex()
private const val SOURCE = "https://huggingface.co/org/model/resolve/0123abc/model.onnx"
private val TARGET = "/files/models/model.onnx".toPath()
private val SOURCED = ModelFileInfo("model.onnx", "q8", CONTENT.length.toLong(), SHA, "/models/search/model.onnx", SOURCE)

private class SourceStore : KeyValueStore {
    val values = mutableMapOf("keychain_alias" to "phone", "server_url" to "https://journal.test/query")
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** Answers [request] with [content] from its Range on (206), or whole (200). */
private fun MockRequestHandleScope.serve(request: HttpRequestData, content: String) =
    request.headers[HttpHeaders.Range]?.removePrefix("bytes=")?.removeSuffix("-")?.toInt()?.let { from ->
        respond(content.substring(from).encodeToByteArray(), HttpStatusCode.PartialContent)
    } ?: respond(content.encodeToByteArray())

class ModelSourceTest {
    private val fs = FakeFileSystem()
    private val server = mutableListOf<String?>()
    private val internet = mutableListOf<String?>()

    /** What the source sends (null: it can't be reached); the server always has the right bytes. */
    private var sourceContent: String? = CONTENT

    private val api = HttpLocalIndexApi(
        ServerHttp(
            JournalSettings(SourceStore()),
            MtlsHttpClientFactory { _, _ ->
                HttpClient(MockEngine { request ->
                    server += request.headers[HttpHeaders.Range]
                    assertEquals("/models/search/model.onnx", request.url.encodedPath)
                    serve(request, CONTENT)
                })
            },
            emptyList(),
        ),
        internet = {
            HttpClient(MockEngine { request ->
                internet += request.headers[HttpHeaders.Range]
                assertEquals(SOURCE, request.url.toString())
                val content = sourceContent ?: throw okio.IOException("offline")
                serve(request, content)
            })
        },
    )

    private fun read() = fs.read(TARGET) { readUtf8() }

    @Test
    fun downloadsFromTheSourceFirst() = runTest {
        assertTrue(api.model(SOURCED, fs, TARGET) {})
        assertEquals(CONTENT, read())
        assertEquals(listOf<String?>(null), internet)
        assertEquals(emptyList(), server)
    }

    @Test
    fun goesOnFromWhatCameWithRange() = runTest {
        fs.createDirectories(TARGET.parent!!)
        fs.write(TARGET) { writeUtf8(CONTENT.take(5)) }
        assertTrue(api.model(SOURCED, fs, TARGET) {})
        assertEquals(CONTENT, read())
        assertEquals(listOf<String?>("bytes=5-"), internet)
    }

    @Test
    fun aSourceThatCantBeReachedFallsBackToTheServer() = runTest {
        sourceContent = null
        assertTrue(api.model(SOURCED, fs, TARGET) {})
        assertEquals(CONTENT, read())
        assertEquals(listOf<String?>(null), server)
    }

    @Test
    fun aSourceThatSentOtherBytesIsDownloadedAgainFromTheServer() = runTest {
        sourceContent = "something else entirely"
        assertTrue(api.model(SOURCED, fs, TARGET) {})
        assertEquals(CONTENT, read())
        // From the start: nothing the source sent is kept.
        assertEquals(listOf<String?>(null), server)
    }

    @Test
    fun withoutASourceOnlyTheServer() = runTest {
        assertTrue(api.model(SOURCED.copy(source = null), fs, TARGET) {})
        assertEquals(CONTENT, read())
        assertEquals(emptyList(), internet)
        assertEquals(listOf<String?>(null), server)
    }

    @Test
    fun theServersBytesAreCheckedToo() = runTest {
        sourceContent = null
        assertFailsWith<Exception> { api.model(SOURCED.copy(sha256 = "0".repeat(64)), fs, TARGET) {} }
        assertTrue(!fs.exists(TARGET))
    }
}

/** A server whose phoneModels are [offered] (throws when [unreachable]); remembers the file it was asked for. */
private class OfferingApi(var offered: List<ModelFileInfo> = emptyList()) : PhoneModelApi {
    var unreachable = false
    val asked = mutableListOf<ModelFileInfo>()

    override suspend fun phoneModels(): List<ModelFileInfo> {
        if (unreachable) throw IllegalStateException("offline")
        return offered
    }

    override suspend fun model(file: ModelFileInfo, fileSystem: FileSystem, target: Path, progress: (Long) -> Unit): Boolean {
        asked += file
        fileSystem.createDirectories(target.parent!!)
        fileSystem.write(target) { writeUtf8(CONTENT) }
        return true
    }
}

class BuiltInPhoneModelTest {
    private val builtIn = ModelFileInfo("photo.tflite", "", CONTENT.length.toLong(), SHA, "/models/phone/photo.tflite", "https://storage.googleapis.com/photo.tflite")
    private val models = "/files/models".toPath()

    @Test
    fun downloadedFromItsSourceWhenTheServerDoesntListIt() = runTest {
        val api = OfferingApi()
        val model = PhoneModel("photo.tflite", api, FakeFileSystem(), models, builtIn = builtIn)
        assertEquals(models / "photo.tflite", model.fetch())
        assertEquals(listOf(builtIn), api.asked)
    }

    @Test
    fun downloadedFromItsSourceWhenTheServerCantBeReached() = runTest {
        val api = OfferingApi().apply { unreachable = true }
        val model = PhoneModel("photo.tflite", api, FakeFileSystem(), models, builtIn = builtIn)
        assertEquals(models / "photo.tflite", model.fetch())
        assertEquals(listOf(builtIn), api.asked)
    }

    @Test
    fun theServersEntryGetsTheSourceWhenItIsTheSameFile() = runTest {
        val served = builtIn.copy(url = "/models/phone/photo.tflite?v=2", source = null)
        val api = OfferingApi(listOf(served))
        PhoneModel("photo.tflite", api, FakeFileSystem(), models, builtIn = builtIn).fetch()
        assertEquals(listOf(served.copy(source = builtIn.source)), api.asked)
    }

    @Test
    fun aNewerFileOnTheServerIsTakenAsTheServerSaysIt() = runTest {
        val newer = builtIn.copy(sha256 = "f".repeat(64), source = null)
        val api = OfferingApi(listOf(newer))
        PhoneModel("photo.tflite", api, FakeFileSystem(), models, builtIn = builtIn).fetch()
        assertEquals(listOf(newer), api.asked)
    }
}
