package com.chencohensolution.journal

import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.SoundRecording
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.similarity.PhotoLooks
import com.chencohensolution.journal.stories.DayWindow
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FeedSource
import com.chencohensolution.journal.stories.buildFeed
import com.chencohensolution.journal.stories.buildWindow
import com.chencohensolution.journal.stories.windowsFor
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The whole library in a list, read as Moments' windows read the database. */
private class ListSource(all: List<MediaItem>) : FeedSource {
    val items = all.sortedByDescending { it.takenAtMillis }
    override fun items(first: LocalDay, last: LocalDay) = items.filter { it.day in first..last }
    override fun newestBefore(day: LocalDay) = items.firstOrNull { it.day < day }
    override fun oldestAfter(day: LocalDay) = items.lastOrNull { it.day > day }
    override fun daysOf(ids: Collection<String>) = items.filter { it.id in ids }.mapTo(HashSet()) { it.day }
}

private const val HOUR = 3_600_000L

private class WindowLibrary(val items: List<MediaItem>, val sounds: List<SoundRecording>, val sets: Map<String, String>)

/** Two months of made-up days: bursts of photos at all hours (some past midnight), several sources, calls, recordings. */
private fun library(seed: Int): WindowLibrary {
    val random = Random(seed)
    val sources = listOf(MediaSource.CAMERA, MediaSource.CAMERA, MediaSource.WHATSAPP, MediaSource.SCREENSHOTS, MediaSource.folder("Telegram"))
    val start = LocalDay(2026, 8, 1).toEpochDay() * 86_400_000L
    val items = mutableListOf<MediaItem>()
    var at = start
    var n = 0
    while (at < start + 60 * 86_400_000L) {
        at += when (random.nextInt(10)) {
            in 0..5 -> random.nextLong(60_000, 40 * 60_000)
            in 6..8 -> random.nextLong(HOUR, 9 * HOUR)
            else -> random.nextLong(20 * HOUR, 70 * HOUR)
        }
        // Days in UTC here; the phone's would be local, which changes nothing about the windows.
        val day = LocalDay.fromEpochDay(at / 86_400_000L)
        items += MediaItem(
            "p${n++}", if (random.nextInt(12) == 0) MediaType.VIDEO else MediaType.IMAGE, at, day, null, 10, 10, 100, "IMG_$n.jpg",
            source = sources[random.nextInt(sources.size)],
        )
    }
    val sounds = (0 until 25).map { k ->
        val begin = start + random.nextLong(60 * 86_400_000L)
        val kind = AudioKind.entries[random.nextInt(3)]
        SoundRecording(
            sha256 = k.toString().padStart(64, '0'), kind = kind, startMillis = begin, day = LocalDay.fromEpochDay(begin / 86_400_000L),
            durationMillis = if (kind == AudioKind.RECORDING) random.nextLong(10 * 60_000, 5 * HOUR) else random.nextLong(30_000, 20 * 60_000),
            source = if (kind == AudioKind.VOICE_MESSAGE) MediaSource.WHATSAPP else MediaSource.OTHER,
        )
    }
    // Two sets merged by hand, each across days.
    val sets = (0 until 2).flatMap { k -> (0 until 3).map { items[random.nextInt(items.size)].id to "set$k" } }.toMap()
    return WindowLibrary(items, sounds, sets)
}

class FeedWindowsTest {
    @Test
    fun rowsBuiltByWindowAreTheWholeBuildsRows() {
        var windows = 0
        repeat(24) { seed ->
            val library = library(seed)
            val source = ListSource(library.items)
            val grouping = if (seed % 2 == 0) TimelineGrouping(GroupingMode.BY_DAY) else TimelineGrouping(GroupingMode.BY_GAP, gapHours = 1 + seed % 6)
            val shown = ShownSources(hidden = if (seed % 3 == 0) setOf(MediaSource.WHATSAPP, MediaSource.SCREENSHOTS) else emptySet())
            val whole = buildFeed(source.items, shown, grouping, Places.NONE, PhotoLooks.NONE, sets = library.sets, sounds = library.sounds)
                .entries.mapNotNull { (it as? FeedEntry.Section)?.section }
            val random = Random(seed + 7)
            val days = source.items.map { it.day }.distinct()
            repeat(6) {
                val changed = (0..random.nextInt(3)).map { days[random.nextInt(days.size)] }.toSet()
                windowsFor(changed, source, grouping, library.sounds, library.sets).forEach { window ->
                    windows++
                    val built = buildWindow(window, source, shown, grouping, Places.NONE, PhotoLooks.NONE, emptySet(), library.sets, emptySet(), library.sounds)
                    val expected = whole.filter { section -> section.days.any { it in window } }
                    assertTrue(expected.all { section -> section.days.all { it in window } }, "seed $seed: a row crosses the edge of $window: ${expected.filterNot { s -> s.days.all { it in window } }.map { s -> s.key + " " + s.days + " " + s.span.items.map { it.id + "@" + it.takenAtMillis } }}")
                    fun summary(sections: List<com.chencohensolution.journal.stories.FeedSection>) = sections.map { s ->
                        "${s.key}: ${s.items.map { it.id }} sounds=${s.sounds.map { it.key }} rec=${s.moments.mapNotNull { it.recording?.sha256?.take(3) }} hidden=${s.hidden}"
                    }
                    assertEquals(summary(expected), summary(built), "seed $seed, $grouping, $window")
                    assertEquals(expected, built, "seed $seed, $grouping, $window")
                }
            }
        }
        assertTrue(windows > 100, "$windows windows")
    }

    @Test
    fun aWindowIsTheChangedDaysWhenTheyAreBreaks() {
        val day = LocalDay(2026, 9, 10)
        val items = listOf(day.plusDays(-2), day, day.plusDays(2)).mapIndexed { i, d ->
            MediaItem("p$i", MediaType.IMAGE, d.toEpochDay() * 86_400_000L + 12 * HOUR, d, null, 10, 10, 100, "IMG_$i.jpg")
        }
        val source = ListSource(items)
        assertEquals(listOf(DayWindow(day, day)), windowsFor(setOf(day), source, TimelineGrouping(GroupingMode.BY_DAY), emptyList(), emptyMap()))
        // Breaks of 3 hours: the days around are a day and more away.
        assertEquals(listOf(DayWindow(day, day)), windowsFor(setOf(day), source, TimelineGrouping(GroupingMode.BY_GAP, gapHours = 3), emptyList(), emptyMap()))
        // Breaks of 72 hours: all three photos are one row.
        assertEquals(
            listOf(DayWindow(day.plusDays(-2), day.plusDays(2))),
            windowsFor(setOf(day), source, TimelineGrouping(GroupingMode.BY_GAP, gapHours = 72), emptyList(), emptyMap()),
        )
    }
}

private fun MediaItem.toLibraryRow() = com.chencohensolution.journal.db.Library_item(
    id = id, sha256 = sha256, server_sha256 = null, is_on_device = isOnDevice, is_video = type == MediaType.VIDEO,
    taken_millis = takenAtMillis, epoch_day = day.toEpochDay(), duration_millis = durationMillis, width = width.toLong(),
    height = height.toLong(), size = sizeBytes, file_name = fileName, burst_id = burstId, source = source.id, latitude = location?.latitude,
    longitude = location?.longitude, modified_millis = modifiedMillis, mime_type = mimeType, is_private = isPrivate,
    aesthetic = aesthetic?.toDouble(),
)

class FeedTableTest {
    @Test
    fun keptRowsAreTheWholeBuildsRowsThroughChanges() {
        repeat(12) { seed ->
            val library = library(seed)
            val database = testLibraryDatabase()
            val queries = database.libraryQueries
            var items = library.items
            database.transaction { items.forEach { queries.upsertLibraryItem(it.toLibraryRow()) } }
            val grouping = if (seed % 2 == 0) TimelineGrouping(GroupingMode.BY_DAY) else TimelineGrouping(GroupingMode.BY_GAP, gapHours = 1 + seed % 6)
            val shown = ShownSources(hidden = if (seed % 3 == 0) setOf(MediaSource.WHATSAPP) else emptySet())
            val context = com.chencohensolution.journal.stories.FeedContext(
                shown, grouping, Places.NONE, PhotoLooks.NONE, emptySet(), library.sets, library.sounds, version = "v$seed",
            )
            val table = com.chencohensolution.journal.stories.FeedTable(database)
            val sounds = library.sounds.associateBy { it.sha256 }
            val random = Random(seed + 99)
            var lastChanged = ""
            repeat(5) { round ->
                table.refresh(context)
                val kept = table.sections(Long.MAX_VALUE, 1_000_000, sounds) { it.item }
                val whole = buildFeed(ListSource(items).items, shown, grouping, Places.NONE, PhotoLooks.NONE, sets = library.sets, sounds = library.sounds)
                    .entries.mapNotNull { (it as? FeedEntry.Section)?.section }
                val wholeKeys = whole.associateBy { it.key }
                val keptKeys = kept.associateBy { it.key }
                val differ = (wholeKeys.keys - keptKeys.keys) + (keptKeys.keys - wholeKeys.keys)
                assertEquals(emptySet(), differ, "seed $seed round $round: " + differ.map { k -> k + " whole=" + wholeKeys[k]?.days + " kept=" + keptKeys[k]?.let { s -> s.days.toString() + s.items.map { it.id } } } + " changed: " + lastChanged)
                assertEquals(whole, kept, "seed $seed round $round")
                val wholeTimeline = com.chencohensolution.journal.stories.FeedTimeline.of(com.chencohensolution.journal.stories.MomentsFeed(whole.map(FeedEntry::Section)))
                assertEquals(wholeTimeline.months, table.timeline().months, "seed $seed round $round: timeline")
                // Some photos deleted, some moved to another time, a few new.
                val gone = items.filter { random.nextInt(25) == 0 }
                val moved = items.filter { it !in gone && random.nextInt(30) == 0 }.map { item ->
                    val at = item.takenAtMillis + random.nextLong(-30 * HOUR, 30 * HOUR)
                    item.copy(takenAtMillis = at, day = LocalDay.fromEpochDay(at / 86_400_000L))
                }
                val added = (0..random.nextInt(4)).map { n ->
                    val at = items[random.nextInt(items.size)].takenAtMillis + random.nextLong(-3 * HOUR, 3 * HOUR)
                    MediaItem("new$round-$n", MediaType.IMAGE, at, LocalDay.fromEpochDay(at / 86_400_000L), null, 10, 10, 100, "NEW_$n.jpg")
                }
                database.transaction {
                    gone.forEach { queries.deleteRemoved(listOf(it.id)) }
                    (moved + added).forEach { queries.upsertLibraryItem(it.toLibraryRow()) }
                }
                val movedIds = moved.associateBy { it.id }
                lastChanged = "gone=${gone.map { it.id + "@" + it.day }} moved=${moved.map { m -> m.id + "@" + items.first { it.id == m.id }.day + "->" + m.day }} added=${added.map { it.id + "@" + it.day }}"
                items = items.filter { it !in gone }.map { movedIds[it.id] ?: it } + added
            }
        }
    }
}

class FeedPagerTest {
    @Test
    fun momentsAreReadAPageAtATimeAsTheWholeBuildsRows() = kotlinx.coroutines.test.runTest {
        val library = library(3)
        val database = testLibraryDatabase()
        database.transaction { library.items.forEach { database.libraryQueries.upsertLibraryItem(it.toLibraryRow()) } }
        val dispatcher = kotlinx.coroutines.test.StandardTestDispatcher(testScheduler)
        val pager = com.chencohensolution.journal.stories.FeedPager(
            com.chencohensolution.journal.stories.FeedTable(database), com.chencohensolution.journal.library.LibraryTable(database), dispatcher, pageSize = { 5 },
        )
        val grouping = TimelineGrouping(GroupingMode.BY_DAY)
        val context = com.chencohensolution.journal.stories.FeedContext(
            ShownSources(hidden = emptySet()), grouping, Places.NONE, PhotoLooks.NONE, emptySet(), library.sets, library.sounds, "v",
        )
        val whole = buildFeed(ListSource(library.items).items, ShownSources(hidden = emptySet()), grouping, Places.NONE, PhotoLooks.NONE, sets = library.sets, sounds = library.sounds)
        val wholeSections = whole.entries.mapNotNull { (it as? FeedEntry.Section)?.section }

        val first = pager.refresh(context, emptySet()) { it.item }
        assertEquals(wholeSections.take(5), first.entries.map { (it as FeedEntry.Section).section })
        assertEquals(whole.counts, first.counts)
        assertEquals(whole.timeline.months, first.timeline.months)
        val more = pager.loadMore(context, emptySet()) { it.item }!!
        assertEquals(wholeSections.take(10), more.entries.map { (it as FeedEntry.Section).section })
        // Going to an old day reads down to its row.
        val old = wholeSections[wholeSections.size - 3]
        val key = pager.sectionOf(old.days.first())!!
        assertEquals(old.key, key)
        val down = pager.loadTo(key, context, emptySet()) { it.item }!!
        assertTrue(down.entries.any { (it as FeedEntry.Section).section.key == old.key })
    }
}
