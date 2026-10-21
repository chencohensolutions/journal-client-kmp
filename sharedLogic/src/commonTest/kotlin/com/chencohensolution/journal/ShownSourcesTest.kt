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
        assertEquals(setOf(MediaSource.CAMERA, MediaSource.OTHER), JournalSettings(ShownTestStore()).shownSources.sources)

        // The Moments filter bar's choice from before: Camera and Other kept as they were, WhatsApp turned off.
        val store = ShownTestStore().apply { values["feed_sources"] = "CAMERA,WHATSAPP" }
        val settings = JournalSettings(store)
        assertEquals(setOf(MediaSource.CAMERA), settings.shownSources.sources)

        val chosen = ShownSources(setOf(MediaSource.WHATSAPP), sayWhatsHidden = false, alwaysShown = setOf(DaySource(OCT_4, MediaSource.SCREENSHOTS)))
        settings.shownSources = chosen
        assertEquals(chosen, JournalSettings(store).shownSources)
        assertEquals(null, store.values["feed_sources"])
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
    fun hiddenPhotosStayOnTheStorylineAndLeaveTagsAndAllPhotos() {
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
        assertEquals(listOf(listOf("c"), listOf("c-near")), state.shownDays(library.days).map { day -> day.items.map { it.id } })
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
        assertEquals(setOf(MediaSource.CAMERA, MediaSource.OTHER), settings.currentState.shownSources.sources)
        stories.hideOnRow(row.key, MediaSource.WHATSAPP)
        assertEquals(mapOf(MediaSource.WHATSAPP to 1), stories.currentState.feed.sections().single().hidden)

        settings.alwaysShow(row.days, MediaSource.WHATSAPP)
        advanceUntilIdle()
        assertEquals(listOf("w", "c"), stories.currentState.feed.sections().single().items.map { it.id })
        assertEquals(setOf(DaySource(OCT_4, MediaSource.WHATSAPP)), JournalSettings(settingsStore).shownSources.alwaysShown)
    }
}
