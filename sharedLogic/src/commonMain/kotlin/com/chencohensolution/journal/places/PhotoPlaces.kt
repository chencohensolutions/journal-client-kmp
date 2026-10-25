package com.chencohensolution.journal.places

import com.chencohensolution.journal.library.BatchWalk
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryKey
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.key
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.Path
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * What a place is called: [area] is the neighborhood or spot ("Jaffa"), [city] the town ("Tel Aviv-Yafo"), [country]
 * the country ("Israel"), in the phone's language.
 */
data class PlaceName(val area: String?, val city: String?, val country: String? = null) {
    /** The most precise name: for one moment. A geocoder sometimes gives only a house number ("79"): skipped. */
    val label: String? get() = area?.takeIf { name -> name.any { it.isLetter() } } ?: city

    /** The town: for a day or a trip. */
    val town: String? get() = city ?: area
}

/** Reads where photos were taken, and names places: EXIF and Geocoder on Android, CLGeocoder on iOS. */
internal interface PlaceReader {
    /**
     * Whether [location] can read photos now (Android needs the media location permission). While it can't, photos aren't
     * marked as having no location, so they are read once it can.
     */
    val canReadLocations: Boolean

    /** The GPS position written in the photo; null when it has none. Called one photo at a time, off the main thread. */
    suspend fun location(item: MediaItem): GeoPoint?

    /** The name of the place at [point]; null when it can't be looked up now (offline), so it is asked again later. */
    suspend fun name(point: GeoPoint): PlaceName?
}

/** Where photos were taken and what those places are called, as far as worked out. */
class Places internal constructor(
    internal val locations: Map<String, GeoPoint>,
    internal val names: Map<String, PlaceName>,
) {
    /** Where [item] was taken, if known. */
    fun location(item: MediaItem): GeoPoint? = item.location ?: locations[item.id]

    /** The name of the place at [point], if looked up already. */
    fun name(point: GeoPoint): PlaceName? = names[cellKey(point)]

    override fun equals(other: Any?): Boolean = other is Places && other.locations == locations && other.names == names

    override fun hashCode(): Int = locations.hashCode() * 31 + names.hashCode()

    companion object {
        val NONE = Places(emptyMap(), emptyMap())

        /** Places already named, for the screen previews: photos carry their own locations there. */
        fun named(names: Map<GeoPoint, PlaceName>): Places = Places(emptyMap(), names.mapKeys { cellKey(it.key) })
    }
}

/**
 * Locations of the photos asked for, read one at a time in the background, and the names of the places they were
 * taken at, looked up once per [CELL_DEGREES] square. Kept in a cache file, so each photo is read once per device;
 * the cache can be deleted at any time.
 */
class PhotoPlaces internal constructor(
    private val reader: PlaceReader,
    private val cache: PlaceCache,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    /** Photos read from the database at a time ([requestFrom]), and four times as many items' locations; tests read fewer. */
    private val batchSize: Int = BATCH,
) {
    // One coroutine at a time touches the fields below; reading suspends, so new requests still get in.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher.limitedParallelism(1))
    private var saved: PlaceCache.Contents? = null
    private val failedPhotos = mutableSetOf<String>()
    private val failedNames = mutableSetOf<String>()
    private var wanted: List<MediaItem> = emptyList()
    private var worker: Job? = null

    /** With [requestFrom]: the library read from the database a batch at a time. */
    private var batches: PlaceBatches? = null

    /** Set by [requestFrom] while the worker reads: it starts again from the newest. */
    private var again = false

    private val _places = MutableStateFlow(Places.NONE)

    /** Grows in batches while photos are read. */
    val places: StateFlow<Places> = _places.asStateFlow()

    private val _loaded = MutableStateFlow(false)

    /** True once the places kept on this phone are in [places]: they are read at once, before any request. */
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    init {
        scope.launch { contents() }
    }

    /** The photos to locate, most wanted first; replaces the previous request. Known ones are skipped. */
    fun request(items: List<MediaItem>) {
        scope.launch {
            wanted = items
            batches = null
            if (worker?.isActive != true) worker = scope.launch { work() }
        }
    }

    /**
     * The photos to locate and the places to name read from the library database instead of a list
     * (docs/plans/library-on-disk.md, step 6b), a batch at a time, newest first: the same photos and places as [request]
     * with the whole library, in the same order. Called again (the library changed), the next read starts from the newest.
     */
    internal fun requestFrom(batches: PlaceBatches) {
        scope.launch {
            this@PhotoPlaces.batches = batches
            again = true
            if (worker?.isActive != true) worker = scope.launch { work() }
        }
    }

    fun close() {
        scope.cancel()
    }

    /** The places kept on this phone, read once and published. */
    private fun contents(): PlaceCache.Contents = saved ?: cache.load().also {
        saved = it
        publish(it)
        _loaded.value = true
    }

    private suspend fun work() {
        val contents = contents()
        var changed = 0
        fun toLocate(item: MediaItem) = item.isOnDevice && item.location == null && item.id !in contents.located && item.id !in failedPhotos
        fun toName(point: GeoPoint?) = point != null && cellKey(point).let { it !in contents.names && it !in failedNames }
        do {
            again = false
            val fed = batches
            // Locations first: a photo's moment needs its own; names can follow.
            if (reader.canReadLocations) {
                val walk = fed?.let { BatchWalk(MediaItem::key, batchSize, it::unlocated) }
                while (true) {
                    val next = (if (walk != null) walk.next(::toLocate) else wanted.firstOrNull(::toLocate)) ?: break
                    // Asked again while this batch was read: from the newest.
                    if (again) break
                    if (locate(next, contents) && ++changed % PUBLISH_EVERY == 0) publish(contents)
                }
            }
            if (again) continue
            // Each item's own location, else the one found for it.
            val walk = fed?.let { BatchWalk<Pair<LibraryKey, GeoPoint?>>({ it.first }, batchSize * 4, it::points) }
            while (true) {
                val next = if (walk != null) {
                    walk.next { (key, point) -> toName(point ?: contents.located[key.id]) }?.let { (key, point) -> point ?: contents.located[key.id] }
                } else {
                    wanted.asSequence().mapNotNull { item -> item.location ?: contents.located[item.id] }.firstOrNull(::toName)
                } ?: break
                if (again) break
                if (name(next, contents) && ++changed % PUBLISH_EVERY == 0) publish(contents)
            }
        } while (again)
        if (changed > 0) {
            publish(contents)
            cache.save(contents)
        }
    }

    /** Reads where [next] was taken into [contents]; false when it can't be read now. */
    private suspend fun locate(next: MediaItem, contents: PlaceCache.Contents): Boolean {
        try {
            contents.located[next.id] = reader.location(next)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Unreadable now (only in the cloud, or the permission went away): not saved, tried next launch.
            failedPhotos += next.id
            return false
        }
        return true
    }

    /** Names the place at [next] into [contents] (or marks it failed, to ask again next launch); true either way. */
    private suspend fun name(next: GeoPoint, contents: PlaceCache.Contents): Boolean {
        val key = cellKey(next)
        val name = try {
            reader.name(cellCenter(next))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        if (name == null) failedNames += key else contents.names[key] = name
        return true
    }

    private fun publish(contents: PlaceCache.Contents) {
        val located = buildMap { contents.located.forEach { (id, point) -> if (point != null) put(id, point) } }
        _places.value = Places(located, contents.names.toMap())
    }

    private companion object {
        /** Screens update after this many photos or names, not after each one. */
        const val PUBLISH_EVERY = 40

        /** Photos read from the database at a time ([requestFrom]): a few hundred. */
        const val BATCH = 500
    }
}

/**
 * Where [PhotoPlaces.requestFrom] reads the library: the database, a batch at a time in the screens' order (newest day
 * first, newest first, then by id) after a row, null for the newest (step 6b).
 */
internal interface PlaceBatches {
    /** Up to [limit] of the phone's photos and videos without a location of their own. */
    suspend fun unlocated(after: LibraryKey?, limit: Int): List<MediaItem>

    /** Up to [limit] items of the whole library, each with its own location (null without one). */
    suspend fun points(after: LibraryKey?, limit: Int): List<Pair<LibraryKey, GeoPoint?>>
}

/** Places are named once per square this many degrees wide (about 500 m). */
internal const val CELL_DEGREES = 0.005

private fun cellIndex(degrees: Double): Long = floor(degrees / CELL_DEGREES).toLong()

internal fun cellKey(point: GeoPoint): String = "${cellIndex(point.latitude)},${cellIndex(point.longitude)}"

private fun cellCenter(point: GeoPoint) = GeoPoint(
    (cellIndex(point.latitude) + 0.5) * CELL_DEGREES,
    (cellIndex(point.longitude) + 0.5) * CELL_DEGREES,
)

/** The distance between two points in meters, along the Earth's surface. */
internal fun distanceMeters(a: GeoPoint, b: GeoPoint): Double {
    val toRadians = kotlin.math.PI / 180
    val dLat = (b.latitude - a.latitude) * toRadians
    val dLng = (b.longitude - a.longitude) * toRadians
    val h = sin(dLat / 2) * sin(dLat / 2) +
        cos(a.latitude * toRadians) * cos(b.latitude * toRadians) * sin(dLng / 2) * sin(dLng / 2)
    return 2 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
}

private const val EARTH_RADIUS_METERS = 6_371_000.0

/**
 * `<dir>/places.json`: device photo id → [latitude, longitude], or [] for a photo without a location; and place
 * cell → name (area, city and country). A cache in the platform's caches folder, rebuilt when it's missing or damaged.
 */
internal class PlaceCache(private val fileSystem: FileSystem, private val dir: Path) {
    private val file = dir / "places.json"

    class Contents(val located: MutableMap<String, GeoPoint?>, val names: MutableMap<String, PlaceName>)

    fun load(): Contents = try {
        val saved = fileSystem.read(file) { json.decodeFromString<CacheFile>(readUtf8()) }
        if (saved.v !in NAMES_WITHOUT_COUNTRY..VERSION) {
            empty()
        } else {
            Contents(
                saved.photos.mapValues { (_, it) -> if (it.size == 2) GeoPoint(it[0], it[1]) else null }.toMutableMap(),
                // Names saved before countries were are looked up again; the photos' locations are kept.
                if (saved.v < VERSION) {
                    mutableMapOf()
                } else {
                    saved.names.mapValues { (_, it) -> PlaceName(it.a, it.c, it.o) }.toMutableMap()
                },
            )
        }
    } catch (e: Exception) {
        empty()
    }

    fun save(contents: Contents) {
        try {
            val file = CacheFile(
                VERSION,
                contents.located.mapValues { (_, point) -> point?.let { listOf(it.latitude, it.longitude) }.orEmpty() },
                contents.names.mapValues { (_, name) -> CachedName(name.area, name.city, name.country) },
            )
            fileSystem.createDirectories(dir)
            val temp = dir / "places.json.tmp"
            fileSystem.write(temp) { writeUtf8(json.encodeToString(file)) }
            fileSystem.atomicMove(temp, this.file)
        } catch (e: Exception) {
            // Only a cache: the photos are read again next time.
        }
    }

    private fun empty() = Contents(mutableMapOf(), mutableMapOf())

    @Serializable
    private class CacheFile(val v: Int, val photos: Map<String, List<Double>>, val names: Map<String, CachedName> = emptyMap())

    @Serializable
    private class CachedName(val a: String? = null, val c: String? = null, val o: String? = null)

    private companion object {
        const val VERSION = 2

        /** The version before names had a country. */
        const val NAMES_WITHOUT_COUNTRY = 1
        val json = Json { ignoreUnknownKeys = true }
    }
}
