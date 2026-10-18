package com.chencohensolution.journal.similarity

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.chencohensolution.journal.library.MediaItem
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Reads MediaStore's thumbnail of a photo and runs MediaPipe's image embedder on it (MobileNet-V3 small, Apache 2.0,
 * bundled as an asset, on the CPU: a few milliseconds a photo).
 */
internal class AndroidPhotoAnalyzer(context: Context) : PhotoAnalyzer {
    private val context = context.applicationContext

    override val modelId = "mediapipe-mobilenet_v3_small-float32"

    override val sameScene = DEFAULT_SAME_SCENE_PERCENT / 100f

    // Made on first use; null if the model can't be loaded, and then only dHashes are compared.
    private val embedder: ImageEmbedder? by lazy {
        try {
            ImageEmbedder.createFromOptions(
                this.context,
                ImageEmbedder.ImageEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL_ASSET).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setL2Normalize(true)
                    .build(),
            )
        } catch (e: Exception) {
            Log.w(TAG, "image embedder unavailable; comparing dHashes only", e)
            null
        }
    }

    override suspend fun analyze(item: MediaItem): PhotoAnalysis? = withContext(Dispatchers.IO) {
        val id = item.id.toLongOrNull() ?: return@withContext null
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
        val thumbnail = try {
            context.contentResolver.loadThumbnail(uri, Size(THUMBNAIL_SIZE, THUMBNAIL_SIZE), null)
        } catch (e: Exception) {
            Log.w(TAG, "no thumbnail for ${item.fileName ?: item.id}", e)
            return@withContext null
        }
        // Pixels can't be read from a hardware bitmap.
        val bitmap = if (thumbnail.config == Bitmap.Config.ARGB_8888) thumbnail else thumbnail.copy(Bitmap.Config.ARGB_8888, false)
        try {
            val embedding = embedder?.let { model ->
                try {
                    model.embed(BitmapImageBuilder(bitmap).build()).embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
                } catch (e: Exception) {
                    Log.w(TAG, "embedding failed for ${item.fileName ?: item.id}", e)
                    null
                }
            }
            PhotoAnalysis(bitmap.toGray(), embedding)
        } finally {
            if (bitmap !== thumbnail) bitmap.recycle()
            thumbnail.recycle()
        }
    }

    private fun Bitmap.toGray(): GrayImage {
        val argb = IntArray(width * height)
        getPixels(argb, 0, width, 0, 0, width, height)
        val gray = IntArray(argb.size) { i ->
            val pixel = argb[i]
            ((pixel shr 16 and 0xFF) * 299 + (pixel shr 8 and 0xFF) * 587 + (pixel and 0xFF) * 114) / 1000
        }
        return GrayImage(width, height, gray)
    }

    companion object {
        /**
         * Checked on the owner's photos: unrelated ones score up to about 0.2, the same scene 0.7–0.75, shots taken
         * the same second 0.85 and up (85% only ever matched those). The owner can change it in Settings.
         */
        const val DEFAULT_SAME_SCENE_PERCENT = 70
        private const val TAG = "PhotoSimilarity"
        private const val MODEL_ASSET = "mobilenet_v3_small.tflite"
        private const val THUMBNAIL_SIZE = 256
    }
}
