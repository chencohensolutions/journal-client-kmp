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

/**
 * Where a photo came from: the camera, WhatsApp, screenshots, a folder an app saves into ([folder]), or anything else
 * ([OTHER]). On Android a folder is told from the file's path and the app that saved it ([sourceOfFolder]), on iOS
 * from the album an app saved it into ([sourceOfAsset]). Each is its own choice in Settings › Backup, Settings › Show
 * in the app and the Moments filter bar (asked Oct 24, 2026; before, every DCIM folder was Camera).
 */
class MediaSource private constructor(
    /** "camera", "whatsapp", "screenshots", "other", or "folder:Petcube": what Settings keeps and the server is told. */
    val id: String,
) : Comparable<MediaSource> {
    /** The folder's name for a folder ("Flowers", "Petcube", "Telegram"); null for the fixed sources. */
    val folder: String? get() = if (id.startsWith(FOLDER)) id.removePrefix(FOLDER) else null

    override fun equals(other: Any?): Boolean = other is MediaSource && other.id == id
    override fun hashCode(): Int = id.hashCode()
    override fun toString(): String = id

    /** Camera, WhatsApp, Screenshots, the folders by name, then Other: the order every screen lists them in. */
    override fun compareTo(other: MediaSource): Int =
        compareValuesBy(this, other, { it.rank }, { it.folder?.lowercase() }, { it.folder })

    private val rank: Int get() = when (this) {
        CAMERA -> 0
        WHATSAPP -> 1
        SCREENSHOTS -> 2
        OTHER -> 4
        else -> 3
    }

    companion object {
        private const val FOLDER = "folder:"

        /** Taken with the phone's camera: DCIM/Camera and files right in DCIM (Android), the iPhone's own photos. */
        val CAMERA = MediaSource("camera")

        /** Received in WhatsApp, in any of its folders. */
        val WHATSAPP = MediaSource("whatsapp")

        /** Screenshots and screen recordings, wherever they are kept. */
        val SCREENSHOTS = MediaSource("screenshots")

        /** Files whose folder and app say nothing: loose in Pictures or Download by an unknown app, synced from a computer. */
        val OTHER = MediaSource("other")

        /** The sources every phone has, in their order. */
        val FIXED: List<MediaSource> = listOf(CAMERA, WHATSAPP, SCREENSHOTS, OTHER)

        /** A folder by its name; one named like a fixed source ("Camera", "WhatsApp", "Screenshots") is that source. */
        fun folder(name: String): MediaSource {
            val trimmed = name.trim()
            return when {
                trimmed.isEmpty() -> OTHER
                else -> FIXED.firstOrNull { it.id.equals(trimmed, ignoreCase = true) } ?: MediaSource(FOLDER + trimmed)
            }
        }

        /**
         * The source an [id] names, or null if it names none. Also takes the names the sources had before folders
         * ("CAMERA", "WHATSAPP"), which Settings kept until Oct 24, 2026.
         */
        fun parse(id: String): MediaSource? = when {
            id.startsWith(FOLDER) -> folder(id.removePrefix(FOLDER))
            else -> FIXED.firstOrNull { it.id.equals(id, ignoreCase = true) }
        }

        /** What a screen offers: the fixed sources, then [present] (those with photos) and [chosen] ones, in order. */
        fun choices(present: Set<MediaSource>, chosen: Set<MediaSource> = emptySet()): List<MediaSource> =
            (FIXED + present + chosen).distinct().sorted()

        /**
         * The same as [choices], with photos per source in [counts], the most photos first (ties in order): the pills
         * of Settings › Backup and Show in the app.
         */
        fun byCount(counts: Map<MediaSource, Int>, chosen: Set<MediaSource> = emptySet()): List<MediaSource> =
            choices(counts.keys, chosen).sortedByDescending { counts[it] ?: 0 }
    }
}

/** A position on Earth in degrees, as GPS writes it. */
data class GeoPoint(val latitude: Double, val longitude: Double)

/** The folders Android makes at the top of shared storage, which many apps share: the folder inside says more. */
private val SHARED_FOLDERS = setOf(
    "dcim", "pictures", "movies", "download", "documents", "music", "recordings", "podcasts", "audiobooks",
    "ringtones", "alarms", "notifications",
)

/**
 * Apps that write other apps' files: Samsung Smart Switch and Google's restore (copying from an old phone), the files
 * app, the shell, MediaStore and the download manager. The file's own folder says more than they do.
 */
private val MOVER_APPS = setOf(
    "com.sec.android.easymover", "com.google.android.apps.restore", "com.android.externalstorage", "com.android.shell",
    "com.android.providers.media", "com.android.providers.media.module", "com.google.android.providers.media.module",
    "com.android.providers.downloads", "com.sec.android.app.myfiles", "com.google.android.documentsui",
    "com.android.documentsui",
)

/** Whether a file's owner app ([ownerPackage], MediaStore's OWNER_PACKAGE_NAME) tells where it came from. */
internal fun isSourceApp(ownerPackage: String?): Boolean = ownerPackage != null && ownerPackage.lowercase() !in MOVER_APPS

/**
 * The source of a file in [relativePath], Android's folder of it inside shared storage ("DCIM/Camera/",
 * "Movies/Petcube/", "Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Images/"). A folder inside DCIM, Pictures,
 * Movies, Download and the other shared folders is a source by its name (so Pictures/Telegram and Download/Telegram
 * are one, "Telegram"); DCIM/Camera and files right in DCIM are Camera. A file right in a shared folder, or in an
 * app's own folder under Android/, is the source of [appName], the app that saved it (its label, when it
 * [isSourceApp]), else Other. Screenshots and WhatsApp are told anywhere in the path, as the server does.
 */
internal fun sourceOfFolder(relativePath: String?, appName: String? = null): MediaSource {
    val byApp = appName?.let(MediaSource::folder) ?: MediaSource.OTHER
    val path = relativePath ?: return byApp
    val lower = path.lowercase()
    if ("screenshot" in lower || "screenrecord" in lower || "screen record" in lower) return MediaSource.SCREENSHOTS
    if ("whatsapp" in lower) return MediaSource.WHATSAPP
    val parts = path.split("/").filter { it.isNotBlank() }
    val top = parts.firstOrNull()?.lowercase() ?: return byApp
    return when {
        top == "dcim" -> parts.getOrNull(1)?.let(MediaSource::folder) ?: MediaSource.CAMERA
        top in SHARED_FOLDERS -> parts.getOrNull(1)?.let(MediaSource::folder) ?: byApp
        top == "android" -> byApp
        else -> MediaSource.folder(parts[0])
    }
}

/** What an iPhone's own camera writes: HEIC/HEIF or JPEG photos, ProRAW DNG, QuickTime videos. */
private val CAMERA_TYPES = setOf("image/heic", "image/heif", "image/jpeg", "image/x-adobe-dng", "image/dng", "video/quicktime")

/**
 * The source of an iOS photo, which has no folder: PhotoKit marks screenshots and screen recordings
 * ([isScreenshot]); WhatsApp saves into an album named "WhatsApp" ([albums], the titles of the regular albums it is
 * in); a photo synced from a computer or in a shared album isn't [inUserLibrary]. Of the rest, a file the camera can't
 * have written (a PNG, GIF, WebP or MP4 saved from an app) is Other; a JPEG saved from Safari can't be told apart and
 * counts as Camera. Unknown [originalMimeType] (not read yet) counts as Camera too, as before it was read. What would
 * be Other but is in exactly one album (often one an app made, like "Petcube") is that album's folder; Camera photos
 * stay Camera whatever albums they are filed in.
 */
internal fun sourceOfAsset(isScreenshot: Boolean, albums: List<String>, inUserLibrary: Boolean, originalMimeType: String?): MediaSource =
    when {
        isScreenshot -> MediaSource.SCREENSHOTS
        albums.any { it.equals(WHATSAPP_ALBUM, ignoreCase = true) } -> MediaSource.WHATSAPP
        inUserLibrary && (originalMimeType == null || originalMimeType.lowercase() in CAMERA_TYPES) -> MediaSource.CAMERA
        else -> albums.singleOrNull()?.let(MediaSource::folder) ?: MediaSource.OTHER
    }

/** The album WhatsApp saves into on iOS. */
internal const val WHATSAPP_ALBUM = "WhatsApp"

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
    /** For a photo not in the library: an original an earlier version downloaded on request and kept in the app's storage. */
    val keptOriginal: String? = null,
    /** The user marked it private on the server; shown only while "Show private media" is on. */
    val isPrivate: Boolean = false,
    /**
     * How good it looks, about 1 to 10 (the server's aesthetic score: 4 to 5 an ordinary snapshot, 6 and more a good
     * photo); null until scored, or for a photo the server doesn't have.
     */
    val aesthetic: Float? = null,
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
