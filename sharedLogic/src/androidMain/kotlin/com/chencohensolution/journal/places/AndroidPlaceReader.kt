package com.chencohensolution.journal.places

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Address
import android.location.Geocoder
import android.media.ExifInterface
import android.provider.MediaStore
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.MediaItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Reads the GPS position from a photo's EXIF, and names places with the platform Geocoder (Google's service: it needs
 * a network connection, and names are asked again when it had none).
 *
 * MediaStore removes the position from what an app reads unless it holds ACCESS_MEDIA_LOCATION and asks for the
 * original file ([MediaStore.setRequireOriginal]).
 */
internal class AndroidPlaceReader(context: Context) : PlaceReader {
    private val context = context.applicationContext
    private val geocoder = if (Geocoder.isPresent()) Geocoder(this.context) else null

    override val canReadLocations: Boolean
        get() = context.checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) == PackageManager.PERMISSION_GRANTED

    override suspend fun location(item: MediaItem): GeoPoint? = withContext(Dispatchers.IO) {
        val id = item.id.toLongOrNull() ?: return@withContext null
        val uri = MediaStore.setRequireOriginal(ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id))
        // Throws when the file can't be read now; PhotoPlaces then tries again on the next launch.
        val latLong = FloatArray(2)
        val found = context.contentResolver.openInputStream(uri)?.use { ExifInterface(it).getLatLong(latLong) } == true
        GeoPoint(latLong[0].toDouble(), latLong[1].toDouble()).takeIf { found && (it.latitude != 0.0 || it.longitude != 0.0) }
    }

    override suspend fun name(point: GeoPoint): PlaceName? {
        val geocoder = geocoder ?: return null
        val address = suspendCancellableCoroutine<Address?> { continuation ->
            geocoder.getFromLocation(
                point.latitude,
                point.longitude,
                1,
                object : Geocoder.GeocodeListener {
                    override fun onGeocode(addresses: MutableList<Address>) = continuation.resume(addresses.firstOrNull())

                    override fun onError(errorMessage: String?) = continuation.resume(null)
                },
            )
        } ?: return null
        val city = address.locality ?: address.subAdminArea ?: address.adminArea
        return PlaceName(area = (address.subLocality ?: address.thoroughfare)?.takeIf { it != city }, city = city)
    }
}
