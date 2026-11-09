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
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.search.SearchState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The library's screens as a back stack of routes (saved as strings across rotation), and what the viewer shows for each.

/** Library screens, as a back stack. Each is encoded as a string so the stack survives rotation. */
internal sealed interface Route {
    data object Journal : Route
    data object Tags : Route
    data class Tag(val name: String) : Route
    data object Settings : Route

    /** One of Settings' pages, opened from its first page. */
    data class SettingsPageRoute(val page: SettingsPage) : Route
    data object FreeUp : Route
    data object Hidden : Route

    /** Settings › Your cameras. */
    data object Cameras : Route

    /** Settings › Sound folders. */
    data object SoundFolders : Route

    /** Settings › Search. */
    data object SearchSettings : Route

    /** Settings › Models on this phone. */
    data object Models : Route

    /** Settings › Backup. */
    data object BackupSettings : Route

    /** Settings › Backup › Copies on other disks. */
    data object Copies : Route

    /** Settings › Backup › Swap with a friend, and its pages: an invite, joining one (or an offer back), the passphrase. */
    data object Swap : Route
    data object SwapHere : Route
    data class SwapThere(val offerId: String? = null) : Route
    data object CopyPassphrase : Route

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

    /** The crop screen's set mode (Recompose): the set's Moments crops, [list] its viewer list ([setListKey]), on [itemId]. */
    data class Recompose(val list: String, val itemId: String) : Route

    /**
     * Opened from the storyline ([storyline]), a tag ([tag]), one row of the Moments tab
     * ([moment], its [com.chencohensolution.journal.stories.FeedSection.key]), or Explore
     * ([scope]: the map's photos, or with [moment] a row of a town's or a year's page). With [moment] and [revealed],
     * the band of that hidden source's photos on the Moments row. With [search], the photos the last search found.
     * With [list], the photos a page showed when it opened the viewer (a person's, similar photos): see [PageLists]; a
     * set's list ([setListKey]) opens it in its set mode ([isSet]).
     */
    data class Viewer(
        val moment: String?,
        val itemId: String,
        val tag: String? = null,
        val scope: ExploreScope? = null,
        val revealed: MediaSource? = null,
        val storyline: Boolean = false,
        val search: Boolean = false,
        val list: String? = null,
    ) : Route {
        val isSet: Boolean get() = setIdsOf(list) != null
    }

    // Tags are free text and moment keys are item ids, so they are URL-encoded to keep "|" out.
    fun encode(): String = when (this) {
        Journal -> "journal"
        Tags -> "tags"
        is Tag -> "tag|${URLEncoder.encode(name, "UTF-8")}"
        Settings -> "settings"
        is SettingsPageRoute -> "settings-page|${page.name}"
        FreeUp -> "free-up"
        Hidden -> "hidden"
        Cameras -> "cameras"
        SoundFolders -> "sound-folders"
        SearchSettings -> "search-settings"
        Models -> "models"
        BackupSettings -> "backup-settings"
        Copies -> "copies"
        Swap -> "swap"
        SwapHere -> "swap-here"
        is SwapThere -> "swap-there|${offerId.orEmpty()}"
        CopyPassphrase -> "copy-passphrase"
        is Person -> "person|$id"
        is Similar -> "similar|$itemId"
        is Crop -> "crop|$kind|$itemId"
        is Recompose -> "recompose|${URLEncoder.encode(list, "UTF-8")}|$itemId"
        is Audio -> "audio|$sha256"
        is Country -> "country|${country?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}"
        is Place -> "place|${URLEncoder.encode(town, "UTF-8")}"
        is Year -> "year|$year|${month ?: ""}"
        is Map -> "map|${at?.let { "${it.latitude},${it.longitude}" }.orEmpty()}"
        // The empty fourth field was All photos' filter (until Oct 24, 2026): kept, so routes saved before still decode.
        is Viewer -> "viewer|${if (storyline) "storyline" else if (search) "search" else ""}|${moment?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}||" +
            "${tag?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}|${scope?.let { URLEncoder.encode(encodeScope(it), "UTF-8") }.orEmpty()}|${revealed?.let { URLEncoder.encode(it.id, "UTF-8") }.orEmpty()}|" +
            "${list?.let { URLEncoder.encode(it, "UTF-8") }.orEmpty()}|$itemId"
    }

    companion object {
        fun decode(value: String): Route {
            val parts = value.split("|")
            return when (parts[0]) {
                "all" -> Journal // All photos, until Oct 24, 2026
                "tags" -> Tags
                "tag" -> Tag(URLDecoder.decode(parts[1], "UTF-8"))
                "settings" -> Settings
                "settings-page" -> SettingsPageRoute(SettingsPage.valueOf(parts[1]))
                "free-up" -> FreeUp
                "hidden" -> Hidden
                "cameras" -> Cameras
                "sound-folders" -> SoundFolders
                "search-settings" -> SearchSettings
                "models" -> Models
                "backup-settings" -> BackupSettings
                "copies" -> Copies
                "swap" -> Swap
                "swap-here" -> SwapHere
                "swap-there" -> SwapThere(parts.getOrNull(1)?.ifEmpty { null })
                // Behind the phone's lock, never brought back by itself: back to the Swap page.
                "copy-passphrase" -> Swap
                "phone-search" -> SearchSettings // On this phone, a screen of its own until Oct 24, 2026
                "person" -> Person(parts[1])
                "similar" -> Similar(parts.drop(1).joinToString("|"))
                "audio" -> Audio(parts[1])
                "crop" -> Crop(parts.drop(2).joinToString("|"), CropKind.valueOf(parts[1]))
                "recompose" -> Recompose(URLDecoder.decode(parts[1], "UTF-8"), parts.drop(2).joinToString("|"))
                "country" -> Country(parts[1].ifEmpty { null }?.let { URLDecoder.decode(it, "UTF-8") })
                "place" -> Place(URLDecoder.decode(parts[1], "UTF-8"))
                "year" -> Year(parts[1].toInt(), parts[2].toIntOrNull())
                "map" -> Map(parts.getOrNull(1)?.split(",")?.mapNotNull { it.toDoubleOrNull() }?.takeIf { it.size == 2 }?.let { (lat, lng) -> GeoPoint(lat, lng) })
                "viewer" -> Viewer(
                    storyline = parts[1] == "storyline",
                    search = parts[1] == "search",
                    moment = parts[2].ifEmpty { null }?.let { URLDecoder.decode(it, "UTF-8") },
                    tag = parts[4].ifEmpty { null }?.let { URLDecoder.decode(it, "UTF-8") },
                    scope = parts[5].ifEmpty { null }?.let { decodeScope(URLDecoder.decode(it, "UTF-8")) },
                    revealed = parts[6].ifEmpty { null }?.let { MediaSource.parse(URLDecoder.decode(it, "UTF-8")) },
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
 * the map's photos, a tag's photos, what [search] found, or what a page showed ([PageLists]). Nothing else opens it.
 */
internal fun viewerItems(library: LibraryState, stories: StoriesState, route: Route.Viewer, search: SearchState, lists: PageLists = PageLists()): List<MediaItem> = when {
    route.search -> search.items
    // A set: as opened, else (after the process was killed) its photos by id, those not read yet read as it shows.
    route.isSet -> lists[route.list!!].ifEmpty { setIdsOf(route.list).orEmpty().mapNotNull { stories.itemsById[it] } }
        .mapNotNull { item -> stories.itemsById[item.id] ?: item.takeUnless { stories.gone(it.id) } }
    route.list != null -> lists[route.list].mapNotNull { item -> stories.itemsById[item.id] ?: item.takeUnless { stories.gone(it.id) } }
    // As the page or the map gave them; when they aren't kept (after the process was killed) they are read again
    // (scopeViewerItems).
    route.scope != null -> lists[scopeListKey(route.scope, route.moment)].mapNotNull { item -> stories.itemsById[item.id] ?: item.takeUnless { stories.gone(it.id) } }
    route.tag != null -> stories.tagged(route.tag).flatMap { it.items }
    route.storyline -> stories.storyline
    route.moment != null && route.revealed != null ->
        stories.feed.section(route.moment)?.revealed?.firstOrNull { it.source == route.revealed }?.items.orEmpty()
    route.moment != null -> stories.feed.section(route.moment)?.items.orEmpty()
    else -> emptyList()
}

/** The viewer's photo [id] and up to this many neighbours each way: what it asks the database for as it is swiped. */
private const val VIEWER_NEIGHBOURS = 50

/** [id] and its neighbours in these ids (from the start when it isn't here). */
internal fun List<String>.around(id: String?): List<String> {
    val at = indexOf(id).coerceAtLeast(0)
    return subList(maxOf(0, at - VIEWER_NEIGHBOURS), minOf(size, at + VIEWER_NEIGHBOURS + 1))
}

/**
 * The photos pages showed when they opened the viewer (a person's page, similar photos), by the page's key, so the
 * viewer swipes through them in that order. Kept while the app runs; a list not here (after the process was killed)
 * is empty, and the viewer closes. The items themselves are kept: StoriesState.itemsById holds only part of the library
 * (docs/plans/library-on-disk.md, step 6a), and they show until the database's are read.
 */
internal class PageLists {
    private val lists = HashMap<String, List<MediaItem>>()

    operator fun get(key: String): List<MediaItem> = lists[key].orEmpty()

    operator fun set(key: String, items: List<MediaItem>) {
        lists[key] = items
    }
}

/** The [PageLists] key of the photos of [scope] (on its row [moment]) a page or the map opened the viewer on. */
internal fun scopeListKey(scope: ExploreScope, moment: String?): String = "scope:${encodeScope(scope)}|${moment.orEmpty()}"

/**
 * The photos the viewer swipes through for an Explore page's row ([moment]) or the map's [scope], read from the
 * database (docs/plans/library-on-disk.md, step 6c): of the sources shown, newest first, the row's as the page makes it.
 */
internal suspend fun scopeViewerItems(reads: ExploreReads, library: LibraryState, stories: StoriesState, scope: ExploreScope, moment: String?): List<MediaItem> {
    val items = reads.scope(library, stories, scope).filter(stories::shows)
    return if (moment == null) items else withContext(Dispatchers.Default) { stories.feedOf(items).section(moment)?.items.orEmpty() }
}
