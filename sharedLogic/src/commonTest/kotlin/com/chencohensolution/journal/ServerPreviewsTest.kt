package com.chencohensolution.journal

import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.server.KeptPreview
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

    /** Decodes as text, saying what was decoded; [originals] false: this device can't decode originals (a DNG 1.7). */
    private fun decoders(originals: Boolean = true) = ServerPreviews.Decoders(
        original = { path -> if (originals) "original" else null },
        preview = { bytes -> bytes.decodeToString() },
    )

    @Test
    fun aTileUsesTheOriginalHereThenTheExactPreviewAndAVideoItsPoster() = runTest {
        // A video: its poster at that size, never its original.
        assertEquals("preview webp high", previews.tile(sha, isVideo = true, "VID.mp4", 320, 240, decoders()))
        assertEquals(0, requests.count { it.encodedPath == "/media/$sha" })

        // Previews on: the exact preview, made by the server.
        assertEquals("preview webp high", previews.tile(sha, isVideo = false, "IMG.HEIC", 640, 480, decoders()))
        // An original in the cache wins over any preview.
        previews.original(sha, "IMG.HEIC")
        requests.clear()
        assertEquals("original", previews.tile(sha, isVideo = false, "IMG.HEIC", 1080, 810, decoders()))
        assertTrue(requests.isEmpty(), "nothing asked for")
    }

    @Test
    fun withPreviewsOffATileDownloadsTheOriginalAndFallsBackToAPreviewWhenItCantBeDecoded() = runTest {
        options = PreviewOptions(enabled = false)
        assertEquals("preview webp high", previews.tile(sha, isVideo = false, "IMG.dng", 640, 480, decoders(originals = false)))
        assertEquals(1, requests.count { it.encodedPath == "/media/$sha" }, "the original was downloaded first")

        // Downloaded now, the original wins over the preview kept, and nothing is asked for.
        requests.clear()
        assertEquals("original", previews.tile(sha, isVideo = false, "IMG.dng", 640, 480, decoders()))
        assertTrue(requests.isEmpty())

        // A photo without its original here: an exact preview kept is used as it is, nothing downloaded.
        val other = "2".repeat(64)
        val dir = "/cache/previews/22/$other".toPath()
        fs.createDirectories(dir)
        fs.write(dir / "320x240-contain-high.webp") { writeUtf8("kept preview") }
        assertEquals("kept preview", previews.tile(other, isVideo = false, "x.jpg", 320, 240, decoders()))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun theViewerDownloadsTheOriginalAndKeepsWhatItHadOffline() = runTest {
        assertNull(previews.largestPreview(sha))
        previews.preview(sha, 640, 480, contain = true)
        assertEquals("preview webp high", previews.largestPreview(sha)?.decodeToString())

        assertEquals("original", previews.viewer(sha, "IMG.HEIC", 2560, decoders()))
        assertTrue(previews.cachedOriginal(sha) != null, "kept for next time")
        // Only when it can't be decoded: a preview at the viewer's size.
        assertEquals("preview webp high", previews.viewer(sha, "IMG.HEIC", 2560, decoders(originals = false)))
        assertEquals("w=2560&h=2560&fit=contain&fmt=webp&q=high", requests.last().encodedQuery)

        val other = "1".repeat(64)
        offline = true
        assertNull(previews.viewer(other, "x.jpg", 2560, decoders()))
    }

    @Test
    fun keptHereReadsEachPreviewsSizeFormatAndQuality() = runTest {
        val dir = "/cache/previews/${sha.take(2)}/$sha".toPath()
        fs.createDirectories(dir)
        for ((name, bytes) in listOf(
            "2560x2560-contain-max.jxl" to 400, // a 4000×3000 photo fitted inside: 2560×1920
            "1080x1440-cover-high.webp" to 200, // filling its box
            "640x640-contain.webp" to 50, // named before formats could be chosen
            "1920x1080-poster.webp" to 100, // a video frame made on the phone
        )) {
            fs.write(dir / name) { write(ByteArray(bytes)) }
        }
        previews.original(sha, "IMG.HEIC")

        val kept = previews.keptHere(sha, photoWidth = 4000, photoHeight = 3000)
        assertEquals(listOf(400L, 200L, 100L, 50L), kept.previews.map { it.bytes })
        val (jxl, webp, poster, old) = kept.previews
        assertEquals(KeptPreview(2560, 1920, PreviewFormat.JPEG_XL, PreviewQuality.MAX, madeHere = false, bytes = 400), jxl)
        assertEquals(1080 to 1440, webp.width to webp.height)
        assertEquals(PreviewQuality.HIGH, webp.quality)
        assertTrue(poster.madeHere)
        assertEquals(640 to 480, old.width to old.height)
        assertNull(old.quality)
        assertEquals(original.size.toLong(), kept.originalBytes)
        assertEquals(750L + original.size, kept.totalBytes)

        assertEquals("https://journal.test/media/$sha", previews.mediaUrl(sha))
    }
}
