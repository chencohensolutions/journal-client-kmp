package com.chencohensolution.journal

import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.server.PreviewFormat
import com.chencohensolution.journal.server.PreviewOptions
import com.chencohensolution.journal.server.PreviewQuality
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.ServerPreviews
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import io.ktor.http.Url
import kotlinx.coroutines.test.runTest
import okio.ByteString.Companion.toByteString
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class PreviewsStore : KeyValueStore {
    val values = mutableMapOf<String, String>("keychain_alias" to "phone", "server_url" to "https://journal.test/query")
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class ServerPreviewsTest {
    private val original = "the original photo, byte for byte".encodeToByteArray()
    private val sha = original.toByteString().sha256().hex()
    private val requests = mutableListOf<Url>()
    private var offline = false
    private val fs = FakeFileSystem()
    private var options = PreviewOptions()

    private val previews = ServerPreviews(
        http = ServerHttp(
            JournalSettings(PreviewsStore()),
            MtlsHttpClientFactory { _, _ ->
                HttpClient(MockEngine { request ->
                    if (offline) throw okio.IOException("offline")
                    requests += request.url
                    val path = request.url.encodedPath
                    when {
                        path == "/media/$sha/preview" -> respond("preview ${request.url.parameters["fmt"]} ${request.url.parameters["q"]}".encodeToByteArray())
                        // Any original asked for gets these bytes, so a wrong sha256 is caught by the check.
                        path.startsWith("/media/") -> respond(original)
                        else -> respond("", HttpStatusCode.NotFound)
                    }
                }) { install(HttpTimeout) }
            },
            emptyList(),
        ),
        fileSystem = fs,
        directory = "/cache/previews".toPath(),
        options = { options },
    )

    @Test
    fun asksForTheChosenFormatAndQualityAndKeepsEachSize() = runTest {
        options = PreviewOptions(format = PreviewFormat.JPEG_XL, quality = PreviewQuality.MAX)
        val first = previews.preview(sha, 640, 480, contain = true)
        assertEquals("preview jxl max", first?.decodeToString())
        assertEquals("w=640&h=480&fit=contain&fmt=jxl&q=max", requests.single().encodedQuery)

        // The same size again comes from this phone; another size is asked for, not scaled from the first.
        previews.preview(sha, 640, 480, contain = true)
        assertEquals(1, requests.size)
        previews.preview(sha, 320, 240, contain = true)
        assertEquals("w=320&h=240&fit=contain&fmt=jxl&q=max", requests.last().encodedQuery)
        assertTrue(fs.exists("/cache/previews/${sha.take(2)}/$sha/640x480-contain-max.jxl".toPath()))

        // Offline, the largest one kept stands in, unless only an exact one will do.
        offline = true
        assertEquals("preview jxl max", previews.preview(sha, 1280, 960, contain = true)?.decodeToString())
        assertNull(previews.preview(sha, 1280, 960, contain = true, orLargest = false))
    }

    @Test
    fun withPreviewsOffTheOriginalIsDownloadedOnceAndChecked() = runTest {
        options = PreviewOptions(enabled = false)
        assertEquals(false, previews.usesPreviews)
        assertNull(previews.cachedOriginal(sha))

        val path = previews.original(sha, "IMG_1234.HEIC")
        assertEquals("/cache/previews/${sha.take(2)}/$sha/original.heic", path)
        assertContentEquals(original, fs.read(path!!.toPath()) { readByteArray() })
        assertEquals(path, previews.cachedOriginal(sha))
        previews.original(sha, "IMG_1234.HEIC")
        assertEquals(1, requests.count { it.encodedPath == "/media/$sha" })
        assertTrue(previews.kept(sha).isEmpty(), "an original isn't a preview")

        // Bytes that don't have the sha256 aren't kept.
        val wrong = "0".repeat(64)
        assertNull(previews.original(wrong, "x.jpg"))
    }
}
