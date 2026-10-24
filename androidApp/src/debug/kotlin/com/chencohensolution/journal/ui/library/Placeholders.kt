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
    CompositionLocalProvider(LocalThumbnailSource provides PlaceholderThumbnails, LocalFaceSource provides PlaceholderFaces) {
        LibraryTheme(content = content)
    }
}

private val tones = longArrayOf(
    0xFF8FB3A6, 0xFFE3CDB4, 0xFFC9D6CF, 0xFFB9A58E, 0xFF6F8F9B, 0xFFD8B7A6,
    0xFFA7B98F, 0xFF9AA7C4, 0xFF5E8A7C, 0xFFE8D9B8, 0xFF7E9C8C, 0xFFCDB8A0,
)

private val skins = longArrayOf(0xFFF1C9A5, 0xFFD9A47C, 0xFFB57A55, 0xFF8C5A3C, 0xFFE8B98F)
private val hairs = longArrayOf(0xFF2B2420, 0xFF5A3A22, 0xFF9A6B3E, 0xFFD8C08A, 0xFF7A7470)

/**
 * A flat landscape (sun and hill) in a color picked by the item's id, at the item's aspect ratio. Items whose file name
 * starts with "face_" (the People mockups) are a head and shoulders instead, the head where [PeopleLabData] puts the face.
 */
internal val PlaceholderThumbnails =ThumbnailSource { item: MediaItem, _ ->
    val w = 320
    val h = if (item.width > 0 && item.height > 0) (w.toLong() * item.height / item.width).toInt().coerceIn(80, 960) else w
    val bitmap = ImageBitmap(w, h)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    paint.color = Color(tones[Math.floorMod(item.id.hashCode(), tones.size)])
    canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
    if (item.fileName?.startsWith("face_") == true) {
        // The person is picked by the file name ("face_<person>_<n>"), so their photos look alike.
        drawHead(canvas, item.fileName!!.split('_').getOrElse(1) { "" }, w * FACE_CENTER_X, h * FACE_CENTER_Y, w * FACE_WIDTH / 2)
        return@ThumbnailSource bitmap
    }
    paint.color = Color.White.copy(alpha = 0.45f)
    canvas.drawCircle(Offset(w * 0.75f, h * 0.27f), minOf(w, h) * 0.11f, paint)
    paint.color = Color.Black.copy(alpha = 0.12f)
    canvas.drawOval(-0.2f * w, 0.62f * h, 1.2f * w, 1.25f * h, paint)
    bitmap
}

/** A head and shoulders around (cx, cy), the face [r] across half, colored by [who]. */
private fun drawHead(canvas: Canvas, who: String, cx: Float, cy: Float, r: Float) {
    val paint = Paint()
    val i = Math.floorMod(who.hashCode(), skins.size)
    paint.color = Color(hairs[i]).copy(alpha = 0.9f)
    canvas.drawOval(cx - r * 2.2f, cy + r * 1.3f, cx + r * 2.2f, cy + r * 5f, paint)
    canvas.drawCircle(Offset(cx, cy - r * 0.15f), r * 1.12f, paint)
    paint.color = Color(skins[i])
    canvas.drawOval(cx - r * 0.86f, cy - r, cx + r * 0.86f, cy + r * 1.08f, paint)
    paint.color = Color.Black.copy(alpha = 0.55f)
    canvas.drawCircle(Offset(cx - r * 0.33f, cy - r * 0.05f), r * 0.09f, paint)
    canvas.drawCircle(Offset(cx + r * 0.33f, cy - r * 0.05f), r * 0.09f, paint)
    canvas.drawOval(cx - r * 0.25f, cy + r * 0.45f, cx + r * 0.25f, cy + r * 0.58f, paint)
}

/** A face as an avatar: a head on a background picked by the face's photo, colored by its person (or group). */
internal val PlaceholderFaces = FaceSource { face, sizePx ->
    val side = sizePx.coerceIn(32, 512)
    val bitmap = ImageBitmap(side, side)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    paint.color = Color(tones[Math.floorMod(face.sha256.hashCode(), tones.size)])
    canvas.drawRect(0f, 0f, side.toFloat(), side.toFloat(), paint)
    drawHead(canvas, face.personId ?: face.sha256, side / 2f, side * 0.48f, side * 0.3f)
    bitmap
}
