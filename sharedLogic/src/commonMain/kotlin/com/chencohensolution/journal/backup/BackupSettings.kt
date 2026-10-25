package com.chencohensolution.journal.backup

import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaSource
import kotlinx.serialization.Serializable

/** Which photos upload first. */
enum class UploadOrder { OLDEST_FIRST, NEWEST_FIRST }

/** What stays on the phone once a WAV recording is on the server as FLAC (it is always sent as FLAC). */
enum class KeepOnPhone {
    /** The WAV as it is; the FLAC made to send it is deleted after the upload. */
    WAV,

    /** The FLAC, written beside the WAV; the WAV is deleted once the server confirms the FLAC. */
    FLAC,
}

/**
 * A folder on the phone the user picked for backup, where calls or recordings are kept (Samsung's Recordings/Call,
 * Voice Recorder, a WhatsApp voice notes folder). Such folders often hold a `.nomedia` file, so the media library never
 * lists them: the app reads them through the access the user gave when picking the folder. Their files are backed up
 * whether or not sound recordings are shown in the app.
 */
@Serializable
data class SoundFolder(
    /** The platform's lasting reference to the folder (Android: the tree URI the folder picker returned). */
    val treeUri: String,
    /** The folder's name as the phone shows it ("Call"). */
    val name: String,
    /** Where it is, for the user ("Recordings › Call"). */
    val path: String,
    /** What its files are; calls are sent as calls even when their names don't say who. */
    val kind: AudioKind,
    val enabled: Boolean = true,
) {
    /** The source its files are uploaded with: WhatsApp for voice messages, else the folder. */
    val source: MediaSource get() = if (kind == AudioKind.VOICE_MESSAGE) MediaSource.WHATSAPP else MediaSource.folder(name)
}

/** The Backup part of Settings: what uploads to the server, and when. */
data class BackupSettings(
    /** Nothing uploads until the user turns backup on. */
    val enabled: Boolean = false,
    val sources: Set<MediaSource> = DEFAULT_SOURCES,
    /** Folders of sound files the user picked, in the order they were added. */
    val soundFolders: List<SoundFolder> = emptyList(),
    val keepOnPhone: KeepOnPhone = KeepOnPhone.WAV,
    /** Only over Wi-Fi (or Ethernet), never on mobile data. */
    val wifiOnly: Boolean = true,
    /** When not empty, only on these Wi-Fi networks (by name); reading the name needs location permission. */
    val wifiNetworks: Set<String> = emptySet(),
    val chargingOnly: Boolean = false,
    val order: UploadOrder = UploadOrder.OLDEST_FIRST,
) {
    companion object {
        /** Only what the camera took, until the user picks more. */
        val DEFAULT_SOURCES: Set<MediaSource> = setOf(MediaSource.CAMERA)
    }

    /**
     * The pills Settings › Backup offers: the fixed sources, those with photos ([counts], photos per source) and
     * those chosen, the most photos first (ties in [MediaSource] order).
     */
    fun choices(counts: Map<MediaSource, Int>): List<MediaSource> = MediaSource.byCount(counts, sources)
}
