package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.server.HiddenCollections
import com.chencohensolution.journal.server.HiddenCollectionsState
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.sin

// The ux/moments-audio round's sample data, fake playback and lab entries (deleted on promotion).

private object AudioLabData {
    private val zone = ZoneId.systemDefault()
    private var nextId = 9000

    private fun at(daysAgo: Int, time: String): Long =
        LocalDate.now().minusDays(daysAgo.toLong()).atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

    private fun dayLabel(daysAgo: Int): String = formatDate(LocalDate.now().minusDays(daysAgo.toLong()), "EEEEMMMMd")

    private fun photo(daysAgo: Int, time: String, ratio: Float = 4f / 3f): MediaItem {
        val taken = at(daysAgo, time)
        val date = LocalDate.now().minusDays(daysAgo.toLong())
        val id = (nextId++).toString()
        return MediaItem(id, MediaType.IMAGE, taken, LocalDay(date.year, date.monthValue, date.dayOfMonth), null, 4000, (4000 / ratio).toInt(), 3_000_000, "IMG_$id.heic")
    }

    /** Loudness that rises and falls like speech or music, the same every time for a clip. */
    private fun peaks(seed: Int, n: Int = 90) = List(n) { i -> (0.35f + 0.45f * abs(sin(i * 0.37f + seed)) * abs(sin(i * 0.11f + seed * 2))).coerceIn(0f, 1f) }

    private fun clip(id: String, kind: AudioKind, daysAgo: Int, start: String, minutes: Double, place: String? = null, contact: String? = null, number: String? = null) =
        AudioClip(id, kind, at(daysAgo, start), (minutes * 60_000).toLong(), place, contact, number, peaks(id.hashCode() % 7))

    val evening = clip("rec-jaffa", AudioKind.RECORDING, 2, "18:10", 105.2, place = "Jaffa Port")
    private val eveningPhotos = listOf("19:48", "19:20", "19:05", "18:43", "18:42", "18:31", "18:14").mapIndexed { i, t -> photo(2, t, if (i % 3 == 1) 3f / 4f else 4f / 3f) }
    val concert = clip("rec-concert", AudioKind.RECORDING, 9, "20:00", 185.0, place = "Barby, Tel Aviv")
    private val concertPhotos = List(24) { i -> photo(9, LocalTime.of(20, 4).plusMinutes(i * 7L + (i % 3)).toString(), if (i % 4 == 0) 3f / 4f else 16f / 9f) }.reversed()
    private val memo = clip("rec-memo", AudioKind.RECORDING, 9, "10:00", 12.5)
    private val calls = listOf(
        clip("call-dad", AudioKind.CALL, 2, "11:40", 4.2, contact = "אבא"),
        clip("call-number", AudioKind.CALL, 2, "09:42", 1.1, number = "0501234567"),
        clip("call-dana", AudioKind.CALL, 2, "09:10", 12.5, contact = "Dana from work, don't call after 8, she has kids"),
    )
    private val voice = clip("voice-1", AudioKind.VOICE_MESSAGE, 2, "08:30", 0.8)
    private val lonelyCalls = listOf(
        clip("call-abroad", AudioKind.CALL, 12, "21:05", 23.0, number = "+14155550123"),
        clip("call-nobody", AudioKind.CALL, 12, "00:02", 25.9),
    )

    val rows: List<AudioFeedRow> = listOf(
        AudioFeedRow.Day(dayLabel(2)),
        AudioFeedRow.Photos("Neve Tzedek", listOf(photo(2, "21:34"), photo(2, "21:30", 3f / 4f))),
        AudioFeedRow.Recording(evening, eveningPhotos),
        AudioFeedRow.Photos("Tel Aviv Port", listOf(photo(2, "16:40"), photo(2, "16:02"))),
        AudioFeedRow.Calls(calls),
        AudioFeedRow.VoiceMessage(voice),
        AudioFeedRow.Day(dayLabel(9)),
        AudioFeedRow.Recording(concert, concertPhotos),
        AudioFeedRow.Recording(memo, emptyList()),
        AudioFeedRow.Day(dayLabel(12)),
        AudioFeedRow.Calls(lonelyCalls),
    )

    val clips: Map<String, AudioClip> = rows.flatMap {
        when (it) {
            is AudioFeedRow.Recording -> listOf(it.clip)
            is AudioFeedRow.Calls -> it.calls
            is AudioFeedRow.VoiceMessage -> listOf(it.clip)
            else -> emptyList()
        }
    }.associateBy { it.id }

    val eveningViewer = AudioViewerState(evening, eveningPhotos, null)
    val callViewer = AudioViewerState(calls[0].copy(number = "0521234567"), emptyList(), null)

    val hidden = HiddenCollections(
        whatsapp = HiddenCollections.Collection("WhatsApp", 3_412, false),
        screenshotsWithoutApp = HiddenCollections.Collection("", 128, false),
        screenshotApps = listOf(HiddenCollections.Collection("Bumble", 42, true), HiddenCollections.Collection("Chrome", 310, false)),
        nudity = HiddenCollections.Nudity(true, 50, 61, 0),
    )
    val hiddenAudio = HiddenAudio(recordings = 1_204, calls = 5_931, voiceMessages = 14, hideRecordings = true, hideCalls = true, hideVoiceMessages = true)
}

/**
 * Plays nothing: a timer moves the position, [fast] times real time so the playhead can be seen moving over the
 * photos. [startAt] starts it already playing there.
 */
@Composable
private fun FakePlayer(startAt: Pair<String, Long>? = null, fast: Float = 20f, content: @Composable (AudioPlayback?, play: (AudioClip) -> Unit, pause: () -> Unit, seek: (AudioClip, Long) -> Unit, setSpeed: (Float) -> Unit, stop: () -> Unit) -> Unit) {
    var playback by remember { mutableStateOf(startAt?.let { AudioPlayback(it.first, it.second, playing = true) }) }
    LaunchedEffect(playback?.clipId, playback?.playing) {
        while (playback?.playing == true) {
            delay(200)
            val p = playback ?: break
            val length = AudioLabData.clips[p.clipId]?.durationMillis ?: AudioLabData.evening.durationMillis
            val next = p.positionMillis + (200 * fast * p.speed).toLong()
            playback = if (next >= length) p.copy(positionMillis = length, playing = false) else p.copy(positionMillis = next)
        }
    }
    content(
        playback,
        { clip -> playback = if (playback?.clipId == clip.id) playback!!.copy(playing = true) else AudioPlayback(clip.id, 0, true) },
        { playback = playback?.copy(playing = false) },
        { clip, ms -> playback = AudioPlayback(clip.id, ms.coerceIn(0, clip.durationMillis), true, playback?.takeIf { it.clipId == clip.id }?.speed ?: 1f) },
        { s -> playback = playback?.copy(speed = s) },
        { playback = null },
    )
}

@Composable
private fun LabMoments(startAt: Pair<String, Long>? = null, screen: @Composable (AudioMomentsState, AudioMomentsActions) -> Unit) =
    FakePlayer(startAt) { playback, play, pause, seek, _, stop ->
        screen(AudioMomentsState(AudioLabData.rows, playback), AudioMomentsActions(play = play, pause = pause, seek = seek, stop = stop))
    }

@Composable
private fun LabViewer(state: AudioViewerState, startAt: Long? = null, screen: @Composable (AudioViewerState, AudioViewerActions) -> Unit) =
    LibraryTheme(dark = true) {
        FakePlayer(startAt?.let { state.clip.id to it }, fast = 1f) { playback, play, pause, seek, setSpeed, _ ->
            screen(
                state.copy(playback = playback),
                AudioViewerActions(play = { play(state.clip) }, pause = pause, seek = { seek(state.clip, it) }, setSpeed = setSpeed),
            )
        }
    }

private const val PLAYING_AT = 32 * 60_000L

internal val momentsAudioOptions = listOf(
    // Fakes: the recordings, calls and voice message (the library has no AUDIO items yet), the waveform's loudness
    // (made up), playing (a timer, 20× fast, no sound), opening a recording or photo (does nothing). Can't show: the
    // sound, audio focus against a feed video, the 2-hour moment split being overridden by a real feed.
    LabOption("a", "Span rail", "A card where it ended, a rail down to where it started past the photos taken meanwhile") {
        LabMoments { s, a -> MomentsAudioScreenA(s, a) }
    },
    // Same fakes as a; plays from 0:32:00 so the marked photo and, after scrolling, the docked player show.
    LabOption("a-playing", "Span rail, playing", "Playing from 32 minutes in: the photo of that moment marked; scroll away for the player") {
        LabMoments(AudioLabData.evening.id to PLAYING_AT) { s, a -> MomentsAudioScreenA(s, a) }
    },
    // Same fakes as a, laid out right to left (the text stays English; the phone's language makes it Hebrew).
    LabOption("a-rtl", "Span rail, right to left", "The rail on the right, as in Hebrew") {
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
            LabMoments(AudioLabData.evening.id to PLAYING_AT) { s, a -> MomentsAudioScreenA(s, a) }
        }
    },
    // Same fakes as a.
    LabOption("b", "Header strip", "A slim player pinned on top while its photos scroll under it; each photo says how far in") {
        LabMoments(AudioLabData.evening.id to PLAYING_AT) { s, a -> MomentsAudioScreenB(s, a) }
    },
    // Same fakes as a; the set turns to the photo of the moment as it plays.
    LabOption("c", "Audio as a set", "The card leads a set you swipe: card, then the photos taken meanwhile") {
        LabMoments { s, a -> MomentsAudioScreenC(s, a) }
    },
)

internal val viewerAudioOptions = listOf(
    // Fakes: the recording and its photos, the loudness, playing (a timer at real speed, no sound), back (nothing).
    LabOption("a", "Filmstrip", "Large waveform with a mark per photo, controls, the photos under it") {
        LabViewer(AudioLabData.eveningViewer, startAt = PLAYING_AT) { s, a -> AudioViewerScreenA(s, a) }
    },
    // Fakes as a, plus Call and Edit contact (do nothing; setCall on the server isn't called).
    LabOption("a-call", "Filmstrip, a call", "A call: who, the number, Call and Edit contact; no photos") {
        LabViewer(AudioLabData.callViewer) { s, a -> AudioViewerScreenA(s, a) }
    },
    // Fakes as a.
    LabOption("b", "Slideshow", "The photo of the moment fills the screen; the player is a sheet below") {
        LabViewer(AudioLabData.eveningViewer, startAt = PLAYING_AT) { s, a -> AudioViewerScreenB(s, a) }
    },
)

internal val hiddenAudioOptions = listOf(
    // Fakes: every count (the server's hiddenCollections has no audio in the app's query yet); switches move here only.
    LabOption("a", "Sound recordings card", "Recordings, calls and voice messages under Nudity, hidden until turned off") {
        var audio by remember { mutableStateOf(AudioLabData.hiddenAudio) }
        HiddenAudioScreenA(
            AudioLabData.hidden,
            audio,
            HiddenAudioActions(
                setRecordings = { audio = audio.copy(hideRecordings = it) },
                setCalls = { audio = audio.copy(hideCalls = it) },
                setVoiceMessages = { audio = audio.copy(hideVoiceMessages = it) },
            ),
        )
    },
)

/** The Hidden collections screen as the app has it, with the same made-up counts. */
@Composable
internal fun HiddenNow() = HiddenContent(
    androidx.compose.ui.res.stringResource(com.chencohensolution.journal.R.string.settings_hidden_title),
    HiddenCollectionsState.Ready(AudioLabData.hidden, "", AudioLabData.hidden.screenshotApps, saving = false, notChanged = false),
    HiddenActions(retry = {}, set = { _, _ -> }, setQuery = {}),
    back = {},
)
