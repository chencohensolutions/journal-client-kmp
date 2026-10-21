package com.chencohensolution.journal.library

enum class MediaType { IMAGE, VIDEO }

/** A calendar day in the device's time zone. */
data class LocalDay(val year: Int, val month: Int, val day: Int) : Comparable<LocalDay> {
    override fun compareTo(other: LocalDay): Int =
        compareValuesBy(this, other, LocalDay::year, LocalDay::month, LocalDay::day)

    /** Days since 1970-01-01 (proleptic Gregorian calendar). */
    fun toEpochDay(): Long {
        // Howard Hinnant's days_from_civil.
        val y = if (month <= 2) year - 1 else year
        val era = (if (y >= 0) y else y - 399) / 400
        val yearOfEra = y - era * 400
        val dayOfYear = (153 * (month + (if (month > 2) -3 else 9)) + 2) / 5 + day - 1
        val dayOfEra = yearOfEra * 365 + yearOfEra / 4 - yearOfEra / 100 + dayOfYear
        return era * 146097L + dayOfEra - 719468
    }

    fun plusDays(days: Long): LocalDay = fromEpochDay(toEpochDay() + days)

    /** ISO 8601, e.g. "2026-10-08". */
    override fun toString(): String =
        "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')}"

    companion object {
        fun fromEpochDay(epochDay: Long): LocalDay {
            // Howard Hinnant's civil_from_days.
            val z = epochDay + 719468
            val era = (if (z >= 0) z else z - 146096) / 146097
            val dayOfEra = z - era * 146097
            val yearOfEra = (dayOfEra - dayOfEra / 1460 + dayOfEra / 36524 - dayOfEra / 146096) / 365
            val dayOfYear = dayOfEra - (365 * yearOfEra + yearOfEra / 4 - yearOfEra / 100)
            val mp = (5 * dayOfYear + 2) / 153
            val day = (dayOfYear - (153 * mp + 2) / 5 + 1).toInt()
            val month = (if (mp < 10) mp + 3 else mp - 9).toInt()
            val year = (yearOfEra + era * 400 + (if (month <= 2) 1 else 0)).toInt()
            return LocalDay(year, month, day)
        }

        /** Parses [toString]'s format; null if it isn't one. */
        fun parse(value: String): LocalDay? {
            val parts = value.split("-")
            if (parts.size != 3) return null
            val (y, m, d) = parts.map { it.toIntOrNull() ?: return null }
            return LocalDay(y, m, d).takeIf { m in 1..12 && d in 1..31 }
        }
    }
}

/** Where a photo or video is in the backup process (see BackupController). */
sealed interface BackupStatus {
    /** Only on this device. */
    data object NotBackedUp : BackupStatus
    data object Queued : BackupStatus

    /** [progress] is 0..1. */
    data class Uploading(val progress: Float) : BackupStatus
    data object BackedUp : BackupStatus
    data class Failed(val reason: String) : BackupStatus
}

/** Where a photo came from, by the folder it is in (Android) or what PhotoKit tells of it (iOS, [sourceOfAsset]). */
enum class MediaSource {
    /** Taken with the phone's camera (DCIM). */
    CAMERA,

    /** Received in WhatsApp. */
    WHATSAPP,
    SCREENSHOTS,

    /** Downloads, other apps' folders, edits saved elsewhere. */
    OTHER,
}

/** A position on Earth in degrees, as GPS writes it. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/**
 * The source of a file in [relativePath], Android's folder of it inside shared storage ("DCIM/Camera/",
 * "Pictures/Screenshots/", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/").
 */
internal fun sourceOfFolder(relativePath: String?): MediaSource {
    val path = relativePath?.lowercase() ?: return MediaSource.OTHER
    return when {
        "screenshot" in path || "screenrecord" in path || "screen record" in path -> MediaSource.SCREENSHOTS
        "whatsapp" in path -> MediaSource.WHATSAPP
        path.startsWith("dcim/") -> MediaSource.CAMERA
        else -> MediaSource.OTHER
    }
}

/** What an iPhone's own camera writes: HEIC/HEIF or JPEG photos, ProRAW DNG, QuickTime videos. */
private val CAMERA_TYPES = setOf("image/heic", "image/heif", "image/jpeg", "image/x-adobe-dng", "image/dng", "video/quicktime")

/**
 * The source of an iOS photo, which has no folder: PhotoKit marks screenshots and screen recordings
 * ([isScreenshot]); WhatsApp saves into an album named "WhatsApp" ([inWhatsAppAlbum]); a photo synced from a computer
 * or in a shared album isn't [inUserLibrary]. Of the rest, a file the camera can't have written (a PNG, GIF, WebP or
 * MP4 saved from an app) is Other; a JPEG saved from Safari can't be told apart and counts as Camera. Unknown
 * [originalMimeType] (not read yet) counts as Camera too, as before it was read.
 */
internal fun sourceOfAsset(isScreenshot: Boolean, inWhatsAppAlbum: Boolean, inUserLibrary: Boolean, originalMimeType: String?): MediaSource =
    when {
        isScreenshot -> MediaSource.SCREENSHOTS
        inWhatsAppAlbum -> MediaSource.WHATSAPP
        !inUserLibrary -> MediaSource.OTHER
        originalMimeType != null && originalMimeType.lowercase() !in CAMERA_TYPES -> MediaSource.OTHER
        else -> MediaSource.CAMERA
    }

/** A photo or video in the device library. */
data class MediaItem(
    /** MediaStore row id on Android, PHAsset localIdentifier on iOS. */
    val id: String,
    val type: MediaType,
    /** When it was taken (falls back to when the file was created), in epoch milliseconds. */
    val takenAtMillis: Long,
    /** [takenAtMillis] as a day in the device's time zone. */
    val day: LocalDay,
    /** Length of a video; null for photos and for videos whose length is unknown. */
    val durationMillis: Long?,
    /** In pixels as it is shown, after the camera's rotation; 0 when unknown. */
    val width: Int,
    val height: Int,
    /** Of the file backup reads (on iOS the current version: an edit's render); null when unknown (not read yet). */
    val sizeBytes: Long?,
    /** "IMG_1234.HEIC"; an edited iOS photo has its original's name with the edit's extension. Null when unknown. */
    val fileName: String?,
    val backup: BackupStatus = BackupStatus.NotBackedUp,
    /** Shots of one camera burst share this: iOS burstIdentifier, or the Samsung "20261008_140901_092" name pattern. */
    val burstId: String? = null,
    val source: MediaSource = MediaSource.CAMERA,
    /**
     * Where it was taken, when the platform reports it with the library (iOS). Android hides it until the file is read
     * (see [com.chencohensolution.journal.places.PhotoPlaces]).
     */
    val location: GeoPoint? = null,
    /** When the file last changed, in epoch milliseconds; an edit saved in place changes it. Null when unknown. */
    val modifiedMillis: Long? = null,
    /** "image/jpeg", "video/mp4", …; null when unknown. */
    val mimeType: String? = null,
    /** The sha256 of its bytes, once known (read by backup, or from the server). */
    val sha256: String? = null,
    /** False for a photo that isn't in the device's library: it is shown from the server, never read from the library. */
    val isOnDevice: Boolean = true,
    /** For a photo not in the library: the original downloaded from the server and kept in the app's storage. */
    val keptOriginal: String? = null,
) {
    /** Whether its original is on this device, in the library or kept by the app: then nothing is fetched for it. */
    val hasOriginalHere: Boolean get() = isOnDevice || keptOriginal != null
}

/** What the user allowed the app to read from the photo library. */
enum class PhotoAccess {
    /** Never asked. */
    NOT_DETERMINED,
    DENIED,

    /** Only photos the user picked (Android "Select photos", iOS "Limited Access"). */
    LIMITED,
    FULL,
}

/** The platform photo library: MediaStore on Android, PhotoKit on iOS. */
internal interface MediaLibrary {
    /** The current permission. Android can't tell "never asked" from "denied" and reports [PhotoAccess.DENIED] for both. */
    fun access(): PhotoAccess

    /** All photos and videos the app can read, in any order. Called off the main thread by the implementation itself. */
    suspend fun loadItems(): List<MediaItem>

    /** Calls [onChange] when photos are added, changed or removed; returns how to stop. Platforms without it never call. */
    fun watch(onChange: () -> Unit): () -> Unit = {}
}
