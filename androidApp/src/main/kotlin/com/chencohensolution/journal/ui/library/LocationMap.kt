package com.chencohensolution.journal.ui.library

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.unit.dp
import com.chencohensolution.journal.library.GeoPoint
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.Style
import org.maplibre.android.snapshotter.MapSnapshotter

/**
 * A still map around [point] with a pin in the middle, for the viewer's info panel. It is drawn once into a picture
 * (MapSnapshotter) rather than kept as a live map view: a live GL view in the viewer's HDR window stopped the screen
 * from updating, and a picture is clipped to the card's corners and scrolls with the panel like any image. Previews and
 * screenshot tests get the plain box with the pin (MapLibre can't draw there).
 */
@Composable
internal fun LocationMap(point: GeoPoint, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.background(LibraryColors.OnInverse.copy(alpha = 0.08f))) {
        if (!LocalInspectionMode.current) {
            val density = LocalDensity.current
            // Drawn at the screen's density, so its labels are the size a map's labels usually are.
            val width = maxWidth.value.toInt()
            val height = maxHeight.value.toInt()
            val style = if (LibraryColors.Inverse.luminance() < 0.5f) DARK_STYLE else LIGHT_STYLE
            val picture = mapPicture(point, style, width, height, density.density)
            picture?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Box(
            Modifier
                .align(Alignment.Center)
                .size(16.dp)
                .background(LibraryColors.Accent, CircleShape)
                .border(3.dp, Color.White, CircleShape),
        )
    }
}

/** The map at [point] as a [width] × [height] dp picture; null until it is drawn, or when it can't be (offline). */
@Composable
private fun mapPicture(point: GeoPoint, style: String, width: Int, height: Int, pixelRatio: Float): Bitmap? {
    val context = LocalContext.current
    var picture by remember(point, style, width, height) { mutableStateOf<Bitmap?>(null) }
    DisposableEffect(point, style, width, height) {
        if (width <= 0 || height <= 0) return@DisposableEffect onDispose {}
        MapLibre.getInstance(context)
        val options = MapSnapshotter.Options(width, height)
            .withStyleBuilder(Style.Builder().fromUri(style))
            .withCameraPosition(CameraPosition.Builder().target(LatLng(point.latitude, point.longitude)).zoom(14.0).build())
            .withPixelRatio(pixelRatio)
            .withLogo(false)
        val snapshotter = MapSnapshotter(context, options)
        snapshotter.start({ snapshot -> picture = snapshot.bitmap }, { /* offline: the plain box and the pin stay */ })
        onDispose { snapshotter.cancel() }
    }
    return picture
}
