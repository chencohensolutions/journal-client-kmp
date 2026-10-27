package com.chencohensolution.journal.library

/** Which of the server's three audio collections a sound recording is in (hidden collections, each on its own). */
enum class AudioKind {
    /** A recording of an event, or a note. */
    RECORDING,

    /** A recorded phone call. */
    CALL,

    /** A WhatsApp voice message. */
    VOICE_MESSAGE;

    companion object {
        /**
         * The kind of a sound recording with [source], as the server's collections have it: by the source first (call is
         * a call, recording a recording, WhatsApp a voice message: the server's sound kinds and the folders the user
         * merged into them, since Oct 27, 2026), then a call by what the server read of it ([isCall]), else a recording.
         */
        fun of(source: MediaSource, isCall: Boolean): AudioKind = when (source.kind) {
            MediaSource.CALL -> CALL
            MediaSource.RECORDING -> RECORDING
            MediaSource.WHATSAPP -> VOICE_MESSAGE
            else -> if (isCall) CALL else RECORDING
        }
    }
}

/**
 * A sound recording on the server: a recording, a phone call or a WhatsApp voice message. Kept apart from the photo
 * library ([LibraryState.sounds]): only Moments and the recording's page show them, so nothing that expects a picture
 * (grids, previews, Explore, the viewer's zoom) ever meets one. Hidden on every device until the user shows its kind
 * (Settings › Private media › Hidden collections). See journal-server-go docs/media.md "Sound recordings".
 */
data class SoundRecording(
    val sha256: String,
    val kind: AudioKind,
    /** When it started, in epoch milliseconds. */
    val startMillis: Long,
    /** [startMillis] as a day where it was recorded. */
    val day: LocalDay,
    /** Its length; 0 when the server didn't know it. */
    val durationMillis: Long,
    /** A call's contact, as the phone named the file; often only one of [contact] and [number] is known. */
    val contact: String? = null,
    val number: String? = null,
    val mimeType: String? = null,
    val source: MediaSource = MediaSource.OTHER,
    val isPrivate: Boolean = false,
) {
    val endMillis: Long get() = startMillis + durationMillis
}
