package com.chencohensolution.journal

import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.settings.DaySource
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FeedReveal
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.MomentsFeed
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.buildFeed
import com.chencohensolution.journal.stories.buildState
import com.chencohensolution.journal.stories.sourceCounts
import com.chencohensolution.journal.stories.yearsOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val HOUR = 3_600_000L
private val OCT_4 = LocalDay(2026, 10, 4)
private val OCT_3 = LocalDay(2026, 10, 3)

private fun shot(id: String, day: LocalDay, hour: Int, source: MediaSource = MediaSource.CAMERA) = MediaItem(
    id, MediaType.IMAGE, day.toEpochDay() * 86_400_000 + hour * HOUR, day, null, 4, 3, 100, "$id.jpg", source = source,
)

private fun newestFirst(vararg items: MediaItem) = items.sortedByDescending { it.takenAtMillis }

private fun MomentsFeed.sections() = entries.filterIsInstance<FeedEntry.Section>().map { it.section }

private class ShownTestStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private class ShownTestLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

class ShownSourcesTest {
    @Test
    fun whatsAppAndScreenshotsAreOffAtFirstAndTheOldFilterBarKeepsTheRest() {
        val importedOff = ShownSources.DEFAULT_HIDDEN_IMPORTED
        assertEquals(setOf(MediaSource.WHATSAPP, MediaSource.SCREENSHOTS) + importedOff, JournalSettings(ShownTestStore()).shownSources.hidden)

        // The Moments filter bar's choice from before: Camera and Other kept as they were, WhatsApp turned off.
        val store = ShownTestStore().apply { values["feed_sources"] = "CAMERA,WHATSAPP" }
        val settings = JournalSettings(store)
        assertEquals(setOf(MediaSource.WHATSAPP, MediaSource.SCREENSHOTS, MediaSource.OTHER) + importedOff, settings.shownSources.hidden)

        val flowers = MediaSource.folder("Flowers, 2024")
        val chosen = ShownSources(
            setOf(MediaSource.CAMERA, flowers),
            sayWhatsHidden = false,
            alwaysShown = setOf(DaySource(OCT_4, MediaSource.SCREENSHOTS), DaySource(OCT_4, flowers)),
        )
        settings.shownSources = chosen
        assertEquals(chosen, JournalSettings(store).shownSources)
        assertEquals(null, store.values["feed_sources"])

        // The filter bar is on at first, and stays off once turned off.
        assertTrue(chosen.showFilterBar)
        settings.shownSources = chosen.copy(showFilterBar = false)
        assertEquals(false, JournalSettings(store).shownSources.showFilterBar)
    }

    @Test
    fun importedIsOneSwitchWithItsKindsUnderIt() {
        val camera = MediaSource.imported(MediaSource.CAMERA)
        val whatsapp = MediaSource.imported(MediaSource.WHATSAPP)
        assertEquals(camera, MediaSource.parse("import:camera"))
        assertEquals(MediaSource.CAMERA, camera.kind)
        assertTrue(camera.isImported && !MediaSource.CAMERA.isImported)

        // Its kinds are offered under Imported, not beside the phones' sources, and only once something was imported.
        val counts = mapOf(MediaSource.CAMERA to 10, camera to 300, MediaSource.imported(MediaSource.OTHER) to 40)
        val shown = ShownSources()
        assertTrue(shown.choices(counts).none { it.isImported })
        assertEquals(listOf(camera, MediaSource.imported(MediaSource.OTHER)), shown.importedChoices(counts).take(2))
        assertEquals(emptyList(), shown.importedChoices(mapOf(MediaSource.CAMERA to 10)))
        assertTrue(shown.isShown(camera) && !shown.isShown(whatsapp))

        // Imported off hides all its kinds, which keep their own choice for when it is on again.
        val off = shown.copy(importedShown = false)
        assertTrue(!off.isShown(camera) && off.isShown(MediaSource.CAMERA))
        assertTrue(off.copy(importedShown = true).isShown(camera))

        // Kept, and read back as it was.
        val store = ShownTestStore()
        JournalSettings(store).shownSources = off
        assertEquals(off, JournalSettings(store).shownSources)
    }

    @Test
    fun theFixedSourcesShownBeforeFoldersStayAsTheyWere() {
        // Kept as the sources shown until Oct 24, 2026: the fixed ones missing from it are hidden, and folders show.
        val store = ShownTestStore().apply { values["shown_sources"] = "CAMERA,WHATSAPP" }
        val shown = JournalSettings(store).shownSources
        assertEquals(setOf(MediaSource.SCREENSHOTS, MediaSource.OTHER) + ShownSources.DEFAULT_HIDDEN_IMPORTED, shown.hidden)
        assertTrue(shown.isShown(MediaSource.folder("Petcube")))
        // Its pills: the most photos first; a hidden source with none stays offered, to show it again.
        val petcube = MediaSource.folder("Petcube")
        val hiddenFolder = MediaSource.folder("Old phone")
        assertEquals(
            listOf(MediaSource.WHATSAPP, petcube, MediaSource.CAMERA, MediaSource.SCREENSHOTS, hiddenFolder, MediaSource.OTHER),
            shown.copy(hidden = shown.hidden + hiddenFolder).choices(mapOf(MediaSource.WHATSAPP to 50, petcube to 5, MediaSource.CAMERA to 2)),
        )
    }

    @Test
    fun cameraBackupKeepsTheCameraFolderAndFreeUpKeepsWhatItKept() {
        // Backup chose the fixed sources by their old names until Oct 24, 2026.
        val store = ShownTestStore().apply {
            values["backup_sources"] = "CAMERA,SCREENSHOTS"
            values["free_up_sources"] = "CAMERA,OTHER"
        }
        val settings = JournalSettings(store)
        assertEquals(setOf(MediaSource.CAMERA, MediaSource.SCREENSHOTS), settings.backup.sources)
        assertEquals(setOf(MediaSource.WHATSAPP, MediaSource.SCREENSHOTS), settings.freeUpKept)

        val petcube = MediaSource.folder("Petcube")
        settings.backup = settings.backup.copy(sources = setOf(MediaSource.CAMERA, petcube))
        assertEquals(setOf(MediaSource.CAMERA, petcube), JournalSettings(store).backup.sources)
        // The most photos first; the rest, a chosen folder with none among them, in their order.
        val flowers = MediaSource.folder("Flowers")
        assertEquals(
            listOf(MediaSource.WHATSAPP, flowers, MediaSource.CAMERA, MediaSource.SCREENSHOTS, petcube, MediaSource.OTHER),
            JournalSettings(store).backup.choices(mapOf(MediaSource.WHATSAPP to 900, flowers to 12, MediaSource.CAMERA to 5)),
        )
        settings.freeUpKept = setOf(petcube)
        assertEquals(setOf(petcube), JournalSettings(store).freeUpKept)
        assertEquals(null, store.values["free_up_sources"])
    }

    @Test
    fun aRowCountsWhatItHidesAndShowsItWhenAsked() {
        val items = newestFirst(
            shot("c1", OCT_4, 10), shot("c2", OCT_4, 18),
            shot("w1", OCT_4, 12, MediaSource.WHATSAPP), shot("w2", OCT_4, 14, MediaSource.WHATSAPP),
            shot("s1", OCT_4, 15, MediaSource.SCREENSHOTS),
            // A day of only hidden photos has no row, and says nothing.
            shot("w3", OCT_3, 9, MediaSource.WHATSAPP),
        )
        val shown = ShownSources()
        val row = buildFeed(items, shown, TimelineGrouping()).sections().single()
        assertEquals(listOf("c2", "c1"), row.items.map { it.id })
        assertEquals(mapOf(MediaSource.WHATSAPP to 2, MediaSource.SCREENSHOTS to 1), row.hidden)
        assertEquals(listOf(MediaSource.WHATSAPP, MediaSource.SCREENSHOTS), row.hidden.keys.toList())

        // Tapping "+2 WhatsApp": a band of them on this row, newest first; the chip for the rest stays.
        val revealed = buildFeed(items, shown, TimelineGrouping(), revealed = setOf(FeedReveal(row.key, MediaSource.WHATSAPP))).sections().single()
        assertEquals(mapOf(MediaSource.SCREENSHOTS to 1), revealed.hidden)
        assertEquals(MediaSource.WHATSAPP, revealed.revealed.single().source)
        assertEquals(listOf("w2", "w1"), revealed.revealed.single().items.map { it.id })
        assertEquals(listOf("c2", "c1"), revealed.items.map { it.id })

        // "Say what's hidden" off: no chips.
        assertEquals(emptyMap(), buildFeed(items, shown.copy(sayWhatsHidden = false), TimelineGrouping()).sections().single().hidden)

        // "Always show this day": its WhatsApp photos are moments of the row, and the day with only them has a row.
        val always = shown.copy(alwaysShown = setOf(DaySource(OCT_4, MediaSource.WHATSAPP), DaySource(OCT_3, MediaSource.WHATSAPP)))
        val sections = buildFeed(items, always, TimelineGrouping()).sections()
        assertEquals(listOf(listOf("c2", "w2", "w1", "c1"), listOf("w3")), sections.map { s -> s.items.map { it.id } })
        assertEquals(mapOf(MediaSource.SCREENSHOTS to 1), sections.first().hidden)
    }

    @Test
    fun aHiddenPhotoIsCountedOnceOnTheNearestRowOfItsDay() {
        // Rows split by a 3-hour break: morning and evening of one day.
        val items = newestFirst(shot("m", OCT_4, 8), shot("e", OCT_4, 20), shot("w", OCT_4, 18, MediaSource.WHATSAPP))
        val rows = buildFeed(items, ShownSources(), TimelineGrouping(GroupingMode.BY_GAP, 3)).sections()
        assertEquals(listOf("e", "m"), rows.map { it.items.single().id })
        assertEquals(listOf(mapOf(MediaSource.WHATSAPP to 1), emptyMap()), rows.map { it.hidden })
    }

    @Test
    fun hiddenPhotosStayOnTheStorylineAndLeaveTags() {
        val written = shot("w-written", OCT_4, 12, MediaSource.WHATSAPP)
        val tagged = shot("w-tag", OCT_4, 13, MediaSource.WHATSAPP)
        val camera = shot("c", OCT_4, 10)
        val nearby = shot("w-near", OCT_3, 9, MediaSource.WHATSAPP)
        val nearbyCamera = shot("c-near", OCT_3, 10)
        val library = LibraryState(days = listOf(DayGroup(OCT_4, newestFirst(written, tagged, camera)), DayGroup(OCT_3, newestFirst(nearby, nearbyCamera))))
        val notes = listOf("c", "w-tag").associateWith { PhotoNote(it, null, 0, tags = listOf("sea"), updatedAt = "2026-10-05T00:00:00Z") } +
            (written.id to PhotoNote(written.id, null, 0, title = "From Dana", updatedAt = "2026-10-05T00:00:00Z"))
        val state = buildState(library, notes, null, TimelineGrouping(), shown = ShownSources())

        assertEquals(listOf("w-written"), state.storyline.map { it.id })
        assertEquals(listOf("c"), state.tagged("sea").flatMap { it.items }.map { it.id })
        assertEquals(1, state.tags.single().count)
    }

    @Test
    fun aYearOfOnlyHiddenPhotosIsListedWithItsCount() {
        val camera = shot("c", OCT_4, 10)
        val hidden = listOf(shot("w", OCT_4, 12, MediaSource.WHATSAPP), shot("old", LocalDay(2016, 5, 1), 9, MediaSource.WHATSAPP))
        val years = yearsOf(listOf(camera), Places.NONE, hidden)
        assertEquals(listOf(2026, 2016), years.map { it.year })
        assertEquals(mapOf(MediaSource.WHATSAPP to 1), years[0].hidden)
        assertEquals(listOf("c"), years[0].covers.map { it.id })
        assertTrue(years[1].items.isEmpty() && years[1].covers.isEmpty())
        assertEquals(mapOf(MediaSource.WHATSAPP to 1), years[1].hidden)
        assertEquals(mapOf(MediaSource.CAMERA to 1, MediaSource.WHATSAPP to 2), sourceCounts(listOf(camera) + hidden))
    }

    @Test
    fun showingOnARowAndAlwaysShowingADay() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val items = listOf(shot("c", OCT_4, 10), shot("w", OCT_4, 12, MediaSource.WHATSAPP))
        val library = LibraryController(ShownTestLibrary(items), JournalSettings(ShownTestStore()), dispatcher)
        library.refresh()
        val settingsStore = ShownTestStore()
        val settings = SettingsController(JournalSettings(settingsStore), dispatcher)
        val fileSystem = FakeFileSystem()
        val stories = StoriesController(
            noteStore = FilePhotoNoteStore(fileSystem, "/data/journal".toPath()),
            library = library,
            settings = settings,
            newId = { "set-1" },
            dispatcher = dispatcher,
            now = { "2026-10-17T12:00:00Z" },
        )
        advanceUntilIdle()
        val row = stories.currentState.feed.sections().single()
        assertEquals(mapOf(MediaSource.WHATSAPP to 1), row.hidden)

        stories.showOnRow(row.key, MediaSource.WHATSAPP)
        assertEquals(listOf("w"), stories.currentState.feed.sections().single().revealed.single().items.map { it.id })
        assertEquals(ShownSources.DEFAULT_HIDDEN, settings.currentState.shownSources.hidden)
        stories.hideOnRow(row.key, MediaSource.WHATSAPP)
        assertEquals(mapOf(MediaSource.WHATSAPP to 1), stories.currentState.feed.sections().single().hidden)

        settings.alwaysShow(row.days, MediaSource.WHATSAPP)
        advanceUntilIdle()
        assertEquals(listOf("w", "c"), stories.currentState.feed.sections().single().items.map { it.id })
        assertEquals(setOf(DaySource(OCT_4, MediaSource.WHATSAPP)), JournalSettings(settingsStore).shownSources.alwaysShown)
    }
}
