package com.chencohensolution.journal.similarity

import com.chencohensolution.journal.library.MediaItem
import kotlinx.cinterop.DoubleVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceGray
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageCreateWithImageInRect
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.Photos.PHAsset
import platform.Photos.PHImageContentModeAspectFit
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsResizeModeFast
import platform.UIKit.UIImage
import platform.Vision.VNElementTypeDouble
import platform.Vision.VNElementTypeFloat
import platform.Vision.VNFeaturePrintObservation
import platform.Vision.VNGenerateImageFeaturePrintRequest
import platform.Vision.VNImageRequestHandler

/**
 * Asks PhotoKit for a small copy of a photo (never from iCloud) and runs Vision's image feature print on it and on
 * its center zoomed in ([EMBEDDING_ZOOMS]); Vision uses the Neural Engine. The simulator may not run the feature
 * print; then the photo is grouped by time.
 */
@OptIn(ExperimentalForeignApi::class)
internal class IosPhotoAnalyzer : PhotoAnalyzer {
    // The feature print changes between revisions; a new one starts a new cache.
    override val modelId = "vision-featureprint-r${VNGenerateImageFeaturePrintRequest().revision}"

    override val sameScene = DEFAULT_SAME_SCENE_PERCENT / 100f

    override suspend fun analyze(item: MediaItem): PhotoAnalysis? = withContext(Dispatchers.Default) {
        val asset = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(item.id), null).firstObject as? PHAsset
            ?: return@withContext null
        val options = PHImageRequestOptions().apply {
            synchronous = true
            deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
            resizeMode = PHImageRequestOptionsResizeModeFast
            networkAccessAllowed = false
        }
        var image: UIImage? = null
        PHImageManager.defaultManager().requestImageForAsset(
            asset,
            CGSizeMake(THUMBNAIL_SIZE, THUMBNAIL_SIZE),
            PHImageContentModeAspectFit,
            options,
        ) { result, _ -> image = result }
        val cgImage = image?.CGImage ?: return@withContext null
        // The whole photo comes first; without its feature print the zoomed ones can't be compared.
        val whole = featurePrint(cgImage)
        val embeddings = if (whole == null) emptyList() else listOf(whole) + EMBEDDING_ZOOMS.drop(1).mapNotNull { zoomed(cgImage, it) }
        PhotoAnalysis(gray(cgImage) ?: return@withContext null, embeddings)
    }

    private fun gray(image: CGImageRef): GrayImage? {
        val width = CGImageGetWidth(image).toInt()
        val height = CGImageGetHeight(image).toInt()
        if (width == 0 || height == 0) return null
        val bytes = ByteArray(width * height)
        val drawn = bytes.usePinned { pinned ->
            val space = CGColorSpaceCreateDeviceGray()
            val context = CGBitmapContextCreate(
                pinned.addressOf(0), width.convert(), height.convert(), 8u, width.convert(), space,
                CGImageAlphaInfo.kCGImageAlphaNone.value,
            )
            CGColorSpaceRelease(space)
            if (context == null) return@usePinned false
            CGContextDrawImage(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()), image)
            CGContextRelease(context)
            true
        }
        if (!drawn) return null
        return GrayImage(width, height, IntArray(bytes.size) { bytes[it].toInt() and 0xFF })
    }

    /** The feature print of [image]'s center zoomed in by [zoom]. */
    private fun zoomed(image: CGImageRef, zoom: Float): FloatArray? {
        val width = CGImageGetWidth(image).toDouble()
        val height = CGImageGetHeight(image).toDouble()
        val w = width / zoom
        val h = height / zoom
        val center = CGImageCreateWithImageInRect(image, CGRectMake((width - w) / 2, (height - h) / 2, w, h)) ?: return null
        try {
            return featurePrint(center)
        } finally {
            CGImageRelease(center)
        }
    }

    private fun featurePrint(image: CGImageRef): FloatArray? {
        val request = VNGenerateImageFeaturePrintRequest()
        val handler = VNImageRequestHandler(cGImage = image, options = emptyMap<Any?, Any?>())
        if (!handler.performRequests(listOf(request), null)) return null
        val observation = request.results?.firstOrNull() as? VNFeaturePrintObservation ?: return null
        val count = observation.elementCount.toInt()
        val bytes = observation.data.bytes ?: return null
        return when (observation.elementType) {
            VNElementTypeFloat -> bytes.reinterpret<FloatVar>().let { values -> FloatArray(count) { values[it] } }
            VNElementTypeDouble -> bytes.reinterpret<DoubleVar>().let { values -> FloatArray(count) { values[it].toFloat() } }
            else -> null
        }
    }

    companion object {
        /** A starting point, not yet checked on real photos; the owner can change it in Settings. */
        const val DEFAULT_SAME_SCENE_PERCENT = 80
        // Big enough that a 2× center still has detail.
        private const val THUMBNAIL_SIZE = 512.0
    }
}
