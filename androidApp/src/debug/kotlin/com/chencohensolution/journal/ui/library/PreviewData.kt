package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.search.SearchChip
import com.chencohensolution.journal.search.SearchState
import com.chencohensolution.journal.search.SearchStatus
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.places.PlaceName
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.stories.FeedBlock
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FeedMoment
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.stories.Moment
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.CropRect
import com.chencohensolution.journal.stories.MomentsFeed
import com.chencohensolution.journal.stories.PhotoCrops
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.stories.RevealedPhotos
import java.time.Instant
import java.time.ZoneId

/**
 * A made-up library, Moments feed and storyline for the screenshot tests. Dates are relative to now, so "Today" and "Yesterday" read
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

    private val ratios = listOf(4f / 3f, 3f / 4f, 16f / 9f, 1f)

    /** Older days, as the library lists them. */
    private val older = (2..23).filter { it % 3 != 0 }.map { d -> day(d, 4) { photo(d, hour = 9 + it * 3, ratio = ratios[it], backup = BackupStatus.BackedUp) } }

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

    private val recent = listOf(today, yesterday, earlier)

    val library = LibraryState(
        access = PhotoAccess.FULL,
        days = (recent + older)
            .flatten()
            .sortedByDescending { it.takenAtMillis }
            .groupBy { it.day }
            .map { (day, items) -> DayGroup(day, items) },
    )

    /** Nothing written yet: the storyline is empty. */
    val stories = StoriesState(itemsById = library.days.flatMap { it.items }.associateBy { it.id })

    private var tripCoverItem: MediaItem? = null

    /** The Moments tab as a feed: rows split into moments by time, place and source, near-duplicates as one photo. */
    val feed: MomentsFeed = run {
        fun shot(daysAgo: Int, hour: Int, ratio: Float, source: MediaSource = MediaSource.CAMERA, video: Int? = null) =
            photo(daysAgo, hour = hour, ratio = ratio, videoSeconds = video).copy(source = source)
        fun similar(daysAgo: Int, hour: Int, ratio: Float, count: Int) = FeedBlock.Similar(List(count) { shot(daysAgo, hour, ratio) })
        fun single(item: MediaItem) = FeedBlock.Single(item)
        fun section(moments: List<FeedMoment>, vararg places: String) =
            FeedSection(Moment(moments.flatMap { it.items }.sortedByDescending { it.takenAtMillis }, GroupingMode.BY_DAY), moments, places.toList())
        val screen = 9f / 19.5f
        val tripCover = shot(14, 11, 16f / 9f).also { tripCoverItem = it }
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
        MomentsFeed(
            entries = listOf(FeedEntry.Section(today), FeedEntry.Section(yesterday), FeedEntry.Section(tripDay)),
            counts = mapOf(MediaSource.CAMERA to 168, MediaSource.WHATSAPP to 19, MediaSource.SCREENSHOTS to 23, MediaSource.OTHER to 4),
            shown = setOf(MediaSource.CAMERA, MediaSource.WHATSAPP, MediaSource.SCREENSHOTS),
        )
    }

    private val todayMoments = (feed.entries.first() as FeedEntry.Section).section.moments

    /** The near-duplicate set at the top of Today, with a note on its first photo. */
    val notedPhoto: MediaItem = todayMoments[0].blocks[0].items.first()

    /** A 4:3 photo of Carmel Market, cropped wide for Moments, with a short note. */
    val croppedPhoto: MediaItem = todayMoments[2].blocks[0].items.first()

    /** A 16:9 photo of a day in Haifa, cropped to the screen's shape for the viewer held upright. */
    val tripPhoto: MediaItem = tripCoverItem!!

    /** This phone's screen held upright (width / height), as in [PHONE]. */
    const val SCREEN_RATIO = 390f / 844f

    val marketCrops = PhotoCrops(
        moments = CropRect(0f, 0.18f, 1f, 0.62f),
        portrait = CropRect(0.4f, 0f, SCREEN_RATIO / (4f / 3f), 1f, SCREEN_RATIO),
        landscape = CropRect(0f, 0.2f, 1f, (4f / 3f) * SCREEN_RATIO, 1 / SCREEN_RATIO),
    )

    private fun note(item: MediaItem, text: String, crops: PhotoCrops = PhotoCrops(), title: String = "") =
        item.id to PhotoNote(item.id, item.fileName, item.takenAtMillis, caption = text, updatedAt = "2026-10-19T10:00:00Z", crops = crops, title = title)

    private val feedNotes = mapOf(
        note(
            notedPhoto,
            "The fishermen came in just as the sun dropped behind the old port. Noa wanted to stay until the last boat " +
                "was tied, so we sat on the wall with the ice cream melting and watched the gulls fight over what was left. " +
                "One of those evenings that didn't need a plan.",
            title = "Last boats at Jaffa",
        ),
        note(croppedPhoto, "Breakfast at the market before everyone woke up.", marketCrops, title = "Carmel Market"),
        note(tripPhoto, "", PhotoCrops(portrait = CropRect(0.52f, 0f, SCREEN_RATIO / (16f / 9f), 1f, SCREEN_RATIO)), title = "The gardens from the top"),
    )

    /** The journal with the feed in its Moments tab, some photos written about: they make the storyline. */
    val feedStories: StoriesState = stories.copy(
        feed = feed,
        notes = feedNotes,
        storyline = listOf(notedPhoto, croppedPhoto, tripPhoto).sortedByDescending { it.takenAtMillis },
        itemsById = stories.itemsById + feed.entries.flatMap { (it as FeedEntry.Section).section.items }.associateBy { it.id },
    )

    /**
     * Seven years of photos for going to a date in Moments (FeedTimeline): quiet weeks, busy days and a few trips, the
     * same on every run. The same as the iOS lab's LabData.timelineStories, built through [StoriesState.feedOf].
     */
    private val yearsOfItems: List<MediaItem> by lazy {
        var seed = 0x9E3779B97F4A7C15uL
        fun next(): Int {
            seed = seed xor (seed shl 13); seed = seed xor (seed shr 7); seed = seed xor (seed shl 17)
            return (seed % 1000uL).toInt()
        }
        var trip = 0
        (0 until 7 * 365).flatMap { daysAgo ->
            if (trip == 0 && next() < 8) trip = 4 + next() % 9
            val weekend = next() < 285
            val chance = if (trip > 0) 950 else if (weekend) 520 else 230
            val count = if (next() < chance) (if (trip > 0) 4 + next() % 9 else 1 + next() % (if (weekend) 6 else 3)) else 0
            if (trip > 0) trip--
            List(count) { photo(daysAgo, hour = 20 - it % 12, ratio = ratios[(daysAgo + it) % 4]) }
        }
    }

    val timelineLibrary: LibraryState by lazy { LibraryState(access = PhotoAccess.FULL, days = yearsOfItems.asDays()) }

    val timelineStories: StoriesState by lazy {
        stories.copy(feed = stories.feedOf(yearsOfItems), itemsById = yearsOfItems.associateBy { it.id })
    }

    // Hiding sources (Settings › Show in the app): WhatsApp and screenshots off, as at first.

    private fun Iterable<MediaItem>.asDays() =
        sortedByDescending { it.takenAtMillis }.groupBy { it.day }.map { (day, items) -> DayGroup(day, items) }

    private val todaySection = (feed.entries[0] as FeedEntry.Section).section
    private val yesterdaySection = (feed.entries[1] as FeedEntry.Section).section

    /** Today's six WhatsApp photos the setting hides, newest first. */
    private val hiddenWhatsApp = List(6) { photo(0, hour = 17 - it % 4, ratio = ratios[it % 4]).copy(source = MediaSource.WHATSAPP) }
        .sortedByDescending { it.takenAtMillis }

    private fun FeedSection.cameraOnly() = copy(moments = moments.filter { it.source == MediaSource.CAMERA })

    /** Moments with WhatsApp and Screenshots off: the rows say what they leave out ("+6 WhatsApp"). */
    val hiddenFeed: MomentsFeed = feed.copy(
        entries = listOf(
            FeedEntry.Section(todaySection.cameraOnly().copy(hidden = mapOf(MediaSource.WHATSAPP to 6, MediaSource.SCREENSHOTS to 3))),
            FeedEntry.Section(yesterdaySection.cameraOnly().copy(hidden = mapOf(MediaSource.SCREENSHOTS to 2))),
        ) + feed.entries.drop(2),
        counts = mapOf(MediaSource.CAMERA to 48_210, MediaSource.WHATSAPP to 18_458, MediaSource.SCREENSHOTS to 2_388, MediaSource.OTHER to 3_104),
        shown = ShownSources.DEFAULT_SOURCES,
    )

    /** Today's "+6 WhatsApp" tapped: the six in a band under the row, "Hide WhatsApp" in its place. */
    val revealedFeed: MomentsFeed = hiddenFeed.copy(
        entries = listOf(
            FeedEntry.Section(
                todaySection.cameraOnly().copy(
                    hidden = mapOf(MediaSource.SCREENSHOTS to 3),
                    revealed = listOf(RevealedPhotos(MediaSource.WHATSAPP, hiddenWhatsApp)),
                ),
            ),
        ) + hiddenFeed.entries.drop(1),
    )

    val todayKey: String = todaySection.key

    val hiddenStories: StoriesState = feedStories.copy(feed = hiddenFeed, shownSources = ShownSources())
    val revealedStories: StoriesState = feedStories.copy(
        feed = revealedFeed,
        shownSources = ShownSources(),
        itemsById = feedStories.itemsById + hiddenWhatsApp.associateBy { it.id },
    )

    /** Photos over several years; 2016 has only WhatsApp photos. */
    private fun year(daysAgo: Int, count: Int, source: MediaSource) =
        List(count) { photo(daysAgo + it % 40, hour = 9 + it % 10, ratio = ratios[it % 4]).copy(source = source) }

    val exploreLibrary = LibraryState(
        access = PhotoAccess.FULL,
        days = (
            year(0, 30, MediaSource.CAMERA) + year(5, 24, MediaSource.WHATSAPP) +
                year(380, 20, MediaSource.CAMERA) + year(390, 51, MediaSource.WHATSAPP) + year(400, 3, MediaSource.SCREENSHOTS) +
                year(800, 12, MediaSource.CAMERA) +
                year(3_700, 41, MediaSource.WHATSAPP)
            ).asDays(),
    )
    val exploreStories: StoriesState = stories.copy(shownSources = ShownSources(), feed = MomentsFeed(shown = ShownSources.DEFAULT_SOURCES))

    // Explore › Places: home in Tel Aviv, more towns in Israel, and trips to other countries.

    private class Town(val name: String, val country: String?, val at: GeoPoint, val count: Int, val daysAgo: Int)

    private val towns = listOf(
        Town("Tel Aviv", "Israel", GeoPoint(32.08, 34.78), 40, 0),
        Town("Jerusalem", "Israel", GeoPoint(31.77, 35.21), 14, 60),
        Town("Haifa", "Israel", GeoPoint(32.79, 34.99), 9, 200),
        Town("Eilat", "Israel", GeoPoint(29.56, 34.95), 6, 500),
        Town("Tiberias", "Israel", GeoPoint(32.79, 35.53), 3, 900),
        Town("Paris", "France", GeoPoint(48.86, 2.35), 18, 300),
        Town("Lyon", "France", GeoPoint(45.76, 4.84), 7, 305),
        Town("Nice", "France", GeoPoint(43.70, 7.27), 5, 1_100),
        Town("Annecy", "France", GeoPoint(45.90, 6.13), 4, 1_104),
        Town("Rome", "Italy", GeoPoint(41.90, 12.50), 12, 700),
        Town("Florence", "Italy", GeoPoint(43.77, 11.26), 6, 703),
        Town("Athens", "Greece", GeoPoint(37.98, 23.73), 8, 1_400),
        Town("Lisbon", "Portugal", GeoPoint(38.72, -9.14), 5, 1_800),
        Town("Tokyo", "Japan", GeoPoint(35.68, 139.69), 4, 2_200),
        Town("Somewhere", null, GeoPoint(10.0, 10.0), 2, 2_500),
    )

    val placesLibrary = LibraryState(
        access = PhotoAccess.FULL,
        days = towns.flatMap { town ->
            List(town.count) { photo(town.daysAgo + it % 5, hour = 9 + it % 10, ratio = ratios[it % 4]).copy(location = town.at) }
        }.asDays(),
    )
    val placesStories: StoriesState = exploreStories.copy(
        places = Places.named(towns.associate { it.at to PlaceName(null, it.name, it.country) }),
    )

    /** What a search for "a dog on the beach at sunset" found: photos of the places library, best first, the server still reading. */
    val searchFound: SearchState = run {
        // Over every town and year of the places library, so the year and place chips have something to choose.
        val items = placesLibrary.days.flatMap { it.items }.filterIndexed { i, _ -> i % 4 == 1 }
        fun town(item: MediaItem) = placesStories.places.location(item)?.let(placesStories.places::name)?.town
        SearchState(
            query = "a dog on the beach at sunset",
            status = SearchStatus.DONE,
            items = items,
            // The first three as one set you swipe, the rest one each.
            sets = listOf(items.take(3)) + items.drop(3).map(::listOf),
            indexed = 12_480,
            total = 14_200,
            yearChips = items.groupingBy { it.day.year }.eachCount().entries.sortedByDescending { it.key }.map { SearchChip(it.key.toString(), it.value) },
            townChips = items.mapNotNull(::town).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { SearchChip(it.key, it.value) },
        )
    }

    /** [searchFound] narrowed to 2025 (the chip chosen). */
    val searchIn2025: SearchState = searchFound.items.filter { it.day.year == 2025 }.let { items -> searchFound.copy(year = 2025, items = items, sets = items.map(::listOf)) }
}
