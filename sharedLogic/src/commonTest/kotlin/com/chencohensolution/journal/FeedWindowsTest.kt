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
