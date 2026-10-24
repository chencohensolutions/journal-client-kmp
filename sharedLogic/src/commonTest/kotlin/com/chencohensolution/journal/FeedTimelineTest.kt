package com.chencohensolution.journal

import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.stories.FeedTimeline
import com.chencohensolution.journal.stories.TimelineMonth
import com.chencohensolution.journal.stories.buildFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val HOUR = 3_600_000L

/** A photo at [hour] o'clock (UTC) on [day]. */
private fun shot(id: String, day: LocalDay, hour: Int = 12) =
    MediaItem(id, MediaType.IMAGE, day.toEpochDay() * 24 * HOUR + hour * HOUR, day, null, 4, 3, 100, "$id.jpg")

private fun timelineOf(vararg items: MediaItem, grouping: TimelineGrouping = TimelineGrouping()): FeedTimeline =
    buildFeed(items.sortedByDescending { it.takenAtMillis }, ShownSources(), grouping).timeline

class FeedTimelineTest {
    private val timeline = timelineOf(
        shot("a", LocalDay(2026, 10, 20), 9), shot("b", LocalDay(2026, 10, 20), 18), shot("c", LocalDay(2026, 10, 3)),
        shot("d", LocalDay(2026, 8, 14)),
        shot("e", LocalDay(2025, 12, 31)), shot("f", LocalDay(2025, 12, 2)),
    )

    @Test
    fun monthsAndDaysAreNewestFirst() {
        assertEquals(listOf(2026 to 10, 2026 to 8, 2025 to 12), timeline.months.map { it.year to it.month })
        assertEquals(listOf(20, 3), timeline.months[0].days.map { it.day.day })
        assertEquals(3, timeline.months[0].count)
        assertEquals(2, timeline.months[0].dayOn(20)?.count)
        assertNull(timeline.months[0].dayOn(21))
        assertEquals(listOf(2026, 2025), timeline.years)
        assertEquals(3, timeline.peak)
    }

    @Test
    fun stepsSkipMonthsWithoutPhotosAndStopAtTheEnds() {
        // Positive is newer: from August, -1 is the December before it, not July.
        assertEquals(2, timeline.step(1, -1))
        assertEquals(0, timeline.step(1, 1))
        assertEquals(0, timeline.step(0, 1))
        assertEquals(2, timeline.step(2, -5))
        assertTrue(timeline.isNewest(0))
        assertTrue(timeline.isOldest(2))
        assertFalse(timeline.isOldest(1))
    }

    @Test
    fun goingToAMonthLandsOnItsNewestDay() {
        assertEquals(LocalDay(2026, 10, 20), timeline.landing(0).day)
        assertEquals(LocalDay(2025, 12, 31), timeline.landing(2).day)
    }

    @Test
    fun aDaySnapsToTheNearestDayWithPhotos() {
        assertEquals(20, timeline.nearestDay(0, 25)?.day?.day)
        assertEquals(3, timeline.nearestDay(0, 1)?.day?.day)
        // 15 is five days from both: the later one.
        assertEquals(20, timelineOf(shot("x", LocalDay(2026, 1, 10)), shot("y", LocalDay(2026, 1, 20))).nearestDay(0, 15)?.day?.day)
    }

    @Test
    fun aMonthWithoutPhotosGoesToTheNearestOne() {
        assertEquals(1, timeline.nearestMonth(2026, 7))
        assertEquals(0, timeline.nearestMonth(2027, 3))
        assertEquals(2, timeline.nearestMonth(2019, 1))
        // September 2026 is a month from both October and August: the newer one.
        assertEquals(0, timeline.nearestMonth(2026, 9))
        assertNull(FeedTimeline.NONE.nearestMonth(2026, 9))
    }

    @Test
    fun crossingAYearIsTold() {
        assertTrue(timeline.crossesYear(1, 2))
        assertFalse(timeline.crossesYear(0, 1))
    }

    @Test
    fun rowsKnowTheirMonth() {
        val feed = buildFeed(listOf(shot("a", LocalDay(2026, 10, 20)), shot("d", LocalDay(2026, 8, 14))), ShownSources(), TimelineGrouping())
        val august = feed.timeline.landing(1)
        assertEquals(1, feed.timeline.monthOf(august.sectionKey))
        assertNull(feed.timeline.monthOf("nothing"))
    }

    @Test
    fun aRowOverTwoDaysLandsOnTheRowForBoth() {
        // By gaps, an evening and the morning after are one row.
        val timeline = timelineOf(
            shot("late", LocalDay(2026, 10, 31), 23), shot("early", LocalDay(2026, 11, 1), 1),
            grouping = TimelineGrouping(mode = GroupingMode.BY_GAP),
        )
        assertEquals(listOf(2026 to 11, 2026 to 10), timeline.months.map { it.year to it.month })
        assertEquals(timeline.landing(0).sectionKey, timeline.landing(1).sectionKey)
        assertEquals(0, timeline.monthOf(timeline.landing(1).sectionKey))
    }

    @Test
    fun monthsKnowTheirLengthAndFirstWeekday() {
        assertEquals(29, TimelineMonth(2024, 2, emptyList()).length)
        assertEquals(28, TimelineMonth(2026, 2, emptyList()).length)
        assertEquals(31, TimelineMonth(2026, 12, emptyList()).length)
        assertEquals(30, TimelineMonth(2026, 11, emptyList()).length)
        // October 1, 2026 is a Thursday: three blanks in a week from Monday, four from Sunday.
        assertEquals(3, TimelineMonth(2026, 10, emptyList()).leadingBlanks(firstWeekday = 1))
        assertEquals(4, TimelineMonth(2026, 10, emptyList()).leadingBlanks(firstWeekday = 7))
        // June 1, 2026 is a Monday.
        assertEquals(0, TimelineMonth(2026, 6, emptyList()).leadingBlanks(firstWeekday = 1))
    }
}
