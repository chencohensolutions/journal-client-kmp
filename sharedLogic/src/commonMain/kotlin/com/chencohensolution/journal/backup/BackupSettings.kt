package com.chencohensolution.journal.backup

import com.chencohensolution.journal.library.MediaSource

/** Which photos upload first. */
enum class UploadOrder { OLDEST_FIRST, NEWEST_FIRST }

/** The Backup part of Settings: what uploads to the server, and when. */
data class BackupSettings(
    /** Nothing uploads until the user turns backup on. */
    val enabled: Boolean = false,
    val sources: Set<MediaSource> = DEFAULT_SOURCES,
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
}
