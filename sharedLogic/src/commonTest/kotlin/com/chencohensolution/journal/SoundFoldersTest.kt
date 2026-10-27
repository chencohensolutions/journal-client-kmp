package com.chencohensolution.journal

import com.chencohensolution.journal.library.FolderNames
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.server.ServerFeatures
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerSoundFolder
import com.chencohensolution.journal.server.SoundFolderMark
import com.chencohensolution.journal.server.SoundFoldersController
import com.chencohensolution.journal.server.SoundFoldersState
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
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

private class SoundFoldersStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private fun folder(id: String, name: String, files: Int, imported: Int, mark: String, pending: Int) =
    """{"__typename":"SoundFolder","id":"$id","name":"$name","files":$files,"imported":$imported,"as":"$mark","pending":$pending}"""

private val JSON = headersOf(HttpHeaders.ContentType, "application/json")

class SoundFoldersTest {
    @Test
    fun mergingAFolderIntoCallsFollowsItsFilesUntilTheyMovedThenSyncs() = runTest {
        var calls = "NONE"
        var pending = 0
        val sent = mutableListOf<String>()
        val synced = CompletableDeferred<Unit>()
        val factory = MtlsHttpClientFactory { _, _ ->
            HttpClient(MockEngine { request ->
                val body = (request.body as io.ktor.http.content.TextContent).text
                sent += body
                fun list() = listOf(
                    folder("folder:call", "Call", 1_623, 0, "NONE", 0),
                    folder("folder:calls", "Calls", 1_520, 1_520, calls, pending),
                    folder("folder:voice recorder", "Voice Recorder", 168, 61, "RECORDING", 0),
                ).joinToString(",")
                val answer = when {
                    "SetSoundFolder" in body -> {
                        calls = "CALL"
                        pending = 1_520
                        """{"data":{"setSoundFolder":[${list()}]}}"""
                    }
                    "SoundFolders" in body -> {
                        pending = (pending - 1_000).coerceAtLeast(0)
                        """{"data":{"soundFolders":[${list()}]}}"""
                    }
                    else -> {
                        synced.complete(Unit)
                        """{"data":{"changes":{"cursor":"c1","hasMore":false,"remaining":0,"changes":[]}}}"""
                    }
                }
                respond(answer, HttpStatusCode.OK, JSON)
            })
        }
        val settings = JournalSettings(SoundFoldersStore()).apply { identityAlias = "hen" }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = ServerLibrary(settings, ServerHttp(settings, factory, emptyList()), FakeFileSystem(), "/server".toPath(), testLibraryDatabase(), dispatcher, io = dispatcher)
        val controller = SoundFoldersController(library, dispatcher)
        controller.load()
        val ready = assertIs<SoundFoldersState.Ready>(controller.state.first { it is SoundFoldersState.Ready })
        assertEquals(listOf("Call", "Calls", "Voice Recorder"), ready.folders.map { it.shownName })
        assertEquals(ServerSoundFolder("folder:voice recorder", "Voice Recorder", 168, 61, SoundFolderMark.RECORDING, 0), ready.folders.last())
        val merged = ready.folders[1]

        controller.setMark(merged, SoundFolderMark.CALL)
        val saving = assertIs<SoundFoldersState.Ready>(controller.currentState)
        assertTrue(saving.saving)
        assertEquals(SoundFolderMark.CALL, saving.folders[1].mark)
        val moving = assertIs<SoundFoldersState.Ready>(controller.state.first { it is SoundFoldersState.Ready && !it.saving })
        assertEquals(1_520, moving.pending)
        assertTrue(sent.any { "SetSoundFolder" in it && """"id":"folder:calls"""" in it && """"as":"CALL"""" in it }, sent.joinToString("\n"))

        // Marked so already: nothing is sent.
        val before = sent.size
        controller.setMark(moving.folders[1], SoundFolderMark.CALL)
        assertEquals(before, sent.size)

        // Asked again while files move: 1,520, then 520, then none, and the library syncs to get them.
        controller.state.first { (it as? SoundFoldersState.Ready)?.pending == 520 }
        controller.state.first { (it as? SoundFoldersState.Ready)?.pending == 0 }
        synced.await()
        controller.close()
    }

    @Test
    fun aMarkTheServerDidntTakeGoesBack() = runTest {
        val factory = MtlsHttpClientFactory { _, _ ->
            HttpClient(MockEngine { request ->
                val body = (request.body as io.ktor.http.content.TextContent).text
                if ("SetSoundFolder" in body) {
                    respond("""{"errors":[{"message":"no"}]}""", HttpStatusCode.OK, JSON)
                } else {
                    val answer = """{"data":{"soundFolders":[${folder("folder:super_recorder", "super_recorder", 7, 7, "NONE", 0)}]}}"""
                    respond(answer, HttpStatusCode.OK, JSON)
                }
            })
        }
        val settings = JournalSettings(SoundFoldersStore()).apply { identityAlias = "hen" }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = ServerLibrary(settings, ServerHttp(settings, factory, emptyList()), FakeFileSystem(), "/server".toPath(), testLibraryDatabase(), dispatcher, io = dispatcher)
        val controller = SoundFoldersController(library, dispatcher)
        controller.load()
        val ready = assertIs<SoundFoldersState.Ready>(controller.state.first { it is SoundFoldersState.Ready })
        controller.setMark(ready.folders.single(), SoundFolderMark.RECORDING)
        val after = assertIs<SoundFoldersState.Ready>(controller.state.first { it is SoundFoldersState.Ready && !it.saving })
        assertTrue(after.notChanged)
        assertEquals(SoundFolderMark.NONE, after.folders.single().mark)
        controller.close()
    }

    @Test
    fun theServersFolderNamesAreAskedOncePerSyncAndKept() = runTest {
        FolderNames.clear()
        try {
            var asked = 0
            val factory = MtlsHttpClientFactory { _, _ ->
                HttpClient(MockEngine { request ->
                    val body = (request.body as io.ktor.http.content.TextContent).text
                    val answer = if ("FolderNames" in body) {
                        asked++
                        """{"data":{"folderNames":[{"__typename":"FolderName","id":"folder:calls","name":"Calls"},{"__typename":"FolderName","id":"folder:petcube","name":"PetCube"}]}}"""
                    } else {
                        """{"data":{"changes":{"cursor":"c1","hasMore":false,"remaining":0,"changes":[]}}}"""
                    }
                    respond(answer, HttpStatusCode.OK, JSON)
                })
            }
            val settings = JournalSettings(SoundFoldersStore()).apply {
                identityAlias = "hen"
                serverFeatures = ServerFeatures(soundFolders = true)
            }
            val dispatcher = StandardTestDispatcher(testScheduler)
            val database = testLibraryDatabase()
            val library = ServerLibrary(settings, ServerHttp(settings, factory, emptyList()), FakeFileSystem(), "/server".toPath(), database, dispatcher, io = dispatcher)
            library.loaded.first { it }
            library.sync()
            assertEquals(1, asked)
            assertEquals("Calls", MediaSource.parse("folder:calls")?.folderName)
            assertEquals("PetCube", MediaSource.folder("petcube").folderName)

            // Kept in the database: shown so at the next start, before the server answers.
            FolderNames.clear()
            assertEquals("Calls", MediaSource.parse("folder:calls")?.folderName) // the id's name, with a capital
            assertEquals("Petcube", MediaSource.parse("folder:petcube")?.folderName)
            val again = ServerLibrary(settings, ServerHttp(settings, factory, emptyList()), FakeFileSystem(), "/server".toPath(), database, dispatcher, io = dispatcher)
            again.loaded.first { it }
            assertEquals("PetCube", MediaSource.parse("folder:petcube")?.folderName)
        } finally {
            FolderNames.clear()
        }
    }
}
