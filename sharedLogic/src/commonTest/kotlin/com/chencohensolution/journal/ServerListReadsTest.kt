package com.chencohensolution.journal

import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.library.StoredMediaLibrary
import com.chencohensolution.journal.local.shownFiles
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.search.serverSha
import com.chencohensolution.journal.server.FeedPage
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.server.ServerLibrary
import com.chencohensolution.journal.server.ServerMedia
import com.chencohensolution.journal.server.ServerMediaStore
import com.chencohensolution.journal.server.parseTimestamp
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.PrivateMedia
import com.chencohensolution.journal.stories.GeoArea
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class ReadsTestStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private class ReadsTestLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private val NAMES = listOf("IMG_1.jpg", "img_1.JPG", "Call 0504.m4a", "PTT-2026.opus", "photo.HEIC", "PHOTO.heic", "a.mp4")
private val SIZES = listOf(10L, 11L, 12L)

private fun readsSha(n: Int) = n.toString(16).padStart(64, '0')

/** A made-up server file: names and sizes from a few, so many share both; times of several years, some unreadable. */
private fun randomMedia(random: Random, n: Int): ServerMedia {
    val year = 2023 + random.nextInt(4)
    val takenAt = when (random.nextInt(6)) {
        0 -> null
        1 -> "not a time"
        // Late on Dec 31 east of UTC: still that year where it was taken.
        2 -> "$year-12-31T23:30:00+05:00"
        else -> "$year-0${1 + random.nextInt(9)}-1${random.nextInt(10)}T0${random.nextInt(10)}:15:00Z"
    }
    val located = random.nextInt(3) != 0
    return ServerMedia(
        sha256 = readsSha(n), isVideo = random.nextInt(5) == 0, fileName = NAMES[random.nextInt(NAMES.size)], mimeType = "image/jpeg",
        size = SIZES[random.nextInt(SIZES.size)], takenAt = takenAt, width = if (random.nextBoolean()) 4000 else null, height = 3000,
        durationMillis = if (random.nextBoolean()) 61_000 else null,
        latitude = if (located) -10.0 + random.nextInt(40) else null, longitude = if (located) 20.0 + random.nextInt(30) else null,
        source = listOf("camera", "whatsapp", null, "folder:Calls")[random.nextInt(4)],
        uploadedAt = if (random.nextInt(10) == 0) "never" else "2026-10-2${random.nextInt(5)}T10:00:00Z",
        isPrivate = random.nextInt(4) == 0, aesthetic = if (random.nextBoolean()) 5.5 else null,
        isAudio = random.nextInt(3) == 0, isCall = random.nextInt(4) == 0, callContact = if (random.nextBoolean()) "Mom" else null,
        callNumber = if (random.nextBoolean()) "0501234567" else null,
    )
}

/**
 * What LocalSearchApi kept of the whole list before step 6e: the files it may find, with when each was taken, and how
 * many files were counted. Copied from the code it replaced, as the reference.
 */
private fun searchedBefore(list: List<ServerMedia>, showPrivate: Boolean, years: List<Int>?, area: GeoArea?): Pair<Map<String, Long?>, Int> {
    val files = list.associateBy { it.sha256 }
    fun yearOf(file: ServerMedia): Int? = (file.takenAt ?: file.uploadedAt).let(::parseTimestamp)?.day?.year
    val keep: (String) -> Boolean = keep@{ sha ->
        val file = files[sha] ?: return@keep false
        if (file.isPrivate && !showPrivate) return@keep false
        if (years != null && yearOf(file) !in years) return@keep false
        if (area != null) {
            val lat = file.latitude ?: return@keep false
            val lon = file.longitude ?: return@keep false
            if (lat < area.south || lat > area.north || lon < area.west || lon > area.east) return@keep false
        }
        true
    }
    val taken = files.keys.filter(keep).associateWith { sha -> files[sha]?.let { f -> (f.takenAt ?: f.uploadedAt).let(::parseTimestamp)?.millis } }
    return taken to files.values.count { !it.isPrivate || showPrivate }
}

/** What PhoneFallbackPeopleApi kept of the whole list before step 6e: the files shown, with when each was taken. */
private fun peopleBefore(list: List<ServerMedia>, includePrivate: Boolean): Map<String, Long?> =
    list.filter { includePrivate || !it.isPrivate }.associateBy { it.sha256 }
        .mapValues { (_, f) -> (f.takenAt ?: f.uploadedAt).let(::parseTimestamp)?.millis }

class ServerListReadsTest {
    private fun TestScope.serverLibrary(database: com.chencohensolution.journal.db.LibraryDatabase): ServerLibrary {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = JournalSettings(ReadsTestStore())
        val factory = MtlsHttpClientFactory { _, _ -> error("no network in this test") }
        return ServerLibrary(settings, ServerHttp(settings, factory, emptyList()), FakeFileSystem(), "/server".toPath(), database, dispatcher, io = dispatcher)
    }

    /**
     * Over generated lists changed in rounds (files added, written again, deleted, private ones made keys and back), each
     * read of the server's list from the database gives what the whole list in memory gave: a photo's sha256 by name and
     * size (the first written of several), a file by sha256, the sound recordings, and what search and people show.
     */
    @Test
    fun readsFromTheDatabaseEqualTheWholeList() = runTest {
        var named = 0
        var several = 0
        repeat(30) { seed ->
            val random = Random(seed)
            val database = testLibraryDatabase()
            val store = ServerMediaStore(database)
            val library = serverLibrary(database)
            advanceUntilIdle()
            var showPrivate = true
            repeat(5) { round ->
                val media = (0 until 25).map { randomMedia(random, random.nextInt(60)) }.distinctBy { it.sha256 }
                val gone = (0 until 5).map { readsSha(random.nextInt(60)) }.filter { sha -> media.none { it.sha256 == sha } }
                store.apply(FeedPage(media, gone, emptyList(), emptyList(), "c$round"), showPrivate)
                if (random.nextInt(3) == 0) {
                    showPrivate = !showPrivate
                    store.holdPrivate(showPrivate)
                }
                val list = store.media()
                val where = "seed $seed round $round"

                // A photo backup hasn't read, found by its name in any case and its size; one with a sha256 keeps it.
                repeat(20) {
                    val item = MediaItem(
                        "d$it", MediaType.IMAGE, 0, LocalDay(2026, 10, 1), null, 10, 10,
                        if (random.nextInt(8) == 0) null else SIZES[random.nextInt(SIZES.size)],
                        if (random.nextInt(8) == 0) null else NAMES[random.nextInt(NAMES.size)].let { if (random.nextBoolean()) it.uppercase() else it.lowercase() },
                        modifiedMillis = 1, sha256 = if (random.nextInt(6) == 0) readsSha(1000 + it) else null,
                    )
                    val expected = serverSha(item, list)
                    assertEquals(expected, library.sha256Of(item), "$where: ${item.fileName} ${item.sizeBytes}")
                    if (item.sha256 == null && expected != null) {
                        named++
                        if (list.count { f -> f.fileName.lowercase() == item.fileName!!.lowercase() && f.size == item.sizeBytes } > 1) several++
                    }
                }
                (0 until 60).map(::readsSha).forEach { sha -> assertEquals(list.firstOrNull { it.sha256 == sha }, library.file(sha), "$where: $sha") }
                assertEquals(list.filter { it.isAudio }, library.sounds(), where)
                assertEquals(list, library.all(), where)

                // Search on the phone: private shown or not, with and without years and an area.
                listOf(true, false).forEach { private ->
                    listOf(null, listOf(2024), listOf(2023, 2026)).forEach { years ->
                        listOf(null, GeoArea(south = 0.0, west = 25.0, north = 20.0, east = 40.0)).forEach { area ->
                            val shown = shownFiles(library::forEachFile, private, years, area)
                            val (taken, total) = searchedBefore(list, private, years, area)
                            assertEquals(taken, shown.taken, "$where: private $private, years $years, area $area")
                            assertEquals(total, shown.total, "$where: total")
                        }
                    }
                    assertEquals(peopleBefore(list, private), shownFiles(library::forEachFile, private).taken, "$where: people")
                }
            }
        }
        assertTrue(named > 100 && several > 30, "found by name and size: $named, of several: $several")
    }

    /**
     * The library's sound recordings: with the database they are read from `server_media` when its list changes, and equal
     * the ones the merge in memory takes from the whole list, also once private files are hidden and shown again.
     */
    @Test
    fun soundsFromTheDatabaseEqualTheMerge() = runTest {
        var privateShown = 0
        repeat(12) { seed ->
            val random = Random(seed)
            val dispatcher = StandardTestDispatcher(testScheduler)
            val database = testLibraryDatabase()
            val store = ServerMediaStore(database)
            store.holdPrivate(true)
            store.apply(FeedPage((0 until 60).map { randomMedia(random, it) }, emptyList(), emptyList(), emptyList(), "c1"), showPrivate = true)
            val library = serverLibrary(database)
            val private = MutableStateFlow(PrivateMedia(show = true))
            val device = (0 until 5).map { n -> MediaItem("$n", MediaType.IMAGE, n * 1000L, LocalDay(2026, 10, 1), null, 10, 10, 10L + n, "IMG_$n.jpg", modifiedMillis = 1) }
            val withTable = LibraryController(
                StoredMediaLibrary(ReadsTestLibrary(device), database, dispatcher), JournalSettings(ReadsTestStore()), dispatcher,
                server = library, privateMedia = private, table = LibraryTable(database),
            )
            val inMemory = LibraryController(ReadsTestLibrary(device), JournalSettings(ReadsTestStore()), dispatcher, server = library, privateMedia = private)
            withTable.refresh()
            inMemory.refresh()
            advanceUntilIdle()
            listOf(true, false, true).forEachIndexed { step, show ->
                private.value = PrivateMedia(show)
                advanceUntilIdle()
                val expected = inMemory.currentState.sounds
                assertTrue(expected.isNotEmpty(), "seed $seed")
                if (!show) assertTrue(expected.none { it.isPrivate }, "seed $seed: private hidden")
                if (step == 0) privateShown += expected.count { it.isPrivate }
                assertEquals(expected, withTable.currentState.sounds, "seed $seed, private shown $show")
            }
            withTable.close()
            inMemory.close()
        }
        assertTrue(privateShown > 10, "private recordings shown: $privateShown")
    }
}
