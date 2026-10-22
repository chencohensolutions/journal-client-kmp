package com.chencohensolution.journal.ui.library

import android.content.Context
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.server.ServerPreviews
import java.io.File
import java.lang.reflect.Modifier
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The info panel's "Camera details" and "Video details": read from the original on this phone (the library's file, an
// original kept by an earlier version, or the one the viewer downloaded into the preview cache), never from the server.

/** An original's metadata: the usual facts ([title], [subtitle], [chips]) and every tag, by section. */
internal class OriginalDetails(
    val title: String?,
    val subtitle: String?,
    val chips: List<String>,
    val sections: List<DetailSection>,
) {
    val count: Int get() = sections.sumOf { it.rows.size }
}

internal class DetailSection(val name: String, val rows: List<Pair<String, String>>)

/**
 * [item]'s details, read from its original on this phone; null when the original isn't here (yet), and empty when
 * it has nothing to show. Read only, off the main thread.
 */
internal suspend fun readOriginalDetails(context: Context, previews: ServerPreviews?, item: MediaItem): OriginalDetails? =
    withContext(Dispatchers.IO) {
        val original = findOriginal(previews, item) ?: return@withContext null
        runCatching {
            if (item.type == MediaType.VIDEO) {
                videoDetails(
                    { extractor -> original.fold({ extractor.setDataSource(context, it, null) }, { extractor.setDataSource(it.path) }) },
                    { retriever -> original.fold({ retriever.setDataSource(context, it) }, { retriever.setDataSource(it.path) }) },
                )
            } else {
                val stream = original.fold({ context.contentResolver.openInputStream(it) }, { it.inputStream() }) ?: return@runCatching null
                stream.buffered().use { photoDetails(ExifInterface(it)) }
            }
        }.getOrNull() ?: OriginalDetails(null, null, emptyList(), emptyList())
    }

/**
 * The library's file (a content URI), or the kept or downloaded original (a file); null when it isn't on this phone.
 * Only read, as PhotoSafetyTest wants: a library photo's GPS tags are hidden by Android here (the map above them comes
 * from PhotoPlaces, which may read them).
 */
private suspend fun findOriginal(previews: ServerPreviews?, item: MediaItem): Either<Uri, File>? {
    if (item.isOnDevice) return runCatching { item.contentUri() }.getOrNull()?.let { Either.Left(it) }
    val path = item.keptOriginal ?: item.sha256?.let { previews?.cachedOriginal(it) } ?: return null
    return Either.Right(File(path))
}

private sealed interface Either<out L, out R> {
    class Left<L>(val value: L) : Either<L, Nothing>
    class Right<R>(val value: R) : Either<Nothing, R>
}

private inline fun <L, R, T> Either<L, R>.fold(left: (L) -> T, right: (R) -> T): T = when (this) {
    is Either.Left -> left(value)
    is Either.Right -> right(value)
}

// Photos: every tag ExifInterface knows, by section.

/** Every tag name ExifInterface knows (its public TAG_ constants), in declaration order, once each. */
private val exifTags: List<String> by lazy {
    ExifInterface::class.java.fields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java && it.name.startsWith("TAG_") }
        .mapNotNull { it.get(null) as? String }
        .distinct()
}

/** Tags that are bytes or layout, not facts; and GPS parts shown as one latitude and longitude. */
private val skippedTags = setOf(
    "MakerNote", "UserComment", "Xmp", "ThumbnailImage", "JPEGInterchangeFormat", "JPEGInterchangeFormatLength",
    "StripOffsets", "StripByteCounts", "RowsPerStrip", "ThumbnailImageLength", "ThumbnailImageWidth",
    "ThumbnailOrientation", "NewSubfileType", "SubfileType", "SubIFDPointer", "ExifIFDPointer", "GPSInfoIFDPointer",
    "InteroperabilityIFDPointer", "CameraSettingsIFDPointer", "ImageProcessingIFDPointer", "OECF",
    "SpatialFrequencyResponse", "DeviceSettingDescription", "CFAPattern", "GPSLatitude", "GPSLatitudeRef",
    "GPSLongitude", "GPSLongitudeRef", "GPSAltitude", "GPSAltitudeRef", "GPSVersionID",
)

private val cameraTags = setOf(
    "Make", "Model", "LensMake", "LensModel", "LensSpecification", "LensSerialNumber", "BodySerialNumber", "Software",
    "CameraOwnerName", "Artist", "Copyright", "ImageDescription", "ImageUniqueID",
)

private val exposureTags = setOf(
    "ExposureTime", "FNumber", "PhotographicSensitivity", "ISOSpeedRatings", "ISOSpeed", "SensitivityType",
    "StandardOutputSensitivity", "RecommendedExposureIndex", "ExposureIndex", "FocalLength", "FocalLengthIn35mmFilm",
    "ExposureProgram", "ExposureBiasValue", "ExposureMode", "MeteringMode", "Flash", "FlashEnergy", "WhiteBalance",
    "LightSource", "ApertureValue", "ShutterSpeedValue", "BrightnessValue", "MaxApertureValue", "SubjectDistance",
    "SubjectDistanceRange", "SubjectArea", "SubjectLocation", "SceneCaptureType", "SceneType", "SensingMethod",
    "DigitalZoomRatio", "Contrast", "Saturation", "Sharpness", "GainControl", "CustomRendered", "FileSource",
)

private val dateTags = setOf(
    "DateTime", "DateTimeOriginal", "DateTimeDigitized", "OffsetTime", "OffsetTimeOriginal", "OffsetTimeDigitized",
    "SubSecTime", "SubSecTimeOriginal", "SubSecTimeDigitized",
)

private fun sectionOf(tag: String): String = when {
    tag.startsWith("GPS") -> "GPS"
    tag in cameraTags -> "Camera"
    tag in exposureTags -> "Exposure"
    tag in dateTags -> "Date"
    tag.startsWith("Exif") || tag.startsWith("FlashpixVersion") || tag.startsWith("Interoperability") -> "Other"
    else -> "Image"
}

private val sectionOrder = listOf("Camera", "Exposure", "Image", "Date", "GPS", "Other")

private val tagLabels = mapOf(
    "FNumber" to "F-number",
    "PhotographicSensitivity" to "ISO",
    "ISOSpeedRatings" to "ISO",
    "FocalLengthIn35mmFilm" to "Focal length (35 mm)",
    "DateTime" to "Modified",
    "DateTimeOriginal" to "Taken",
    "DateTimeDigitized" to "Digitized",
    "OffsetTime" to "Time zone (modified)",
    "OffsetTimeOriginal" to "Time zone",
    "OffsetTimeDigitized" to "Time zone (digitized)",
    "ImageLength" to "Image height",
    "PixelXDimension" to "Width",
    "PixelYDimension" to "Height",
    "ExposureBiasValue" to "Exposure bias",
    "MaxApertureValue" to "Max aperture",
    "ImageUniqueID" to "Unique image ID",
    "YCbCrPositioning" to "YCbCr positioning",
    "YCbCrSubSampling" to "YCbCr subsampling",
    "YCbCrCoefficients" to "YCbCr coefficients",
    "GPSTimeStamp" to "Time (UTC)",
    "GPSDateStamp" to "Date (UTC)",
)

/** "ExposureTime" → "Exposure time", "GPSImgDirection" → "Img direction"; acronyms kept. */
private fun labelOf(tag: String): String {
    tagLabels[tag]?.let { return it }
    val name = tag.removePrefix("GPS")
    val words = Regex("[A-Z]+(?![a-z])|[A-Z]?[a-z]+|\\d+").findAll(name).map { it.value }.toList()
    return words.mapIndexed { i, word ->
        when {
            word.length > 1 && word.all { it.isUpperCase() } -> word
            i == 0 -> word.replaceFirstChar { it.uppercase() }
            else -> word.lowercase()
        }
    }.joinToString(" ").ifEmpty { tag }
}

private fun ExifInterface.number(tag: String): Double? = getAttributeDouble(tag, Double.NaN).takeUnless { it.isNaN() }

private fun ExifInterface.int(tag: String): Int? = getAttributeInt(tag, Int.MIN_VALUE).takeUnless { it == Int.MIN_VALUE }

/** "1.68", "24", "6.9": no trailing zeros. */
private fun short(value: Double, decimals: Int = 2): String =
    String.format(Locale.ROOT, "%.${decimals}f", value).trimEnd('0').trimEnd('.')

private fun exposureText(seconds: Double): String =
    if (seconds > 0 && seconds < 1) "1/${(1 / seconds).roundToInt()} s" else "${short(seconds, 1)} s"

private fun biasText(ev: Double): String = if (abs(ev) < 0.005) "0 EV" else String.format(Locale.ROOT, "%+.1f EV", ev)

private val exposurePrograms = listOf(
    "Not defined", "Manual", "Normal program", "Aperture priority", "Shutter priority", "Creative program",
    "Action program", "Portrait mode", "Landscape mode",
)
private val meteringModes = mapOf(
    0 to "Unknown", 1 to "Average", 2 to "Center-weighted average", 3 to "Spot", 4 to "Multi-spot", 5 to "Pattern",
    6 to "Partial", 255 to "Other",
)
private val orientations = mapOf(
    1 to "Normal", 2 to "Mirrored", 3 to "Rotated 180°", 4 to "Mirrored and rotated 180°", 5 to "Mirrored and rotated 90°",
    6 to "Rotated 90°", 7 to "Mirrored and rotated 270°", 8 to "Rotated 270°",
)
private val sensingMethods = mapOf(
    1 to "Not defined", 2 to "One-chip color area", 3 to "Two-chip color area", 4 to "Three-chip color area",
    5 to "Color sequential area", 7 to "Trilinear", 8 to "Color sequential linear",
)

/** The value shown for [tag]: well-known ones in words and units, the rest as written; null when there's nothing to show. */
private fun ExifInterface.valueOf(tag: String): String? {
    val raw = getAttribute(tag)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    return when (tag) {
        "ExposureTime" -> number(tag)?.let(::exposureText)
        "FNumber" -> number(tag)?.let { "f/${short(it)}" }
        "FocalLength" -> number(tag)?.let { mm ->
            val full = int("FocalLengthIn35mmFilm")?.takeIf { it > 0 }
            "${short(mm, 1)} mm" + (full?.let { " ($it mm in 35 mm)" } ?: "")
        }
        "FocalLengthIn35mmFilm" -> "$raw mm"
        "ExposureBiasValue" -> number(tag)?.let(::biasText)
        "ApertureValue", "ShutterSpeedValue", "BrightnessValue", "MaxApertureValue" -> number(tag)?.let { "${short(it)} EV" }
        "SubjectDistance" -> number(tag)?.let { "${short(it)} m" }
        "ExposureProgram" -> int(tag)?.let { exposurePrograms.getOrNull(it) } ?: raw
        "MeteringMode" -> int(tag)?.let { meteringModes[it] } ?: raw
        "Flash" -> int(tag)?.let { if (it and 1 == 1) "Fired" else "Did not fire" } ?: raw
        "WhiteBalance" -> if (raw == "0") "Auto" else "Manual"
        "ExposureMode" -> listOf("Auto", "Manual", "Auto bracket").getOrNull(int(tag) ?: -1) ?: raw
        "SceneCaptureType" -> listOf("Standard", "Landscape", "Portrait", "Night").getOrNull(int(tag) ?: -1) ?: raw
        "SubjectDistanceRange" -> listOf("Unknown", "Macro", "Close", "Distant").getOrNull(int(tag) ?: -1) ?: raw
        "SensingMethod" -> int(tag)?.let { sensingMethods[it] } ?: raw
        "SceneType" -> if (raw == "1") "Directly photographed" else raw
        "Orientation" -> int(tag)?.let { orientations[it] } ?: raw
        "ColorSpace" -> when (raw) {
            "1" -> "sRGB"
            "65535" -> "Uncalibrated"
            else -> raw
        }
        "ResolutionUnit" -> when (raw) {
            "2" -> "Inches"
            "3" -> "Centimeters"
            else -> raw
        }
        "YCbCrPositioning" -> when (raw) {
            "1" -> "Centered"
            "2" -> "Co-sited"
            else -> raw
        }
        "ImageWidth", "ImageLength", "PixelXDimension", "PixelYDimension" -> "$raw px"
        "GPSSpeed" -> number(tag)?.let { speed ->
            val unit = when (getAttribute("GPSSpeedRef")) {
                "M" -> "mph"
                "N" -> "knots"
                else -> "km/h"
            }
            "${short(speed, 1)} $unit"
        }
        "GPSImgDirection", "GPSDestBearing", "GPSTrack" -> number(tag)?.let { "${short(it, 1)}°" }
        else -> readable(raw)
    }
}

/** A value as written, with rationals ("72/1") as numbers; null when it is bytes or too long to read. */
private fun readable(raw: String): String? {
    if (raw.length > 120 || raw.any { it.isISOControl() }) return null
    return Regex("""(\d+)/(\d+)""").replace(raw) { match ->
        val (n, d) = match.destructured
        if (d.toLong() == 0L) match.value else short(n.toDouble() / d.toDouble())
    }
}

private fun photoDetails(exif: ExifInterface): OriginalDetails {
    val rows = exifTags
        .filter { it !in skippedTags && !it.endsWith("Ref") }
        .mapNotNull { tag -> exif.valueOf(tag)?.let { tag to it } }
    val gps = buildList {
        exif.latLong?.let { (lat, lon) ->
            add("Latitude" to String.format(Locale.ROOT, "%.5f° %s", abs(lat), if (lat >= 0) "N" else "S"))
            add("Longitude" to String.format(Locale.ROOT, "%.5f° %s", abs(lon), if (lon >= 0) "E" else "W"))
        }
        if (exif.getAttribute(ExifInterface.TAG_GPS_ALTITUDE) != null) {
            val meters = exif.getAltitude(0.0)
            add("Altitude" to "${meters.roundToInt().let(::abs)} m ${if (meters >= 0) "above" else "below"} sea level")
        }
    }
    val bySection = rows.groupBy({ sectionOf(it.first) }, { labelOf(it.first) to it.second })
        .mapValues { (_, list) -> list.distinctBy { it.first } }
        .toMutableMap()
    if (gps.isNotEmpty()) bySection["GPS"] = gps + bySection["GPS"].orEmpty()
    val sections = sectionOrder.mapNotNull { name -> bySection[name]?.let { DetailSection(name, it) } }

    val make = exif.getAttribute(ExifInterface.TAG_MAKE)?.trim().orEmpty()
    val model = exif.getAttribute(ExifInterface.TAG_MODEL)?.trim().orEmpty()
    val title = when {
        model.isEmpty() -> make
        model.startsWith(make, ignoreCase = true) -> model
        else -> "$make $model"
    }.ifEmpty { null }
    val chips = listOfNotNull(
        exif.int(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY)?.takeIf { it > 0 }?.let { "ISO $it" },
        exif.int(ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM)?.takeIf { it > 0 }?.let { "$it mm" }
            ?: exif.number(ExifInterface.TAG_FOCAL_LENGTH)?.let { "${short(it, 1)} mm" },
        exif.number(ExifInterface.TAG_F_NUMBER)?.let { "f/${short(it)}" },
        exif.number(ExifInterface.TAG_EXPOSURE_TIME)?.let(::exposureText),
        exif.number(ExifInterface.TAG_EXPOSURE_BIAS_VALUE)?.let(::biasText),
    )
    return OriginalDetails(title, exif.getAttribute(ExifInterface.TAG_LENS_MODEL)?.trim(), chips, sections)
}

// Videos: the tracks' formats (MediaExtractor) and the file's metadata (MediaMetadataRetriever).

private val videoCodecs = mapOf(
    MediaFormat.MIMETYPE_VIDEO_HEVC to ("HEVC" to "HEVC (H.265)"),
    MediaFormat.MIMETYPE_VIDEO_AVC to ("H.264" to "H.264 (AVC)"),
    MediaFormat.MIMETYPE_VIDEO_AV1 to ("AV1" to "AV1"),
    MediaFormat.MIMETYPE_VIDEO_VP9 to ("VP9" to "VP9"),
    MediaFormat.MIMETYPE_VIDEO_VP8 to ("VP8" to "VP8"),
    MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION to ("Dolby Vision" to "Dolby Vision"),
    MediaFormat.MIMETYPE_VIDEO_MPEG4 to ("MPEG-4" to "MPEG-4 Part 2"),
    MediaFormat.MIMETYPE_VIDEO_H263 to ("H.263" to "H.263"),
)

private val audioCodecs = mapOf(
    MediaFormat.MIMETYPE_AUDIO_AAC to "AAC",
    MediaFormat.MIMETYPE_AUDIO_OPUS to "Opus",
    MediaFormat.MIMETYPE_AUDIO_AMR_NB to "AMR-NB",
    MediaFormat.MIMETYPE_AUDIO_AMR_WB to "AMR-WB",
    MediaFormat.MIMETYPE_AUDIO_MPEG to "MP3",
    MediaFormat.MIMETYPE_AUDIO_FLAC to "FLAC",
    MediaFormat.MIMETYPE_AUDIO_VORBIS to "Vorbis",
    MediaFormat.MIMETYPE_AUDIO_AC3 to "Dolby Digital",
    MediaFormat.MIMETYPE_AUDIO_EAC3 to "Dolby Digital Plus",
    MediaFormat.MIMETYPE_AUDIO_RAW to "PCM",
)

private fun profileName(mime: String, profile: Int): String? = when (mime) {
    MediaFormat.MIMETYPE_VIDEO_HEVC -> when (profile) {
        CodecProfileLevel.HEVCProfileMain -> "Main"
        CodecProfileLevel.HEVCProfileMain10, CodecProfileLevel.HEVCProfileMain10HDR10,
        CodecProfileLevel.HEVCProfileMain10HDR10Plus -> "Main 10"
        CodecProfileLevel.HEVCProfileMainStill -> "Main Still"
        else -> null
    }
    MediaFormat.MIMETYPE_VIDEO_AVC -> when (profile) {
        CodecProfileLevel.AVCProfileBaseline, CodecProfileLevel.AVCProfileConstrainedBaseline -> "Baseline"
        CodecProfileLevel.AVCProfileMain -> "Main"
        CodecProfileLevel.AVCProfileHigh, CodecProfileLevel.AVCProfileConstrainedHigh -> "High"
        CodecProfileLevel.AVCProfileHigh10 -> "High 10"
        else -> null
    }
    MediaFormat.MIMETYPE_VIDEO_AV1 -> when (profile) {
        CodecProfileLevel.AV1ProfileMain8 -> "Main"
        CodecProfileLevel.AV1ProfileMain10, CodecProfileLevel.AV1ProfileMain10HDR10,
        CodecProfileLevel.AV1ProfileMain10HDR10Plus -> "Main 10"
        else -> null
    }
    else -> null
}

private fun MediaFormat.int(key: String): Int? = if (containsKey(key)) runCatching { getNumber(key)?.toInt() }.getOrNull() else null

private fun MediaFormat.float(key: String): Float? = if (containsKey(key)) runCatching { getNumber(key)?.toFloat() }.getOrNull() else null

/** "4K", "1080p": by the shorter side, as video is named. */
private fun resolutionName(width: Int, height: Int): String? {
    val short = minOf(width, height)
    return when {
        short >= 4320 -> "8K"
        short >= 2160 -> "4K"
        short <= 0 -> null
        else -> "${short}p"
    }
}

private fun bitrateText(bitsPerSecond: Long): String = when {
    bitsPerSecond >= 1_000_000 -> "${short(bitsPerSecond / 1_000_000.0, 1)} Mb/s"
    else -> "${(bitsPerSecond / 1000.0).roundToInt()} kb/s"
}

private fun channelsText(channels: Int): String = when (channels) {
    1 -> "mono"
    2 -> "stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$channels channels"
}

/** "20261012T064105.000Z" → "2026-10-12 06:41:05 UTC". */
private fun videoDate(value: String): String {
    val match = Regex("""^(\d{4})(\d{2})(\d{2})T(\d{2})(\d{2})(\d{2})""").find(value) ?: return value
    val (y, mo, d, h, mi, s) = match.destructured
    return "$y-$mo-$d $h:$mi:$s UTC"
}

/** Where the frame rate goes in the title and chips, filled in once the capture rate is known. */
private const val FPS_MARK = "\u0000fps"

private fun videoDetails(openExtractor: (MediaExtractor) -> Unit, openRetriever: (MediaMetadataRetriever) -> Unit): OriginalDetails {
    val video = mutableListOf<Pair<String, String>>()
    val audio = mutableListOf<Pair<String, String>>()
    val file = mutableListOf<Pair<String, String>>()
    var title = listOf<String>()
    // The rate the camera recorded at, when the file says (its average can be lower: 56 for a 60 fps clip).
    var frameRate: Float? = null
    val subtitle = mutableListOf<String>()
    val chips = mutableListOf<String>()
    val trackKinds = mutableListOf<String>()

    val extractor = MediaExtractor()
    try {
        openExtractor(extractor)
        for (i in 0 until extractor.trackCount) {
            val format = extractor.getTrackFormat(i)
            val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
            when {
                mime.startsWith("video/") && video.isEmpty() -> {
                    trackKinds += "Video"
                    val (shortName, longName) = videoCodecs[mime] ?: (mime.removePrefix("video/") to mime.removePrefix("video/"))
                    val profile = format.int(MediaFormat.KEY_PROFILE)?.let { profileName(mime, it) }
                    video += "Codec" to (longName + (profile?.let { ", $it profile" } ?: ""))
                    val width = format.int(MediaFormat.KEY_WIDTH) ?: 0
                    val height = format.int(MediaFormat.KEY_HEIGHT) ?: 0
                    val resolution = resolutionName(width, height)
                    if (width > 0 && height > 0) video += "Resolution" to "$width × $height" + (resolution?.let { " ($it)" } ?: "")
                    val fps = format.float(MediaFormat.KEY_FRAME_RATE)?.takeIf { it > 0 }
                    fps?.let { video += "Frame rate" to "${short(it.toDouble())} fps" }
                    format.int(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 }?.let { video += "Bitrate" to bitrateText(it.toLong()) }
                    val tenBit = profile == "Main 10" || profile == "High 10"
                    if (tenBit) video += "Bit depth" to "10-bit"
                    val transfer = when (format.int(MediaFormat.KEY_COLOR_TRANSFER)) {
                        MediaFormat.COLOR_TRANSFER_HLG -> "HLG"
                        MediaFormat.COLOR_TRANSFER_ST2084 -> "HDR10 (PQ)"
                        MediaFormat.COLOR_TRANSFER_SDR_VIDEO -> "SDR"
                        MediaFormat.COLOR_TRANSFER_LINEAR -> "Linear"
                        else -> null
                    }
                    val hdr = transfer == "HLG" || transfer == "HDR10 (PQ)" || mime == MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION
                    transfer?.let { video += "Transfer" to it }
                    when (format.int(MediaFormat.KEY_COLOR_STANDARD)) {
                        MediaFormat.COLOR_STANDARD_BT2020 -> "BT.2020"
                        MediaFormat.COLOR_STANDARD_BT709 -> "BT.709"
                        MediaFormat.COLOR_STANDARD_BT601_PAL, MediaFormat.COLOR_STANDARD_BT601_NTSC -> "BT.601"
                        else -> null
                    }?.let { video += "Color primaries" to it }
                    when (format.int(MediaFormat.KEY_COLOR_RANGE)) {
                        MediaFormat.COLOR_RANGE_FULL -> "Full"
                        MediaFormat.COLOR_RANGE_LIMITED -> "Limited"
                        else -> null
                    }?.let { video += "Color range" to it }
                    format.int(MediaFormat.KEY_I_FRAME_INTERVAL)?.let { video += "Key frame interval" to "$it s" }

                    frameRate = fps
                    title = listOfNotNull(shortName, resolution, FPS_MARK)
                    profile?.let { subtitle += "$it profile" }
                    if (hdr) subtitle += transfer ?: "HDR"
                    if (fps != null) chips += FPS_MARK
                    if (hdr) chips += if (tenBit) "10-bit HDR" else "HDR"
                }
                mime.startsWith("audio/") && audio.isEmpty() -> {
                    trackKinds += "Audio"
                    val codec = audioCodecs[mime] ?: mime.removePrefix("audio/")
                    val aacProfile = format.int(MediaFormat.KEY_AAC_PROFILE)
                        ?.takeIf { mime == MediaFormat.MIMETYPE_AUDIO_AAC }
                        ?.let { if (it == CodecProfileLevel.AACObjectLC) "LC" else if (it == CodecProfileLevel.AACObjectHE) "HE" else null }
                    audio += "Codec" to listOfNotNull(codec, aacProfile).joinToString(" ")
                    val channels = format.int(MediaFormat.KEY_CHANNEL_COUNT)
                    channels?.let { audio += "Channels" to "$it (${channelsText(it)})" }
                    val rate = format.int(MediaFormat.KEY_SAMPLE_RATE)
                    rate?.let { audio += "Sample rate" to "${short(it / 1000.0, 1)} kHz" }
                    format.int(MediaFormat.KEY_BIT_RATE)?.takeIf { it > 0 }?.let { audio += "Bitrate" to bitrateText(it.toLong()) }
                    subtitle += "$codec audio"
                    if (rate != null) chips += "${short(rate / 1000.0, 1)} kHz" + (channels?.let { " ${channelsText(it)}" } ?: "")
                }
                else -> trackKinds += if (mime.startsWith("application/")) "Metadata" else mime.substringBefore('/').replaceFirstChar { it.uppercase() }
            }
        }
    } finally {
        extractor.release()
    }

    MediaMetadataRetriever().use { retriever ->
        openRetriever(retriever)
        fun meta(key: Int) = retriever.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
        meta(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)?.let { file += "Container" to it.substringAfter('/').uppercase() }
        meta(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { ms ->
            file += "Duration" to String.format(Locale.ROOT, "%d:%04.1f", ms / 60_000, (ms % 60_000) / 1000.0)
        }
        meta(MediaMetadataRetriever.METADATA_KEY_BITRATE)?.toLongOrNull()?.let {
            file += "Overall bitrate" to bitrateText(it)
            if (video.none { (label, _) -> label == "Bitrate" }) chips.add(minOf(1, chips.size), bitrateText(it))
        }
        meta(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.let { video += "Rotation" to "$it°" }
        if (trackKinds.isNotEmpty()) file += "Tracks" to trackKinds.joinToString(", ")
        meta(MediaMetadataRetriever.METADATA_KEY_DATE)?.takeUnless { it.startsWith("1904") }?.let { file += "Recorded" to videoDate(it) }
        meta(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()?.takeIf { it > 0 }?.let {
            file += "Capture frame rate" to short(it.toDouble())
            frameRate = it
        }
        meta(MediaMetadataRetriever.METADATA_KEY_LOCATION)?.let { file += "Location" to it }
        meta(MediaMetadataRetriever.METADATA_KEY_WRITER)?.let { file += "Writer" to it }
        meta(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { file += "Title" to it }
    }

    val sections = listOf(DetailSection("Video", video), DetailSection("Audio", audio), DetailSection("File", file)).filter { it.rows.isNotEmpty() }
    val fps = frameRate
    title = title.mapNotNull { if (it == FPS_MARK) fps?.let { rate -> "${rate.roundToInt()} fps" } else it }
    val shownChips = chips.mapNotNull { if (it == FPS_MARK) fps?.let { rate -> "${short(rate.toDouble())} fps" } else it }
    return OriginalDetails(title.joinToString(" · ").ifEmpty { null }, subtitle.joinToString(" · ").ifEmpty { null }, shownChips, sections)
}
