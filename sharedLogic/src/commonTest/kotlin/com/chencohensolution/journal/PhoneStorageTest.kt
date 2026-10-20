package com.chencohensolution.journal

import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.ServerPreviews
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.storage.FreeUpActivity
import com.chencohensolution.journal.storage.FreeUpGroup
import com.chencohensolution.journal.storage.FreeUpLine
import com.chencohensolution.journal.storage.FreeUpPlan
import com.chencohensolution.journal.storage.FreeUpProgress
import com.chencohensolution.journal.storage.FreeUpResult
import com.chencohensolution.journal.storage.FreeUpState
import com.chencohensolution.journal.storage.FreeUpSummary
import com.chencohensolution.journal.storage.KeptReason
import com.chencohensolution.journal.storage.MediaKind
import com.chencohensolution.journal.storage.PhoneStorage
import com.chencohensolution.journal.storage.VideoCache
import com.chencohensolution.journal.storage.mediaKind
import com.chencohensolution.journal.storage.megapixels
import com.chencohensolution.journal.storage.percentOf
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class StorageStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private class FakeVideos : VideoCache {
    val bytes = mutableMapOf<String, Long>()
    override fun usedBytes() = bytes.values.sum()
    override fun cachedBytes(sha256: String) = bytes[sha256] ?: 0
    override fun clear() = bytes.clear()
}

class PhoneStorageTest {
    private val fs = FakeFileSystem()
    private val previews = ServerPreviews(
        http = ServerHttp(JournalSettings(StorageStore()), MtlsHttpClientFactory { _, _ -> HttpClient(MockEngine { respond("") }) }, emptyList()),
        fileSystem = fs,
        directory = "/cache/previews".toPath(),
    )
    private val gb = 1L shl 30
    private val storage = PhoneStorage(previews) { 10 * gb }

    @Test
    fun previewsAndVideosShareOneLimitAndOneClear() = runTest {
        val dir = "/cache/previews/ab/ab12".toPath()
        fs.createDirectories(dir)
        fs.write(dir / "640x640-contain-high.webp") { write(ByteArray(1000)) }
        val videos = FakeVideos().apply { bytes["sha-video"] = 5000 }
        storage.attachVideos(videos)

        assertEquals(6000, storage.usedBytes())
        assertEquals(10 * gb - 1000, storage.videoBudget())
        assertEquals(5000L, storage.keptHere("sha-video", isVideo = true, width = 1920, height = 1080).videoBytes)
        assertNull(storage.keptHere("sha-video", isVideo = false, width = 1920, height = 1080).videoBytes)

        storage.clear()
        assertEquals(0, storage.usedBytes())
        assertTrue(videos.bytes.isEmpty())
        assertFalse(fs.exists(dir / "640x640-contain-high.webp"))
    }

    @Test
    fun videosKeepTheirShareHoweverMuchPreviewsTake() {
        val tiny = PhoneStorage(previews) { 100 }
        assertEquals(PhoneStorage.MIN_SHARE_BYTES, tiny.videoBudget())
    }

    @Test
    fun mediaFacts() {
        assertEquals(MediaKind("HEIC"), mediaKind("image/heic"))
        assertEquals(MediaKind("DNG", raw = true), mediaKind("image/x-adobe-dng"))
        assertEquals(MediaKind("MP4", video = true), mediaKind("video/mp4"))
        assertNull(mediaKind("application/x-unknown"))
        assertEquals(12, megapixels(4000, 3000))
        assertNull(megapixels(640, 480))
        assertEquals("3.0%", percentOf(30, 1000))
        assertEquals("0.4%", percentOf(4, 1000))
        assertEquals("25%", percentOf(250, 1000))
        assertNull(percentOf(1, 0))
    }

    @Test
    fun freeUpSummaryAndStatusLines() {
        val photo = MediaItem("1", MediaType.IMAGE, 0, LocalDay(2026, 1, 1), null, 4000, 3000, 1000, "IMG.jpg")
        val plan = FreeUpPlan(listOf(FreeUpGroup(listOf(photo))))
        assertEquals(FreeUpSummary.Counting, FreeUpState().summary)
        assertEquals(FreeUpSummary.Nothing, FreeUpState(plan = FreeUpPlan(emptyList())).summary)
        assertEquals(FreeUpSummary.CanFree(1000), FreeUpState(plan = plan).summary)

        val running = FreeUpState(plan = plan, activity = FreeUpActivity.Running(FreeUpProgress(3, 10, 0, 0, "IMG.jpg")), waitingCount = 2, waitingBytes = 50)
        assertEquals(FreeUpSummary.Checking(4, 10), running.summary)
        assertFalse(running.needsConfirmation, "not while a run goes on")
        assertEquals(listOf(FreeUpLine.Checking(4, 10, "IMG.jpg"), FreeUpLine.Waiting(2, 50)), running.statusLines)

        val done = FreeUpState(
            plan = plan,
            waitingCount = 2,
            waitingBytes = 50,
            lastResult = FreeUpResult(removed = 5, removedBytes = 500, waiting = 2, kept = mapOf(KeptReason.MISSING_ON_SERVER to 1)),
        )
        assertEquals(FreeUpSummary.Waiting(2, 50), done.summary)
        assertTrue(done.needsConfirmation)
        assertEquals(
            listOf(FreeUpLine.Removed(5, 500), FreeUpLine.Kept(KeptReason.MISSING_ON_SERVER, 1), FreeUpLine.Waiting(2, 50)),
            done.statusLines,
        )
        assertEquals(listOf(FreeUpLine.Stopped("offline")), FreeUpState(activity = FreeUpActivity.Stopped("offline")).statusLines)
    }
}
