package com.chencohensolution.journal

import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.SoundRecording
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.FeedMoment
import com.chencohensolution.journal.stories.FeedSound
import com.chencohensolution.journal.stories.MomentsFeed
import com.chencohensolution.journal.stories.buildFeed
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

private const val MINUTE = 60_000L
private val DAY = LocalDay(2026, 10, 8)
private fun at(minute: Int) = DAY.toEpochDay() * 86_400_000 + minute * MINUTE

private fun photo(id: String, minute: Int, source: MediaSource = MediaSource.CAMERA) =
    MediaItem(id, MediaType.IMAGE, at(minute), DAY, null, 4, 3, 100, "$id.jpg", source = source)

private fun sound(sha: String, kind: AudioKind, minute: Int, minutes: Int, source: MediaSource = MediaSource.OTHER) =
    SoundRecording(sha, kind, at(minute), DAY, minutes * MINUTE, source = source)

private fun feed(items: List<MediaItem>, sounds: List<SoundRecording>, shown: Set<MediaSource> = MediaSource.entries.toSet(), grouping: TimelineGrouping = TimelineGrouping()) =
    buildFeed(items.sortedByDescending { it.takenAtMillis }, ShownSources(shown), grouping, sounds = sounds)

private fun MomentsFeed.sections() = entries.filterIsInstance<FeedEntry.Section>().map { it.section }

class SoundFeedTest {
    @Test
    fun aRecordingTakesThePhotosTakenWhileItRanAcrossTheGap() {
        // A three-hour recording: its photos are hours apart, which would split camera moments, but stay one.
        val feed = feed(
            listOf(photo("before", 0), photo("p1", 70), photo("p2", 200), photo("p3", 240), photo("after", 300)),
            listOf(sound("rec", AudioKind.RECORDING, 60, 190)),
        )
        val section = feed.sections().single()
        val recording = section.moments.single { it.recording != null }
        assertEquals(listOf("p1", "p2", "p3"), recording.items.map { it.id })
        assertEquals(at(60), recording.startMillis)
        assertEquals(at(250), recording.endMillis)
        // The other photos are moments of their own, newest first around it.
        assertEquals(listOf(listOf("after"), listOf("p1", "p2", "p3"), listOf("before")), section.moments.map { m -> m.items.map { it.id } })
        // A sound recording is no photo: not in the row's items' count of photos per source.
        assertEquals(mapOf(MediaSource.CAMERA to 5), feed.counts)
    }

    @Test
    fun aRecordingWithNoPhotosStillShows() {
        val section = feed(emptyList(), listOf(sound("memo", AudioKind.RECORDING, 600, 12))).sections().single()
        val moment = section.moments.single()
        assertEquals("memo", moment.recording?.sha256)
        assertTrue(moment.items.isEmpty())
        assertNull(moment.place)
    }

    @Test
    fun callsOneAfterAnotherAreOneRowAndAreDrawnByTime() {
        val section = feed(
            listOf(photo("noon", 720)),
            listOf(
                sound("c1", AudioKind.CALL, 540, 5),
                sound("c2", AudioKind.CALL, 580, 2),
                // Hours later: a row of its own.
                sound("c3", AudioKind.CALL, 900, 10),
            ),
        ).sections().single()
        val runs = section.sounds.map { assertIs<FeedSound.Calls>(it).calls.map { call -> call.sha256 } }
        assertEquals(listOf(listOf("c3"), listOf("c2", "c1")), runs)
        // Newest first among the moments: the 15:00 call, the noon photo, the morning calls.
        assertEquals(listOf("c3", "noon", "c2"), section.parts.map {
            when (it) {
                is FeedSound.Calls -> it.calls.first().sha256
                is FeedMoment -> it.items.first().id
                else -> "?"
            }
        })
    }

    @Test
    fun voiceMessagesShowWhileWhatsAppDoes() {
        val items = listOf(photo("cam", 600))
        val voice = sound("v", AudioKind.VOICE_MESSAGE, 610, 1, MediaSource.WHATSAPP)
        assertIs<FeedSound.Voice>(feed(items, listOf(voice)).sections().single().sounds.single())
        assertTrue(feed(items, listOf(voice), shown = setOf(MediaSource.CAMERA)).sections().single().sounds.isEmpty())
        // Recordings and calls aren't photos of a source: they show whatever sources are on.
        val call = sound("call", AudioKind.CALL, 620, 1)
        assertEquals(1, feed(items, listOf(call), shown = setOf(MediaSource.CAMERA)).sections().single().sounds.size)
    }

    @Test
    fun aDayWithOnlySoundsIsARowAndARecordingIsNotCutInTwo() {
        val day = feed(emptyList(), listOf(sound("c", AudioKind.CALL, 60, 3))).sections()
        assertEquals(1, day.size)
        // By gaps of 2 hours: a 5-hour recording with photos at both ends stays one row.
        val byGap = TimelineGrouping(mode = GroupingMode.BY_GAP, gapHours = 2)
        val rows = feed(
            listOf(photo("start", 5), photo("end", 295)),
            listOf(sound("long", AudioKind.RECORDING, 0, 300)),
            grouping = byGap,
        ).sections()
        assertEquals(1, rows.size)
        assertEquals(listOf("start", "end"), rows.single().moments.single().items.map { it.id })
    }
}
