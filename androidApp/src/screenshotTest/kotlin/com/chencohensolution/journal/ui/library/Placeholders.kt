package com.chencohensolution.journal.ui.library

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import com.chencohensolution.journal.library.MediaItem

/** A phone the size of the HTML mockups (docs/screens/mockups). */
internal const val PHONE = "spec:width=390dp,height=844dp,dpi=420"

/** The app's themes, with placeholder pictures instead of the photo library (there is none in a screenshot). */
@Composable
internal fun Screen(content: @Composable () -> Unit) = MaterialTheme {
    CompositionLocalProvider(LocalThumbnailSource provides PlaceholderThumbnails) {
        LibraryTheme(content = content)
    }
}

private val tones = longArrayOf(
    0xFF8FB3A6, 0xFFE3CDB4, 0xFFC9D6CF, 0xFFB9A58E, 0xFF6F8F9B, 0xFFD8B7A6,
    0xFFA7B98F, 0xFF9AA7C4, 0xFF5E8A7C, 0xFFE8D9B8, 0xFF7E9C8C, 0xFFCDB8A0,
)

/** A flat landscape (sun and hill) in a color picked by the item's id, at the item's aspect ratio. */
private val PlaceholderThumbnails = ThumbnailSource { item: MediaItem, _ ->
    val w = 320
    val h = if (item.width > 0 && item.height > 0) (w.toLong() * item.height / item.width).toInt().coerceIn(80, 960) else w
    val bitmap = ImageBitmap(w, h)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    paint.color = Color(tones[Math.floorMod(item.id.hashCode(), tones.size)])
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    paint.color = Color.White.copy(alpha = 0.45f)
    canvas.drawCircle(Offset(w * 0.75f, h * 0.27f), minOf(w, h) * 0.11f, paint)
    paint.color = Color.Black.copy(alpha = 0.12f)
    canvas.drawOval(-0.2f * w, 0.62f * h, 1.2f * w, 1.25f * h, paint)
    bitmap
}
