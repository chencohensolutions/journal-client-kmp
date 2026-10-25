package com.chencohensolution.journal

import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.server.PrivateFile
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private class SoundStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class SoundLibraryTest {
    private val call = "a".repeat(64)
    private val voice = "d".repeat(64)
    private val photo = "c".repeat(64)

    private fun media(sha: String, kind: String, source: String, call: String = "null") =
        """{"sha256":"$sha","kind":"$kind","fileName":"x","mimeType":"audio/mp4","size":10,"takenAt":"2025-03-17T12:15:19+02:00",""" +
            """"endedAt":"2025-03-17T12:19:11+02:00","width":null,"height":null,"durationMs":232000,"call":$call,"location":null,""" +
            """"source":"$source","uploadedAt":"2026-10-22T10:00:00Z","nudity":null,"aesthetic":null,"private":false}"""

    private fun change(json: String, sha: String) = """{"kind":"MEDIA","path":"media/$sha","deleted":false,"hidden":false,"media":$json}"""

    @Test
    fun soundRecordingsAreKeptApartFromPhotos() = runTest {
        val sinces = mutableListOf<String?>()
        val factory = MtlsHttpClientFactory { _, _ ->
            HttpClient(MockEngine { request ->
                val body = (request.body as io.ktor.http.content.TextContent).text
                sinces += Regex(""""since":"([^"]*)"""").find(body)?.groupValues?.get(1)
                val changes = listOf(
                    change(media(call, "AUDIO", "other", """{"contact":null,"number":"0501234567"}"""), call),
                    change(media(voice, "AUDIO", "whatsapp"), voice),
                    change(media(photo, "PHOTO", "camera"), photo),
                ).joinToString(",")
                respond("""{"data":{"changes":{"cursor":"c1","hasMore":false,"remaining":0,"changes":[$changes]}}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            })
        }
        val settings = JournalSettings(SoundStore()).apply { identityAlias = "hen" }
        val files = FakeFileSystem()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = ServerLibrary(settings, ServerHttp(settings, factory, emptyList()), files, "/server".toPath(), testLibraryDatabase(), dispatcher, io = dispatcher)
        advanceUntilIdle()
        library.sync()

        val byKind = library.media.value.associateBy { it.sha256 }
        assertTrue(byKind.getValue(call).isAudio)
        assertFalse(byKind.getValue(photo).isAudio)
        val recording = byKind.getValue(call).toSoundRecording()
        assertEquals(AudioKind.CALL, recording.kind)
        assertEquals("0501234567", recording.number)
        assertNull(recording.contact)
        assertEquals(232_000, recording.durationMillis)
        assertEquals(AudioKind.VOICE_MESSAGE, byKind.getValue(voice).toSoundRecording().kind)

        // A media.json from before the database is read into it once and deleted: one from before sound recordings
        // were kept (v 1) starts the feed again, one of v 2 goes on from its cursor.
        val saved = "/server/media.json".toPath()
        files.createDirectories(saved.parent!!)
        files.write(saved) { writeUtf8("""{"v":1,"cursor":"c1","media":[]}""") }
        val older = ServerLibrary(settings, ServerHttp(settings, factory, emptyList()), files, "/server".toPath(), testLibraryDatabase(), dispatcher, io = dispatcher)
        advanceUntilIdle()
        assertFalse(files.exists(saved))
        older.sync()
        assertEquals(listOf<String?>(null, null), sinces)
        files.write(saved) { writeUtf8("""{"v":2,"cursor":"c1","media":[]}""") }
        val current = ServerLibrary(settings, ServerHttp(settings, factory, emptyList()), files, "/server".toPath(), testLibraryDatabase(), dispatcher, io = dispatcher)
        advanceUntilIdle()
        current.sync()
        assertEquals("c1", sinces.last())
    }

    @Test
    fun privateFilesStayOutOfTheDatabaseWhileHidden() = runTest {
        val sinces = mutableListOf<String?>()
        val factory = MtlsHttpClientFactory { _, _ ->
            HttpClient(MockEngine { request ->
                val body = (request.body as io.ktor.http.content.TextContent).text
                sinces += Regex(""""since":"([^"]*)"""").find(body)?.groupValues?.get(1)
                val changes = listOf(
                    change(media(photo, "PHOTO", "camera").replace(""""private":false""", """"private":true"""), photo),
                    change(media(voice, "AUDIO", "whatsapp"), voice),
                ).joinToString(",")
                respond("""{"data":{"changes":{"cursor":"c1","hasMore":false,"remaining":0,"changes":[$changes]}}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            })
        }
        val settings = JournalSettings(SoundStore()).apply { identityAlias = "hen" }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = ServerLibrary(
            settings, ServerHttp(settings, factory, emptyList()), FakeFileSystem(), "/server".toPath(), testLibraryDatabase(), dispatcher,
            io = dispatcher, showPrivateAtStart = { false },
        )
        advanceUntilIdle()
        library.sync()
        // Only what hides this phone's copy of it: no row.
        assertEquals(listOf(voice), library.media.value.map { it.sha256 })
        assertEquals(listOf(PrivateFile(photo, "x", 10)), library.privateFiles.value)

        // Shown: the whole feed is read again and brings its row back.
        library.showPrivate(true)
        library.media.first { it.size == 2 }
        assertEquals(listOf<String?>(null, null), sinces)
        assertEquals(setOf(photo, voice), library.media.value.map { it.sha256 }.toSet())
        assertTrue(library.media.value.first { it.sha256 == photo }.isPrivate)
        assertEquals(emptyList(), library.privateFiles.value)

        // Hidden again: the row goes at once, without asking the server.
        library.showPrivate(false)
        library.media.first { it.size == 1 }
        assertEquals(listOf(voice), library.media.value.map { it.sha256 })
        assertEquals(listOf(PrivateFile(photo, "x", 10)), library.privateFiles.value)
        assertEquals(2, sinces.size)
    }

    @Test
    fun aLibraryOpenedAgainGoesOnFromItsDatabase() = runTest {
        val sinces = mutableListOf<String?>()
        val factory = MtlsHttpClientFactory { _, _ ->
            HttpClient(MockEngine { request ->
                val body = (request.body as io.ktor.http.content.TextContent).text
                sinces += Regex(""""since":"([^"]*)"""").find(body)?.groupValues?.get(1)
                val changes = if (sinces.size == 1) change(media(photo, "PHOTO", "camera"), photo) else ""
                respond("""{"data":{"changes":{"cursor":"c${sinces.size}","hasMore":false,"remaining":0,"changes":[$changes]}}}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            })
        }
        val settings = JournalSettings(SoundStore()).apply { identityAlias = "hen" }
        val dispatcher = StandardTestDispatcher(testScheduler)
        val database = testLibraryDatabase()
        val first = ServerLibrary(settings, ServerHttp(settings, factory, emptyList()), FakeFileSystem(), "/server".toPath(), database, dispatcher, io = dispatcher)
        advanceUntilIdle()
        first.sync()

        val again = ServerLibrary(settings, ServerHttp(settings, factory, emptyList()), FakeFileSystem(), "/server".toPath(), database, dispatcher, io = dispatcher)
        advanceUntilIdle()
        assertEquals(listOf(photo), again.media.value.map { it.sha256 })
        again.sync()
        assertEquals(listOf(null, "c1"), sinces)
    }
}
