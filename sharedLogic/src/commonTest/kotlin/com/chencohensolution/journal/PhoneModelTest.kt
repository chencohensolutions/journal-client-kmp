package com.chencohensolution.journal

import com.chencohensolution.journal.local.ModelFileInfo
import com.chencohensolution.journal.local.PhoneDownload
import com.chencohensolution.journal.local.PhoneIndex
import com.chencohensolution.journal.local.PhoneIndexKind
import com.chencohensolution.journal.local.PhoneModel
import com.chencohensolution.journal.local.PhoneModelApi
import com.chencohensolution.journal.local.PhoneModelKind
import com.chencohensolution.journal.local.PhoneModelState
import com.chencohensolution.journal.local.PhoneModelStatus
import com.chencohensolution.journal.local.PhoneModels
import com.chencohensolution.journal.local.PhoneSearchState
import com.chencohensolution.journal.local.photoRow
import com.chencohensolution.journal.local.wordsRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val MODELS = "/files/models".toPath()

/** Waits in real time (not the test's virtual time) for work on the model's own scope. */
private suspend fun waitUntil(done: () -> Boolean) = withContext(Dispatchers.Default) {
    withTimeout(5_000) { while (!done()) delay(10) }
}
private val FILE = ModelFileInfo("model.tflite", "", 4, "abc", "/models/phone/model.tflite")

/** A server offering [offered]; each download writes [bytes] whole, or [breaks] (false) or throws ([wrong]). */
private class FakePhoneModelApi(var offered: List<ModelFileInfo> = listOf(FILE)) : PhoneModelApi {
    var breaks = false
    var wrong = false
    var unreachable = false
    var downloads = 0

    override suspend fun phoneModels(): List<ModelFileInfo> {
        if (unreachable) throw IllegalStateException("offline")
        return offered
    }

    override suspend fun model(file: ModelFileInfo, fileSystem: FileSystem, target: Path, progress: (Long) -> Unit): Boolean {
        downloads++
        fileSystem.createDirectories(target.parent!!)
        if (wrong) throw IllegalStateException("${file.name} didn't match its sha256")
        fileSystem.write(target) { writeUtf8(if (breaks) "mo" else "mode") }
        return !breaks
    }
}

class PhoneModelTest {
    @Test
    fun downloadedOnceThenKept() = runTest {
        val fileSystem = FakeFileSystem()
        val api = FakePhoneModelApi()
        val model = PhoneModel("model.tflite", api, fileSystem, MODELS)
        assertNull(model.path())
        assertEquals(MODELS / "model.tflite", model.fetch())
        assertEquals(MODELS / "model.tflite", model.path())
        assertEquals("abc", fileSystem.read(MODELS / "model.tflite.ok") { readUtf8() })

        // Kept: no second download, even with the server unreachable.
        api.unreachable = true
        assertEquals(MODELS / "model.tflite", PhoneModel("model.tflite", api, fileSystem, MODELS).fetch())
        assertEquals(1, api.downloads)
    }

    @Test
    fun nothingWhileTheServerDoesNotOfferIt() = runTest {
        val api = FakePhoneModelApi(offered = emptyList())
        val model = PhoneModel("model.tflite", api, FakeFileSystem(), MODELS)
        assertNull(model.fetch())
        assertEquals(0, api.downloads)
        api.unreachable = true
        assertNull(model.fetch())
    }

    @Test
    fun aBrokenOrWrongDownloadIsNotUsedAndIsTriedAgain() = runTest {
        val fileSystem = FakeFileSystem()
        val api = FakePhoneModelApi()
        val model = PhoneModel("model.tflite", api, fileSystem, MODELS)
        api.breaks = true
        assertNull(model.fetch())
        assertNull(model.path())
        api.breaks = false
        api.wrong = true
        assertNull(model.fetch())
        assertNull(model.path())
        api.wrong = false
        assertEquals(MODELS / "model.tflite", model.fetch())
        assertEquals(3, api.downloads)
    }

    @Test
    fun waitsForWifiUnlessForced() = runTest {
        val fileSystem = FakeFileSystem()
        val api = FakePhoneModelApi()
        val model = PhoneModel("model.tflite", api, fileSystem, MODELS, wifiOnly = { true }, onWifi = { false })
        assertNull(model.fetch())
        assertTrue(model.state.value.waitingForWifi)
        assertEquals(0, api.downloads)
        assertEquals(MODELS / "model.tflite", model.fetch(force = true))
        assertTrue(model.state.value.here)
        assertFalse(model.state.value.waitingForWifi)
    }

    @Test
    fun deletedThenDownloadedAgain() = runTest {
        val fileSystem = FakeFileSystem()
        val api = FakePhoneModelApi()
        val model = PhoneModel("model.tflite", api, fileSystem, MODELS)
        model.fetch()
        model.delete()
        // delete runs on the model's own scope.
        waitUntil { model.path() == null }
        assertNull(model.path())
        assertFalse(model.state.value.here)
        assertEquals(MODELS / "model.tflite", model.fetch())
        assertEquals(2, api.downloads)
    }

    @Test
    fun theServerSaysWhetherItHasIt() = runTest {
        val api = FakePhoneModelApi(offered = emptyList())
        val model = PhoneModel("model.tflite", api, FakeFileSystem(), MODELS)
        assertNull(model.fetch())
        assertEquals(false, model.state.value.offered)
        assertEquals(PhoneModelStatus.NOT_OFFERED, photoRow(model.state.value).status)
        api.unreachable = true
        assertNull(model.fetch())
        assertTrue(model.state.value.unreachable)
    }
}

class PhoneModelsTest {
    private val MB = 1_000_000L

    @Test
    fun thePhotoModelsRow() {
        assertEquals(PhoneModelStatus.MISSING, photoRow(PhoneModelState()).status)
        assertEquals(PhoneModelStatus.HERE, photoRow(PhoneModelState(here = true, bytes = 11 * MB)).status)
        val downloading = photoRow(PhoneModelState(bytes = 11 * MB, downloaded = 4 * MB))
        assertEquals(PhoneModelStatus.DOWNLOADING, downloading.status)
        assertEquals(4 * MB, downloading.downloadedBytes)
        assertEquals(11 * MB, downloading.downloadingBytes)
        assertEquals(PhoneModelStatus.WAITING_FOR_WIFI, photoRow(PhoneModelState(waitingForWifi = true)).status)
        assertEquals(PhoneModelStatus.FAILED, photoRow(PhoneModelState(failed = true, offered = true)).status)
    }

    private fun search(on: Boolean, vararg changes: (PhoneSearchState) -> PhoneSearchState): PhoneSearchState {
        var state = PhoneSearchState(
            PhoneIndexKind.entries.map { PhoneIndex(it, on = on && it == PhoneIndexKind.WORDS, bytes = 440 * MB, modelBytes = if (it == PhoneIndexKind.WORDS) 290 * MB else 0) },
            loaded = true,
        )
        changes.forEach { state = it(state) }
        return state
    }

    @Test
    fun searchByWordsFollowsOnDeviceSearch() {
        val off = wordsRow(search(on = false))
        assertEquals(PhoneModelStatus.MISSING, off.status)
        assertEquals(290 * MB, off.bytes)
        assertEquals(440 * MB, off.withBytes)
        assertEquals(PhoneModelStatus.WAITING_FOR_WIFI, wordsRow(search(true, { it.copy(waitingForWifi = true) })).status)
        val downloading = wordsRow(search(true, { it.copy(download = PhoneDownload(PhoneIndexKind.WORDS, 100 * MB, 290 * MB)) }))
        assertEquals(PhoneModelStatus.DOWNLOADING, downloading.status)
        assertEquals(100 * MB, downloading.downloadedBytes)
        assertEquals(PhoneModelStatus.FAILED, wordsRow(search(true, { it.copy(error = "offline") })).status)
        val here = wordsRow(search(true, { it.copy(wordsModelHere = true) }))
        assertEquals(PhoneModelStatus.HERE, here.status)
        assertEquals(0, here.withBytes)
        val notOffered = search(false, { s -> s.copy(indexes = s.indexes.map { it.copy(modelBytes = 0) }) })
        assertEquals(PhoneModelStatus.NOT_OFFERED, wordsRow(notOffered).status)
    }

    @Test
    fun downloadAllAndDeleteDriveThePhotoModel() = runTest {
        val fileSystem = FakeFileSystem()
        val api = FakePhoneModelApi()
        val photo = PhoneModel("model.tflite", api, fileSystem, MODELS)
        val models = PhoneModels(photo, null)
        assertEquals(listOf(PhoneModelKind.PHOTOS), models.state.value.models.map { it.kind })
        models.downloadAll()
        waitUntil { models.state.value.here == 1 }
        assertEquals(1, models.state.value.here)
        models.delete(PhoneModelKind.PHOTOS)
        waitUntil { models.state.value.here == 0 }
        assertEquals(0, models.state.value.here)
        assertNull(photo.path())
    }
}
