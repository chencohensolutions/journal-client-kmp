package com.chencohensolution.journal.lab

import com.chencohensolution.journal.backup.BackupActivity
import com.chencohensolution.journal.backup.BackupProgress
import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.places.PlaceName
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.search.SearchChip
import com.chencohensolution.journal.search.SearchState
import com.chencohensolution.journal.search.SearchStatus
import com.chencohensolution.journal.server.HiddenCollections
import com.chencohensolution.journal.server.HiddenCollectionsState
import com.chencohensolution.journal.server.StorageUsage
import com.chencohensolution.journal.server.SyncProgress
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.storage.FreeUpGroup
import com.chencohensolution.journal.storage.FreeUpPlan
import com.chencohensolution.journal.storage.FreeUpState
import com.chencohensolution.journal.stories.CropRect
import com.chencohensolution.journal.stories.FeedBlock
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FeedMoment
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.stories.Moment
import com.chencohensolution.journal.stories.MomentsFeed
import com.chencohensolution.journal.stories.PhotoCrops
import com.chencohensolution.journal.stories.PhotoNote
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.buildFeed
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.dateByAddingTimeInterval
import platform.Foundation.timeIntervalSince1970

/**
 * The iOS UI lab's made-up library, Moments feed and storyline (iosApp/iosApp/Lab): the same data as Android's
 * PreviewData, built here because Kotlin's default arguments and `copy` aren't available in Swift. Dates are relative
 * to now, so "Today" and "Yesterday" read right whenever the lab opens. Only the lab reads it.
 */
object LabData {
    private var nextId = 1

    /** A photo [daysAgo] days back; [ratio] is width / height. */
    private fun photo(
        daysAgo: Int,
        hour: Int = 12,
        ratio: Float = 4f / 3f,
        backup: BackupStatus = BackupStatus.NotBackedUp,
        videoSeconds: Int? = null,
    ): MediaItem {
        val calendar = NSCalendar.currentCalendar
        val then = NSDate().dateByAddingTimeInterval(-daysAgo * 86_400.0)
        val taken = calendar.dateBySettingHour(hour.toLong(), minute = (nextId % 60).toLong(), second = 0, ofDate = then, options = 0u) ?: then
        val parts = calendar.components(NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay, taken)
        val id = (nextId++).toString()
        return MediaItem(
            id = id,
            type = if (videoSeconds != null) MediaType.VIDEO else MediaType.IMAGE,
            takenAtMillis = (taken.timeIntervalSince1970 * 1000).toLong(),
            day = LocalDay(parts.year.toInt(), parts.month.toInt(), parts.day.toInt()),
            durationMillis = videoSeconds?.let { it * 1000L },
            width = 4000,
            height = (4000 / ratio).toInt(),
            sizeBytes = 3_200_000,
            fileName = "IMG_$id.heic",
            backup = backup,
        )
    }

    /** [count] photos on one day, newest first, as the library lists them. */
    private fun day(daysAgo: Int, count: Int, item: (Int) -> MediaItem = { photo(daysAgo, hour = 20 - it) }): List<MediaItem> =
        List(count) { item(it) }

    private val ratios = listOf(4f / 3f, 3f / 4f, 16f / 9f, 1f)

    private fun Iterable<MediaItem>.asDays() =
        sortedByDescending { it.takenAtMillis }.groupBy { it.day }.map { (day, items) -> DayGroup(day, items) }

    /** Older days, as the library lists them. */
    private val older = (2..23).filter { it % 3 != 0 }.map { d -> day(d, 4) { photo(d, hour = 9 + it * 3, ratio = ratios[it], backup = BackupStatus.BackedUp) } }

    private val today = day(0, 7) {
        when (it) {
            0 -> photo(0, hour = 21, backup = BackupStatus.Uploading(0.43f))
            1, 2 -> photo(0, hour = 20 - it, backup = BackupStatus.Queued, videoSeconds = if (it == 2) 42 else null)
            else -> photo(0, hour = 18 - it)
        }
    }
    private val yesterday = day(1, 5) {
        photo(1, hour = 22 - it, backup = when (it) { 0, 1 -> BackupStatus.BackedUp; 2 -> BackupStatus.Failed("Server error"); else -> BackupStatus.NotBackedUp })
    }
    private val earlier = day(11, 6)

    val library = LibraryState(access = PhotoAccess.FULL, days = (listOf(today, yesterday, earlier) + older).flatten().asDays())

    /** Nothing written yet: the storyline is empty. */
    val stories = StoriesState(itemsById = library.days.flatMap { it.items }.associateBy { it.id })

    private var tripCoverItem: MediaItem? = null

    /** The Moments tab as a feed: rows split into moments by time, place and source, near-duplicates as one photo. */
    private val feed: MomentsFeed = run {
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

    private val todaySection = (feed.entries[0] as FeedEntry.Section).section
    private val yesterdaySection = (feed.entries[1] as FeedEntry.Section).section

    /** The near-duplicate set at the top of Today, with a note on its first photo. */
    val notedPhoto: MediaItem = todaySection.moments[0].blocks[0].items.first()

    /** A 4:3 photo of Carmel Market, cropped wide for Moments, with a short note. */
    val croppedPhoto: MediaItem = todaySection.moments[2].blocks[0].items.first()

    /** A 16:9 photo of a day in Haifa, cropped to the screen's shape for the viewer held upright. */
    val tripPhoto: MediaItem = tripCoverItem!!

    /** An iPhone's screen held upright (width / height). */
    const val SCREEN_RATIO = 9f / 19.5f

    val marketCrops = PhotoCrops(
        moments = CropRect(0f, 0.18f, 1f, 0.62f),
        portrait = CropRect(0.4f, 0f, SCREEN_RATIO / (4f / 3f), 1f, SCREEN_RATIO),
        landscape = CropRect(0f, 0.2f, 1f, (4f / 3f) * SCREEN_RATIO, 1 / SCREEN_RATIO),
    )

    private fun note(item: MediaItem, text: String, crops: PhotoCrops = PhotoCrops(), title: String = "", tags: List<String> = emptyList()) =
        item.id to PhotoNote(item.id, item.fileName, item.takenAtMillis, caption = text, updatedAt = "2026-10-19T10:00:00Z", crops = crops, title = title, tags = tags)

    private val feedNotes = mapOf(
        note(
            notedPhoto,
            "The fishermen came in just as the sun dropped behind the old port. Noa wanted to stay until the last boat " +
                "was tied, so we sat on the wall with the ice cream melting and watched the gulls fight over what was left. " +
                "One of those evenings that didn't need a plan.",
            title = "Last boats at Jaffa",
        ),
        note(croppedPhoto, "Breakfast at the market before everyone woke up.", marketCrops, title = "Carmel Market"),
        note(
            tripPhoto,
            "",
            PhotoCrops(portrait = CropRect(0.52f, 0f, SCREEN_RATIO / (16f / 9f), 1f, SCREEN_RATIO)),
            title = "The gardens from the top",
            tags = listOf("Haifa", "Family trip"),
        ),
    )

    /** The journal with the feed in its Moments tab, some photos written about: they make the storyline. */
    val feedStories: StoriesState = stories.copy(
        feed = feed,
        notes = feedNotes,
        storyline = listOf(notedPhoto, croppedPhoto, tripPhoto).sortedByDescending { it.takenAtMillis },
        itemsById = stories.itemsById + feed.entries.flatMap { (it as FeedEntry.Section).section.items }.associateBy { it.id },
    )

    private fun FeedSection.cameraOnly() = copy(moments = moments.filter { it.source == MediaSource.CAMERA })

    /** Moments with WhatsApp and Screenshots off (as at first): the rows say what they leave out ("+6 WhatsApp"). */
    val hiddenStories: StoriesState = feedStories.copy(
        feed = feed.copy(
            entries = listOf(
                FeedEntry.Section(todaySection.cameraOnly().copy(hidden = mapOf(MediaSource.WHATSAPP to 6, MediaSource.SCREENSHOTS to 3))),
                FeedEntry.Section(yesterdaySection.cameraOnly().copy(hidden = mapOf(MediaSource.SCREENSHOTS to 2))),
            ) + feed.entries.drop(2),
            counts = mapOf(MediaSource.CAMERA to 48_210, MediaSource.WHATSAPP to 18_458, MediaSource.SCREENSHOTS to 2_388, MediaSource.OTHER to 3_104),
            shown = ShownSources().shownOf(MediaSource.FIXED),
        ),
        shownSources = ShownSources(),
    )

    /**
     * Photos per source as on the owner's phone once each folder and app became a source (Oct 24, 2026): a few big
     * ones and many small ones, for the Moments filter bar and Settings.
     */
    val folderCounts: Map<MediaSource, Int> = mapOf(
        MediaSource.WHATSAPP to 10_282, MediaSource.CAMERA to 1_240, MediaSource.SCREENSHOTS to 306,
        MediaSource.folder("Workers") to 23, MediaSource.folder("Chrome") to 22, MediaSource.OTHER to 17,
        MediaSource.folder("Google") to 6, MediaSource.folder("Petcube") to 5, MediaSource.folder("X") to 4,
        MediaSource.folder("Bank Hapoalim") to 3, MediaSource.folder("Youtube") to 3, MediaSource.folder("Kodi") to 2,
        MediaSource.folder("Wikipedia") to 2, MediaSource.folder("Battle.net") to 2, MediaSource.folder("Telegram") to 2,
        MediaSource.folder("ChatGPT") to 2, MediaSource.folder("AdobeLightroom") to 1, MediaSource.folder("Studio") to 1,
    )

    /** The Moments feed with [folderCounts]: everything shown but WhatsApp and Screenshots, as at first. */
    val folderStories: StoriesState = hiddenStories.copy(
        feed = hiddenStories.feed.copy(
            counts = folderCounts,
            shown = ShownSources().shownOf(folderCounts.keys),
            sheet = ShownSources().choices(folderCounts),
        ),
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
    val exploreStories: StoriesState = stories.copy(shownSources = ShownSources(), feed = MomentsFeed())

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

    /** The viewer's photo with its details filled in, as the info panel shows them: located, kept here and on the server. */
    val infoPhoto: MediaItem = tripPhoto.copy(
        fileName = "PXL_20261012_093512417.jpg",
        sizeBytes = 4_812_377,
        width = 4080,
        height = 3072,
        mimeType = "image/jpeg",
        sha256 = "9f2c41d0b7e5a3c6f18e2d4b7a90c3e5f61d28b4a7c9e0f3d5b6a1c8e2f4d7b9",
        backup = BackupStatus.BackedUp,
        location = GeoPoint(32.81431, 34.98712),
    )

    /** [feedStories] with [infoPhoto] in place of the trip photo, and its place named. */
    val infoStories: StoriesState = feedStories.copy(
        itemsById = feedStories.itemsById + (infoPhoto.id to infoPhoto),
        places = Places.named(mapOf(infoPhoto.location!! to PlaceName(area = "Bahá'í Gardens", city = "Haifa", country = "Israel"))),
    )

    // Settings and Free up space: the choices as at first, backup on and running, the counts of a large library.

    val settings = SettingsState()

    /** Backup on for the camera and WhatsApp, 41 of 120 photos sent. */
    val backup = BackupState(
        settings = BackupSettings(enabled = true, sources = setOf(MediaSource.CAMERA, MediaSource.WHATSAPP)),
        activity = BackupActivity.Running(
            BackupProgress(done = 41, total = 120, bytesDone = 182_000_000, bytesTotal = 512_000_000, bytesPerSecond = 5_100_000),
        ),
    )

    /** Photos per source, shown or not, for Show in the app. */
    val sourceCounts: Map<MediaSource, Int> get() = folderCounts
    val notBackedUp = 79
    val previewBytes = 1_840_000_000L
    val serverUsage = StorageUsage(photos = 48_210, videos = 1_204, bytes = 214_300_000_000)

    /** The library reading the server's list again after a change in Hidden collections. */
    val syncProgress = SyncProgress(done = 38_000, total = 95_412)

    /** Free up space, counted: the backed-up photos of [library], as 24 MB files. */
    val freeUp = FreeUpState(
        plan = FreeUpPlan(
            library.days.flatMap { it.items }.filter { it.backup == BackupStatus.BackedUp }.map { FreeUpGroup(listOf(it.copy(sizeBytes = 24_000_000))) },
        ),
    )

    /** Hidden collections: WhatsApp shown, the dating apps hidden, the rest shown, most files first. */
    val hiddenCollections: HiddenCollectionsState = run {
        val apps = listOf(
            HiddenCollections.Collection("Bumble", 1_458, hidden = true),
            HiddenCollections.Collection("Microsoft Remote Desktop", 842, hidden = false),
            HiddenCollections.Collection("OkCupid", 476, hidden = true),
            HiddenCollections.Collection("Chrome", 311, hidden = false),
            HiddenCollections.Collection("Tinder", 108, hidden = true),
            HiddenCollections.Collection("Maps", 64, hidden = false),
        )
        HiddenCollectionsState.Ready(
            collections = HiddenCollections(
                apps = listOf(HiddenCollections.Collection("WhatsApp", 9_870, hidden = false)),
                screenshotsWithoutApp = HiddenCollections.Collection("", 1_204, hidden = false),
                screenshotApps = apps,
                nudity = HiddenCollections.Nudity(hidden = true, fromPercent = 50, files = 312, notScanned = 1_840),
                recordings = HiddenCollections.Collection("recordings", 1_204, hidden = true),
                calls = HiddenCollections.Collection("calls", 6_196, hidden = true),
                voiceMessages = HiddenCollections.Collection("voiceMessages", 14, hidden = true),
            ),
            query = "",
            shownApps = apps,
            saving = false,
            notChanged = false,
        )
    }

    /** What a search for "a dog on the beach at sunset" found: photos of the places library, best first, the server still reading. */
    /**
     * Seven years of photos for going to a date in Moments (FeedTimeline): quiet weeks, busy days and a few
     * trips, the same on every run. Built into a real feed, by day.
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
        stories.copy(
            feed = buildFeed(yearsOfItems, ShownSources(hidden = emptySet()), TimelineGrouping()),
            itemsById = yearsOfItems.associateBy { it.id },
        )
    }

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
}
