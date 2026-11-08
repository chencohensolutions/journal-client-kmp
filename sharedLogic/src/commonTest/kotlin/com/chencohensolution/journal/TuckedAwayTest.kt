package com.chencohensolution.journal

import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.mergeWithServer
import com.chencohensolution.journal.local.FileWalk
import com.chencohensolution.journal.local.shownFiles
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.server.FeedPage
import com.chencohensolution.journal.server.FileFacts
import com.chencohensolution.journal.server.ServerMedia
import com.chencohensolution.journal.server.ServerMediaStore
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.similarity.PhotoLooks
import com.chencohensolution.journal.stories.FeedContext
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FeedPager
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.stories.FeedTable
import com.chencohensolution.journal.stories.TuckedReveal
import com.chencohensolution.journal.stories.TuckedSession
import com.chencohensolution.journal.stories.allTucked
import com.chencohensolution.journal.stories.buildFeed
import com.chencohensolution.journal.stories.storylineDays
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private const val HOUR = 3_600_000L
private val DAY_1 = LocalDay(2026, 11, 1)
private val DAY_2 = LocalDay(2026, 11, 2)
private val DAY_3 = LocalDay(2026, 11, 3)

private fun photo(id: String, day: LocalDay, hour: Int, tucked: Boolean = false, source: MediaSource = MediaSource.CAMERA) = MediaItem(
    id, MediaType.IMAGE, day.toEpochDay() * 86_400_000L + hour * HOUR, day, null, 10, 10, 100, "$id.jpg",
    source = source, isTucked = tucked,
)

private fun sections(feed: com.chencohensolution.journal.stories.MomentsFeed) = feed.entries.map { (it as FeedEntry.Section).section }

/** Tucked away (Nov 8, 2026): left out of Moments with a count per day, revealed for the session, never searched. */
class TuckedAwayTest {
    private val byDay = TimelineGrouping(GroupingMode.BY_DAY)

    // Day 3: two shown, one tucked. Day 2: only tucked. Day 1: nothing tucked.
    private val library = listOf(
        photo("c", DAY_3, 18), photo("t3", DAY_3, 15, tucked = true), photo("a", DAY_3, 10),
        photo("t2a", DAY_2, 12, tucked = true), photo("t2b", DAY_2, 11, tucked = true),
        photo("x", DAY_1, 9),
    )

    @Test
    fun tuckedPhotosLeaveMomentsAndEachDaySaysHowMany() {
        val rows = sections(buildFeed(library, ShownSources(), byDay))
        assertEquals(listOf(DAY_3, DAY_2, DAY_1), rows.map { it.span.items.first().day })
        assertEquals(listOf(listOf("c", "a"), emptyList(), listOf("x")), rows.map { row -> row.items.map { it.id } })
        assertEquals(listOf(1, 2, 0), rows.map { it.tucked })
        assertTrue(rows.none { it.tuckedShown })
    }

    @Test
    fun revealingADayShowsItsTuckedPhotosInPlaceAndKeepsTheRows() {
        val hidden = sections(buildFeed(library, ShownSources(), byDay))
        val shown = sections(buildFeed(library, ShownSources(), byDay, tuckedShown = setOf(DAY_3)))
        assertEquals(hidden.map { it.key }, shown.map { it.key }, "revealing never moves rows")
        assertEquals(listOf("c", "t3", "a"), shown[0].items.map { it.id })
        assertTrue(shown[0].tuckedShown)
        assertEquals(1, shown[0].tucked)
        assertFalse(shown[1].tuckedShown, "another day stays hidden")
        assertEquals(emptyList(), shown[1].items)
    }

    @Test
    fun aTuckedPhotoOfAHiddenSourceIsNeitherOnItsChipNorRevealed() {
        val items = library + photo("w", DAY_3, 16, tucked = true, source = MediaSource.WHATSAPP) + photo("w2", DAY_3, 17, source = MediaSource.WHATSAPP)
        val shownSources = ShownSources(hidden = setOf(MediaSource.WHATSAPP))
        val row = sections(buildFeed(items, shownSources, byDay, tuckedShown = setOf(DAY_3)))[0]
        assertEquals(mapOf(MediaSource.WHATSAPP to 1), row.hidden)
        assertEquals(1, row.tucked)
        assertFalse("w" in row.items.map { it.id })
    }

    @Test
    fun theStorylineByDayCountsAndRevealsTuckedPhotos() {
        val storyline = listOf(photo("s1", DAY_3, 12), photo("s2", DAY_3, 10, tucked = true), photo("s3", DAY_2, 9, tucked = true))
        val hidden = storylineDays(storyline, TuckedSession.NONE)
        assertEquals(listOf(DAY_3, DAY_2), hidden.map { it.day }, "a day of only tucked photos keeps its heading")
        assertEquals(listOf(listOf("s1"), emptyList()), hidden.map { d -> d.items.map { it.id } })
        assertEquals(listOf(1, 1), hidden.map { it.tucked })
        val revealed = storylineDays(storyline, TuckedSession(days = setOf(DAY_2)))
        assertEquals(listOf(listOf("s1"), listOf("s3")), revealed.map { d -> d.items.map { it.id } })
        assertEquals(listOf(false, true), revealed.map { it.revealed })
    }

    @Test
    fun theSessionResetsAfterFiveMinutesAway() {
        var now = 0L
        val session = TuckedReveal { now }
        assertTrue(session.needsLock)
        session.unlocked()
        session.toggle(listOf(DAY_3))
        assertEquals(setOf(DAY_3), session.current.days)
        // Back after four minutes: the same session.
        session.wentToBackground()
        now += 4 * 60_000L
        session.cameBack()
        assertFalse(session.needsLock)
        assertEquals(setOf(DAY_3), session.current.days)
        // Tapped again: hidden.
        session.toggle(listOf(DAY_3))
        assertEquals(emptySet(), session.current.days)
        session.show(listOf(DAY_2, DAY_1))
        // Away for more than five minutes: a new session, locked, nothing revealed.
        session.wentToBackground()
        now += TuckedReveal.SESSION_GAP_MILLIS + 1
        session.cameBack()
        assertTrue(session.needsLock)
        assertEquals(TuckedSession.NONE, session.current)
    }

    @Test
    fun aRowOfManyDaysIsRevealedWhenAnyOfItsDaysIs() {
        val session = TuckedReveal { 0 }
        session.toggle(listOf(DAY_2, DAY_3))
        assertEquals(setOf(DAY_2, DAY_3), session.current.days)
        session.hide(listOf(DAY_2))
        // One of them still shows: the pill hides both.
        session.toggle(listOf(DAY_2, DAY_3))
        assertEquals(emptySet(), session.current.days)
    }

    @Test
    fun bringBackIsOfferedWhenEveryPickedPhotoIsTucked() {
        assertTrue(allTucked(listOf(photo("a", DAY_1, 1, tucked = true), photo("b", DAY_1, 2, tucked = true))))
        assertFalse(allTucked(listOf(photo("a", DAY_1, 1, tucked = true), photo("b", DAY_1, 2))))
        assertFalse(allTucked(emptyList()))
    }

    @Test
    fun thePhonesCopyOfATuckedFileIsTuckedByNameAndSize() {
        val phone = MediaItem("1", MediaType.IMAGE, 0, DAY_1, null, 10, 10, 1234, "IMG_1.jpg")
        val server = ServerMedia(
            sha256 = "f".repeat(64), isVideo = false, fileName = "img_1.JPG", mimeType = "image/jpeg", size = 1234,
            uploadedAt = "2026-11-01T10:00:00Z", isTucked = true,
        )
        assertTrue(mergeWithServer(listOf(phone), emptyMap(), listOf(server)).single().isTucked)
    }

    @Test
    fun anOlderServersPageKeepsTheTuckedFlag() {
        val database = testLibraryDatabase()
        val store = ServerMediaStore(database)
        val file = ServerMedia(sha256 = "a".repeat(64), isVideo = false, fileName = "a.jpg", mimeType = "image/jpeg", size = 1, uploadedAt = "2026-11-01T10:00:00Z")
        store.apply(FeedPage(listOf(file.copy(isTucked = true)), emptyList(), emptyList(), emptyList(), "1"), showPrivate = true)
        // From a server without the flag the file comes as not tucked: what the row had stays.
        store.apply(FeedPage(listOf(file.copy(aesthetic = 6.0)), emptyList(), emptyList(), emptyList(), "2", knowsTucked = false), showPrivate = true)
        assertTrue(store.file(file.sha256)!!.isTucked)
        store.apply(FeedPage(listOf(file), emptyList(), emptyList(), emptyList(), "3"), showPrivate = true)
        assertFalse(store.file(file.sha256)!!.isTucked)
    }

    @Test
    fun thePhonesSearchNeverFindsWhatIsTuckedAway() = runTest {
        val files = listOf(
            FileFacts("a", null, "2026-11-01T10:00:00Z", null, null, isPrivate = false),
            FileFacts("b", null, "2026-11-01T10:00:00Z", null, null, isPrivate = false, isTucked = true),
        )
        val walk: FileWalk = { each -> files.forEach(each) }
        assertEquals(setOf("a"), shownFiles(walk, includePrivate = true).taken.keys)
    }

    @Test
    fun momentsFromTheDatabaseRevealTuckedDaysInMemory() = runTest {
        val database = testLibraryDatabase()
        val queries = database.libraryQueries
        database.transaction { library.forEach { queries.upsertLibraryItem(it.toRow()) } }
        val table = FeedTable(database)
        val pager = FeedPager(table, LibraryTable(database), Dispatchers.Unconfined)
        fun context(days: Set<LocalDay>) = FeedContext(ShownSources(), byDay, Places.NONE, PhotoLooks.NONE, emptySet(), emptyMap(), emptyList(), "v", days)
        val hidden: List<FeedSection> = sections(pager.refresh(context(emptySet()), emptySet()) { it.item })
        assertEquals(listOf(1, 2, 0), hidden.map { it.tucked })
        assertEquals(listOf(listOf("c", "a"), emptyList(), listOf("x")), hidden.map { row -> row.items.map { it.id } })
        val shown = sections(pager.refresh(context(setOf(DAY_2)), emptySet()) { it.item })
        assertEquals(hidden.map { it.key }, shown.map { it.key })
        assertEquals(listOf("t2a", "t2b"), shown[1].items.map { it.id })
        assertTrue(shown[1].tuckedShown)
        // The rows kept are never the revealed ones.
        assertEquals(listOf("c", "a"), sections(pager.refresh(context(emptySet()), emptySet()) { it.item })[0].items.map { it.id })
        // The timeline counts only what shows when nothing is revealed: day 2 has no photos in it.
        assertFalse(table.timeline().months.flatMap { it.days }.any { it.day == DAY_2 })
    }
}

private fun MediaItem.toRow() = com.chencohensolution.journal.db.Library_item(
    id = id, sha256 = sha256, server_sha256 = null, is_on_device = isOnDevice, is_video = type == MediaType.VIDEO,
    taken_millis = takenAtMillis, epoch_day = day.toEpochDay(), duration_millis = durationMillis, width = width.toLong(),
    height = height.toLong(), size = sizeBytes, file_name = fileName, burst_id = burstId, source = source.id, latitude = location?.latitude,
    longitude = location?.longitude, modified_millis = modifiedMillis, mime_type = mimeType, is_private = isPrivate,
    aesthetic = aesthetic?.toDouble(), is_tucked = isTucked,
)
