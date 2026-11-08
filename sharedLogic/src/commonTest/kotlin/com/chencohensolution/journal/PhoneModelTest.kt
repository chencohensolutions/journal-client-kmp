package com.chencohensolution.journal

import com.chencohensolution.journal.local.ModelFileInfo
import com.chencohensolution.journal.local.PhoneModel
import com.chencohensolution.journal.local.PhoneModelApi
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val MODELS = "/files/models".toPath()
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
}
