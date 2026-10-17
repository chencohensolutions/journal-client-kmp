package com.chencohensolution.journal.library

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSCalendarUnitMonth
import platform.Foundation.NSCalendarUnitYear
import platform.Foundation.NSDate
import platform.Foundation.timeIntervalSince1970
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHPhotoLibrary

/** Reads photos and videos from PhotoKit. Ids are PHAsset local identifiers. */
internal class IosMediaLibrary : MediaLibrary {
    override fun access(): PhotoAccess = when (PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)) {
        PHAuthorizationStatusAuthorized -> PhotoAccess.FULL
        PHAuthorizationStatusLimited -> PhotoAccess.LIMITED
        PHAuthorizationStatusNotDetermined -> PhotoAccess.NOT_DETERMINED
        else -> PhotoAccess.DENIED
    }

    override suspend fun loadItems(): List<MediaItem> = withContext(Dispatchers.Default) {
        val calendar = NSCalendar.currentCalendar
        val items = mutableListOf<MediaItem>()
        for (mediaType in listOf(PHAssetMediaTypeImage, PHAssetMediaTypeVideo)) {
            val result = PHAsset.fetchAssetsWithMediaType(mediaType, null)
            for (i in 0uL until result.count) {
                val asset = result.objectAtIndex(i) as PHAsset
                val date = asset.creationDate ?: asset.modificationDate ?: NSDate()
                val parts = calendar.components(NSCalendarUnitYear or NSCalendarUnitMonth or NSCalendarUnitDay, date)
                val isVideo = mediaType == PHAssetMediaTypeVideo
                items += MediaItem(
                    id = asset.localIdentifier,
                    type = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
                    takenAtMillis = (date.timeIntervalSince1970 * 1000).toLong(),
                    day = LocalDay(parts.year.toInt(), parts.month.toInt(), parts.day.toInt()),
                    durationMillis = if (isVideo) (asset.duration * 1000).toLong().takeIf { it > 0 } else null,
                    width = asset.pixelWidth.toInt(),
                    height = asset.pixelHeight.toInt(),
                    // Size and file name need a PHAssetResource lookup per asset; the viewer asks PhotoKit for them.
                    sizeBytes = null,
                    fileName = null,
                    burstId = asset.burstIdentifier,
                )
            }
        }
        items
    }
}
