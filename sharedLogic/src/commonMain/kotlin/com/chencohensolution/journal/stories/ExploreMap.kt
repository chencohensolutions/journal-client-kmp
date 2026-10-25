package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.settings.ShownSources
import kotlin.math.floor
import kotlin.math.pow

/*
 * The Explore map's numbers (docs/plans/library-on-disk.md, step 6c): its years, where it opens, and the photos around
 * what it shows counted per square, so it never holds every located photo. Worked out the same from the library's items
 * here (previews, tests) and from the database's grouped queries (ExploreFromDatabase.kt).
 */

/**
 * The map's squares are about this many points wide on screen: a quarter of the reach of its circles (48 points), so
 * the map's own clustering joins them as it joined single photos before.
 */
private const val MAP_CELL_POINTS = 12.0

/** The width in degrees of the map's squares at [zoom], by whole zoom levels so they stay put while the map pans. */
fun mapCellDegrees(zoom: Double): Double = 360.0 * MAP_CELL_POINTS / (512.0 * 2.0.pow(floor(zoom.coerceIn(0.0, 30.0))))

/** A located photo (or video) the map shows: its id, day and place. */
internal class MapPhoto(val id: String, val day: LocalDay, val point: GeoPoint)

/** The located photos shown, at a glance: their years, and where the map opens on a range of them. */
class MapOverview internal constructor(
    /** The years of the located photos shown, oldest to newest; null when there are none. */
    val years: IntRange?,
    /** The box around each day's located photos shown (per source from the database, per photo from memory). */
    private val days: List<Pair<LocalDay, GeoArea>>,
    /**
     * Photos [MapArea]s are counted from besides the database's: from memory every located photo shown, from the
     * database those without a location of their own that a place was found for.
     */
    internal val photos: List<MapPhoto>,
    /** The whole library's photos per source, shown or not, for the "Showing" sheet. */
    val counts: Map<MediaSource, Int>,
) {
    /**
     * The box around the located photos shown of the years [from] to [to]; null when there are none. All of them in one
     * place make a box of no size.
     */
    fun bounds(from: Int, to: Int): GeoArea? {
        val boxes = days.filter { (day, _) -> day.year in from..to }.map { it.second }
        if (boxes.isEmpty()) return null
        return GeoArea(boxes.minOf { it.south }, boxes.minOf { it.west }, boxes.maxOf { it.north }, boxes.maxOf { it.east })
    }

    internal companion object {
        /** An overview of [days]' boxes and [photos], the years from both. */
        fun of(days: List<Pair<LocalDay, GeoArea>>, photos: List<MapPhoto>, counts: Map<MediaSource, Int>): MapOverview {
            val all = days + photos.map { it.day to GeoArea(it.point.latitude, it.point.longitude, it.point.latitude, it.point.longitude) }
            val years = if (all.isEmpty()) null else all.minOf { it.first.year }..all.maxOf { it.first.year }
            return MapOverview(years, all, photos, counts)
        }
    }
}

/** A square of the map with photos in it: where they are on average, how many, and the id of the only one. */
class MapCell(val point: GeoPoint, val count: Int, val id: String?)

/** What the map draws around what it shows ([cells]), and how many photos are in view ([inView]). */
class MapArea(val cells: List<MapCell>, val inView: Int)

/** The overview of the photos and videos of [library] that [shown] shows and that have a place ([Places.location]). */
fun mapOverviewOf(library: List<MediaItem>, places: Places, shown: ShownSources): MapOverview =
    MapOverview.of(
        emptyList(),
        library.filter(shown::shows).mapNotNull { item -> places.location(item)?.let { MapPhoto(item.id, item.day, it) } },
        sourceCounts(library),
    )

/**
 * The located photos shown of the years [from] to [to] around [visible] (a screen's width and height further each way,
 * so a short pan shows them at once) counted per square of [mapCellDegrees] at [zoom], and how many are in [visible].
 */
fun mapAreaOf(overview: MapOverview, visible: GeoArea, zoom: Double, from: Int, to: Int): MapArea {
    val photos = overview.photos.filter { it.day.year in from..to }
    val size = mapCellDegrees(zoom)
    val around = visible.widened()
    val cells = CellSums()
    photos.forEach { if (it.point in around) cells.add(size, it.point, it.id) }
    return MapArea(cells.cells(), photos.count { it.point in visible })
}

/** Photos added up per map square: their number and the sums of their places, and the id of a square's only one. */
internal class CellSums {
    private class Sum(var count: Int, var latitude: Double, var longitude: Double, var id: String?)

    private val sums = HashMap<Pair<Long, Long>, Sum>()

    fun add(key: Pair<Long, Long>, count: Int, latitude: Double, longitude: Double, id: String?) {
        val sum = sums[key]
        if (sum == null) {
            sums[key] = Sum(count, latitude, longitude, id)
        } else {
            sum.count += count
            sum.latitude += latitude
            sum.longitude += longitude
            sum.id = null
        }
    }

    fun add(size: Double, point: GeoPoint, id: String) = add(cellOf(point, size), 1, point.latitude, point.longitude, id)

    /** The squares, in the order of their places (south to north, then west to east). */
    fun cells(): List<MapCell> = sums.entries.sortedWith(compareBy({ it.key.first }, { it.key.second })).map { (_, sum) ->
        MapCell(GeoPoint(sum.latitude / sum.count, sum.longitude / sum.count), sum.count, sum.id?.takeIf { sum.count == 1 })
    }
}

/** The map square of [point] for squares of [size] degrees, as the database counts them (floored). */
internal fun cellOf(point: GeoPoint, size: Double): Pair<Long, Long> =
    floor(point.latitude / size).toLong() to floor(point.longitude / size).toLong()

/**
 * [this] with its height added north and south and its width east and west (wrapping across the date line), the whole
 * world's width once that is wider than it.
 */
internal fun GeoArea.widened(): GeoArea {
    val height = north - south
    val width = if (west <= east) east - west else east + 360 - west
    val south = (south - height).coerceAtLeast(-90.0)
    val north = (north + height).coerceAtMost(90.0)
    if (width * 3 >= 360 || west !in -180.0..180.0 || east !in -180.0..180.0) return GeoArea(south, -180.0, north, 180.0)
    val west = (west - width).let { if (it < -180) it + 360 else it }
    val east = (east + width).let { if (it > 180) it - 360 else it }
    return GeoArea(south, west, north, east)
}
