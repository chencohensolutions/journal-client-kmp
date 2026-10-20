package com.chencohensolution.journal.storage

import kotlin.math.roundToInt

/** A file's kind as people know it: "HEIC", "JPEG", "DNG" ([raw]), "MP4" ([video]). */
data class MediaKind(val name: String, val video: Boolean = false, val raw: Boolean = false)

/** The kind of a MIME type; null when it has no short name (show the MIME type itself). */
fun mediaKind(mimeType: String): MediaKind? = when (mimeType.lowercase()) {
    "image/heic", "image/heif" -> MediaKind("HEIC")
    "image/jpeg" -> MediaKind("JPEG")
    "image/png" -> MediaKind("PNG")
    "image/webp" -> MediaKind("WebP")
    "image/avif" -> MediaKind("AVIF")
    "image/jxl" -> MediaKind("JPEG XL")
    "image/x-adobe-dng", "image/dng" -> MediaKind("DNG", raw = true)
    "video/mp4" -> MediaKind("MP4", video = true)
    "video/quicktime" -> MediaKind("MOV", video = true)
    "video/3gpp" -> MediaKind("3GP", video = true)
    else -> null
}

/** Megapixels of [width] × [height], rounded; null under one. */
fun megapixels(width: Int, height: Int): Int? = (width.toLong() * height / 1_000_000.0).roundToInt().takeIf { it >= 1 }

/** [part] of [whole] in percent: "3%", "0.4%" (one decimal under 10); null without a whole. */
fun percentOf(part: Long, whole: Long): String? {
    if (whole <= 0) return null
    val percent = part * 100.0 / whole
    if (percent >= 10 || part == 0L) return "${percent.roundToInt()}%"
    val tenths = (percent * 10).roundToInt()
    return "${tenths / 10}.${tenths % 10}%"
}
