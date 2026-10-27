package com.chencohensolution.journal

import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.isSourceApp
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.library.sourceOfFolder
import com.chencohensolution.journal.places.PhotoPlaces
import com.chencohensolution.journal.places.PlaceCache
import com.chencohensolution.journal.places.PlaceName
import com.chencohensolution.journal.places.PlaceReader
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.places.cellKey
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.SettingsController
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.similarity.PhotoFingerprint
import com.chencohensolution.journal.similarity.PhotoLooks
import com.chencohensolution.journal.stories.FeedBlock
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FilePhotoNoteStore
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.MomentsFeed
import com.chencohensolution.journal.stories.buildFeed
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

private const val MINUTE = 60_000L
private const val HOUR = 60 * MINUTE

private val FEED_DAY = LocalDay(2026, 10, 8)

/** A photo taken [minute] minutes into [day] (UTC, so the test doesn't depend on the time zone). */
private fun pic(
    id: String,
    minute: Int,
    source: MediaSource = MediaSource.CAMERA,
    at: GeoPoint? = null,
    day: LocalDay = FEED_DAY,
    type: MediaType = MediaType.IMAGE,
    burst: String? = null,
    second: Int = 0,
) = MediaItem(
    id, type, day.toEpochDay() * 86_400_000 + minute * MINUTE + second * 1000L, day, null, 4, 3, 100, "$id.jpg",
    burstId = burst, source = source, location = at,
)

/** Newest first, as the library lists them. */
private fun newestFirst(vararg items: MediaItem) = items.sortedByDescending { it.takenAtMillis }

private val TEL_AVIV = GeoPoint(32.0853, 34.7818)
private val JAFFA = GeoPoint(32.0504, 34.7522)
private val HAIFA = GeoPoint(32.7940, 34.9896)

private fun feed(vararg items: MediaItem, places: Places = Places.NONE, looks: PhotoLooks = PhotoLooks.NONE, hidden: Set<MediaSource> = emptySet()) =
    buildFeed(newestFirst(*items), ShownSources(hidden), TimelineGrouping(), places, looks)

private val ALL_SOURCES = ShownSources(hidden = emptySet())

private fun MomentsFeed.sections() = entries.filterIsInstance<FeedEntry.Section>().map { it.section }

/** Each moment's photo ids, newest moment first. */
private fun MomentsFeed.moments() = sections().flatMap { section -> section.moments.map { moment -> moment.items.map { it.id } } }

class FeedTest {
    @Test
    fun foldersTellTheSource() {
        assertEquals(MediaSource.CAMERA, sourceOfFolder("DCIM/Camera/"))
        assertEquals(MediaSource.SCREENSHOTS, sourceOfFolder("DCIM/Screenshots/"))
        assertEquals(MediaSource.SCREENSHOTS, sourceOfFolder("Pictures/Screenshots/"))
        assertEquals(MediaSource.SCREENSHOTS, sourceOfFolder("Movies/Screen recordings/"))
        assertEquals(MediaSource.WHATSAPP, sourceOfFolder("Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"))
        assertEquals(MediaSource.OTHER, sourceOfFolder("Download/"))
        assertEquals(MediaSource.OTHER, sourceOfFolder(null))
    }

    @Test
    fun eachFolderIsASource() {
        // Each folder in DCIM is its own source (asked Oct 24, 2026); files right in DCIM are Camera, as before.
        assertEquals(MediaSource.CAMERA, sourceOfFolder("DCIM/"))
        assertEquals(MediaSource.CAMERA, sourceOfFolder("DCIM/camera/"))
        assertEquals(MediaSource.folder("Flowers"), sourceOfFolder("DCIM/Flowers/"))
        assertEquals("Flowers", sourceOfFolder("DCIM/Flowers/2024/").folder)
        assertEquals(MediaSource.folder("ספירלה יום"), sourceOfFolder("DCIM/ספירלה יום/"))
        // So is a folder an app saves into, wherever it is; the same name is one source.
        assertEquals(MediaSource.folder("Petcube"), sourceOfFolder("Movies/Petcube/"))
        assertEquals(MediaSource.folder("Telegram"), sourceOfFolder("Pictures/Telegram/"))
        assertEquals(sourceOfFolder("Pictures/Telegram/"), sourceOfFolder("Download/Telegram/"))
        assertEquals(MediaSource.folder("Youtube"), sourceOfFolder("Youtube/"))
        // WhatsApp and screenshots stay one source each, in any folder.
        assertEquals(MediaSource.WHATSAPP, sourceOfFolder("Pictures/WhatsApp/"))
        assertEquals(MediaSource.WHATSAPP, sourceOfFolder("Movies/WhatsApp/", appName = "WhatsApp"))
        assertEquals(MediaSource.SCREENSHOTS, sourceOfFolder("DCIM/Screen recordings/"))
    }

    @Test
    fun aFileLooseInASharedFolderIsItsApps() {
        assertEquals(MediaSource.folder("ChatGPT"), sourceOfFolder("Pictures/", appName = "ChatGPT"))
        // The same source as the app's own folder.
        assertEquals(sourceOfFolder("Pictures/ChatGPT/"), sourceOfFolder("Pictures/", appName = "ChatGPT"))
        assertEquals(MediaSource.folder("Chrome"), sourceOfFolder("Download/", appName = "Chrome"))
        assertEquals(MediaSource.folder("Petcube"), sourceOfFolder("Android/media/com.petcube.android/", appName = "Petcube"))
        assertEquals(MediaSource.OTHER, sourceOfFolder("Pictures/", appName = null))
        // The app doesn't change a folder's source.
        assertEquals(MediaSource.folder("X"), sourceOfFolder("Download/X/", appName = "Chrome"))
        assertEquals(MediaSource.CAMERA, sourceOfFolder("DCIM/Camera/", appName = "Camera"))
        // Apps that copy other apps' files (from an old phone, the files app) say nothing.
        assertTrue(isSourceApp("com.petcube.android"))
        assertFalse(isSourceApp("com.sec.android.easyMover"))
        assertFalse(isSourceApp("com.android.externalstorage"))
        assertFalse(isSourceApp(null))
    }

    @Test
    fun sourcesKeepTheirOrderAndIds() {
        val flowers = MediaSource.folder("Flowers")
        val petcube = MediaSource.folder("petcube")
        assertEquals(
            listOf(MediaSource.CAMERA, MediaSource.SCREENSHOTS, flowers, petcube, MediaSource.OTHER),
            MediaSource.choices(setOf(petcube, MediaSource.OTHER), setOf(flowers)),
        )
        assertEquals("folder:Flowers", flowers.id)
        assertEquals(flowers, MediaSource.parse("folder:Flowers"))
        assertEquals(MediaSource.CAMERA, MediaSource.parse("camera"))
        // What Settings and the server kept before folders.
        assertEquals(MediaSource.CAMERA, MediaSource.parse("CAMERA"))
        assertEquals(MediaSource.WHATSAPP, MediaSource.parse("whatsapp"))
        assertEquals(null, MediaSource.parse("dcim"))
        // A folder named like a fixed source is that source.
        assertEquals(MediaSource.CAMERA, MediaSource.folder("Camera"))
        assertEquals(null, MediaSource.CAMERA.folder)
    }

    @Test
    fun cameraMomentsSplitAfterTwoHoursOrAMove() {
        val walk = listOf(
            pic("a", 0, at = TEL_AVIV),
            // 1.2 km on foot, a few minutes later: the same moment.
            pic("b", 10, at = GeoPoint(32.0950, 34.7860)),
            pic("c", 15),
        )
        // Jaffa, 40 minutes later: moved, so a new moment.
        val jaffa = listOf(pic("d", 55, at = JAFFA), pic("e", 60))
        // Back home after more than two hours.
        val evening = listOf(pic("f", 200))
        assertEquals(listOf(listOf("f"), listOf("d", "e"), listOf("a", "b", "c")), feed(*(walk + jaffa + evening).toTypedArray()).moments())
    }

    @Test
    fun rowsReadBackInTimeAllTheWayDown() {
        // Two moments of the day; the near-duplicates stay oldest first inside their set.
        val section = feed(pic("a", 0), pic("b", 30), pic("c", 300), pic("d1", 330, burst = "x"), pic("d2", 330, burst = "x", second = 1)).sections().single()
        assertEquals(listOf("d1", "d2", "c", "b", "a"), section.items.map { it.id })
        assertEquals(listOf(listOf("d1", "d2"), listOf("c")), section.moments.first().shownBlocks.map { block -> block.items.map { it.id } })
    }

    @Test
    fun appPhotosAreOneMomentPerRowAndHiddenSourcesAreLeftOut() {
        val items = arrayOf(
            pic("cam", 0),
            pic("w1", 10, MediaSource.WHATSAPP),
            pic("w2", 400, MediaSource.WHATSAPP),
            pic("s1", 20, MediaSource.SCREENSHOTS),
        )
        assertEquals(listOf(listOf("s1"), listOf("w1", "w2"), listOf("cam")), feed(*items).moments())

        val cameraOnly = feed(*items, hidden = setOf(MediaSource.WHATSAPP, MediaSource.SCREENSHOTS))
        assertEquals(listOf(listOf("cam")), cameraOnly.moments())
        assertEquals(mapOf(MediaSource.CAMERA to 1, MediaSource.WHATSAPP to 2, MediaSource.SCREENSHOTS to 1), cameraOnly.counts)
        assertEquals(setOf(MediaSource.CAMERA, MediaSource.OTHER), cameraOnly.shown)
    }

    @Test
    fun aFolderSeenForTheFirstTimeShows() {
        val petcube = MediaSource.folder("Petcube")
        val items = arrayOf(pic("cam", 0), pic("pet", 60, source = petcube), pic("w", 120, source = MediaSource.WHATSAPP))
        val feed = buildFeed(newestFirst(*items), ShownSources(), TimelineGrouping())
        assertEquals(listOf(listOf("pet"), listOf("cam")), feed.moments())
        assertEquals(listOf(MediaSource.CAMERA, MediaSource.SCREENSHOTS, petcube, MediaSource.WHATSAPP, MediaSource.OTHER), feed.choices)
        assertEquals(setOf(MediaSource.CAMERA, petcube, MediaSource.OTHER), feed.shown)

        val noPets = buildFeed(newestFirst(*items), ShownSources(hidden = ShownSources.DEFAULT_HIDDEN + petcube), TimelineGrouping())
        assertEquals(listOf(listOf("cam")), noPets.moments())
    }

    @Test
    fun theFilterBarShowsWhatShowsTheMostFirstAndCountsTheRest() {
        val petcube = MediaSource.folder("Petcube")
        val gone = MediaSource.folder("Old phone")
        val items = arrayOf(
            pic("cam", 0), pic("pet1", 60, source = petcube), pic("pet2", 61, source = petcube),
            pic("w1", 120, source = MediaSource.WHATSAPP), pic("w2", 121, source = MediaSource.WHATSAPP), pic("w3", 122, source = MediaSource.WHATSAPP),
        )
        val feed = buildFeed(newestFirst(*items), ShownSources(hidden = ShownSources.DEFAULT_HIDDEN + gone), TimelineGrouping())
        // Only what shows and has photos, the most first.
        assertEquals(listOf(petcube, MediaSource.CAMERA), feed.bar)
        // "+1": WhatsApp; Screenshots and the folder with no photos left aren't counted…
        assertEquals(1, feed.hiddenCount)
        // …but the sheet offers them, the most photos first.
        assertEquals(listOf(MediaSource.WHATSAPP, petcube, MediaSource.CAMERA, MediaSource.SCREENSHOTS, gone, MediaSource.OTHER), feed.sheet)
    }

    @Test
    fun nearDuplicatesAreOneBlock() {
        val looks = PhotoLooks(
            mapOf(
                "x1" to PhotoFingerprint(longArrayOf(0L), listOf(byteArrayOf(1, 2, 3))),
                "x2" to PhotoFingerprint(longArrayOf(1L), listOf(byteArrayOf(1, 2, 3))),
                "y" to PhotoFingerprint(longArrayOf(-1L), listOf(byteArrayOf(-3, 2, -1))),
            ),
            0.9f,
        )
        val items = arrayOf(
            pic("b1", 0, burst = "burst"), pic("b2", 1, burst = "burst"),
            // Looked at: alike although minutes apart; not alike although seconds apart.
            pic("x1", 5), pic("x2", 9), pic("y", 10),
            // Not looked at yet: by time.
            pic("t1", 30), pic("t2", 30, second = 5),
            pic("v", 30, type = MediaType.VIDEO, second = 8),
        )
        val blocks = feed(*items, looks = looks).sections().single().moments.single().blocks
        assertEquals(listOf(listOf("b1", "b2"), listOf("x1", "x2"), listOf("y"), listOf("t1", "t2"), listOf("v")), blocks.map { block -> block.items.map { it.id } })
        assertIs<FeedBlock.Single>(blocks[2])

        // A cropped photo leaves its set, and the ones after it don't join it.
        val cropped = buildFeed(newestFirst(*items), ALL_SOURCES, TimelineGrouping(), looks = looks, alone = setOf("b1"))
            .sections().single().moments.single().blocks
        assertEquals(listOf("b1"), cropped.first().items.map { it.id })
        assertEquals(listOf("b2"), cropped[1].items.map { it.id })
    }

    @Test
    fun momentsAreNamedByTheirPlaces() {
        val places = Places(
            emptyMap(),
            mapOf(cellKey(TEL_AVIV) to PlaceName("Old North", "Tel Aviv"), cellKey(JAFFA) to PlaceName("Jaffa Port", "Jaffa")),
        )
        val feed = feed(pic("a", 0, at = TEL_AVIV), pic("b", 5, at = TEL_AVIV), pic("c", 60, at = JAFFA), pic("d", 65), places = places)
        val section = feed.sections().single()
        assertEquals(listOf("Jaffa Port", "Old North"), section.moments.map { it.place })
        assertEquals(listOf("Tel Aviv", "Jaffa"), section.places)
        assertEquals("Haifa", PlaceName("79", "Haifa").label)
    }

    @Test
    fun videosFromOnePlaceAreOneSet() {
        val near = GeoPoint(32.0858, 34.7818) // About 55 m from TEL_AVIV.
        val far = GeoPoint(32.0900, 34.7818) // About 500 m: the same moment, another place.
        val items = arrayOf(
            pic("v1", 0, at = TEL_AVIV, type = MediaType.VIDEO),
            pic("p", 2, at = TEL_AVIV),
            pic("v2", 4, at = near, type = MediaType.VIDEO),
            pic("v3", 6, at = far, type = MediaType.VIDEO),
            pic("v4", 8, type = MediaType.VIDEO),
            pic("v5", 10, at = near, type = MediaType.VIDEO),
        )
        val blocks = feed(*items).sections().single().moments.single().blocks.map { block -> block.items.map { it.id } }
        // Where the first video of the place is, with the photo between after it; no location: alone.
        assertEquals(listOf(listOf("v1", "v2", "v5"), listOf("p"), listOf("v3"), listOf("v4")), blocks)
    }

    @Test
    fun photosMergedByHandAreOneSetWhereTheOldestIs() {
        // Two moments (3 hours apart) and a burst; "a" and "late" merged by hand, "b2" taken out of its burst.
        val items = arrayOf(
            pic("a", 0), pic("b1", 10, burst = "x"), pic("b2", 11, burst = "x"), pic("b3", 12, burst = "x"),
            pic("late", 200), pic("later", 210),
        )
        val feed = buildFeed(
            newestFirst(*items), ALL_SOURCES, TimelineGrouping(),
            alone = setOf("b2"),
            sets = mapOf("a" to "s1", "late" to "s1"),
        )
        val blocks = feed.sections().single().moments.map { moment -> moment.blocks.map { block -> block.items.map { it.id } } }
        // Newest moment first: "late" now shows only in the set, with "a", in the older moment.
        assertEquals(listOf(listOf(listOf("later")), listOf(listOf("a", "late"), listOf("b1"), listOf("b2"), listOf("b3"))), blocks)
    }
}

private class FakePlaceReader(var canRead: Boolean = true) : PlaceReader {
    val read = mutableListOf<String>()
    val named = mutableListOf<GeoPoint>()
    var online = true
    override val canReadLocations get() = canRead

    override suspend fun location(item: MediaItem): GeoPoint? {
        read += item.id
        if (item.id.startsWith("cloud")) error("only in the cloud")
        return if (item.id.startsWith("gps")) TEL_AVIV else null
    }

    override suspend fun name(point: GeoPoint): PlaceName? {
        named += point
        return if (online) PlaceName("Old North", "Tel Aviv") else null
    }
}

private val PLACES_DIR = "/cache/places".toPath()

class PhotoPlacesTest {
    @Test
    fun locationsAndNamesAreReadOnceAndCached() = runTest {
        val fileSystem = FakeFileSystem()
        val reader = FakePlaceReader()
        val places = PhotoPlaces(reader, PlaceCache(fileSystem, PLACES_DIR), StandardTestDispatcher(testScheduler))
        val items = listOf(pic("gps1", 0), pic("gps2", 1), pic("none", 2), pic("cloud", 3), pic("video", 4, type = MediaType.VIDEO))
        places.request(items)
        advanceUntilIdle()
        // Videos too: without a place a video joins the moment before it, even after a drive.
        assertEquals(listOf("gps1", "gps2", "none", "cloud", "video"), reader.read)
        assertEquals(TEL_AVIV, places.places.value.location(items[0]))
        // Both photos are in one square: named once.
        assertEquals(1, reader.named.size)
        assertEquals("Old North", places.places.value.name(TEL_AVIV)?.label)

        val again = FakePlaceReader()
        val reloaded = PhotoPlaces(again, PlaceCache(fileSystem, PLACES_DIR), StandardTestDispatcher(testScheduler))
        reloaded.request(items)
        advanceUntilIdle()
        // Only the photo that couldn't be read is tried again.
        assertEquals(listOf("cloud"), again.read)
        assertTrue(again.named.isEmpty())
        assertEquals("Tel Aviv", reloaded.places.value.name(TEL_AVIV)?.town)
    }

    @Test
    fun withoutPermissionNothingIsMarkedAsHavingNoLocation() = runTest {
        val fileSystem = FakeFileSystem()
        val reader = FakePlaceReader(canRead = false)
        reader.online = false
        val places = PhotoPlaces(reader, PlaceCache(fileSystem, PLACES_DIR), StandardTestDispatcher(testScheduler))
        // iOS gives the location with the library; it's named, though offline it isn't saved.
        places.request(listOf(pic("gps1", 0), pic("ios", 1, at = JAFFA)))
        advanceUntilIdle()
        assertTrue(reader.read.isEmpty())
        assertEquals(1, reader.named.size)

        val granted = FakePlaceReader()
        val later = PhotoPlaces(granted, PlaceCache(fileSystem, PLACES_DIR), StandardTestDispatcher(testScheduler))
        later.request(listOf(pic("gps1", 0), pic("ios", 1, at = JAFFA)))
        advanceUntilIdle()
        assertEquals(listOf("gps1"), granted.read)
        assertEquals("Old North", later.places.value.name(JAFFA)?.label)
    }
}

private class FeedTestLibrary(val items: List<MediaItem>) : MediaLibrary {
    override fun access() = PhotoAccess.FULL
    override suspend fun loadItems() = items
}

private class FeedTestStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class FeedControllerTest {
    @Test
    fun setsAndSourcesAreKeptInNotes() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val items = listOf(pic("a", 0, burst = "x"), pic("b", 1, burst = "x"), pic("c", 30), pic("w", 40, MediaSource.WHATSAPP), pic("s", 50, MediaSource.SCREENSHOTS))
        val library = LibraryController(FeedTestLibrary(items), JournalSettings(FeedTestStore()), dispatcher)
        library.refresh()
        val settings = SettingsController(JournalSettings(FeedTestStore()), dispatcher)
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
        // Camera and Other are shown at first; WhatsApp and screenshots aren't, and the row says so.
        val section = stories.currentState.feed.sections().single()
        assertEquals(setOf("a", "b", "c"), section.items.map { it.id }.toSet())
        assertEquals(mapOf(MediaSource.WHATSAPP to 1, MediaSource.SCREENSHOTS to 1), section.hidden)

        settings.toggleFeedSource(MediaSource.WHATSAPP)
        settings.toggleFeedSource(MediaSource.SCREENSHOTS)
        advanceUntilIdle()
        assertEquals(5, stories.currentState.feed.sections().single().items.size)

        // The photo a set was turned to is kept in its note file, so it syncs; turning back to its first forgets it.
        stories.turnSet(listOf("a", "b"), "b")
        assertEquals(true, stories.currentState.note("b")?.front)
        assertEquals(true, FilePhotoNoteStore(fileSystem, "/data/journal".toPath()).loadAll().single { it.deviceId == "b" }.front)
        stories.turnSet(listOf("a", "b"), "a")
        assertEquals(null, stories.currentState.note("b"))
        assertEquals(null, stories.currentState.note("a"))
        // A set scrolling into view reports the page it already shows: no new state, which would redraw the screen.
        val before = stories.currentState
        stories.turnSet(listOf("a", "b"), "a")
        assertSame(before, stories.currentState)

        // Merged by hand, then one taken out: kept in the note files, so they survive a restart.
        stories.mergeInFeed(listOf("c", "w"))
        advanceUntilIdle()
        assertTrue(stories.currentState.feed.sections().single().moments.any { moment -> moment.blocks.any { block -> block.items.map { it.id } == listOf("c", "w") } })
        stories.takeOutOfSet("b")
        advanceUntilIdle()
        val blocks = stories.currentState.feed.sections().single().moments.flatMap { it.blocks }.map { block -> block.items.map { it.id } }
        assertTrue(listOf("a") in blocks && listOf("b") in blocks, "$blocks")

        assertEquals(5, stories.currentState.feed.sections().single().items.size)
    }
}
