package com.chencohensolution.journal.server

/**
 * One preview kept on this device: its pixels, how it was encoded ([format] and [quality]; null for a file named
 * before they could be chosen), whether it was [madeHere] (a video's frame, made on this device), and its size.
 */
data class KeptPreview(
    val width: Int,
    val height: Int,
    val format: PreviewFormat?,
    val quality: PreviewQuality?,
    val madeHere: Boolean,
    val bytes: Long,
)

/** What this device keeps of one server photo or video: its previews (largest first), its original, cached video. */
data class KeptHere(
    val previews: List<KeptPreview>,
    val originalBytes: Long?,
    /** Bytes of a video cached by the player, from parts streamed so far; null for a photo or none. */
    val videoBytes: Long? = null,
) {
    val totalBytes: Long get() = previews.sumOf { it.bytes } + (originalBytes ?: 0) + (videoBytes ?: 0)
    val isEmpty: Boolean get() = previews.isEmpty() && originalBytes == null && videoBytes == null
}

/**
 * Reads a kept preview's name: "1080x1440-contain-high.jxl" (box, fit, quality, format; a video's first frame has
 * "-first" before its quality), "1080x1440-cover.webp" (from
 * before formats could be chosen) or "1920x1080-poster.webp" (made on this device). A preview fitted inside its box
 * ("contain") has the photo's shape; the server never scales up, so neither is larger than the photo.
 */
internal fun keptPreview(kept: ServerPreviews.Kept, photoWidth: Int, photoHeight: Int): KeptPreview? {
    val name = kept.file.substringAfterLast('/').substringAfterLast('\\')
    val extension = name.substringAfterLast('.', "").lowercase()
    val parts = name.substringBeforeLast('.').split('-')
    val box = parts.firstOrNull()?.split('x')?.mapNotNull { it.toIntOrNull() }?.takeIf { it.size == 2 } ?: return null
    val (boxWidth, boxHeight) = box
    val madeHere = parts.getOrNull(1) == "poster"
    val (width, height) = when {
        madeHere || photoWidth <= 0 || photoHeight <= 0 -> boxWidth to boxHeight
        parts.getOrNull(1) == "contain" -> {
            val scale = minOf(boxWidth.toDouble() / photoWidth, boxHeight.toDouble() / photoHeight, 1.0)
            maxOf(1, (photoWidth * scale).toInt()) to maxOf(1, (photoHeight * scale).toInt())
        }
        else -> {
            // Filling the box: the box itself, or its shape at the photo's resolution when the photo is smaller.
            val scale = minOf(photoWidth.toDouble() / boxWidth, photoHeight.toDouble() / boxHeight, 1.0)
            maxOf(1, (boxWidth * scale).toInt()) to maxOf(1, (boxHeight * scale).toInt())
        }
    }
    return KeptPreview(
        width = width,
        height = height,
        format = PreviewFormat.entries.firstOrNull { it.extension == extension } ?: PreviewFormat.JPEG.takeIf { extension == "jpeg" },
        // A video's first frame has "first" before its quality ("640x480-contain-first-high.webp").
        quality = PreviewQuality.entries.firstOrNull { it.query == parts.getOrNull(2) || it.query == parts.getOrNull(3) && parts[2] == "first" },
        madeHere = madeHere,
        bytes = kept.bytes,
    )
}
