package com.chencohensolution.journal.server

/** How a preview the server makes is encoded (journal-server-go docs/media.md, `fmt`). */
enum class PreviewFormat(internal val query: String, internal val extension: String) {
    WEBP("webp", "webp"),

    /** HEVC in HEIF. */
    HEIF("heif", "heif"),

    /** AV1 in HEIF: the smallest at the same quality. */
    AVIF("avif", "avif"),

    /** The most detail at the same size; decoded by a library the app ships (Android has no decoder of its own). */
    JPEG_XL("jxl", "jxl"),
    JPEG("jpeg", "jpg"),
}

/** How much detail a preview keeps against how small it is (`q`). */
enum class PreviewQuality(internal val query: String) { LOW("low"), MEDIUM("medium"), HIGH("high"), MAX("max") }

/**
 * How this device shows photos that are only on the server (Settings → Storage on this phone): previews the server
 * makes at the exact size drawn, or, with [enabled] off, the originals themselves, downloaded into the same cache.
 * The first image, shown before a large photo's sharp preview and while a list moves fast, is set in Settings →
 * Advanced: [firstFormat] at [firstWidthPercent] of the window's width (0: none, the sharp preview only).
 *
 * A video's thumbnail is its very first frame. With [videoFramesOnPhone] (off by default) this phone decodes it itself,
 * from the first frame cut out of the video by the server (`GET /media/{sha256}/frame`, about a keyframe: 0.3 to 2 MB
 * for 4K), instead of the server's preview of it (tens of kilobytes): more data, no decoding on the server.
 */
data class PreviewOptions(
    val enabled: Boolean = true,
    val format: PreviewFormat = PreviewFormat.WEBP,
    val quality: PreviewQuality = PreviewQuality.HIGH,
    /** HEIF by default: most Android phones decode it on their video chip. */
    val firstFormat: PreviewFormat = PreviewFormat.HEIF,
    val firstWidthPercent: Int = DEFAULT_FIRST_WIDTH_PERCENT,
    val videoFramesOnPhone: Boolean = false,
) {
    companion object {
        const val DEFAULT_FIRST_WIDTH_PERCENT = 50

        /** The sizes Settings offers for the first image; 0 is none. */
        val FIRST_WIDTH_OPTIONS = listOf(0, 25, 33, 50, 75)
    }
}
