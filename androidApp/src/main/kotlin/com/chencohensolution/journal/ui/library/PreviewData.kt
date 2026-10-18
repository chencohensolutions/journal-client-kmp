package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.stories.Moment
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.Story
import com.chencohensolution.journal.stories.StoryMember
import com.chencohensolution.journal.stories.StoryView
import com.chencohensolution.journal.stories.TimelineEntry
import java.time.Instant
import java.time.ZoneId

/**
 * Made-up library and stories for previews and screenshot tests (src/screenshotTest). Dates are relative to now, so
 * "Today" and "Yesterday" read right whenever the previews are rendered. Thumbnails are placeholders drawn by
 * [rememberThumbnail].
 */
internal object PreviewData {
    private const val HOUR = 3_600_000L
    private const val DAY = 24 * HOUR
    private val now = System.currentTimeMillis()
    private var nextId = 1

    /** A photo [daysAgo] days back; [ratio] is width / height. */
    fun photo(
        daysAgo: Int,
        hour: Int = 12,
        ratio: Float = 4f / 3f,
        backup: BackupStatus = BackupStatus.NotBackedUp,
        videoSeconds: Int? = null,
    ): MediaItem {
        val taken = Instant.ofEpochMilli(now - daysAgo * DAY).atZone(ZoneId.systemDefault()).withHour(hour).withMinute(nextId % 60)
        val id = (nextId++).toString()
        return MediaItem(
            id = id,
            type = if (videoSeconds != null) MediaType.VIDEO else MediaType.IMAGE,
            takenAtMillis = taken.toInstant().toEpochMilli(),
            day = LocalDay(taken.year, taken.monthValue, taken.dayOfMonth),
            durationMillis = videoSeconds?.let { it * 1000L },
            width = 4000,
            height = (4000 / ratio).toInt(),
            sizeBytes = 3_200_000,
            fileName = "IMG_$id.heic",
            backup = backup,
        )
    }

    /** [count] photos on one day, newest first, as the library lists them. */
    fun day(daysAgo: Int, count: Int, item: (Int) -> MediaItem = { photo(daysAgo, hour = 20 - it) }): List<MediaItem> =
        List(count) { item(it) }

    fun story(title: String, items: List<MediaItem>, intro: String = ""): StoryView {
        val sorted = items.sortedBy { it.takenAtMillis }
        val story = Story(
            id = "2026-10-17T12-00-00.000Z-$title",
            title = title,
            members = sorted.map { StoryMember(it.id, it.fileName, it.takenAtMillis) },
            createdAt = "2026-10-17T12:00:00Z",
            updatedAt = "2026-10-17T12:00:00Z",
            intro = intro,
        )
        return StoryView(story, sorted)
    }

    private val ratios = listOf(4f / 3f, 3f / 4f, 16f / 9f, 1f)

    val japan = story(
        "Japan trip",
        (2..10).flatMap { d -> day(d, 4) { photo(d, hour = 9 + it * 3, ratio = ratios[it], backup = if (d > 6) BackupStatus.BackedUp else BackupStatus.NotBackedUp) } },
        intro = "Nine days, two cities and far too many trains.",
    )
    val birthday = story("Noa’s birthday", day(15, 6) { photo(15, hour = 16 + it % 4, backup = BackupStatus.BackedUp) })
    val weekend = story("Weekend in the north", day(22, 5) { photo(22, ratio = 16f / 9f, backup = BackupStatus.BackedUp) } + day(23, 4) { photo(23, backup = BackupStatus.BackedUp) })

    val today = day(0, 7) {
        when (it) {
            0 -> photo(0, hour = 21, backup = BackupStatus.Uploading(0.43f))
            1, 2 -> photo(0, hour = 20 - it, backup = BackupStatus.Queued, videoSeconds = if (it == 2) 42 else null)
            else -> photo(0, hour = 18 - it)
        }
    }
    val yesterday = day(1, 5) {
        photo(1, hour = 22 - it, backup = when (it) { 0, 1 -> BackupStatus.BackedUp; 2 -> BackupStatus.Failed("Server error"); else -> BackupStatus.NotBackedUp })
    }
    val earlier = day(11, 6)

    val unsorted = listOf(today, yesterday, earlier)

    val library = LibraryState(
        access = PhotoAccess.FULL,
        days = (unsorted + listOf(japan.items, birthday.items, weekend.items))
            .flatten()
            .sortedByDescending { it.takenAtMillis }
            .groupBy { it.day }
            .map { (day, items) -> DayGroup(day, items) },
    )

    val stories = StoriesState(
        stories = listOf(japan, birthday, weekend),
        timeline = listOf(
            TimelineEntry.MomentEntry(Moment(today, GroupingMode.BY_DAY)),
            TimelineEntry.MomentEntry(Moment(yesterday, GroupingMode.BY_DAY)),
            TimelineEntry.StoryEntry(japan),
            TimelineEntry.MomentEntry(Moment(earlier, GroupingMode.BY_DAY)),
            TimelineEntry.StoryEntry(birthday),
            TimelineEntry.StoryEntry(weekend),
        ),
        unsortedDays = unsorted.map { DayGroup(it.first().day, it) },
        moments = unsorted.map { Moment(it, GroupingMode.BY_DAY) },
    )

    /** A first launch: access granted, but no stories yet. */
    val noStories = stories.copy(stories = emptyList(), timeline = stories.timeline.filterIsInstance<TimelineEntry.MomentEntry>())
}
