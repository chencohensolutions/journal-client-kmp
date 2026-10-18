package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.stories.FeedBlock
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FeedMoment
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.stories.FeedTrip
import com.chencohensolution.journal.stories.Moment
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.Story
import com.chencohensolution.journal.stories.StoryGroup
import com.chencohensolution.journal.stories.StoryMember
import com.chencohensolution.journal.stories.StoryView
import com.chencohensolution.journal.stories.TimelineEntry
import com.chencohensolution.journal.stories.MomentsFeed
import java.time.Instant
import java.time.ZoneId

/**
 * Made-up library and stories for the screenshot tests. Dates are relative to now, so "Today" and "Yesterday" read
 * right whenever the previews are rendered. The pictures are placeholders (see [Screen]).
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

    /** A story without a title: only its dates and count show above its photos. */
    val untitled = story("", day(12, 8) { photo(12, hour = 10 + it, ratio = ratios[it % 4], backup = BackupStatus.BackedUp) })
    val dinner = story("Dinner at Maya’s", day(18, 2) { photo(18, hour = 20 + it, backup = BackupStatus.BackedUp) })

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
        days = (unsorted + listOf(japan.items, untitled.items, birthday.items, dinner.items, weekend.items))
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

    /** The journal with an untitled story and a two-photo one too, for the story card designs. */
    val moreStories = stories.copy(
        stories = listOf(japan, untitled, birthday, dinner, weekend),
        timeline = listOf(
            TimelineEntry.StoryEntry(japan),
            TimelineEntry.StoryEntry(untitled),
            TimelineEntry.StoryEntry(birthday),
            TimelineEntry.StoryEntry(dinner),
            TimelineEntry.StoryEntry(weekend),
        ),
    )

    /**
     * A story with a group of seven photos and a three-photo subgroup that holds the cover: eight tiles, so the last
     * row of the group's page is not full.
     */
    val seaside: StoryView = run {
        val items = day(5, 10) { photo(5, hour = 8 + it, ratio = ratios[it % 4], backup = BackupStatus.BackedUp) }.sortedBy { it.takenAtMillis }
        val view = story("Summer by the sea", items)
        val groups = listOf(
            StoryGroup("g1", "Day at the sea", coverId = items[8].id, memberIds = items.take(7).map { it.id }, text = "Cold water, warm sand."),
            StoryGroup("g2", "Sunset", coverId = items[8].id, memberIds = items.drop(7).map { it.id }, parentId = "g1"),
        )
        StoryView(view.story.copy(groups = groups), view.items)
    }

    /** A first launch: access granted, but no stories yet. */
    val noStories = stories.copy(stories = emptyList(), timeline = stories.timeline.filterIsInstance<TimelineEntry.MomentEntry>())

    /** The Moments tab as a feed: rows split into moments by time, place and source, near-duplicates as one photo. */
    val feed: MomentsFeed = run {
        fun shot(daysAgo: Int, hour: Int, ratio: Float, source: MediaSource = MediaSource.CAMERA, video: Int? = null) =
            photo(daysAgo, hour = hour, ratio = ratio, videoSeconds = video).copy(source = source)
        fun similar(daysAgo: Int, hour: Int, ratio: Float, count: Int) = FeedBlock.Similar(List(count) { shot(daysAgo, hour, ratio) })
        fun single(item: MediaItem) = FeedBlock.Single(item)
        fun section(moments: List<FeedMoment>, vararg places: String) =
            FeedSection(Moment(moments.flatMap { it.items }.sortedByDescending { it.takenAtMillis }, GroupingMode.BY_DAY), moments, places.toList())
        val screen = 9f / 19.5f
        val tripCover = shot(14, 11, 16f / 9f)
        val today = section(
            listOf(
                FeedMoment(MediaSource.CAMERA, listOf(similar(0, 19, 4f / 3f, 4), single(shot(0, 19, 3f / 4f)), single(shot(0, 19, 16f / 9f, video = 42))), "Jaffa Port"),
                FeedMoment(MediaSource.WHATSAPP, listOf(single(shot(0, 14, 3f / 4f, MediaSource.WHATSAPP)), single(shot(0, 16, 1f, MediaSource.WHATSAPP))), null),
                FeedMoment(MediaSource.CAMERA, listOf(single(shot(0, 9, 4f / 3f)), similar(0, 10, 3f / 4f, 3), single(shot(0, 10, 1f))), "Carmel Market"),
            ),
            "Tel Aviv", "Jaffa",
        )
        val yesterday = section(
            listOf(
                FeedMoment(MediaSource.SCREENSHOTS, listOf(single(shot(1, 15, screen, MediaSource.SCREENSHOTS)), single(shot(1, 16, screen, MediaSource.SCREENSHOTS))), null),
                FeedMoment(MediaSource.CAMERA, listOf(single(shot(1, 20, 3f / 4f)), similar(1, 21, 4f / 3f, 5)), "Old North"),
            ),
            "Tel Aviv",
        )
        val tripDay = section(listOf(FeedMoment(MediaSource.CAMERA, listOf(single(tripCover), similar(14, 12, 3f / 4f, 3)), "Bahá’í Gardens")), "Haifa")
        val trip = FeedTrip("trip", "Haifa", LocalDay.fromEpochDay(tripCover.day.toEpochDay() - 2), tripCover.day, tripDay.items)
        MomentsFeed(
            entries = listOf(FeedEntry.Section(today), FeedEntry.Section(yesterday), FeedEntry.Trip(trip), FeedEntry.Section(tripDay)),
            counts = mapOf(MediaSource.CAMERA to 168, MediaSource.WHATSAPP to 19, MediaSource.SCREENSHOTS to 23, MediaSource.OTHER to 4),
            shown = setOf(MediaSource.CAMERA, MediaSource.WHATSAPP, MediaSource.SCREENSHOTS),
        )
    }

    /** The journal with the feed in its Moments tab. */
    val feedStories: StoriesState = stories.copy(feed = feed)
}
