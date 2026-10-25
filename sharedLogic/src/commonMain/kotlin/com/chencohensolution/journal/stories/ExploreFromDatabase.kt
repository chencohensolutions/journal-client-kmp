package com.chencohensolution.journal.stories

import com.chencohensolution.journal.library.ExploreRow
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryKey
import com.chencohensolution.journal.library.LibraryRow
import com.chencohensolution.journal.library.LibraryTable
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.key
import com.chencohensolution.journal.places.CELL_DEGREES
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.places.distanceMeters
import com.chencohensolution.journal.settings.ShownSources

/*
 * Explore and the map from the library database (docs/plans/library-on-disk.md, step 6c): the same lists, pages and
 * numbers as Explore.kt and ExploreMap.kt work out from the library's items, without holding them. Blocking: call off
 * the main thread.
 */

/** Rows Explore's summaries read at a time. */
private const val EXPLORE_BATCH = 2_000

/** Every row in the screens' order, a batch at a time, as Explore's summaries read them. */
private inline fun LibraryTable.walk(each: (ExploreRow) -> Unit) {
    var after: LibraryKey? = null
    do {
        val rows = exploreRows(after, EXPLORE_BATCH)
        rows.forEach(each)
        after = rows.lastOrNull()?.key
    } while (rows.size == EXPLORE_BATCH)
}

/** What one walk added up for a town (see [exploreOf]). */
private class TownTally(val town: String) {
    var count = 0
    var firstYear = Int.MAX_VALUE
    var lastYear = Int.MIN_VALUE
    val years = LinkedHashMap<Int, Int>()
    val countries = LinkedHashMap<String, Int>()

    /** Its photos near home, and where the first of them is in the walk. */
    var near = 0
    var firstNear = Long.MAX_VALUE
    var images = 0
    var firstItem: String? = null
    var firstImage: String? = null
    var best: String? = null
    var bestScore = 0f

    /** Its photo: the best-looking, else the newest photo, else the newest video (as [coverOf]). */
    val cover: String get() = best ?: firstImage ?: firstItem!!
}

/** What one walk added up for a year with photos shown. */
private class YearTally(
    /** How many photos its covers are spread across (its photos, or its videos when it has none). */
    val size: Int,
    val ofImages: Boolean,
) {
    val months = LinkedHashSet<Int>()
    val towns = LinkedHashMap<String, Int>()
    var seen = 0
    val first = arrayOfNulls<String>(YEAR_COVERS)
    val best = arrayOfNulls<String>(YEAR_COVERS)
    val bestScore = FloatArray(YEAR_COVERS)
}

/** A year's photos and videos shown and what the sources setting hides of it, from the counts per day. */
private class YearCount {
    var items = 0
    var images = 0
    val hidden = HashMap<MediaSource, Int>()
}

/**
 * [exploreOf] of the library database, equal to the one of its items (newest first): the years' counts and what they
 * hide come from the photos counted per day, source and type in SQL; the towns, countries and covers from one walk
 * through the rows in the screens' order, a few numbers each. The covers are the best-looking by this phone's scores
 * before the server's ([scoreOf]), which only memory has, so they can't be picked in SQL. A second walk, only when a
 * country has fewer than four towns, finds the photos its row spreads across them. The covers are then read by id and
 * shown ([shownRow]). Home is [known], else worked out as [home] does.
 */
internal fun exploreOf(
    table: LibraryTable,
    places: Places,
    shown: ShownSources,
    known: ExploreHome?,
    scoreOf: (serverSha: String?, aesthetic: Float?) -> Float?,
    shownRow: (LibraryRow) -> MediaItem,
): ExploreSummary {
    // Rows can change between the walks and the reading of the covers: then it is all done again, and the last time
    // what is gone is left out.
    for (attempt in 1..3) exploreOnce(table, places, shown, known, scoreOf, shownRow, lastTry = attempt == 3)?.let { return it }
    error("unreachable")
}

@Suppress("LongMethod", "CyclomaticComplexMethod")
private fun exploreOnce(
    table: LibraryTable,
    places: Places,
    shown: ShownSources,
    known: ExploreHome?,
    scoreOf: (serverSha: String?, aesthetic: Float?) -> Float?,
    shownRow: (LibraryRow) -> MediaItem,
    lastTry: Boolean,
): ExploreSummary? {
    val found = places.locations
    val yearCounts = HashMap<Int, YearCount>()
    table.dayCounts().forEach { group ->
        val year = yearCounts.getOrPut(group.day.year) { YearCount() }
        if (shown.shows(group.day, group.source)) {
            year.items += group.count
            if (!group.isVideo) year.images += group.count
        } else {
            year.hidden[group.source] = (year.hidden[group.source] ?: 0) + group.count
        }
    }
    val homePoint = if (known != null) known.point else home(table, found, shown)

    val towns = LinkedHashMap<String, TownTally>()
    val years = HashMap<Int, YearTally>()
    var anyLocated = false
    var at = 0L
    table.walk { row ->
        at++
        val day = row.key.day
        if (!shown.shows(day, row.source)) return@walk
        val point = row.point ?: found[row.key.id]
        if (point != null) anyLocated = true
        val name = point?.let(places::name)
        val town = name?.town
        val image = !row.isVideo
        val score = scoreOf(row.serverSha, row.aesthetic)
        val id = row.key.id
        // The year: its months, towns, and the best-looking of each part of it.
        val year = years.getOrPut(day.year) {
            val counts = yearCounts[day.year]
            if (counts == null || counts.items == 0) YearTally(0, true) else YearTally(if (counts.images > 0) counts.images else counts.items, counts.images > 0)
        }
        year.months += day.month
        town?.let { year.towns[it] = (year.towns[it] ?: 0) + 1 }
        if (year.size > 0 && (image || !year.ofImages)) {
            val index = year.seen++
            for (part in 0 until YEAR_COVERS) {
                if (index !in yearSlice(part, year.size)) continue
                if (year.first[part] == null) year.first[part] = id
                if (score != null && (year.best[part] == null || score > year.bestScore[part])) {
                    year.best[part] = id
                    year.bestScore[part] = score
                }
            }
        }
        // The town: its count, years, country, photo, and whether it is home.
        if (town == null) return@walk
        val tally = towns.getOrPut(town) { TownTally(town) }
        tally.count++
        tally.firstYear = minOf(tally.firstYear, day.year)
        tally.lastYear = maxOf(tally.lastYear, day.year)
        tally.years[day.year] = (tally.years[day.year] ?: 0) + 1
        name?.country?.let { tally.countries[it] = (tally.countries[it] ?: 0) + 1 }
        if (tally.firstItem == null) tally.firstItem = id
        if (image) {
            tally.images++
            if (tally.firstImage == null) tally.firstImage = id
            if (score != null && (tally.best == null || score > tally.bestScore)) {
                tally.best = id
                tally.bestScore = score
            }
        }
        if (homePoint != null && point != null && distanceMeters(point, homePoint) <= HOME_TOWN_METERS) {
            tally.near++
            if (tally.firstNear == Long.MAX_VALUE) tally.firstNear = at
        }
    }

    // The home town: the most photos near home, on a tie the one met first (as groupingBy and maxBy keep it).
    val homeTown = towns.values.filter { it.near > 0 }.maxWithOrNull(compareBy<TownTally> { it.near }.thenByDescending { it.firstNear })?.town
    val ordered = towns.values.sortedWith(compareByDescending<TownTally> { it.town == homeTown }.thenByDescending { it.count }.thenBy { it.town })
    val countryOf = ordered.associate { tally -> tally.town to tally.countries.entries.maxByOrNull { it.value }?.key }

    // Countries of fewer towns than covers spread more photos across their towns' photos (without the towns' own).
    val spread = HashMap<String, MutableMap<Int, Int>>() // town → its photo's place among its others → place in the country's
    val picks = HashMap<String?, Array<String?>>()
    ordered.groupBy { countryOf[it.town] }.forEach { (country, inCountry) ->
        if (inCountry.size >= COUNTRY_COVERS) return@forEach
        val others = inCountry.map { maxOf(0, it.images - 1) }
        val wanted = spreadOver(others.sum(), COUNTRY_COVERS - inCountry.size)
        picks[country] = arrayOfNulls(wanted.size)
        wanted.forEachIndexed { pick, place ->
            var offset = 0
            for ((i, tally) in inCountry.withIndex()) {
                if (place < offset + others[i]) {
                    spread.getOrPut(tally.town) { HashMap() }[place - offset] = pick
                    break
                }
                offset += others[i]
            }
        }
    }
    if (spread.isNotEmpty()) {
        val seen = HashMap<String, Int>()
        table.walk { row ->
            if (row.isVideo || !shown.shows(row.key.day, row.source)) return@walk
            val town = (row.point ?: found[row.key.id])?.let(places::name)?.town ?: return@walk
            val wanted = spread[town] ?: return@walk
            if (row.key.id == towns.getValue(town).cover) return@walk
            val index = seen[town] ?: 0
            seen[town] = index + 1
            wanted[index]?.let { pick -> picks.getValue(countryOf[town])[pick] = row.key.id }
        }
    }

    // The covers, read and shown.
    val ids = HashSet<String>()
    ordered.forEach { ids += it.cover }
    years.values.forEach { year -> (0 until YEAR_COVERS).forEach { part -> (year.best[part] ?: year.first[part])?.let(ids::add) } }
    picks.values.forEach { pick -> pick.forEach { it?.let(ids::add) } }
    val items = table.byIds(ids).mapValues { shownRow(it.value) }
    if (!lastTry && ids.any { it !in items }) return null

    val summaries = ordered.mapNotNull { tally ->
        PlaceSummary(
            town = tally.town,
            items = emptyList(),
            home = tally.town == homeTown,
            country = countryOf[tally.town],
            count = tally.count,
            cover = items[tally.cover] ?: return@mapNotNull null,
            firstYear = tally.firstYear,
            lastYear = tally.lastYear,
            years = tally.years.toList().sortedByDescending { it.first },
        )
    }
    val countries = countriesOf(summaries) { inCountry ->
        val covers = inCountry.map { it.cover }.take(COUNTRY_COVERS)
        if (covers.size == COUNTRY_COVERS) covers else covers + picks[inCountry.first().country].orEmpty().mapNotNull { id -> id?.let(items::get) }.distinct()
    }
    val yearSummaries = yearCounts.entries.sortedByDescending { it.key }.map { (year, counts) ->
        val tally = years[year]
        YearSummary(
            year = year,
            items = emptyList(),
            towns = tally?.towns?.entries?.sortedByDescending { it.value }?.take(YEAR_TOWNS)?.map { it.key }.orEmpty(),
            covers = if (tally == null || tally.size == 0) {
                emptyList()
            } else {
                (0 until YEAR_COVERS).mapNotNull { part -> (tally.best[part] ?: tally.first[part])?.let(items::get) }.distinct()
            },
            hidden = counts.hidden.keys.sorted().associateWith { counts.hidden.getValue(it) },
            count = counts.items,
            months = tally?.months?.toList().orEmpty(),
        )
    }
    return ExploreSummary(countries, yearSummaries, anyLocated, table.sourceCounts())
}

/** The first and last epoch days [scope]'s years and month take in (every day when it has none). */
private fun ExploreScope.days(): Pair<Long, Long> {
    var first = Long.MIN_VALUE
    var last = Long.MAX_VALUE
    fun from(day: LocalDay) { first = maxOf(first, day.toEpochDay()) }
    fun until(day: LocalDay) { last = minOf(last, day.toEpochDay() - 1) }
    year?.let { year ->
        if (month != null) {
            from(LocalDay(year, month, 1))
            until(if (month == 12) LocalDay(year + 1, 1, 1) else LocalDay(year, month + 1, 1))
        } else {
            from(LocalDay(year, 1, 1))
            until(LocalDay(year + 1, 1, 1))
        }
    }
    fromYear?.let { from(LocalDay(it, 1, 1)) }
    toYear?.let { until(LocalDay(it + 1, 1, 1)) }
    return first to last
}

/**
 * The box around the place squares named [town] (a square wider each way, so rounding at an edge never leaves a photo
 * out); null when none is.
 */
private fun townBox(places: Places, town: String): GeoArea? {
    val cells = places.names.filterValues { it.town == town }.keys.map { cell -> cell.split(",").map(String::toLong) }
    if (cells.isEmpty()) return null
    return GeoArea(
        south = (cells.minOf { it[0] } - 1) * CELL_DEGREES,
        west = (cells.minOf { it[1] } - 1) * CELL_DEGREES,
        north = (cells.maxOf { it[0] } + 2) * CELL_DEGREES,
        east = (cells.maxOf { it[1] } + 2) * CELL_DEGREES,
    )
}

/**
 * The items [scope] takes in (of every source: the caller leaves out what the sources setting hides), newest first, as
 * shown ([shownRow]): equal to the library's items that [ExploreScope.matches]. A year or month is a range of days on
 * the day index; a town or an area is the rows with a location of their own in its box and the photos a place was
 * found for there, each checked by [ExploreScope.matches].
 */
internal fun scopeItemsOf(table: LibraryTable, places: Places, scope: ExploreScope, shownRow: (LibraryRow) -> MediaItem): List<MediaItem> {
    val (first, last) = scope.days()
    if (scope.town == null && scope.area == null) return table.inDays(first, last).map(shownRow).filter { scope.matches(it, places) }
    val box = scope.town?.let { townBox(places, it) ?: return emptyList() } ?: scope.area!!
    fun near(point: GeoPoint) = (scope.area == null || point in scope.area) && (scope.town == null || places.name(point)?.town == scope.town)
    val located = table.locatedIn(box, first, last).filter { (_, point) -> near(point) }.map { it.first }
    val placed = places.locations.filter { (_, point) -> near(point) }.keys
    return table.byIds(located + placed).values.map(shownRow).filter { scope.matches(it, places) }.sortedBy { it.key }
}

/**
 * The box around the library's photos in [town] (of every source), as search sends a town: the place squares named so
 * counted in SQL, and the photos without a location of their own that a place was found for there. Null when none is.
 */
internal fun townArea(table: LibraryTable, places: Places, town: String): GeoArea? {
    val named = places.names.filterValues { it.town == town }.keys
    if (named.isEmpty()) return null
    val boxes = table.cellBoxes().filterKeys { it in named }.values.toMutableList()
    val placed = places.locations.filter { (_, point) -> places.name(point)?.town == town }
    table.unlocated(placed.keys).forEach { row ->
        val point = placed.getValue(row.key.id)
        boxes += GeoArea(point.latitude, point.longitude, point.latitude, point.longitude)
    }
    if (boxes.isEmpty()) return null
    return GeoArea(boxes.minOf { it.south }, boxes.minOf { it.west }, boxes.maxOf { it.north }, boxes.maxOf { it.east })
}

/**
 * [mapOverviewOf] from the database: the located rows' boxes per day and source in SQL (those [shown] shows), and the
 * photos without a location of their own that a place was found for, read by id.
 */
internal fun mapOverviewOf(table: LibraryTable, places: Places, shown: ShownSources): MapOverview {
    val days = table.locatedDays().filter { shown.shows(it.day, it.source) }.map { it.day to it.box }
    val placed = table.unlocated(places.locations.keys).mapNotNull { row ->
        places.locations[row.key.id]?.takeIf { shown.shows(row.key.day, row.source) }?.let { MapPhoto(row.key.id, row.key.day, it) }
    }
    return MapOverview.of(days, placed, table.sourceCounts())
}

/** [mapAreaOf] from the database: the rows counted per square in SQL, with [overview]'s photos placed by a place found. */
internal fun mapAreaOf(table: LibraryTable, overview: MapOverview, shown: ShownSources, visible: GeoArea, zoom: Double, from: Int, to: Int): MapArea {
    val first = LocalDay(from, 1, 1).toEpochDay()
    val last = LocalDay(to + 1, 1, 1).toEpochDay() - 1
    val size = mapCellDegrees(zoom)
    val around = visible.widened()
    val cells = CellSums()
    table.mapCells(size, around, first, last, shown).forEach { cells.add(it.lat to it.lon, it.count, it.latSum, it.lonSum, it.onlyId) }
    val placed = overview.photos.filter { it.day.year in from..to }
    placed.forEach { if (it.point in around) cells.add(size, it.point, it.id) }
    return MapArea(cells.cells(), table.mapCount(visible, first, last, shown) + placed.count { it.point in visible })
}
