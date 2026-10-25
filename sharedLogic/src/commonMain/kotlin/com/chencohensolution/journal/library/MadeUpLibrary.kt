package com.chencohensolution.journal.library

import com.chencohensolution.journal.server.ServerMedia
import kotlin.random.Random

/**
 * A made-up library of [count] photos and videos, to measure the app with a library as large as wanted (debug builds
 * only; docs/plans/library-on-disk.md, phase 0). Half are on this phone and on the server ([deviceItems], matched to
 * [serverMedia] by file name and size, as backed-up photos the ledger hasn't read are), half only on the server. They
 * are the same on every run: fifteen years of moments of 1 to 30 photos, a few videos, from the camera, WhatsApp,
 * screenshots and a Telegram folder, every one with a location so nothing tries to read one from a file.
 *
 * Nothing real is touched: backup and Free up space read the real library, never this one; the device ids are past any
 * MediaStore id and the sha256s start with [SHA_PREFIX], so every preview of them is blank; and the server's list keeps
 * them out of `server/media.json`.
 */
internal class MadeUpLibrary(val count: Int, private val endMillis: Long = END_MILLIS) {
    private val all: List<Made> by lazy { make() }

    /** The half on this phone, newest first. */
    val deviceItems: List<MediaItem> by lazy { all.filter { it.onDevice }.map { it.item } }

    /** Every one of them, as the server's change feed would list it. */
    val serverMedia: List<ServerMedia> by lazy { all.map { it.toServerMedia() } }

    private class Made(val item: MediaItem, val sha256: String, val onDevice: Boolean)

    private fun make(): List<Made> {
        val random = Random(SEED)
        val made = ArrayList<Made>(count)
        // Moments spread evenly over the span, newest first, each a run of photos seconds to minutes apart.
        val momentCount = (count / AVERAGE_MOMENT).coerceAtLeast(1)
        val step = SPAN_MILLIS / momentCount
        var moment = 0
        while (made.size < count) {
            val start = endMillis - moment * step - random.nextLong(step.coerceAtLeast(1))
            val size = minOf(1 + random.nextInt(2 * AVERAGE_MOMENT - 1), count - made.size)
            val source = when (random.nextInt(100)) {
                in 0..59 -> MediaSource.CAMERA
                in 60..84 -> MediaSource.WHATSAPP
                in 85..94 -> MediaSource.SCREENSHOTS
                else -> TELEGRAM
            }
            val place = PLACES[random.nextInt(PLACES.size)]
            var at = start
            repeat(size) {
                val n = made.size
                val video = random.nextInt(100) < VIDEO_PERCENT
                val wide = random.nextBoolean()
                val sha = SHA_PREFIX + n.toString(16).padStart(64 - SHA_PREFIX.length, '0')
                val item = MediaItem(
                    id = (FIRST_ID + n).toString(),
                    type = if (video) MediaType.VIDEO else MediaType.IMAGE,
                    takenAtMillis = at,
                    day = LocalDay.fromEpochDay((at + ZONE_OFFSET_MILLIS).floorDiv(DAY_MILLIS)),
                    durationMillis = if (video) 2_000L + random.nextLong(120_000) else null,
                    width = if (wide) 4000 else 3000,
                    height = if (wide) 3000 else 4000,
                    sizeBytes = 200_000L + random.nextLong(6_000_000),
                    fileName = (if (video) "VID_" else "IMG_") + n + (if (video) ".mp4" else ".jpg"),
                    source = source,
                    location = GeoPoint(place.latitude + random.nextDouble(-0.005, 0.005), place.longitude + random.nextDouble(-0.005, 0.005)),
                    modifiedMillis = at,
                    mimeType = if (video) "video/mp4" else "image/jpeg",
                )
                made += Made(item, sha, onDevice = n % 2 == 0)
                at -= 5_000L + random.nextLong(240_000)
            }
            moment++
        }
        return made
    }

    private fun Made.toServerMedia() = ServerMedia(
        sha256 = sha256,
        isVideo = item.type == MediaType.VIDEO,
        fileName = item.fileName!!,
        mimeType = item.mimeType!!,
        size = item.sizeBytes!!,
        takenAt = rfc3339(item.takenAtMillis),
        width = item.width,
        height = item.height,
        durationMillis = item.durationMillis,
        latitude = item.location?.latitude,
        longitude = item.location?.longitude,
        source = item.source.id,
        uploadedAt = rfc3339(item.takenAtMillis),
    )

    companion object {
        /** Made-up files' sha256s start with this, which no real file's is likely to. */
        const val SHA_PREFIX = "0000made"

        /** Made-up device ids start here, far past any MediaStore id. */
        const val FIRST_ID = 9_000_000_000L

        /**
         * Whether [item] is made up: backup never takes one, whatever reaches it, since the server may be the real one
         * (the owner's, Oct 25, 2026).
         */
        fun isMadeUp(item: MediaItem): Boolean =
            (item.id.toLongOrNull() ?: 0) >= FIRST_ID || item.sha256?.startsWith(SHA_PREFIX) == true

        private const val SEED = 25_10_2026
        private const val AVERAGE_MOMENT = 8
        private const val VIDEO_PERCENT = 6
        private const val DAY_MILLIS = 86_400_000L
        private const val ZONE_OFFSET_MILLIS = 3 * 3_600_000L
        private const val SPAN_MILLIS = 15 * 365 * DAY_MILLIS

        /** Oct 25, 2026, noon UTC. */
        private const val END_MILLIS = 1_792_929_600_000L
        private val TELEGRAM = MediaSource.folder("Telegram")

        /** Towns the moments are in: Tel Aviv, Jerusalem, Haifa, Eilat, London, Paris, New York. */
        private val PLACES = listOf(
            GeoPoint(32.0853, 34.7818), GeoPoint(31.7683, 35.2137), GeoPoint(32.7940, 34.9896), GeoPoint(29.5577, 34.9519),
            GeoPoint(51.5072, -0.1276), GeoPoint(48.8566, 2.3522), GeoPoint(40.7128, -74.0060),
        )

        /** [millis] as RFC 3339 at the made-up library's zone offset, as an uploading phone writes takenAt. */
        private fun rfc3339(millis: Long): String {
            val local = millis + ZONE_OFFSET_MILLIS
            val day = LocalDay.fromEpochDay(local.floorDiv(DAY_MILLIS))
            val ofDay = local.mod(DAY_MILLIS)
            fun two(n: Long) = n.toString().padStart(2, '0')
            return "${day}T${two(ofDay / 3_600_000)}:${two(ofDay / 60_000 % 60)}:${two(ofDay / 1_000 % 60)}+03:00"
        }
    }
}

/** The real library with a [madeUp] one added: what [LibraryController] reads while the made-up library is on. */
internal class WithMadeUp(private val real: MediaLibrary, private val madeUp: MadeUpLibrary) : MediaLibrary, LeavesOut {
    override fun access(): PhotoAccess = real.access()
    override suspend fun loadItems(): List<MediaItem> = real.loadItems() + madeUp.deviceItems
    override fun watch(onChange: () -> Unit): () -> Unit = real.watch(onChange)
    override suspend fun leaveOut(hides: (MediaItem) -> Boolean) {
        (real as? LeavesOut)?.leaveOut(hides)
    }
}
