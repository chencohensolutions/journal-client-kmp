package com.chencohensolution.journal

import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.stories.CropRect
import com.chencohensolution.journal.stories.FeedBlock
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.MomentsFeed
import com.chencohensolution.journal.stories.ShowOn
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.buildFeed
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val FRI = LocalDay(2026, 11, 6)
private val SAT = LocalDay(2026, 11, 7)
private val SUN = LocalDay(2026, 11, 8)
private val SETS_ROOT = "/data/journal".toPath()

private fun shot(id: String, day: LocalDay, hour: Int, minute: Int = 0, burst: String? = null) = MediaItem(
    id, MediaType.IMAGE, day.toEpochDay() * 86_400_000 + hour * 3_600_000L + minute * 60_000L, day, null, 4, 3, 100, "$id.jpg",
    burstId = burst,
)

private class SetsTestLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private class SetsTestStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private fun MomentsFeed.blocks(): List<FeedBlock> =
    entries.filterIsInstance<FeedEntry.Section>().flatMap { entry -> entry.section.moments.flatMap { it.shownBlocks } }

/** Each row's days and its blocks' photo ids, newest row first. */
private fun MomentsFeed.rows(): List<Pair<List<LocalDay>, List<List<String>>>> =
    entries.filterIsInstance<FeedEntry.Section>().map { entry ->
        entry.section.days to entry.section.moments.flatMap { m -> m.shownBlocks.map { b -> b.items.map { it.id } } }
    }

class MomentSetsTest {
    /** A burst of three on Friday (b1-b3), a weekend of photos (f, s1, s2, u), one alone on Sunday evening. */
    private val items = listOf(
        shot("b1", FRI, 9, 0, burst = "x"), shot("b2", FRI, 9, 0, burst = "x"), shot("b3", FRI, 9, 0, burst = "x"),
        shot("f", FRI, 18), shot("s1", SAT, 11), shot("s2", SAT, 12), shot("u", SUN, 10), shot("alone", SUN, 20),
    ).sortedByDescending { it.takenAtMillis }

    private class Setup(val stories: StoriesController, val fileSystem: FakeFileSystem)

    private fun TestScope.setup(fileSystem: FakeFileSystem = FakeFileSystem()): Setup {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val library = LibraryController(SetsTestLibrary(items), JournalSettings(SetsTestStore()), dispatcher)
        library.refresh()
        var id = 0
        var minute = 0
        val stories = StoriesController(
            noteStore = FilePhotoNoteStore(fileSystem, SETS_ROOT),
            library = library,
            settings = SettingsController(JournalSettings(SetsTestStore()), dispatcher),
            dispatcher = dispatcher,
            now = { "2026-11-09T12:${(minute++).toString().padStart(2, '0')}:00Z" },
            newId = { "set-${++id}" },
        )
        advanceUntilIdle()
        return Setup(stories, fileSystem)
    }

    private val burst = listOf("b1", "b2", "b3")

    private fun StoriesController.burstBlock() =
        currentState.feed.blocks().single { block -> block.items.any { it.id == "b1" } }

    @Test
    fun highlightsAreNumberedInTheOrderMarkedAndRenumbered() = runTest {
        val stories = setup().stories
        assertIs<FeedBlock.Similar>(stories.burstBlock())
        stories.setHighlight(burst, "b3", true)
        stories.setHighlight(burst, "b1", true)
        assertEquals(2, stories.currentState.note("b1")?.highlight)
        assertEquals(1, stories.currentState.note("b3")?.highlight)
        val block = assertIs<FeedBlock.Similar>(stories.burstBlock())
        assertEquals(listOf("b3", "b1"), block.highlights.map { it.id })
        // The first highlight is the cover.
        assertEquals("b3", stories.currentState.frontOf(block.items))

        // Moved in the grid sheet: the order changes, the numbers follow.
        stories.moveHighlight(burst, "b1", 0)
        assertEquals(listOf("b1", "b3"), assertIs<FeedBlock.Similar>(stories.burstBlock()).highlights.map { it.id })
        assertEquals(1, stories.currentState.note("b1")?.highlight)
        assertEquals(2, stories.currentState.note("b3")?.highlight)

        // Unmarking the first makes the next one first; unmarking the last leaves no note at all.
        stories.setHighlight(burst, "b1", false)
        assertNull(stories.currentState.note("b1"))
        assertEquals(1, stories.currentState.note("b3")?.highlight)
        stories.setHighlight(burst, "b3", false)
        assertNull(stories.currentState.note("b3"))
        assertEquals(emptyList(), assertIs<FeedBlock.Similar>(stories.burstBlock()).highlights)
    }

    @Test
    fun anOldFrontIsReadAsTheFirstHighlightAndNotWrittenAgain() = runTest {
        val fileSystem = FakeFileSystem()
        fileSystem.createDirectories(SETS_ROOT / "media")
        fileSystem.write(SETS_ROOT / "media" / "b2.json") {
            writeUtf8("""{"v": 4, "deviceId": "b2", "front": true, "updatedAt": "2026-10-01T00:00:00Z", "later": 7}""")
        }
        val stories = setup(fileSystem).stories
        assertEquals(1, stories.currentState.note("b2")?.highlight)
        assertEquals("b2", stories.currentState.frontOf(stories.burstBlock().items))

        stories.setHighlight(burst, "b1", true)
        stories.moveHighlight(burst, "b1", 0)
        val file = fileSystem.read(SETS_ROOT / "media" / "b2.json") { readUtf8() }
        assertTrue("\"highlight\": 2" in file, file)
        assertFalse("\"front\"" in file, file)
        assertTrue("\"v\": 5" in file, file)
        // A newer version's field is kept.
        assertTrue("\"later\": 7" in file, file)
    }

    @Test
    fun aSetWithoutHighlightsOpensOnItsBestLookingOrFirstPhotoAndTurningWritesNothing() = runTest {
        val setup = setup()
        // There is no way to save a turn any more: only highlights are kept in notes.
        assertNull(setup.stories.currentState.frontOf(setup.stories.burstBlock().items))
        assertFalse(setup.fileSystem.exists(SETS_ROOT / "media"))
    }

    @Test
    fun makingAMomentNamesItKeepsItsHighlightsAndSavesIt() = runTest {
        val setup = setup()
        val stories = setup.stories
        val set = stories.makeMoment(listOf("f", "s1", "s2", "u"), "  Beach weekend ", listOf("s2", "f"), ShowOn.FIRST)
        assertEquals("set-1", set)
        assertEquals(listOf("f", "s1", "s2", "u").map { "set-1" }, listOf("f", "s1", "s2", "u").map { stories.currentState.note(it)?.feedSet })
        assertEquals("Beach weekend", stories.currentState.note("s2")?.setInfo?.name)
        assertNull(stories.currentState.note("f")?.setInfo)

        // Shown where its oldest photo is, on Friday, with its name and highlights.
        val block = assertIs<FeedBlock.Similar>(stories.currentState.feed.blocks().single { it.items.size == 4 })
        assertEquals(listOf("s2", "f"), block.highlights.map { it.id })
        assertEquals("Beach weekend", block.named?.name)
        assertFalse(block.named!!.isPartOf(block))
        assertEquals(FRI, block.items.first().day)
        assertEquals("Beach weekend", stories.currentState.namedSetOf("u")?.name)

        val file = setup.fileSystem.read(SETS_ROOT / "media" / "s2.json") { readUtf8() }
        assertTrue("\"setInfo\"" in file && "\"showOn\": \"first\"" in file && "\"highlight\": 1" in file, file)

        // Kept after a restart.
        val again = setup(setup.fileSystem).stories
        assertEquals("Beach weekend", assertIs<FeedBlock.Similar>(again.currentState.feed.blocks().single { it.items.size == 4 }).named?.name)

        // Fewer than two photos make nothing.
        assertNull(stories.makeMoment(listOf("alone"), "One", emptyList(), ShowOn.FIRST))
    }

    @Test
    fun theNameMovesWithTheCover() = runTest {
        val stories = setup().stories
        stories.makeMoment(listOf("f", "s1", "s2"), "Weekend", listOf("f", "s1"), ShowOn.FIRST)
        assertEquals("Weekend", stories.currentState.note("f")?.setInfo?.name)
        stories.moveHighlight(listOf("f", "s1", "s2"), "s1", 0)
        assertNull(stories.currentState.note("f")?.setInfo)
        assertEquals("Weekend", stories.currentState.note("s1")?.setInfo?.name)

        // Taken out of the set: it is no longer a highlight, and the name stays with the set.
        stories.takeOutOfSet("s1")
        assertNull(stories.currentState.note("s1")?.highlight)
        assertNull(stories.currentState.note("s1")?.setInfo)
        assertEquals("Weekend", stories.currentState.namedSetOf("f")?.name)
        assertEquals(listOf("f", "s2"), stories.currentState.namedSetOf("s2")?.itemIds)
    }

    @Test
    fun swipingPastASetGoesToWhatIsShownBesideIt() = runTest {
        val stories = setup().stories
        val feed = stories.currentState.feed
        val shown = feed.entries.flatMap { (it as FeedEntry.Section).section.moments.flatMap { m -> m.shownBlocks } }
        assertTrue(shown.size >= 3, "$shown")
        val middle = shown[1]
        assertEquals(shown[2], feed.beside(middle.items.map { it.id }, forward = true)?.second)
        assertEquals(shown[0], feed.beside(middle.items.map { it.id }, forward = false)?.second)
        assertNull(feed.beside(shown.first().items.map { it.id }, forward = false))
        assertNull(feed.beside(shown.last().items.map { it.id }, forward = true))
        assertNull(feed.beside(listOf("nowhere"), forward = true))
    }

    @Test
    fun aPhotoOfASetIsFoundInItsSet() = runTest {
        val feed = setup().stories.currentState.feed
        val blocks = feed.entries.flatMap { (it as FeedEntry.Section).section.moments.flatMap { m -> m.shownBlocks } }
        val set = blocks.filterIsInstance<FeedBlock.Similar>().first()
        set.items.forEach { assertEquals(set, feed.setShowing(it.id)) }
        blocks.filterIsInstance<FeedBlock.Single>().forEach { assertNull(feed.setShowing(it.item.id)) }
        assertNull(feed.setShowing("nowhere"))
    }

    @Test
    fun aMomentIsNamedAndRenamedLater() = runTest {
        val setup = setup()
        val stories = setup.stories
        val weekend = listOf("f", "s1", "s2", "u")
        stories.makeMoment(weekend, "", listOf("s1"), ShowOn.LAST)
        stories.nameMoment(weekend, " Beach weekend ")
        assertEquals("Beach weekend", stories.currentState.namedSetOf("u")?.name)
        // Its place in Moments and its cover stay.
        assertEquals(ShowOn.LAST, stories.currentState.namedSetOf("u")?.showOn)
        assertEquals("Beach weekend", stories.currentState.note("s1")?.setInfo?.name)
        assertEquals(weekend.map { "set-1" }, weekend.map { stories.currentState.note(it)?.feedSet })

        stories.nameMoment(weekend, "Sea")
        assertEquals("Sea", stories.currentState.feed.blocks().single { it.items.size == 4 }.named?.name)
        // Cleared: unnamed, still together.
        stories.nameMoment(weekend, "  ")
        assertNull(stories.currentState.feed.blocks().single { it.items.size == 4 }.named!!.name)

        // Kept after a restart.
        stories.nameMoment(weekend, "Sea")
        assertEquals("Sea", setup(setup.fileSystem).stories.currentState.namedSetOf("f")?.name)
    }

    @Test
    fun aMomentNeedsNoName() = runTest {
        val setup = setup()
        val stories = setup.stories
        val weekend = listOf("f", "s1", "s2", "u")
        stories.makeMoment(weekend, "  ", emptyList(), ShowOn.LAST)
        // Shown together where its newest photo is, as a named one would be, with no name.
        val rows = stories.currentState.feed.rows()
        assertTrue(weekend in rows.single { SUN in it.first }.second, "$rows")
        val block = stories.currentState.feed.blocks().single { it.items.size == 4 }
        assertNotNull(block.named)
        assertNull(block.named!!.name)
        assertEquals(weekend, stories.currentState.namedSetOf("f")?.itemIds)

        // Kept after a restart.
        val again = setup(setup.fileSystem).stories
        assertNull(again.currentState.feed.blocks().single { it.items.size == 4 }.named!!.name)
    }

    @Test
    fun aNamedSetShowsOnItsFirstItsLastOrEachOfItsDays() = runTest {
        val stories = setup().stories
        val weekend = listOf("f", "s1", "s2", "u")
        stories.makeMoment(weekend, "Beach weekend", emptyList(), ShowOn.LAST)
        // On Sunday, where its newest photo is; Friday and Saturday keep only what isn't in it.
        val rows = stories.currentState.feed.rows()
        val sunday = rows.single { SUN in it.first }
        assertTrue(weekend in sunday.second, "$rows")
        assertTrue(rows.none { FRI in it.first && it.second.flatten().any { id -> id in weekend } }, "$rows")
        assertTrue(rows.none { SAT in it.first }, "$rows")

        // On each day: each day its own photos, all of them part of the whole set.
        stories.makeMoment(weekend, "Beach weekend", emptyList(), ShowOn.EACH)
        val each = stories.currentState.feed.blocks().filter { it.named != null }
        assertEquals(setOf(listOf("f"), listOf("s1", "s2"), listOf("u")), each.map { block -> block.items.map { it.id } }.toSet())
        each.forEach { block ->
            assertTrue(block.named!!.isPartOf(block))
            assertEquals(weekend, block.named!!.itemIds)
        }
        assertIs<FeedBlock.Single>(each.single { block -> block.items.map { it.id } == listOf("f") })
    }

    @Test
    fun buildingTheFeedPlacesSetsByShowOn() {
        val sets = mapOf("f" to "w", "s1" to "w", "u" to "w")
        fun build(showOn: ShowOn?) = buildFeed(
            items, ShownSources(hidden = emptySet()), TimelineGrouping(), sets = sets,
            placements = showOn?.let { mapOf("w" to it) }.orEmpty(),
        ).rows().flatMap { (days, blocks) -> blocks.filter { block -> block.any { it in sets } }.map { days.single() to it } }
        assertEquals(listOf(FRI to listOf("f", "s1", "u")), build(null))
        assertEquals(listOf(FRI to listOf("f", "s1", "u")), build(ShowOn.FIRST))
        assertEquals(listOf(SUN to listOf("f", "s1", "u")), build(ShowOn.LAST))
        assertEquals(listOf(SUN to listOf("u"), SAT to listOf("s1"), FRI to listOf("f")), build(ShowOn.EACH))
    }

    @Test
    fun sameFrameForAllKeepsTheSetTogether() = runTest {
        val setup = setup()
        val stories = setup.stories
        val frame = CropRect(0.1f, 0.1f, 0.8f, 0.8f)
        // A burst nobody merged: cropping its photos would take them out of it, so it becomes a set made by hand.
        stories.setMomentsCrop(burst, frame)
        burst.forEach { assertEquals(frame, stories.currentState.note(it)?.crops?.moments) }
        assertEquals(burst, stories.burstBlock().items.map { it.id })
        assertEquals(1, burst.map { stories.currentState.note(it)?.feedSet }.distinct().size)

        // A set made by hand keeps its id, and a crop per photo is saved at once.
        val set = stories.makeMoment(listOf("s1", "s2"), "Sat", emptyList(), ShowOn.FIRST)
        stories.setMomentsCrops(mapOf("s1" to frame, "s2" to null))
        assertEquals(frame, stories.currentState.note("s1")?.crops?.moments)
        assertNull(stories.currentState.note("s2")?.crops?.moments)
        assertEquals(set, stories.currentState.note("s1")?.feedSet)
        assertNotNull(stories.currentState.feed.blocks().singleOrNull { block -> block.items.map { it.id } == listOf("s1", "s2") })

        // Cropping a photo of a set made by hand on its own doesn't take it out either.
        stories.setCrops("s2", stories.currentState.note("s2")!!.crops.copy(moments = frame))
        assertNotNull(stories.currentState.feed.blocks().singleOrNull { block -> block.items.map { it.id } == listOf("s1", "s2") })
    }
}
