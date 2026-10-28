package com.chencohensolution.journal.backup

import com.awxkee.jxlcoder.JxlCoder

/**
 * JPEG photos as JPEG XL, losslessly, with libjxl (through the app's JPEG XL library, jxl-coder): the JPEG's own
 * compressed data packed again in about a fifth less space, with what libjxl needs to give back the very same JPEG,
 * byte for byte (EXIF and all). The server does the same (journal-server-go docs/media.md "JPEG XL").
 */
object JpegXl {
    /**
     * [jpeg] packed as JPEG XL, once unpacking it again gave the very same bytes; null when libjxl can't pack it (some
     * JPEGs, such as grayscale ones, it can't), or packing saves nothing.
     */
    fun pack(jpeg: ByteArray): ByteArray? {
        val jxl = runCatching { JxlCoder.Convenience.construct(jpeg) }.getOrNull() ?: return null
        if (jxl.size >= jpeg.size) return null
        return jxl.takeIf { unpack(it)?.contentEquals(jpeg) == true }
    }

    /** The JPEG [jxl] was packed from, byte for byte; null when it isn't JPEG XL or wasn't packed from a JPEG. */
    fun unpack(jxl: ByteArray): ByteArray? =
        if (!isJpegXl(jxl)) null else runCatching { JxlCoder.Convenience.reconstructJPEG(jxl) }.getOrNull()?.takeIf { it.isNotEmpty() }

    fun isJpegXl(bytes: ByteArray): Boolean = runCatching { JxlCoder.isJXL(bytes) }.getOrDefault(false)
}
