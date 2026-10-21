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
import com.chencohensolution.journal.library.LibraryFilter
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource

// The library's screens as a back stack of routes (saved as strings across rotation), and what the viewer shows for each.

/** Library screens, as a back stack. Each is encoded as a string so the stack survives rotation. */
internal sealed interface Route {
    data object Journal : Route
    data object AllPhotos : Route
    data class Story(val id: String) : Route
    data class Group(val storyId: String, val groupId: String) : Route
    data object Tags : Route
    data class Tag(val name: String) : Route
    data object Settings : Route
    data object FreeUp : Route

    /** Explore: a country's page (null: the towns whose country isn't known), a town's page, a year's page (on one of its months), and the map. */
    data class Country(val country: String?) : Route
    data class Place(val town: String) : Route
    data class Year(val year: Int, val month: Int?) : Route
    data object Map : Route

    /** The crop screen for one photo, opened on [kind]. */
    data class Crop(val itemId: String, val kind: CropKind) : Route

    /**
     * Opened from a group ([storyId] and [groupId]), a story ([storyId]), a tag ([tag]), one row of the Moments tab
     * ([moment], its [com.chencohensolution.journal.stories.FeedSection.key]), All photos with [filter], or Explore
     * ([scope]: the map's photos, or with [moment] a row of a town's or a year's page). With [moment] and [revealed],
     * the band of that hidden source's photos on the Moments row.
     */
    data class Viewer(
        val storyId: String?,
        val groupId: String?,
        val moment: String?,
        val filter: LibraryFilter,
        val itemId: String,
        val tag: String? = null,
        val scope: ExploreScope? = null,
        val revealed: MediaSource? = null,
    ) : Route

    // Tags are free text and moment keys are item ids, so they are URL-encoded to keep "|" out.
    fun encode(): String = when (this) {
        Journal -> "journal"
        AllPhotos -> "all"
        is Story -> "story|$id"
        is Group -> "group|$storyId|$groupId"
        Tags -> "tags"
        is Tag -> "tag|${URLEncoder.encode(name, "UTF-8")}"
        Settings -> "settings"
        FreeUp -> "free-up"
        is Crop -> "crop|$kind|$itemId"
        is Country -> "country|${country?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}"
        is Place -> "place|${URLEncoder.encode(town, "UTF-8")}"
        is Year -> "year|$year|${month ?: ""}"
        Map -> "map"
        is Viewer -> "viewer|${storyId.orEmpty()}|${groupId.orEmpty()}|${moment?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}|$filter|" +
            "${tag?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}|${scope?.let { URLEncoder.encode(encodeScope(it), "UTF-8") }.orEmpty()}|${revealed?.name.orEmpty()}|$itemId"
    }

    companion object {
        fun decode(value: String): Route {
            val parts = value.split("|")
            return when (parts[0]) {
                "all" -> AllPhotos
                "story" -> Story(parts[1])
                "group" -> Group(parts[1], parts[2])
                "tags" -> Tags
                "tag" -> Tag(URLDecoder.decode(parts[1], "UTF-8"))
                "settings" -> Settings
                "free-up" -> FreeUp
                "crop" -> Crop(parts.drop(2).joinToString("|"), CropKind.valueOf(parts[1]))
                "country" -> Country(parts[1].ifEmpty { null }?.let { URLDecoder.decode(it, "UTF-8") })
                "place" -> Place(URLDecoder.decode(parts[1], "UTF-8"))
                "year" -> Year(parts[1].toInt(), parts[2].toIntOrNull())
                "map" -> Map
                "viewer" -> Viewer(
                    storyId = parts[1].ifEmpty { null },
                    groupId = parts[2].ifEmpty { null },
                    moment = parts[3].ifEmpty { null }?.let { URLDecoder.decode(it, "UTF-8") },
                    filter = LibraryFilter.valueOf(parts[4]),
                    tag = parts[5].ifEmpty { null }?.let { URLDecoder.decode(it, "UTF-8") },
                    scope = parts[6].ifEmpty { null }?.let { decodeScope(URLDecoder.decode(it, "UTF-8")) },
                    revealed = MediaSource.entries.firstOrNull { it.name == parts[7] },
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

/** A back stack entry; its depth tells apart a group opened twice, from its parent and from the viewer. */
internal data class StackEntry(val depth: Int, val route: Route) {
    val contentKey get() = "$depth|${route.encode()}"
}

/**
 * The items the viewer swipes through: the group or story it was opened from (where a group is one stop, its cover),
 * the row of the Moments tab or an Explore page (as shown there), the map's photos, or everything in the All photos filter.
 */
internal fun viewerItems(library: LibraryState, stories: StoriesState, route: Route.Viewer): List<MediaItem> = when {
    route.scope != null && route.moment != null -> stories.feedOf(scopeItems(library, stories, route.scope)).section(route.moment)?.items.orEmpty()
    route.scope != null -> scopeItems(library, stories, route.scope)
    route.tag != null -> stories.tagged(route.tag).flatMap { it.items }
    route.storyId != null && route.groupId != null -> stories.story(route.storyId)?.group(route.groupId)?.viewerItems.orEmpty()
    route.storyId != null -> stories.story(route.storyId)?.viewerItems.orEmpty()
    route.moment != null && route.revealed != null ->
        stories.feed.section(route.moment)?.revealed?.firstOrNull { it.source == route.revealed }?.items.orEmpty()
    route.moment != null -> stories.feed.section(route.moment)?.items.orEmpty()
    else -> stories.shownDays(library.days(route.filter)).flatMap { it.items }
}

/** The photos an Explore page or the map shows, newest first. */
internal fun scopeItems(library: LibraryState, stories: StoriesState, scope: ExploreScope): List<MediaItem> =
    exploreItems(library, stories).filter { scope.matches(it, stories.places) }
