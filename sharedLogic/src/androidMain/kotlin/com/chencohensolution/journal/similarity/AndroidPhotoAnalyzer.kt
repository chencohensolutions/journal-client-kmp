package com.chencohensolution.journal.similarity

import android.content.ContentUris
import android.content.Context
import android.graphics.Bitmap
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.local.PhoneModel
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.imageembedder.ImageEmbedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel

/**
 * Reads MediaStore's thumbnail of a photo and runs MediaPipe's image embedder (MobileNet-V3 large, Apache 2.0) on it
 * and on its center zoomed in ([EMBEDDING_ZOOMS]). On the CPU, which MediaPipe runs with XNNPACK: Google measures
 * about 10 ms a run on a Pixel 6, and the GPU isn't faster for this model.
 *
 * The model (10.9 MB) isn't in the app: [model] downloads it from the user's server on first use
 * ([MODEL_FILE], `phoneModels`). Until it is here, photos are read by dHash alone and marked
 * [PhotoAnalysis.withoutModel], to be read again for their embeddings once it is.
 */
internal class AndroidPhotoAnalyzer(context: Context, private val model: PhoneModel) : PhotoAnalyzer {
    private val context = context.applicationContext

    override val modelId = "mediapipe-mobilenet_v3_large-float32"

    override val sameScene = DEFAULT_SAME_SCENE_PERCENT / 100f

    // Made once the model's file is here; until then (or if it can't be loaded) only dHashes are compared.
    @Volatile private var embedder: ImageEmbedder? = null
    @Volatile private var loadFailed = false

    override val embeds: Boolean get() = embedder() != null

    override suspend fun prepare(): Boolean = withContext(Dispatchers.IO) {
        model.fetch() != null && embedder() != null
    }

    @Synchronized
    private fun embedder(): ImageEmbedder? {
        embedder?.let { return it }
        if (loadFailed) return null
        val file = model.path() ?: return null
        return try {
            ImageEmbedder.createFromOptions(
                this.context,
                ImageEmbedder.ImageEmbedderOptions.builder()
                    .setBaseOptions(BaseOptions.builder().setModelAssetBuffer(mapped(file.toFile())).build())
                    .setRunningMode(RunningMode.IMAGE)
                    .setL2Normalize(true)
                    .build(),
            ).also { embedder = it }
        } catch (e: Exception) {
            // A damaged file was caught by its sha256; this is MediaPipe failing on this phone: dHashes only.
            Log.w(TAG, "image embedder unavailable; comparing dHashes only", e)
            loadFailed = true
            null
        }
    }

    /** The model's file mapped into memory: MediaPipe reads a direct buffer as it would its own asset. */
    private fun mapped(file: File): MappedByteBuffer =
        FileInputStream(file).use { it.channel.map(FileChannel.MapMode.READ_ONLY, 0, it.channel.size()) }

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
            val embedder = embedder()
            val embeddings = embedder?.let { model ->
                try {
                    EMBEDDING_ZOOMS.mapNotNull { zoom -> model.embed(bitmap, zoom) }
                } catch (e: Exception) {
                    Log.w(TAG, "embedding failed for ${item.fileName ?: item.id}", e)
                    null
                }
            }.orEmpty()
            PhotoAnalysis(bitmap.toGray(), embeddings, withoutModel = embedder == null && !loadFailed)
        } finally {
            if (bitmap !== thumbnail) bitmap.recycle()
            thumbnail.recycle()
        }
    }

    /** The embedding of [bitmap]'s center zoomed in by [zoom] (1 is the whole photo); MediaPipe scales it to 224 px. */
    private fun ImageEmbedder.embed(bitmap: Bitmap, zoom: Float): FloatArray? {
        val w = (bitmap.width / zoom).toInt()
        val h = (bitmap.height / zoom).toInt()
        val center = if (zoom <= 1f) bitmap else Bitmap.createBitmap(bitmap, (bitmap.width - w) / 2, (bitmap.height - h) / 2, w, h)
        try {
            return embed(BitmapImageBuilder(center).build()).embeddingResult().embeddings().firstOrNull()?.floatEmbedding()
        } finally {
            if (center !== bitmap) center.recycle()
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
         * Checked on the owner's 1,217 story photos with this model and zoom-aware comparison: the same scene scores
         * 0.61–0.90; of photos taken days apart, 0.2% reach 0.60 (median 0.06). The owner can change it in Settings.
         */
        const val DEFAULT_SAME_SCENE_PERCENT = 60
        private const val TAG = "PhotoSimilarity"
        /** The model's name in the server's `phoneModels`; kept in `files/models/`. */
        const val MODEL_FILE = "mobilenet_v3_large.tflite"

        // Big enough that a 2× center still fills the model's 224 px input.
        private const val THUMBNAIL_SIZE = 512
    }
}
