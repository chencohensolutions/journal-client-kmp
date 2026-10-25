package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.staticCompositionLocalOf
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.stories.ExploreScope
import com.chencohensolution.journal.stories.ExploreSummary
import com.chencohensolution.journal.stories.GeoArea
import com.chencohensolution.journal.stories.MapArea
import com.chencohensolution.journal.stories.MapOverview
import com.chencohensolution.journal.stories.StoriesController
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.exploreOf
import com.chencohensolution.journal.stories.mapAreaOf
import com.chencohensolution.journal.stories.mapOverviewOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where Explore and the map read the library: in the app from the database, through [StoriesController]
 * (docs/plans/library-on-disk.md, step 6c); in previews and screenshots from the items of the [LibraryState] given.
 */
internal interface ExploreReads {
    /** Explore's lists for [stories] (its places, sources shown and home). */
    suspend fun summary(library: LibraryState, stories: StoriesState): ExploreSummary

    /** Everything [scope] takes in, of every source (the caller leaves out what [StoriesState.shows] hides), newest first. */
    suspend fun scope(library: LibraryState, stories: StoriesState, scope: ExploreScope): List<MediaItem>

    suspend fun mapOverview(library: LibraryState, stories: StoriesState): MapOverview

    /** The photos the map draws around [visible] at [zoom] for the years [from] to [to], and how many are in view. */
    suspend fun mapArea(stories: StoriesState, overview: MapOverview, visible: GeoArea, zoom: Double, from: Int, to: Int): MapArea
}

/** From the items of the library given: previews and screenshots, which have no database. */
internal object ItemsExploreReads : ExploreReads {
    override suspend fun summary(library: LibraryState, stories: StoriesState) = withContext(Dispatchers.Default) { summaryOf(library, stories) }

    override suspend fun scope(library: LibraryState, stories: StoriesState, scope: ExploreScope) = withContext(Dispatchers.Default) {
        library.days.flatMap { it.items }.filter { scope.matches(it, stories.places) }
    }

    override suspend fun mapOverview(library: LibraryState, stories: StoriesState) = withContext(Dispatchers.Default) {
        mapOverviewOf(library.days.flatMap { it.items }, stories.places, stories.shownSources)
    }

    override suspend fun mapArea(stories: StoriesState, overview: MapOverview, visible: GeoArea, zoom: Double, from: Int, to: Int) =
        withContext(Dispatchers.Default) { mapAreaOf(overview, visible, zoom, from, to) }

    /** Explore's lists from the items at once: what previews draw. */
    fun summaryOf(library: LibraryState, stories: StoriesState): ExploreSummary =
        exploreOf(library.days.flatMap { it.items }, stories.places, stories.shownSources, stories.home)
}

/** From the library database, through [controller]. */
internal class DatabaseExploreReads(private val controller: StoriesController) : ExploreReads {
    override suspend fun summary(library: LibraryState, stories: StoriesState) = controller.explore(stories)

    override suspend fun scope(library: LibraryState, stories: StoriesState, scope: ExploreScope) = controller.scopeItems(scope, stories)

    override suspend fun mapOverview(library: LibraryState, stories: StoriesState) = controller.mapOverview(stories)

    override suspend fun mapArea(stories: StoriesState, overview: MapOverview, visible: GeoArea, zoom: Double, from: Int, to: Int) =
        controller.mapArea(overview, visible, zoom, from, to, stories)
}

/** The [ExploreReads] of the screens below; the app provides the database's. */
internal val LocalExploreReads = staticCompositionLocalOf<ExploreReads> { ItemsExploreReads }
