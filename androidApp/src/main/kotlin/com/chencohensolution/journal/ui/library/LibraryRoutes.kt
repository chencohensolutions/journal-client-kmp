package com.chencohensolution.journal.ui.library

import android.provider.Settings
import java.net.URLDecoder
import java.net.URLEncoder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.stories.CropKind
import com.chencohensolution.journal.stories.ExploreScope
import com.chencohensolution.journal.stories.GeoArea
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryFilter
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.search.SearchState

// The library's screens as a back stack of routes (saved as strings across rotation), and what the viewer shows for each.

/** Library screens, as a back stack. Each is encoded as a string so the stack survives rotation. */
internal sealed interface Route {
    data object Journal : Route
    data object AllPhotos : Route
    data object Tags : Route
    data class Tag(val name: String) : Route
    data object Settings : Route
    data object FreeUp : Route
    data object Hidden : Route

    /** Settings › Search. */
    data object SearchSettings : Route

    /** A person's page, or a group's ("Who's this?"). */
    data class Person(val id: String) : Route

    /** The photos that look like [itemId]'s (its details › Looks like this › More). */
    data class Similar(val itemId: String) : Route

    /**
     * Explore: a country's page (null: the towns whose country isn't known), a town's page, a year's page (on one of its
     * months), and the map (on [Map.at] close up, from a photo's details; else showing every photo).
     */
    data class Country(val country: String?) : Route
    data class Place(val town: String) : Route
    data class Year(val year: Int, val month: Int?) : Route
    data class Map(val at: GeoPoint? = null) : Route

    /** A sound recording's page, by its sha256. */
    data class Audio(val sha256: String) : Route

    /** The crop screen for one photo, opened on [kind]. */
    data class Crop(val itemId: String, val kind: CropKind) : Route

    /**
     * Opened from the storyline ([storyline]), a tag ([tag]), one row of the Moments tab
     * ([moment], its [com.chencohensolution.journal.stories.FeedSection.key]), All photos with [filter], or Explore
     * ([scope]: the map's photos, or with [moment] a row of a town's or a year's page). With [moment] and [revealed],
     * the band of that hidden source's photos on the Moments row. With [search], the photos the last search found.
     * With [list], the photos a page showed when it opened the viewer (a person's, similar photos): see [PageLists].
     */
    data class Viewer(
        val moment: String?,
        val filter: LibraryFilter,
        val itemId: String,
        val tag: String? = null,
        val scope: ExploreScope? = null,
        val revealed: MediaSource? = null,
        val storyline: Boolean = false,
        val search: Boolean = false,
        val list: String? = null,
    ) : Route

    // Tags are free text and moment keys are item ids, so they are URL-encoded to keep "|" out.
    fun encode(): String = when (this) {
        Journal -> "journal"
        AllPhotos -> "all"
        Tags -> "tags"
        is Tag -> "tag|${URLEncoder.encode(name, "UTF-8")}"
        Settings -> "settings"
        FreeUp -> "free-up"
        Hidden -> "hidden"
        SearchSettings -> "search-settings"
        is Person -> "person|$id"
        is Similar -> "similar|$itemId"
        is Crop -> "crop|$kind|$itemId"
        is Audio -> "audio|$sha256"
        is Country -> "country|${country?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}"
        is Place -> "place|${URLEncoder.encode(town, "UTF-8")}"
        is Year -> "year|$year|${month ?: ""}"
        is Map -> "map|${at?.let { "${it.latitude},${it.longitude}" }.orEmpty()}"
        is Viewer -> "viewer|${if (storyline) "storyline" else if (search) "search" else ""}|${moment?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}|$filter|" +
            "${tag?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}|${scope?.let { URLEncoder.encode(encodeScope(it), "UTF-8") }.orEmpty()}|${revealed?.name.orEmpty()}|" +
            "${list?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}|$itemId"
    }

    companion object {
        fun decode(value: String): Route {
            val parts = value.split("|")
            return when (parts[0]) {
                "all" -> AllPhotos
                "tags" -> Tags
                "tag" -> Tag(URLDecoder.decode(parts[1], "UTF-8"))
                "settings" -> Settings
                "free-up" -> FreeUp
                "hidden" -> Hidden
                "search-settings" -> SearchSettings
                "phone-search" -> SearchSettings // On this phone, a screen of its own until Oct 24, 2026
                "person" -> Person(parts[1])
                "similar" -> Similar(parts.drop(1).joinToString("|"))
                "audio" -> Audio(parts[1])
                "crop" -> Crop(parts.drop(2).joinToString("|"), CropKind.valueOf(parts[1]))
                "country" -> Country(parts[1].ifEmpty { null }?.let { URLDecoder.decode(it, "UTF-8") })
                "place" -> Place(URLDecoder.decode(parts[1], "UTF-8"))
                "year" -> Year(parts[1].toInt(), parts[2].toIntOrNull())
                "map" -> Map(parts.getOrNull(1)?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { (lat, lng) -> GeoPoint(lat, lng) })
                "viewer" -> Viewer(
                    storyline = parts[1] == "storyline",
                    search = parts[1] == "search",
                    moment = parts[2].ifEmpty { null }?.let { URLDecoder.decode(it, "UTF-8") },
                    filter = LibraryFilter.valueOf(parts[3]),
                    tag = parts[4].ifEmpty { null }?.let { URLDecoder.decode(it, "UTF-8") },
                    scope = parts[5].ifEmpty { null }?.let { decodeScope(URLDecoder.decode(it, "UTF-8")) },
                    revealed = MediaSource.entries.firstOrNull { it.name == parts[6] },
                    list = parts[7].ifEmpty { null }?.let { URLDecoder.decode(it, "UTF-8") },
                    itemId = parts.drop(8).joinToString("|"),
                )
                else -> Journal
            }
        }
    }
}

/** An Explore scope as text: its fields in order, tab-separated, empty when not set; the area as "south,west,north,east". */
private fun encodeScope(scope: ExploreScope): String = with(scope) {
    listOf(town, year, month, fromYear, toYear, area?.let { "${it.south},${it.west},${it.north},${it.east}" }).joinToString("\t") { it?.toString().orEmpty() }
}

private fun decodeScope(value: String): ExploreScope {
    val parts = value.split("\t")
    return ExploreScope(
        town = parts[0].ifEmpty { null },
        year = parts[1].toIntOrNull(),
        month = parts[2].toIntOrNull(),
        fromYear = parts[3].toIntOrNull(),
        toYear = parts[4].toIntOrNull(),
        area = parts[5].split(",").mapNotNull { it.toDoubleOrNull() }.takeIf { it.size == 4 }?.let { (s, w, n, e) -> GeoArea(s, w, n, e) },
    )
}

internal val BackStackSaver = listSaver<List<Route>, String>(
    save = { stack -> stack.map(Route::encode) },
    restore = { saved -> saved.map(Route::decode) },
)

/** The photo the viewer shows, for the entry at [depth] under it. */
internal data class Landing(val depth: Int, val itemId: String)

/** A back stack entry; its depth tells apart a screen opened twice. */
internal data class StackEntry(val depth: Int, val route: Route) {
    val contentKey get() = "$depth|${route.encode()}"
}

/**
 * The items the viewer swipes through: the storyline, the row of the Moments tab or an Explore page (as shown there),
 * the map's photos, a tag's photos, what [search] found, or everything in the All photos filter.
 */
internal fun viewerItems(library: LibraryState, stories: StoriesState, route: Route.Viewer, search: SearchState, lists: PageLists = PageLists()): List<MediaItem> = when {
    route.search -> search.items
    route.list != null -> lists[route.list].mapNotNull { stories.itemsById[it] }
    route.scope != null && route.moment != null -> stories.feedOf(scopeItems(library, stories, route.scope)).section(route.moment)?.items.orEmpty()
    route.scope != null -> scopeItems(library, stories, route.scope)
    route.tag != null -> stories.tagged(route.tag).flatMap { it.items }
    route.storyline -> stories.storyline
    route.moment != null && route.revealed != null ->
        stories.feed.section(route.moment)?.revealed?.firstOrNull { it.source == route.revealed }?.items.orEmpty()
    route.moment != null -> stories.feed.section(route.moment)?.items.orEmpty()
    else -> stories.shownDays(library.days(route.filter)).flatMap { it.items }
}

/**
 * The photos pages showed when they opened the viewer (a person's page, similar photos), by the page's key, so the
 * viewer swipes through them in that order. Kept while the app runs; a list not here (after the process was killed)
 * is empty, and the viewer closes.
 */
internal class PageLists {
    private val lists = HashMap<String, List<String>>()

    operator fun get(key: String): List<String> = lists[key].orEmpty()

    operator fun set(key: String, ids: List<String>) {
        lists[key] = ids
    }
}

/** The photos an Explore page or the map shows, newest first. */
internal fun scopeItems(library: LibraryState, stories: StoriesState, scope: ExploreScope): List<MediaItem> =
    exploreItems(library, stories).filter { scope.matches(it, stories.places) }
