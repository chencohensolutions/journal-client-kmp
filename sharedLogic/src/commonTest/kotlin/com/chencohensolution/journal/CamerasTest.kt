package com.chencohensolution.journal

import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.server.CameraModel
import com.chencohensolution.journal.server.CamerasController
import com.chencohensolution.journal.server.CamerasState
import com.chencohensolution.journal.server.HiddenCollections
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.cameraName
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.ShownSourcesPills
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

private class CameraStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class CamerasTest {
    @Test
    fun aCameraIsNamedByItsModelWithItsMakeWhenTheModelLacksIt() {
        assertEquals("Canon EOS 80D", cameraName("Canon", "Canon EOS 80D"))
        assertEquals("Samsung SM-S931B", cameraName("samsung", "SM-S931B"))
        assertEquals("iPhone 15 Pro", cameraName("", "iPhone 15 Pro"))
        assertEquals("Nikon", cameraName("nikon", " "))
        assertEquals(null, cameraName(null, ""))
    }

    private fun model(name: String, mine: Boolean, pending: Int) =
        """{"__typename":"CameraModel","make":"Canon","model":"$name","files":312,"imported":300,"years":[2014,2016],"mine":$mine,"marked":${!mine},"pending":$pending}"""

    @Test
    fun markingACameraNotMineFollowsItsFilesUntilTheyMoved() = runTest {
        var pending = 0
        var mine = true
        val sent = mutableListOf<String>()
        val synced = CompletableDeferred<Unit>()
        val factory = MtlsHttpClientFactory { _, _ ->
            HttpClient(MockEngine { request ->
                val body = (request.body as io.ktor.http.content.TextContent).text
                sent += body
                val json = when {
                    "SetCamera" in body -> {
                        mine = false
                        pending = 312
                        """{"data":{"setCamera":[${model("Canon EOS 80D", mine, pending)}]}}"""
                    }
                    "Cameras" in body -> {
                        pending = (pending - 200).coerceAtLeast(0)
                        """{"data":{"cameras":[${model("Canon EOS 80D", mine, pending)}]}}"""
                    }
                    else -> {
                        synced.complete(Unit)
                        """{"data":{"changes":{"cursor":"c1","hasMore":false,"remaining":0,"changes":[]}}}"""
                    }
                }
                respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            })
        }
        val settings = JournalSettings(CameraStore()).apply { identityAlias = "hen" }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = ServerLibrary(settings, ServerHttp(settings, factory, emptyList()), FakeFileSystem(), "/server".toPath(), testLibraryDatabase(), dispatcher, io = dispatcher)
        val controller = CamerasController(library, dispatcher)
        controller.load()
        val ready = assertIs<CamerasState.Ready>(controller.state.first { it is CamerasState.Ready })
        val canon = ready.cameras.single()
        assertEquals(CameraModel("Canon", "Canon EOS 80D", 312, 300, listOf(2014, 2016), mine = true, marked = false, pending = 0), canon)
        assertEquals("2014", canon.firstYear.toString())

        controller.setMine(canon, mine = false)
        val saving = assertIs<CamerasState.Ready>(controller.currentState)
        assertTrue(saving.saving)
        assertEquals(false, saving.cameras.single().mine)
        val moving = assertIs<CamerasState.Ready>(controller.state.first { it is CamerasState.Ready && !it.saving })
        assertEquals(312, moving.pending)
        assertTrue(sent.any { """"make":"Canon","model":"Canon EOS 80D","mine":false""" in it }, sent.joinToString("\n"))

        // Asked again while files move: 312, then 112, then none, and the library syncs to get them.
        controller.state.first { (it as? CamerasState.Ready)?.pending == 112 }
        controller.state.first { (it as? CamerasState.Ready)?.pending == 0 }
        synced.await()
        controller.close()
    }

    @Test
    fun showInTheAppSaysWhatPrivateSettingsHides() {
        val counts = mapOf(
            MediaSource.CAMERA to 1240,
            MediaSource.SCREENSHOTS to 88,
            MediaSource.folder("Petcube") to 5,
            MediaSource.imported(MediaSource.CAMERA) to 38410,
            MediaSource.imported(MediaSource.SCREENSHOTS) to 540,
        )
        val shown = ShownSources()
        // Before Private settings is opened, nothing is known of it.
        val before = ShownSourcesPills.of(shown, counts, hidden = null)
        assertEquals(false, before.privateKnown)
        assertEquals(listOf("camera", "screenshots", "folder:Petcube", "folder:WhatsApp", "other"), before.fromPhones.map { it.source.id })
        assertTrue(before.fromPhones.none { it.allHiddenEverywhere || it.hiddenEverywhere > 0 })

        val hidden = HiddenCollections(
            apps = listOf(HiddenCollections.Collection("WhatsApp", 1650, true, imported = 1600)),
            screenshotsWithoutApp = HiddenCollections.Collection("", 0, false),
            screenshotApps = listOf(HiddenCollections.Collection("Bumble", 1640, true, imported = 1600)),
            nudity = HiddenCollections.Nudity(true, 50, 0, 0),
        )
        val after = ShownSourcesPills.of(shown, counts, hidden)
        assertTrue(after.privateKnown)
        val whatsapp = after.fromPhones.single { it.source == MediaSource.WHATSAPP }
        assertTrue(whatsapp.allHiddenEverywhere)
        assertEquals(MediaSource.WHATSAPP, after.fromPhones.last().source) // locked ones last
        assertEquals(40, after.fromPhones.single { it.source == MediaSource.SCREENSHOTS }.hiddenEverywhere)
        // Imported: its kinds, WhatsApp among them though none of it reaches this phone; screenshots hidden at first.
        assertTrue(after.importedKinds.single { it.source == MediaSource.imported(MediaSource.WHATSAPP) }.allHiddenEverywhere)
        assertEquals(false, after.importedKinds.single { it.source == MediaSource.imported(MediaSource.SCREENSHOTS) }.shown)
        assertEquals(38410 + 540, after.importedCount)
        assertEquals(listOf(MediaSource.WHATSAPP), after.hiddenWhole)
        assertEquals(mapOf(MediaSource.SCREENSHOTS to 1640), after.hiddenInPart)
    }
}
