package com.chencohensolution.journal

import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.library.StoredMediaLibrary
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.PrivateMedia
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FeedPager
import com.chencohensolution.journal.stories.FeedSound
import com.chencohensolution.journal.stories.FeedTable
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.SOUND_PREFIX
import com.chencohensolution.journal.stories.StoriesController
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class HiddenSoundsStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private class HiddenSoundsPhone(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

/**
 * The whole path of calls and recordings hidden and shown again (Settings › Private media › Hidden collections), asked
 * by the owner on Oct 26, 2026, when they stayed in Moments once hidden: the server's feed through [ServerLibrary] into
 * the database, [LibraryController] with its table, and Moments' rows read by [StoriesController]'s [FeedPager].
 */
class HiddenSoundsTest {
    private val photo = "c".repeat(64)
    private val call1 = "a".repeat(64)
    private val call2 = "b".repeat(64)
    private val recording = "d".repeat(64)
    private val secret = "e".repeat(64)
    private val sounds = listOf(call1, call2, recording, secret)

    private fun media(sha: String, kind: String, takenAt: String, endedAt: String, call: String = "null", private: Boolean = false, durationMs: Long = 240_000) =
        """{"sha256":"$sha","kind":"$kind","fileName":"$sha.m4a","mimeType":"audio/mp4","size":10,"takenAt":"$takenAt",""" +
            """"endedAt":"$endedAt","width":null,"height":null,"durationMs":$durationMs,"call":$call,"location":null,""" +
            """"source":"other","uploadedAt":"2026-10-22T10:00:00Z","nudity":null,"aesthetic":null,"private":$private}"""

    private val number = """{"contact":null,"number":"0501234567"}"""

    /** What the server lists of each file while sound recordings are shown. */
    private val listed = mapOf(
        photo to media(photo, "PHOTO", "2025-03-17T14:00:00+02:00", "2025-03-17T14:00:00+02:00", durationMs = 0).replace("audio/mp4", "image/jpeg"),
        call1 to media(call1, "AUDIO", "2025-03-17T10:00:00+02:00", "2025-03-17T10:04:00+02:00", number),
        call2 to media(call2, "AUDIO", "2025-03-17T10:10:00+02:00", "2025-03-17T10:14:00+02:00", number),
        recording to media(recording, "AUDIO", "2025-03-10T12:00:00+02:00", "2025-03-10T12:04:00+02:00"),
        secret to media(secret, "AUDIO", "2025-03-05T12:00:00+02:00", "2025-03-05T12:04:00+02:00", private = true),
    )

    private fun shown(sha: String) = """{"kind":"MEDIA","path":"media/$sha","deleted":false,"hidden":false,"media":${listed.getValue(sha)}}"""
    private fun hidden(sha: String) = """{"kind":"MEDIA","path":"media/$sha","deleted":true,"hidden":true,"media":null}"""

    /** The recordings Moments shows anywhere: as moments, calls or voice messages, or a row's stand-ins. */
    private fun StoriesController.soundsShown(): Set<String> {
        val sections = currentState.feed.entries.filterIsInstance<FeedEntry.Section>().map { it.section }
        return sections.flatMapTo(HashSet()) { section ->
            section.moments.mapNotNull { it.recording?.sha256 } +
                section.sounds.flatMap { sound ->
                    when (sound) {
                        is FeedSound.Calls -> sound.calls.map { it.sha256 }
                        is FeedSound.Voice -> listOf(sound.recording.sha256)
                    }
                } +
                section.span.items.filter { it.id.startsWith(SOUND_PREFIX) }.map { it.id.removePrefix(SOUND_PREFIX) }
        }
    }

    /** The timeline's days and counts: photos only, never recordings. */
    private fun StoriesController.timeline(): Map<LocalDay, Int> =
        currentState.feed.timeline.months.flatMap { it.days }.associate { it.day to it.count }

    private val photoDays = mapOf(LocalDay(2025, 3, 20) to 1, LocalDay(2025, 3, 17) to 1)

    /**
     * A server whose feed is that of [phase]: 1 shown, 2 hidden, 3 shown again. The cursor is the phase's: a feed from
     * it has nothing new.
     */
    private fun server(phase: () -> Int) = MtlsHttpClientFactory { _, _ ->
        HttpClient(MockEngine { request ->
            val body = (request.body as io.ktor.http.content.TextContent).text
            if ("Changes" !in body) return@MockEngine respond("", HttpStatusCode.ServiceUnavailable)
            val since = Regex(""""since":"([^"]*)"""").find(body)?.groupValues?.get(1)
            val now = phase()
            val changes = when {
                since == null -> (listOf(photo) + if (now == 2) emptyList() else sounds).map(::shown)
                since == "c$now" -> emptyList()
                now == 2 -> sounds.map(::hidden)
                else -> sounds.map(::shown)
            }.joinToString(",")
            respond(
                """{"data":{"changes":{"cursor":"c$now","hasMore":false,"remaining":0,"changes":[$changes]}}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        })
    }

    /** The app's controllers on [database], as at a start. */
    private class App(val server: ServerLibrary, val library: LibraryController, val settings: SettingsController, val stories: StoriesController) {
        fun close() {
            stories.close()
            settings.close()
            library.close()
        }
    }

    private fun kotlinx.coroutines.test.TestScope.start(
        database: com.chencohensolution.journal.db.LibraryDatabase,
        factory: MtlsHttpClientFactory,
        privateMedia: MutableStateFlow<PrivateMedia>,
    ): App {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = JournalSettings(HiddenSoundsStore()).apply { identityAlias = "hen" }
        val server = ServerLibrary(settings, ServerHttp(settings, factory, emptyList()), FakeFileSystem(), "/server".toPath(), database, dispatcher, io = dispatcher)
        val day = LocalDay(2025, 3, 20)
        val phone = HiddenSoundsPhone(listOf(MediaItem("1", MediaType.IMAGE, day.toEpochDay() * 86_400_000 + 43_200_000, day, null, 10, 10, 100, "IMG_1.jpg", modifiedMillis = 1)))
        val library = LibraryController(
            StoredMediaLibrary(phone, database, dispatcher), JournalSettings(HiddenSoundsStore()), dispatcher,
            server = server, privateMedia = privateMedia, table = LibraryTable(database),
        )
        val settingsController = SettingsController(JournalSettings(HiddenSoundsStore()), dispatcher)
        val stories = StoriesController(
            noteStore = FilePhotoNoteStore(FakeFileSystem(), "/data/journal".toPath()),
            library = library,
            settings = settingsController,
            newId = { "set" },
            dispatcher = dispatcher,
            feed = FeedPager(FeedTable(database), LibraryTable(database), dispatcher, pageSize = { 10 }),
            shownRow = library::shownRow,
        )
        return App(server, library, settingsController, stories)
    }

    /** Rows made with the recordings before a restart lose them when they are hidden after it. */
    @Test
    fun soundRecordingsHiddenAfterARestartLeaveMoments() = runTest {
        var phase = 1
        val factory = server { phase }
        val database = testLibraryDatabase()
        val privateMedia = MutableStateFlow(PrivateMedia(show = true))
        val first = start(database, factory, privateMedia)
        first.library.refresh()
        advanceUntilIdle()
        first.server.sync()
        advanceUntilIdle()
        assertEquals(sounds.toSet(), first.stories.soundsShown())
        first.close()

        val again = start(database, factory, privateMedia)
        again.library.refresh()
        advanceUntilIdle()
        assertEquals(sounds.toSet(), again.stories.soundsShown(), "kept from before")
        phase = 2
        again.server.sync()
        advanceUntilIdle()
        assertEquals(emptySet(), again.stories.soundsShown(), "hidden")
        assertEquals(photoDays, again.stories.timeline())
        again.close()
    }

    @Test
    fun soundRecordingsHiddenLeaveMomentsAndComeBackWhenShown() = runTest {
        var phase = 1
        val privateMedia = MutableStateFlow(PrivateMedia(show = true))
        val app = start(testLibraryDatabase(), server { phase }, privateMedia)
        val server = app.server
        val library = app.library
        val stories = app.stories
        library.refresh()
        advanceUntilIdle()
        server.sync()
        advanceUntilIdle()

        assertEquals(sounds.toSet(), stories.soundsShown(), "shown at first")
        assertEquals(sounds.toSet(), library.state.value.sounds.map { it.sha256 }.toSet())
        assertEquals(photoDays, stories.timeline())

        // Hidden: the server sends them as deleted and hidden.
        phase = 2
        server.sync()
        advanceUntilIdle()
        assertEquals(emptyList(), library.state.value.sounds)
        assertEquals(emptySet(), stories.soundsShown(), "hidden")
        assertEquals(photoDays, stories.timeline())
        // The photo of the calls' day is still shown.
        assertTrue(stories.currentState.feed.entries.filterIsInstance<FeedEntry.Section>().any { s -> s.section.items.any { it.day == LocalDay(2025, 3, 17) } })

        // Shown again.
        phase = 3
        server.sync()
        advanceUntilIdle()
        assertEquals(sounds.toSet(), stories.soundsShown(), "shown again")
        assertEquals(photoDays, stories.timeline())

        // "Show private media" off: the private recording leaves Moments.
        privateMedia.value = PrivateMedia(show = false)
        advanceUntilIdle()
        server.mediaVersion.first { server.all().none { it.sha256 == secret } }
        advanceUntilIdle()
        assertEquals(sounds.toSet() - secret, stories.soundsShown(), "private hidden")
        assertEquals(photoDays, stories.timeline())

        // On again: the feed is read again from the start and brings it back.
        privateMedia.value = PrivateMedia(show = true)
        advanceUntilIdle()
        server.mediaVersion.first { server.all().any { it.sha256 == secret } }
        advanceUntilIdle()
        assertEquals(sounds.toSet(), stories.soundsShown(), "private shown")
        assertEquals(photoDays, stories.timeline())

        app.close()
    }
}
