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
        assertEquals("Samsung SM-X999", cameraName("samsung", "SM-X999"))
        assertEquals("iPhone 15 Pro", cameraName("", "iPhone 15 Pro"))
        assertEquals("Nikon", cameraName("nikon", " "))
        assertEquals(null, cameraName(null, ""))
    }

    @Test
    fun theAppNamesTheModelsItKnowsAndKeepsS25AndS25PlusApart() {
        assertEquals("Samsung Galaxy S25", cameraName("samsung", "SM-S931B"))
        assertEquals("Samsung Galaxy S25", cameraName("samsung", "Galaxy S25"))
        assertEquals("Samsung Galaxy S25", cameraName("", "Galaxy S25"))
        assertEquals("Samsung Galaxy S25", cameraName("SAMSUNG", "sm-s931b"))
        assertEquals("Samsung Galaxy S25+", cameraName("samsung", "SM-S936B"))
        assertEquals("Samsung Galaxy S25+", cameraName("samsung", "Galaxy S25+"))
        assertEquals("Samsung Galaxy S25+", cameraName(null, "galaxy s25+"))
        assertEquals("Samsung Galaxy S20 FE 5G", cameraName("samsung", "SM-G781B"))
        assertEquals("Sony Xperia XZ2 Compact", cameraName("Sony", "H8324"))
        assertEquals("LG Optimus L5 II", cameraName("LGE", "LG-E450"))
        assertEquals("LG Optimus L5 II", cameraName("LG Electronics", "LG-E450"))
        assertEquals("Huawei P9 lite mini", cameraName("HUAWEI", "SLA-L22"))
        assertEquals("Samsung Galaxy S III", cameraName("SAMSUNG", "GT-I9300"))
    }

    @Test
    fun makesAreSpelledAsTheAppSpellsThemAndNotRepeated() {
        assertEquals("Nikon D800", cameraName("NIKON CORPORATION", "NIKON D800"))
        assertEquals("Nikon D5100", cameraName("NIKON CORPORATION", "NIKON D5100"))
        assertEquals("Canon PowerShot SX10 IS", cameraName("Canon", "Canon PowerShot SX10 IS"))
        assertEquals("HTC Wildfire", cameraName("HTC", "Wildfire"))
        assertEquals("Apple iPhone SE", cameraName("Apple", "iPhone SE"))
        assertEquals("LG K420", cameraName("LGE", "K420"))
        assertEquals("LG-K420", cameraName("LGE", "LG-K420"))
        assertEquals("Huawei ANE-LX1", cameraName("HUAWEI", "ANE-LX1"))
        // A maker the app doesn't list keeps its spelling, with a capital.
        assertEquals("Leica M11", cameraName("leica", "M11"))
    }

    private fun camera(make: String, model: String, files: Int, years: List<Int>, mine: Boolean = true, marked: Boolean = false, pending: Int = 0) =
        CameraModel(make, model, files, imported = files / 2, years = years, mine = mine, marked = marked, pending = pending)

    @Test
    fun modelsWithOneNameAreOneCameraMineOnlyWhenEveryOneIs() {
        val ready = CamerasState.Ready(
            listOf(
                camera("Sony", "H8324", 27298, listOf(2018, 2019)),
                camera("samsung", "Galaxy S25", 8498, listOf(2025, 2026), marked = true),
                camera("samsung", "Galaxy S25+", 3415, listOf(2025)),
                camera("samsung", "SM-S936B", 61, listOf(2025), mine = false, marked = true, pending = 20),
                camera("samsung", "SM-S931B", 43, listOf(2024, 2025), marked = true, pending = 5),
                camera("", "Galaxy S25", 1, listOf(2026), marked = true),
            ),
        )
        assertEquals(listOf("Sony Xperia XZ2 Compact", "Samsung Galaxy S25", "Samsung Galaxy S25+"), ready.cameras.map { it.name })
        val s25 = ready.cameras.single { it.name == "Samsung Galaxy S25" }
        assertEquals(listOf("Galaxy S25", "SM-S931B", "Galaxy S25"), s25.members.map { it.model })
        assertEquals(8498 + 43 + 1, s25.files)
        assertEquals(4249 + 21 + 0, s25.imported)
        assertEquals(listOf(2024, 2025, 2026), s25.years)
        assertEquals(5, s25.pending)
        assertTrue(s25.mine)
        assertTrue(s25.marked)
        val s25Plus = ready.cameras.single { it.name == "Samsung Galaxy S25+" }
        assertEquals(3415 + 61, s25Plus.files)
        assertEquals(false, s25Plus.mine) // one of its models isn't the user's
        assertEquals(false, s25Plus.marked)
        assertEquals(25, ready.pending)
    }

    @Test
    fun markingACameraMarksEveryModelOfItAsTheServerTagsIt() = runTest {
        val models = mutableListOf(
            """{"__typename":"CameraModel","make":"samsung","model":"Galaxy S25","files":8498,"imported":0,"years":[2025],"mine":true,"marked":false,"pending":0}""",
            """{"__typename":"CameraModel","make":"samsung","model":"SM-S931B","files":43,"imported":0,"years":[2025],"mine":true,"marked":false,"pending":0}""",
            """{"__typename":"CameraModel","make":"","model":"Galaxy S25","files":1,"imported":0,"years":[2025],"mine":true,"marked":false,"pending":0}""",
            """{"__typename":"CameraModel","make":"samsung","model":"Galaxy S25+","files":3415,"imported":0,"years":[2025],"mine":true,"marked":false,"pending":0}""",
        )
        val sent = mutableListOf<String>()
        val factory = MtlsHttpClientFactory { _, _ ->
            HttpClient(MockEngine { request ->
                val body = (request.body as io.ktor.http.content.TextContent).text
                val json = if ("SetCamera" in body) {
                    sent += body
                    // Marked: kept as sent, so the switch stays as moved.
                    for (i in models.indices) {
                        val make = Regex(""""make":"([^"]*)"""").find(body)!!.groupValues[1]
                        val model = Regex(""""model":"([^"]*)"""").find(body)!!.groupValues[1]
                        if (""""make":"$make","model":"$model",""" in models[i]) models[i] = models[i].replace(""""marked":false""", """"marked":true""")
                    }
                    """{"data":{"setCamera":[${models.joinToString(",")}]}}"""
                } else {
                    """{"data":{"cameras":[${models.joinToString(",")}]}}"""
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
        assertEquals(listOf(8542, 3415), ready.cameras.map { it.files })
        val s25 = ready.cameras.first()

        controller.setMine(s25, mine = true)
        val done = assertIs<CamerasState.Ready>(controller.state.first { it is CamerasState.Ready && !it.saving })
        assertEquals(3, sent.size, sent.joinToString("\n"))
        assertTrue(sent.any { """"make":"samsung","model":"Galaxy S25","mine":true""" in it }, sent.joinToString("\n"))
        assertTrue(sent.any { """"make":"samsung","model":"SM-S931B","mine":true""" in it }, sent.joinToString("\n"))
        assertTrue(sent.any { """"make":"","model":"Galaxy S25","mine":true""" in it }, sent.joinToString("\n"))
        assertTrue(sent.none { "Galaxy S25+" in it })
        assertEquals(false, done.notChanged)
        assertTrue(done.cameras.first().marked)
        assertEquals(false, done.cameras.last().marked)
        controller.close()
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
        assertEquals("Canon EOS 80D", canon.name)
        assertEquals(CameraModel("Canon", "Canon EOS 80D", 312, 300, listOf(2014, 2016), mine = true, marked = false, pending = 0), canon.members.single())
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
