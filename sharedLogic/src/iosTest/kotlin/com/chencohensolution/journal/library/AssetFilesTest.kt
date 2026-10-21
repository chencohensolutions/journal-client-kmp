package com.chencohensolution.journal.library

import platform.Photos.PHAssetResourceTypeAdjustmentData
import platform.Photos.PHAssetResourceTypeAlternatePhoto
import platform.Photos.PHAssetResourceTypeFullSizePairedVideo
import platform.Photos.PHAssetResourceTypeFullSizePhoto
import platform.Photos.PHAssetResourceTypeFullSizeVideo
import platform.Photos.PHAssetResourceTypePairedVideo
import platform.Photos.PHAssetResourceTypePhoto
import platform.Photos.PHAssetResourceTypePhotoProxy
import platform.Photos.PHAssetResourceTypeVideo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

private val MIME = mapOf(
    "public.heic" to "image/heic",
    "public.jpeg" to "image/jpeg",
    "com.adobe.raw-image" to "image/x-adobe-dng",
    "com.apple.quicktime-movie" to "video/quicktime",
    "com.apple.adjustmentdata" to null,
)

private fun facts(vararg files: AssetFile, video: Boolean = false) = factsOf(files.toList(), video, 1_000) { MIME[it] }

class AssetFilesTest {
    @Test
    fun aLivePhotoIsItsPhoto() {
        val live = facts(
            AssetFile(PHAssetResourceTypePhoto, "IMG_0001.HEIC", "public.heic", 2_400_000),
            AssetFile(PHAssetResourceTypePairedVideo, "IMG_0001.MOV", "com.apple.quicktime-movie", 3_100_000),
        )
        assertEquals("IMG_0001.HEIC", live.fileName)
        assertEquals(2_400_000, live.sizeBytes)
        assertEquals("image/heic", live.mimeType)
        assertEquals(1_000, live.modifiedMillis)
    }

    @Test
    fun anEditIsItsRenderUnderTheOriginalsName() {
        val edited = facts(
            AssetFile(PHAssetResourceTypePhoto, "IMG_0002.HEIC", "public.heic", 2_400_000),
            AssetFile(PHAssetResourceTypeAdjustmentData, "Adjustments.plist", "com.apple.adjustmentdata", 900),
            AssetFile(PHAssetResourceTypeFullSizePhoto, "FullSizeRender.jpg", "public.jpeg", 1_800_000),
            AssetFile(PHAssetResourceTypePairedVideo, "IMG_0002.MOV", "com.apple.quicktime-movie", 3_100_000),
            AssetFile(PHAssetResourceTypeFullSizePairedVideo, "FullSizeRender.mov", "com.apple.quicktime-movie", 2_900_000),
        )
        assertEquals("IMG_0002.jpg", edited.fileName)
        assertEquals(1_800_000, edited.sizeBytes)
        assertEquals("image/jpeg", edited.mimeType)
        assertEquals("image/heic", edited.originalMimeType)

        val video = facts(
            AssetFile(PHAssetResourceTypeVideo, "IMG_0003.MOV", "com.apple.quicktime-movie", 40_000_000),
            AssetFile(PHAssetResourceTypeFullSizeVideo, "FullSizeRender.mov", "com.apple.quicktime-movie", 12_000_000),
            video = true,
        )
        assertEquals("IMG_0003.mov", video.fileName)
        assertEquals(12_000_000, video.sizeBytes)
    }

    @Test
    fun rawAndJpegIsItsJpeg() {
        val pair = facts(
            AssetFile(PHAssetResourceTypePhoto, "IMG_0004.JPG", "public.jpeg", 3_000_000),
            AssetFile(PHAssetResourceTypeAlternatePhoto, "IMG_0004.DNG", "com.adobe.raw-image", 25_000_000),
        )
        assertEquals("IMG_0004.JPG", pair.fileName)
        assertEquals("image/jpeg", pair.mimeType)
    }

    @Test
    fun aPhotoStillProcessingHasNoFileYet() {
        val proxy = facts(AssetFile(PHAssetResourceTypePhotoProxy, "IMG_0005.JPG", "public.jpeg", 900_000))
        assertNull(proxy.fileName)
        assertNull(proxy.sizeBytes)
        assertEquals(1_000, proxy.assetModifiedMillis)
        // A size PhotoKit doesn't know is unknown, not empty: backup skips it.
        assertNull(facts(AssetFile(PHAssetResourceTypePhoto, "IMG_0006.HEIC", "public.heic", 0)).sizeBytes)
    }

    @Test
    fun utTypesGiveMimeTypes() {
        val mime = { uti: String -> factsOf(listOf(AssetFile(PHAssetResourceTypePhoto, "a", uti, 1)), false, null, ::mimeTypeOf).mimeType }
        assertEquals("image/heic", mime("public.heic"))
        assertEquals("image/jpeg", mime("public.jpeg"))
        assertEquals("image/png", mime("public.png"))
        assertEquals("video/quicktime", mime("com.apple.quicktime-movie"))
        assertEquals("video/mp4", mime("public.mpeg-4"))
        assertEquals("image/x-adobe-dng", mime("com.adobe.raw-image"))
    }
}
