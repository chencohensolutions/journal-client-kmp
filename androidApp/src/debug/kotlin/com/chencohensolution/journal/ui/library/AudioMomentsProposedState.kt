package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.library.MediaItem

// Proposed state for sound recordings in Moments and the viewer (UI lab round ux/moments-audio). On promotion this
// moves to sharedLogic: the library gets MediaType.AUDIO from the server's kind AUDIO, Media.call and Media.endedAt
// (journal-server-go docs/media.md "Sound recordings"), and the feed logic produces these rows.

/** Which of the server's three audio collections a recording is in. */
internal enum class AudioKind { RECORDING, CALL, VOICE_MESSAGE }

/**
 * One sound recording: a recording of an event, a phone call or a WhatsApp voice message. [contact] and [number] are
 * a call's (usually only one is known, as the recorder named the file). [peaks] is the loudness along it, 0 to 1, for
 * the waveform; empty until read.
 */
internal data class AudioClip(
    val id: String,
    val kind: AudioKind,
    val startMillis: Long,
    val durationMillis: Long,
    val place: String? = null,
    val contact: String? = null,
    val number: String? = null,
    val peaks: List<Float> = emptyList(),
) {
    val endMillis: Long get() = startMillis + durationMillis
}

/** What is playing: one clip at a time, never on its own. */
internal data class AudioPlayback(val clipId: String, val positionMillis: Long, val playing: Boolean, val speed: Float = 1f)

/** A row of Moments with sound recordings in it, newest first like the rest of the feed. */
internal sealed interface AudioFeedRow {
    /** The line between days. */
    data class Day(val label: String) : AudioFeedRow

    /** Camera photos outside any recording, with their moment's label. */
    data class Photos(val place: String?, val items: List<MediaItem>) : AudioFeedRow

    /**
     * A recording with the photos taken while it ran ([during], newest first). A recording pulls these into one
     * moment even across the 2-hour gap that splits camera moments.
     */
    data class Recording(val clip: AudioClip, val during: List<MediaItem>) : AudioFeedRow

    /** Calls next to each other, folded into one row until opened. */
    data class Calls(val calls: List<AudioClip>) : AudioFeedRow

    /** A WhatsApp voice message. */
    data class VoiceMessage(val clip: AudioClip) : AudioFeedRow
}

internal data class AudioMomentsState(val rows: List<AudioFeedRow>, val playback: AudioPlayback? = null)

/** What the feed's sound recordings can ask for. Nothing plays until [play] is tapped. */
internal class AudioMomentsActions(
    val play: (AudioClip) -> Unit = {},
    val pause: () -> Unit = {},
    val seek: (AudioClip, Long) -> Unit = { _, _ -> },
    val stop: () -> Unit = {},
    val openClip: (AudioClip) -> Unit = {},
    val openPhoto: (MediaItem) -> Unit = {},
)

/** The viewer's page for one recording: the photos taken while it ran, oldest first, to step through as it plays. */
internal data class AudioViewerState(
    val clip: AudioClip,
    val during: List<MediaItem>,
    val playback: AudioPlayback?,
)

internal class AudioViewerActions(
    val play: () -> Unit = {},
    val pause: () -> Unit = {},
    val seek: (Long) -> Unit = {},
    val setSpeed: (Float) -> Unit = {},
    val back: () -> Unit = {},
    val dial: (String) -> Unit = {},
    val editContact: () -> Unit = {},
)

/** Settings → Private media → Hidden collections, with the server's three audio collections. */
internal data class HiddenAudio(val recordings: Int, val calls: Int, val voiceMessages: Int, val hideRecordings: Boolean, val hideCalls: Boolean, val hideVoiceMessages: Boolean)
