package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.places.distanceMeters

/*
 * The Explore tab: every photo by the town it was taken in, or by year, and on a map. Like the Moments tab it is
 * worked out again from the library and the places found so far; nothing is saved.
 */

/** A town and its photos, newest first. */
data class PlaceSummary(
    val town: String,
    val items: List<MediaItem>,
    /** The town the owner lives in (see [home]). */
    val home: Boolean,
) {
    val cover: MediaItem get() = items.firstOrNull { it.type == MediaType.IMAGE } ?: items.first()
    val firstYear: Int get() = items.last().day.year
    val lastYear: Int get() = items.first().day.year

    /** Its years with photos, newest first, and how many in each. */
    val years: List<Pair<Int, Int>> get() = items.groupingBy { it.day.year }.eachCount().toList().sortedByDescending { it.first }
}

/** A year's photos, newest first. */
data class YearSummary(
    val year: Int,
    val items: List<MediaItem>,
    /** Where most of them were taken, most photos first. */
    val towns: List<String>,
    /** A few photos spread across the year, newest first. */
    val covers: List<MediaItem>,
) {
    /** Its months with photos (1–12), newest first. */
    val months: List<Int> get() = items.map { it.day.month }.distinct()
}

/** The photos an Explore page or the map shows: all of these that are set must match. */
data class ExploreScope(
    val town: String? = null,
    val year: Int? = null,
    /** 1–12, within [year]. */
    val month: Int? = null,
    val fromYear: Int? = null,
    val toYear: Int? = null,
    val area: GeoArea? = null,
) {
    fun matches(item: MediaItem, places: Places): Boolean {
        val year = item.day.year
        if (this.year != null && year != this.year) return false
        if (month != null && item.day.month != month) return false
        if (fromYear != null && year < fromYear) return false
        if (toYear != null && year > toYear) return false
        if (town == null && area == null) return true
        val point = places.location(item) ?: return false
        if (area != null && point !in area) return false
        return town == null || places.name(point)?.town == town
    }
}

/** A rectangle of the map, as shown on screen. */
data class GeoArea(val south: Double, val west: Double, val north: Double, val east: Double) {
    operator fun contains(point: GeoPoint): Boolean =
        point.latitude in south..north &&
            // Across the date line the west edge is east of the east edge.
            if (west <= east) point.longitude in west..east else point.longitude >= west || point.longitude <= east
}

/** The year covers show this many photos. */
internal const val YEAR_COVERS = 4

/** Photos this close to home are in the home town. */
internal const val HOME_TOWN_METERS = 10_000.0

/** Towns listed for a year. */
private const val YEAR_TOWNS = 4

/**
 * Every town with photos among [library] (newest first): the home town first, then the others by number of photos.
 * Photos whose place isn't known yet are left out until it is.
 */
fun placesOf(library: List<MediaItem>, places: Places): List<PlaceSummary> {
    val byTown = library.groupBy { item -> places.location(item)?.let(places::name)?.town }
    val home = home(library, places)
    val homeTown = home?.let { point ->
        library.mapNotNull { item ->
            places.location(item)?.takeIf { distanceMeters(it, point) <= HOME_TOWN_METERS }?.let(places::name)?.town
        }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
    return byTown.mapNotNull { (town, items) -> town?.let { PlaceSummary(it, items, it == homeTown) } }
        .sortedWith(compareByDescending<PlaceSummary> { it.home }.thenByDescending { it.items.size }.thenBy { it.town })
}

/** Every year with photos among [library] (newest first), newest first. */
fun yearsOf(library: List<MediaItem>, places: Places): List<YearSummary> =
    library.groupBy { it.day.year }.map { (year, items) ->
        val towns = items.mapNotNull { item -> places.location(item)?.let(places::name)?.town }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(YEAR_TOWNS).map { it.key }
        val images = items.filter { it.type == MediaType.IMAGE }.ifEmpty { items }
        val covers = (0 until YEAR_COVERS).map { images[it * images.size / YEAR_COVERS] }.distinct()
        YearSummary(year, items, towns, covers)
    }.sortedByDescending { it.year }
