package com.chencohensolution.journal.places

import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.MediaItem
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreLocation.CLGeocoder
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLPlacemark
import kotlin.coroutines.resume

/**
 * Names places with CLGeocoder (Apple's service: it needs a network connection). PhotoKit gives each photo's location
 * with the library ([MediaItem.location]), so there is nothing to read from the files.
 */
internal class IosPlaceReader : PlaceReader {
    private val geocoder = CLGeocoder()

    override val canReadLocations: Boolean get() = false

    override suspend fun location(item: MediaItem): GeoPoint? = item.location

    override suspend fun name(point: GeoPoint): PlaceName? {
        // Apple throttles an app that geocodes much more than about one place a second.
        delay(GEOCODE_PAUSE_MILLIS)
        val placemark = suspendCancellableCoroutine<CLPlacemark?> { continuation ->
            geocoder.reverseGeocodeLocation(CLLocation(point.latitude, point.longitude)) { placemarks, _ ->
                continuation.resume(placemarks?.firstOrNull() as? CLPlacemark)
            }
            continuation.invokeOnCancellation { geocoder.cancelGeocode() }
        } ?: return null
        val city = placemark.locality ?: placemark.subAdministrativeArea ?: placemark.administrativeArea
        return PlaceName(
            area = (placemark.subLocality ?: placemark.thoroughfare)?.takeIf { it != city },
            city = city,
            country = placemark.country,
        )
    }

    private companion object {
        const val GEOCODE_PAUSE_MILLIS = 1_200L
    }
}
