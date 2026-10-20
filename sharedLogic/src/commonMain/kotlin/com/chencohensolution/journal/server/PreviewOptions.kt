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
 */
data class PreviewOptions(
    val enabled: Boolean = true,
    val format: PreviewFormat = PreviewFormat.WEBP,
    val quality: PreviewQuality = PreviewQuality.HIGH,
)
