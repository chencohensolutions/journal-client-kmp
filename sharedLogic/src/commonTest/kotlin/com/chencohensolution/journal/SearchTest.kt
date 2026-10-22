package com.chencohensolution.journal

import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.places.PlaceName
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.search.SearchAnswer
import com.chencohensolution.journal.search.SearchChip
import com.chencohensolution.journal.stories.GeoArea
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.search.SearchController
import com.chencohensolution.journal.search.SearchError
import com.chencohensolution.journal.search.SearchStatus
import com.chencohensolution.journal.search.SearchUnreachable
import com.chencohensolution.journal.server.ServerMedia
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SearchTest {
    private fun sha(c: Char) = c.toString().repeat(64)

    private fun item(id: String, sha256: String?, name: String = "IMG_$id.heic", size: Long = 100) =
        MediaItem(id, MediaType.IMAGE, 0, LocalDay(2026, 10, 8), null, 4000, 3000, size, name, sha256 = sha256)

    private fun library(vararg items: MediaItem) =
        LibraryState(access = PhotoAccess.FULL, days = listOf(DayGroup(LocalDay(2026, 10, 8), items.toList())))

    private fun serverFile(c: Char, name: String, size: Long) =
        ServerMedia(sha256 = sha(c), isVideo = false, fileName = name, mimeType = "image/heic", size = size, uploadedAt = "2026-10-19T22:05:21Z")

    @Test
    fun showsTheLibrarysPhotosInTheServersOrder() = runTest {
        val library = MutableStateFlow(library(item("1", sha('a')), item("2", sha('b')), item("3", sha('c'))))
        var asked: Pair<String, Int>? = null
        val search = SearchController(library, { text, limit, _, _ ->
            asked = text to limit
            SearchAnswer(listOf(sha('c'), sha('z'), sha('a')), indexed = 80, total = 100)
        }, dispatcher = StandardTestDispatcher(testScheduler))

        search.search("  a dog on the beach  ")
        assertEquals(SearchStatus.SEARCHING, search.currentState.status)
        advanceUntilIdle()

        assertEquals("a dog on the beach" to 1000, asked)
        val state = search.currentState
        assertEquals(SearchStatus.DONE, state.status)
        // What the library doesn't show (z: private, or not synced yet) is left out.
        assertEquals(listOf("3", "1"), state.items.map { it.id })
        assertEquals(80, state.indexed)
        assertTrue(state.stillReading)
        search.close()
    }

    @Test
    fun followsTheLibraryAfterTheAnswer() = runTest {
        val library = MutableStateFlow(library(item("1", sha('a')), item("2", sha('b'))))
        val search = SearchController(library, { _, _, _, _ -> SearchAnswer(listOf(sha('a'), sha('b')), 2, 2) }, dispatcher = StandardTestDispatcher(testScheduler))
        search.search("snow")
        advanceUntilIdle()
        assertEquals(listOf("1", "2"), search.currentState.items.map { it.id })

        // Photo 1 made private and hidden: it leaves the results without searching again.
        library.value = library(item("2", sha('b')))
        advanceUntilIdle()
        assertEquals(listOf("2"), search.currentState.items.map { it.id })
        search.close()
    }

    @Test
    fun findsADevicePhotoBackupHasntReadByNameAndSize() = runTest {
        val library = MutableStateFlow(library(item("7", sha256 = null, name = "20261008_190157.heic", size = 541_212)))
        val server = MutableStateFlow(listOf(serverFile('a', "20261008_190157.HEIC", 541_212)))
        val search = SearchController(library, { _, _, _, _ -> SearchAnswer(listOf(sha('a')), 1, 1) }, server, dispatcher = StandardTestDispatcher(testScheduler))
        search.search("sunset")
        advanceUntilIdle()
        assertEquals(listOf("7"), search.currentState.items.map { it.id })
        search.close()
    }

    @Test
    fun saysWhyASearchFailed() = runTest {
        val library = MutableStateFlow(library(item("1", sha('a'))))
        var fail: Exception = SearchUnreachable("timeout")
        val search = SearchController(library, { _, _, _, _ -> throw fail }, dispatcher = StandardTestDispatcher(testScheduler))

        search.search("cat")
        advanceUntilIdle()
        assertEquals(SearchStatus.FAILED, search.currentState.status)
        assertEquals(SearchError.OFFLINE, search.currentState.error)
        assertEquals("cat", search.currentState.query)

        fail = IllegalStateException("text search is off on this server")
        search.search("cat")
        advanceUntilIdle()
        assertEquals(SearchError.SERVER, search.currentState.error)
        assertEquals("text search is off on this server", search.currentState.errorDetail)
        search.close()
    }

    @Test
    fun aNewSearchReplacesOneRunningAndBlankClears() = runTest {
        val library = MutableStateFlow(library(item("1", sha('a')), item("2", sha('b'))))
        val first = CompletableDeferred<SearchAnswer>()
        val search = SearchController(library, { text, _, _, _ ->
            if (text == "slow") first.await() else SearchAnswer(listOf(sha('b')), 2, 2)
        }, dispatcher = StandardTestDispatcher(testScheduler))

        search.search("slow")
        advanceUntilIdle()
        search.search("fast")
        advanceUntilIdle()
        first.complete(SearchAnswer(listOf(sha('a')), 2, 2))
        advanceUntilIdle()
        assertEquals("fast", search.currentState.query)
        assertEquals(listOf("2"), search.currentState.items.map { it.id })

        search.search("   ")
        assertEquals(SearchStatus.IDLE, search.currentState.status)
        assertEquals(emptyList(), search.currentState.items)
        search.close()
    }

    private val telAviv = GeoPoint(32.08, 34.78)
    private val jaffa = GeoPoint(32.05, 34.75)
    private val paris = GeoPoint(48.86, 2.35)

    private fun at(id: String, sha: Char, year: Int, point: GeoPoint) =
        item(id, sha(sha)).copy(day = LocalDay(year, 6, 1), location = point)

    private val placed = MutableStateFlow(
        StoriesState(places = Places.named(mapOf(telAviv to PlaceName(null, "Tel Aviv", "Israel"), jaffa to PlaceName(null, "Tel Aviv", "Israel"), paris to PlaceName(null, "Paris", "France")))),
    )

    @Test
    fun chipsCountTheMatchesYearsAndTownsEachWithinTheOthersChoice() = runTest {
        val library = MutableStateFlow(library(at("1", 'a', 2026, telAviv), at("2", 'b', 2025, paris), at("3", 'c', 2025, telAviv), at("4", 'd', 2019, jaffa)))
        val asked = mutableListOf<Triple<Int, List<Int>?, GeoArea?>>()
        val search = SearchController(library, { _, limit, years, area ->
            asked += Triple(limit, years, area)
            when {
                years == null && area == null -> SearchAnswer(listOf(sha('a'), sha('b'), sha('c'), sha('d')), 4, 4)
                years == listOf(2025) && area == null -> SearchAnswer(listOf(sha('c'), sha('b')), 4, 4)
                else -> SearchAnswer(listOf(sha('d'), sha('a'), sha('b')), 4, 4)
            }
        }, stories = placed, dispatcher = StandardTestDispatcher(testScheduler))

        search.search("the sea")
        advanceUntilIdle()
        assertEquals(listOf(SearchChip("2026", 1), SearchChip("2025", 2), SearchChip("2019", 1)), search.currentState.yearChips)
        assertEquals(listOf(SearchChip("Tel Aviv", 3), SearchChip("Paris", 1)), search.currentState.townChips)

        // A year: the server is asked for the best of it.
        search.chooseYear(2025)
        assertEquals(SearchStatus.SEARCHING, search.currentState.status)
        advanceUntilIdle()
        assertEquals(Triple(100, listOf(2025), null), asked.last())
        assertEquals(listOf("3", "2"), search.currentState.items.map { it.id })
        // Ties by name.
        assertEquals(listOf(SearchChip("Paris", 1), SearchChip("Tel Aviv", 1)), search.currentState.townChips)

        // A town: sent as the box around this device's photos of it (Tel Aviv and Jaffa's points), a little wider; what
        // the box catches of another town is left out.
        search.chooseYear(null)
        search.chooseTown("Tel Aviv")
        advanceUntilIdle()
        val (limit, years, area) = asked.last()
        assertEquals(100, limit)
        assertNull(years)
        assertEquals(GeoArea(32.04, 34.74, 32.09, 34.79), area?.let { GeoArea(it.south.round(), it.west.round(), it.north.round(), it.east.round()) })
        assertEquals(listOf("4", "1"), search.currentState.items.map { it.id })
        assertEquals(listOf(SearchChip("2026", 1), SearchChip("2025", 1), SearchChip("2019", 1)), search.currentState.yearChips)

        // Neither: the first answer again, without asking.
        val asks = asked.size
        search.chooseTown(null)
        advanceUntilIdle()
        assertEquals(asks, asked.size)
        assertEquals(listOf("1", "2", "3", "4"), search.currentState.items.map { it.id })

        // A new search drops the choices.
        search.chooseYear(2019)
        advanceUntilIdle()
        search.search("the sea again")
        assertNull(search.currentState.year)
        search.close()
    }

    private fun Double.round() = kotlin.math.round(this * 100) / 100
}
