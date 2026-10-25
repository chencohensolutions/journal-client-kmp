package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.places.distanceMeters
import com.chencohensolution.journal.settings.ShownSources

/*
 * The Explore tab: every photo by the country and town it was taken in, or by year, and on a map. Like the Moments tab it is
 * worked out again from the library and the places found so far; nothing is saved.
 */

/**
 * A town and its photos, newest first. Worked out from the library database (docs/plans/library-on-disk.md, step 6c)
 * it keeps only what the screens show of them ([count], [cover], the years) and [items] is empty; the town's page reads
 * its photos itself.
 */
data class PlaceSummary(
    val town: String,
    val items: List<MediaItem>,
    /** The town the owner lives in (see [home]). */
    val home: Boolean,
    /** The country most of its photos were taken in; null while none is known. */
    val country: String? = null,
    /** How many photos it has. */
    val count: Int = items.size,
    /** Its best-looking photo (the server's aesthetic score); the newest when none is scored. */
    val cover: MediaItem = coverOf(items),
    val firstYear: Int = items.last().day.year,
    val lastYear: Int = items.first().day.year,
    /** Its years with photos, newest first, and how many in each. */
    val years: List<Pair<Int, Int>> = items.groupingBy { it.day.year }.eachCount().toList().sortedByDescending { it.first },
)

/** The best-looking photo of [items] (newest first), else the newest photo, else the newest video: a town's photo. */
internal fun coverOf(items: List<MediaItem>): MediaItem =
    bestLooking(items.filter { it.type == MediaType.IMAGE }) ?: items.firstOrNull { it.type == MediaType.IMAGE } ?: items.first()

/** A country and its towns, as [placesOf] orders them. */
data class CountrySummary(
    /** Null for the towns whose country isn't known. */
    val country: String?,
    val places: List<PlaceSummary>,
    /** The country of the home town. */
    val home: Boolean,
    /**
     * Up to four photos for its row in the list of countries: each town's photo, most photos first, then, for a
     * country of fewer towns, more photos of the country spread across its towns' photos (photos rather than videos).
     */
    val covers: List<MediaItem> = countryCovers(places),
) {
    val photoCount: Int get() = places.sumOf { it.count }
    val firstYear: Int get() = places.minOf { it.firstYear }
    val lastYear: Int get() = places.maxOf { it.lastYear }
}

/** [CountrySummary.covers] of [places], from their items. */
private fun countryCovers(places: List<PlaceSummary>): List<MediaItem> {
    val towns = places.map { it.cover }.take(COUNTRY_COVERS)
    if (towns.size == COUNTRY_COVERS) return towns
    val others = places.flatMap { it.items }.filter { it.type == MediaType.IMAGE && it !in towns }
    return towns + spreadOver(others.size, COUNTRY_COVERS - towns.size).map { others[it] }.distinct()
}

/** Where [wanted] photos spread across [size] are: the places in the list [CountrySummary.covers] takes them from. */
internal fun spreadOver(size: Int, wanted: Int): List<Int> = (0 until minOf(wanted, size)).map { it * size / wanted }

/** A country's row in the list of countries shows this many photos. */
internal const val COUNTRY_COVERS = 4

/**
 * A year's photos, newest first. Worked out from the library database (step 6c) [items] is empty and [count] and
 * [months] say what they would.
 */
data class YearSummary(
    val year: Int,
    /** Empty when every photo of the year is hidden ([hidden]). */
    val items: List<MediaItem>,
    /** Where most of them were taken, most photos first. */
    val towns: List<String>,
    /** A few photos spread across the year, newest first; only shown photos. */
    val covers: List<MediaItem>,
    /** Its photos the sources setting hides, per source in [MediaSource] order: "+2,410 WhatsApp hidden". */
    val hidden: Map<MediaSource, Int> = emptyMap(),
    /** How many photos it shows; 0 when every photo of the year is hidden. */
    val count: Int = items.size,
    /** Its months with photos (1–12), newest first. */
    val months: List<Int> = items.map { it.day.month }.distinct(),
)

/**
 * Explore's lists (the towns by country, the years), whether any photo shown has a place yet, and the whole library's
 * photos per source, shown or not, for the "Showing" sheet.
 */
class ExploreSummary(
    val countries: List<CountrySummary>,
    val years: List<YearSummary>,
    val anyLocated: Boolean,
    val counts: Map<MediaSource, Int>,
)

/** The ids of every photo [this] shows: the towns', countries' and years' covers. */
internal fun ExploreSummary.coverIds(): Set<String> = buildSet {
    countries.forEach { country ->
        country.places.forEach { add(it.cover.id) }
        country.covers.forEach { add(it.id) }
    }
    years.forEach { year -> year.covers.forEach { add(it.id) } }
}

/** [this] with its covers as [items] has them now (by id); those not in it stay as they were. */
internal fun ExploreSummary.withCovers(items: Map<String, MediaItem>): ExploreSummary {
    fun fresh(item: MediaItem) = items[item.id] ?: item
    return ExploreSummary(
        countries.map { country -> country.copy(places = country.places.map { it.copy(cover = fresh(it.cover)) }, covers = country.covers.map(::fresh)) },
        years.map { it.copy(covers = it.covers.map(::fresh)) },
        anyLocated,
        counts,
    )
}

/**
 * [ExploreSummary] of [library] (newest first, as the screens list it), of what [shown] shows, with home as the
 * database worked it out ([known], see [placesOf]).
 */
fun exploreOf(library: List<MediaItem>, places: Places, shown: ShownSources, known: ExploreHome?): ExploreSummary {
    val (items, hidden) = library.partition(shown::shows)
    return ExploreSummary(
        countriesOf(placesOf(items, places, known)),
        yearsOf(items, places, hidden),
        items.any { places.location(it) != null },
        sourceCounts(library),
    )
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

/**
 * Where home is (see [placesOf]), worked out from the library database (docs/plans/library-on-disk.md, step 6b);
 * [point] is null while too few photos are located.
 */
data class ExploreHome(val point: GeoPoint?)

/** The year covers show this many photos. */
internal const val YEAR_COVERS = 4

/** Photos this close to home are in the home town. */
internal const val HOME_TOWN_METERS = 10_000.0

/** Towns listed for a year. */
internal const val YEAR_TOWNS = 4

/**
 * Every town with photos among [library] (newest first): the home town first, then the others by number of photos.
 * Photos whose place isn't known yet are left out until it is.
 */
fun placesOf(library: List<MediaItem>, places: Places): List<PlaceSummary> = placesOf(library, places, null)

/** [placesOf], with home as the database worked it out ([StoriesState.home]); null works it out from [library]. */
fun placesOf(library: List<MediaItem>, places: Places, known: ExploreHome?): List<PlaceSummary> {
    val byTown = library.groupBy { item -> places.location(item)?.let(places::name)?.town }
    val home = if (known != null) known.point else home(library, places)
    val homeTown = home?.let { point ->
        library.mapNotNull { item ->
            places.location(item)?.takeIf { distanceMeters(it, point) <= HOME_TOWN_METERS }?.let(places::name)?.town
        }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }
    return byTown.mapNotNull { (town, items) ->
        town?.let {
            val country = items.mapNotNull { item -> places.location(item)?.let(places::name)?.country }
                .groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
            PlaceSummary(it, items, it == homeTown, country)
        }
    }
        .sortedWith(compareByDescending<PlaceSummary> { it.home }.thenByDescending { it.items.size }.thenBy { it.town })
}

/**
 * [places] by country: the home country first, then the others by number of photos, and the towns whose country
 * isn't known last. Each keeps the towns in the order given.
 */
fun countriesOf(places: List<PlaceSummary>): List<CountrySummary> = countriesOf(places, ::countryCovers)

/** [countriesOf], each country's covers given by [covers] (of its towns, in order). */
internal fun countriesOf(places: List<PlaceSummary>, covers: (List<PlaceSummary>) -> List<MediaItem>): List<CountrySummary> =
    places.groupBy { it.country }.map { (country, towns) -> CountrySummary(country, towns, towns.any { it.home }, covers(towns)) }
        .sortedWith(
            compareByDescending<CountrySummary> { it.home }.thenBy { it.country == null }
                .thenByDescending { it.photoCount }.thenBy { it.country },
        )

/**
 * Every year with photos among [library] (newest first), newest first, each counting its photos among [hidden] (those
 * the sources setting leaves out). A year with only hidden photos is listed too, with no items.
 */
fun yearsOf(library: List<MediaItem>, places: Places, hidden: List<MediaItem>): List<YearSummary> {
    val hiddenByYear = hidden.groupBy { it.day.year }
    val byYear = library.groupBy { it.day.year }
    return (byYear.keys + hiddenByYear.keys).map { year ->
        val items = byYear[year].orEmpty()
        val towns = items.mapNotNull { item -> places.location(item)?.let(places::name)?.town }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(YEAR_TOWNS).map { it.key }
        val images = items.filter { it.type == MediaType.IMAGE }.ifEmpty { items }
        // Spread across the year: the best-looking of each part of it.
        val covers = if (images.isEmpty()) emptyList() else (0 until YEAR_COVERS).map { part ->
            val slice = yearSlice(part, images.size).let { images.subList(it.first, it.last + 1) }
            bestLooking(slice) ?: slice.first()
        }.distinct()
        YearSummary(year, items, towns, covers, sourceCounts(hiddenByYear[year].orEmpty()))
    }.sortedByDescending { it.year }
}

/** Which of a year's [size] photos its cover [part] (of [YEAR_COVERS]) is the best-looking of: never none. */
internal fun yearSlice(part: Int, size: Int): IntRange =
    (part * size / YEAR_COVERS) until maxOf(part * size / YEAR_COVERS + 1, (part + 1) * size / YEAR_COVERS)

/** How many of [items] come from each source, in [MediaSource] order, leaving out sources with none. */
fun sourceCounts(items: List<MediaItem>): Map<MediaSource, Int> {
    val counts = items.groupingBy { it.source }.eachCount()
    return counts.keys.sorted().associateWith { counts.getValue(it) }
}

/** The best-looking of [items] by the server's aesthetic score; null when none is scored. Ties go to the first. */
fun bestLooking(items: List<MediaItem>): MediaItem? {
    var best: MediaItem? = null
    for (item in items) {
        val score = item.aesthetic ?: continue
        if (best == null || score > best.aesthetic!!) best = item
    }
    return best
}

/** [items] the best-looking first (unscored last), then the newest: a page's "Best first". */
fun bestFirst(items: List<MediaItem>): List<MediaItem> =
    items.sortedWith(compareByDescending<MediaItem> { it.aesthetic ?: -1f }.thenByDescending { it.takenAtMillis })
