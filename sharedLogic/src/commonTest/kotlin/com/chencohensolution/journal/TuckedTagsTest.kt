package com.chencohensolution.journal

import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.server.AppDataAnswer
import com.chencohensolution.journal.server.AppDataContent
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.MomentsFeed
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.TagTucking
import com.chencohensolution.journal.stories.TagsSyncSource
import com.chencohensolution.journal.stories.TuckedReveal
import com.chencohensolution.journal.stories.TuckedSession
import com.chencohensolution.journal.stories.TuckedTagCount
import com.chencohensolution.journal.stories.TuckedTags
import com.chencohensolution.journal.stories.buildFeed
import com.chencohensolution.journal.stories.mergeTucked
import com.chencohensolution.journal.stories.storylineDays
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOUR = 3_600_000L
private val DAY_1 = LocalDay(2026, 11, 1)
private val DAY_2 = LocalDay(2026, 11, 2)
private val ROOT = "/data/journal".toPath()

private fun shot(id: String, day: LocalDay, hour: Int, tucked: Boolean = false) = MediaItem(
    id, MediaType.IMAGE, day.toEpochDay() * 86_400_000L + hour * HOUR, day, null, 10, 10, 100, "$id.jpg", isTucked = tucked,
)

private fun note(id: String, vararg tags: String, highlight: Int? = null) =
    PhotoNote(id, "$id.jpg", 0, tags = tags.toList(), updatedAt = "2026-11-19T00:00:00Z", highlight = highlight)

private fun rows(feed: MomentsFeed) = feed.entries.map { (it as FeedEntry.Section).section }

/** Tucked-away tags (Nov 19, 2026): a tag takes its photos out of the feeds, each day says how many, a highlight shows through. */
class TuckedTagsTest {
    private val byDay = TimelineGrouping(GroupingMode.BY_DAY)

    // Day 2: a and c shown, d1 and d2 tagged Dog, h highlighted Dog, f tucked as a file (and Dog), b tagged Cat and Dog.
    private val library = listOf(
        shot("a", DAY_2, 20), shot("d1", DAY_2, 19), shot("h", DAY_2, 18), shot("d2", DAY_2, 17), shot("f", DAY_2, 16, tucked = true),
        shot("b", DAY_2, 15), shot("c", DAY_2, 14),
        shot("d3", DAY_1, 10),
    )
    private val notes = listOf(
        note("d1", "Dog"), note("d2", "dog"), note("h", "Dog", highlight = 1), note("f", "Dog"), note("b", "Dog", "Cat"), note("d3", "Dog"),
        note("c", "Beach"),
    ).associateBy { it.deviceId }

    private fun tagging(session: TuckedSession = TuckedSession.NONE, vararg tucked: String) = TagTucking.of(notes, tucked.toList(), session)

    @Test
    fun aTuckedTagsPhotosLeaveMomentsAndEachDayHasItsPill() {
        val feed = rows(buildFeed(library, ShownSources(), byDay, tagged = tagging(TuckedSession.NONE, "Cat", "Dog")))
        assertEquals(listOf("a", "h", "c"), feed[0].items.map { it.id }, "the highlight shows through, the file stays tucked")
        // b has Cat and Dog: it counts once, under Cat (the first alphabetically).
        assertEquals(listOf(TuckedTagCount("Cat", 1, false), TuckedTagCount("Dog", 2, false)), feed[0].tuckedTags)
        assertEquals(1, feed[0].tucked, "the file tucked away counts on the generic pill only")
        // A day whose photos are all tucked keeps its row, with only the pill.
        assertEquals(emptyList(), feed[1].items)
        assertEquals(listOf(TuckedTagCount("Dog", 1, false)), feed[1].tuckedTags)
    }

    @Test
    fun revealingATagOnADayShowsOnlyThatTagsPhotosOfThatDay() {
        val reveal = TuckedReveal()
        reveal.toggleTag(listOf(DAY_2), "dog")
        val feed = rows(buildFeed(library, ShownSources(), byDay, tagged = tagging(reveal.current, "Cat", "Dog")))
        assertEquals(listOf("a", "d1", "h", "d2", "c"), feed[0].items.map { it.id })
        assertEquals(listOf(false, true), feed[0].tuckedTags.map { it.shown })
        assertEquals(emptyList(), feed[1].items, "another day stays hidden")
        // Tapped again: hidden.
        reveal.toggleTag(listOf(DAY_2), "Dog")
        assertEquals(emptySet(), reveal.current.dayTags)
        // Show everywhere, then hide again (which also hides the days revealed one by one).
        reveal.toggleTag(listOf(DAY_1), "Dog")
        reveal.showEverywhere("Dog")
        val everywhere = rows(buildFeed(library, ShownSources(), byDay, tagged = tagging(reveal.current, "Dog")))
        assertEquals(listOf("d3"), everywhere[1].items.map { it.id })
        assertTrue("b" in everywhere[0].items.map { it.id })
        reveal.hideEverywhere("dog")
        assertEquals(TuckedSession.NONE, reveal.current)
    }

    @Test
    fun theStorylineCountsTuckedTagsPerDay() {
        val days = storylineDays(library, TuckedSession.NONE, tagging(TuckedSession.NONE, "Dog"))
        assertEquals(listOf("a", "h", "c"), days[0].items.map { it.id })
        assertEquals(listOf(TuckedTagCount("Dog", 3, false)), days[0].tags)
        assertEquals(1, days[0].tucked)
        assertEquals(emptyList(), days[1].items)
    }

    @Test
    fun nothingTuckedChangesNothing() {
        val plain = rows(buildFeed(library, ShownSources(), byDay))
        val tagged = rows(buildFeed(library, ShownSources(), byDay, tagged = tagging()))
        assertEquals(plain.map { r -> r.items.map { it.id } }, tagged.map { r -> r.items.map { it.id } })
        assertTrue(tagged.all { it.tuckedTags.isEmpty() })
    }

    @Test
    fun aConflictKeepsThisPhonesChangesOverTheServersList() {
        // Tucked Cat here and brought back Dog; the server meanwhile tucked Receipts.
        assertEquals(listOf("Receipts", "Cat"), mergeTucked(base = listOf("Dog"), local = listOf("Cat"), server = listOf("dog", "Receipts")))
        // Nothing changed here: the server's list.
        assertEquals(listOf("Receipts"), mergeTucked(listOf("Dog"), listOf("Dog"), listOf("Receipts")))
    }

    @Test
    fun tuckedTagsAreKeptOnThePhoneAndSyncedWithTheServer() = runTest {
        val fs = FakeFileSystem()
        val server = FakeAppData()
        server.content = """{"v":1,"tucked":["Receipts"],"updatedAt":"x","color":"red"}"""
        val source = TagsSyncSource(MutableStateFlow(true), MutableStateFlow(0), server::read, server::write)
        val tags = TuckedTags(fs, ROOT, source) { "2026-11-19T10:00:00Z" }
        tags.syncNow()
        assertEquals(listOf("Receipts"), tags.tucked.value, "brought in from the server")

        tags.set("Dog", true)
        tags.syncNow()
        val sent = Json.parseToJsonElement(server.content!!).jsonObject
        assertEquals(listOf("Receipts", "Dog"), sent["tucked"]!!.jsonArray.map { it.jsonPrimitive.content })
        assertEquals("red", sent["color"]!!.jsonPrimitive.content, "unknown fields are kept")

        // Changed on another phone meanwhile, and here: merged, sent again.
        server.content = """{"v":1,"tucked":["Receipts","Dog","Cat"],"updatedAt":"y"}"""
        tags.set("Receipts", false)
        tags.syncNow()
        assertEquals(listOf("Dog", "Cat"), tags.tucked.value)
        assertEquals(listOf("Dog", "Cat"), Json.parseToJsonElement(server.content!!).jsonObject["tucked"]!!.jsonArray.map { it.jsonPrimitive.content })

        // Read again from the file, as at the next start.
        val again = TuckedTags(fs, ROOT, null)
        assertEquals(listOf("Dog", "Cat"), again.tucked.value)
        assertTrue(again.isTucked("cat"))
    }

    @Test
    fun offlineTheChangeWaitsAndADamagedFileIsNeverFatal() = runTest {
        val fs = FakeFileSystem()
        val server = FakeAppData()
        server.reachable = false
        val tags = TuckedTags(fs, ROOT, TagsSyncSource(MutableStateFlow(true), MutableStateFlow(0), server::read, server::write)) { "t" }
        tags.set("Dog", true)
        tags.syncNow()
        assertNull(server.content)
        server.reachable = true
        tags.syncNow()
        assertTrue("Dog" in server.content!!)

        fs.write(ROOT / TuckedTags.FILE) { writeUtf8("{not json") }
        val damaged = TuckedTags(fs, ROOT, null)
        assertEquals(emptyList(), damaged.tucked.value)
        damaged.set("Cat", true)
        assertEquals(listOf("Cat"), TuckedTags(fs, ROOT, null).tucked.value)
    }

    @Test
    fun theControllerTucksTagsCountsThemOverSeveralPhotosAndHighlightsShowThrough() = runTest {
        val stories = controller(this)
        advanceUntilIdle()
        stories.addTag(listOf("d1", "d2", "c"), "Dog")
        stories.addTag(listOf("c"), "Beach")
        advanceUntilIdle()
        val counts = stories.currentState.tagCounts(listOf("d1", "c", "a")).associateBy { it.tag }
        assertEquals(2, counts.getValue("Dog").count)
        assertTrue(counts.getValue("Dog").some)
        assertEquals(1, counts.getValue("Beach").count)
        assertTrue(stories.currentState.tagCounts(listOf("d1", "d2")).first { it.tag == "Dog" }.all)

        stories.setTagTucked("dog", true)
        advanceUntilIdle()
        var state = stories.currentState
        assertTrue(state.tags.first { it.name == "Dog" }.tucked)
        assertEquals("Dog", state.tuckedTagOf("d1"))
        val row = rows(state.feed).first()
        assertEquals(listOf("a"), row.items.map { it.id })
        assertEquals(listOf(TuckedTagCount("Dog", 3, false)), row.tuckedTags)

        // A highlight shows through, and its note says so; a lone highlight makes no set.
        stories.setHighlighted(listOf("c"), true)
        advanceUntilIdle()
        state = stories.currentState
        assertEquals(1, state.note("c")?.highlight)
        assertNull(state.note("c")?.feedSet)
        assertNull(state.tuckedTagOf("c"))
        assertEquals("Dog", state.shownThroughTag("c"))
        assertEquals(listOf("a", "c"), rows(state.feed).first().items.map { it.id })

        stories.setHighlighted(listOf("c"), false)
        stories.setTagTucked("Dog", false)
        advanceUntilIdle()
        assertEquals(4, rows(stories.currentState.feed).first().items.size)
        assertTrue(rows(stories.currentState.feed).first().tuckedTags.isEmpty())
    }

    private fun controller(scope: TestScope): StoriesController {
        val dispatcher = StandardTestDispatcher(scope.testScheduler)
        val items = listOf(shot("a", DAY_2, 20), shot("d1", DAY_2, 19), shot("d2", DAY_2, 17), shot("c", DAY_2, 14))
        val library = LibraryController(TuckedTagsLibrary(items), JournalSettings(TuckedTagsStore()), dispatcher)
        library.refresh()
        return StoriesController(
            noteStore = FilePhotoNoteStore(FakeFileSystem(), ROOT),
            library = library,
            settings = SettingsController(JournalSettings(TuckedTagsStore()), dispatcher),
            newId = { "set-1" },
            dispatcher = dispatcher,
            now = { "2026-11-19T12:00:00Z" },
        )
    }
}

/** The server's app data "tags", with its version and conflicts. */
private class FakeAppData {
    var content: String? = null
    var reachable = true
    private val version get() = content?.hashCode()?.toString()

    fun read(): AppDataContent? = if (!reachable) null else AppDataContent(content, version)

    fun write(next: String?, base: String?): AppDataAnswer? {
        if (!reachable) return null
        if (base != version) return AppDataAnswer(false, AppDataContent(content, version))
        content = next
        return AppDataAnswer(true, AppDataContent(content, version))
    }
}

private class TuckedTagsLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private class TuckedTagsStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}
