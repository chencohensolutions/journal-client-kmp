package com.chencohensolution.journal

import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.people.Face
import com.chencohensolution.journal.people.FaceBox
import com.chencohensolution.journal.people.PageSort
import com.chencohensolution.journal.people.PeopleApi
import com.chencohensolution.journal.people.PeopleController
import com.chencohensolution.journal.people.PeopleList
import com.chencohensolution.journal.people.PeopleStatus
import com.chencohensolution.journal.people.PeopleUnreachable
import com.chencohensolution.journal.people.Person
import com.chencohensolution.journal.people.PersonAnswer
import com.chencohensolution.journal.people.PersonController
import com.chencohensolution.journal.people.PersonHit
import com.chencohensolution.journal.people.faceCrop
import com.chencohensolution.journal.search.SearchAnswer
import com.chencohensolution.journal.search.SearchController
import com.chencohensolution.journal.server.ServerMedia
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PeopleTest {
    private fun sha(c: Char) = c.toString().repeat(64)

    private fun face(c: Char, person: String?, n: Int = 0) = Face("${sha(c)}:$n", sha(c), FaceBox(0.4f, 0.3f, 0.2f, 0.2f), person)

    private fun item(id: String, c: Char, day: Int, aesthetic: Float? = null) =
        MediaItem(id, MediaType.IMAGE, day * 86_400_000L, LocalDay(2026, 10, day), null, 4000, 3000, 100, "IMG_$id.jpg", sha256 = sha(c), aesthetic = aesthetic)

    private val mom = Person("p1", "Mom", 3, 3, face('a', "p1"))
    private val dana = Person("p2", "Dana", 1, 1, face('b', "p2"))
    private val group = Person("g1", null, 4, 4, face('c', "g1"))

    /** A server with Mom in a, b and c (Dana in b, the group in c), and what the app asked it. */
    private class FakePeople(var list: PeopleList) : PeopleApi {
        val calls = mutableListOf<String>()
        var hits: List<PersonHit> = emptyList()
        var offline = false

        override suspend fun people(includePrivate: Boolean): PeopleList {
            if (offline) throw PeopleUnreachable("offline")
            return list
        }

        override suspend fun media(ids: List<String>, text: String?, limit: Int, offset: Int, includePrivate: Boolean): PersonAnswer {
            calls += "media $ids ${text.orEmpty()}"
            return PersonAnswer(hits, hits.size, hits.size)
        }

        override suspend fun name(id: String, name: String): Person {
            calls += "name $id $name"
            return Person("p9", name, 4, 4, list.find(id)!!.cover)
        }

        override suspend fun merge(into: String, from: List<String>): Person {
            calls += "merge $into $from"
            return list.find(into)!!
        }

        override suspend fun moveFace(faceId: String, to: String?) {
            calls += "move $faceId $to"
        }

        override suspend fun forget(id: String) {
            calls += "forget $id"
        }

        override suspend fun mark(person: String, yes: List<String>, no: List<String>): Person {
            calls += "mark $person yes $yes no $no"
            return list.find(person)!!
        }

        override suspend fun nameFace(faceId: String, name: String): Person {
            calls += "nameFace $faceId $name"
            return list.people.first()
        }
    }

    private fun TestScope.setUp(api: FakePeople, vararg items: MediaItem): Pair<PeopleController, MutableStateFlow<LibraryState>> {
        val people = PeopleController(api, { false }, StandardTestDispatcher(testScheduler))
        val library = MutableStateFlow(LibraryState(access = PhotoAccess.FULL, days = listOf(DayGroup(LocalDay(2026, 10, 1), items.toList()))))
        return people to library
    }

    @Test
    fun listsPeopleAndGroups() = runTest {
        val api = FakePeople(PeopleList(listOf(mom), listOf(group), read = 80, total = 100))
        val (people, _) = setUp(api)
        people.load()
        advanceUntilIdle()
        assertEquals(PeopleStatus.READY, people.currentState.status)
        assertEquals(listOf("p1"), people.currentState.list.people.map { it.id })
        assertTrue(people.currentState.stillReading)
        assertEquals(listOf(mom), people.matching("mo"))

        api.offline = true
        people.reload()
        advanceUntilIdle()
        // What was loaded stays.
        assertEquals(PeopleStatus.UNREACHABLE, people.currentState.status)
        assertEquals(listOf("p1"), people.currentState.list.people.map { it.id })
    }

    @Test
    fun aPersonsPageShowsTheirPhotosAndWhoTheyAreWith() = runTest {
        val api = FakePeople(PeopleList(listOf(mom, dana), listOf(group), 100, 100))
        val a = item("1", 'a', 1, aesthetic = 4f)
        val b = item("2", 'b', 3, aesthetic = 7f)
        val c = item("3", 'c', 2)
        api.hits = listOf(
            PersonHit(sha('b'), 0, 0, listOf(face('b', "p1"), face('b', "p2", 1))),
            PersonHit(sha('c'), 1, 0, listOf(face('c', "p1"), face('c', "g1", 1))),
            PersonHit(sha('a'), 2, 1, listOf(face('a', "p1"))),
            PersonHit(sha('z'), 3, 2, listOf(face('z', "p1"))),
        )
        val (people, library) = setUp(api, a, b, c)
        people.load()
        val page = PersonController("p1", api, people, library, MutableStateFlow(emptyList<ServerMedia>()), { false }, StandardTestDispatcher(testScheduler))
        advanceUntilIdle()
        val state = page.currentState
        // Newest first; z isn't in the library.
        assertEquals(listOf("2", "3", "1"), state.items.map { it.id })
        assertEquals(listOf("p2", "g1"), state.together.map { it.person.id })
        assertEquals("${sha('a')}:0", state.faces["1"]?.id)

        page.setSort(PageSort.BEST)
        assertEquals(listOf("2", "1", "3"), page.currentState.items.map { it.id })

        page.notThem(a)
        assertEquals(listOf("2", "3"), page.currentState.items.map { it.id }.filter { it != "1" })
        advanceUntilIdle()
        assertTrue("move ${sha('a')}:0 null" in api.calls)
    }

    @Test
    fun namingAGroupMakesThePageTheNewPersons() = runTest {
        val api = FakePeople(PeopleList(listOf(mom), listOf(group), 100, 100))
        val (people, library) = setUp(api)
        people.load()
        advanceUntilIdle()
        val page = PersonController("g1", api, people, library, MutableStateFlow(emptyList<ServerMedia>()), { false }, StandardTestDispatcher(testScheduler))
        page.setName("Mo")
        assertEquals(listOf(mom), page.currentState.matches)
        page.rename("Avi")
        advanceUntilIdle()
        assertEquals("p9", page.currentState.id)
        assertEquals("Avi", page.currentState.person?.name)
        assertTrue("name g1 Avi" in api.calls)

        page.join("p1")
        advanceUntilIdle()
        assertTrue(api.calls.any { it.startsWith("merge p1") })
    }

    @Test
    fun searchingByPersonNeedsNoWords() = runTest {
        val (_, library) = setUp(FakePeople(PeopleList(listOf(mom), emptyList(), 1, 1)), item("1", 'a', 1), item("2", 'b', 2))
        val asked = mutableListOf<String>()
        val search = SearchController(
            library,
            { _, _, _, _, _ -> SearchAnswer(listOf(sha('b')), 1, 1) },
            dispatcher = StandardTestDispatcher(testScheduler),
            peopleSearch = { ids, text, _, _ ->
                asked += "$ids ${text.orEmpty()}"
                SearchAnswer(listOf(sha('a')), 1, 1)
            },
        )
        search.addPerson(mom)
        advanceUntilIdle()
        assertEquals(listOf("1"), search.currentState.items.map { it.id })
        search.search("beach")
        advanceUntilIdle()
        assertEquals(listOf("[p1] ", "[p1] beach"), asked)
        search.removePerson("p1")
        advanceUntilIdle()
        // Words alone go to the plain search again.
        assertEquals(listOf("2"), search.currentState.items.map { it.id })
        search.clear()
        assertTrue(search.currentState.people.isEmpty())
    }

    @Test
    fun correctingAFaceReloadsThePeopleAndCountsTheChange() = runTest {
        val api = FakePeople(PeopleList(listOf(mom, dana), emptyList(), 1, 1))
        val (people, _) = setUp(api)
        people.load()
        advanceUntilIdle()
        people.notPerson(face('b', "p1"), "p1")
        people.moveFace(face('c', "g1"), "p2")
        people.nameFace(face('d', null), "  Avi ")
        advanceUntilIdle()
        assertEquals(
            listOf("mark p1 yes [] no [${sha('b')}:0]", "move ${sha('c')}:0 p2", "nameFace ${sha('d')}:0 Avi"),
            api.calls,
        )
        assertEquals(3, people.currentState.changes)
        assertTrue(!people.currentState.correcting)
    }

    @Test
    fun anAvatarIsASquareAroundTheFace() {
        // A 4:3 photo and a face 0.2 × 0.2 of it (wider than tall in pixels): a square 1.5 times its width, around its
        // middle (0.5, 0.4), as fractions of the photo.
        val crop = faceCrop(face('a', null), 4000, 3000)
        listOf(0.35f, 0.2f, 0.3f, 0.4f).forEachIndexed { i, expected -> assertEquals(expected, crop[i], 0.001f) }
    }
}
