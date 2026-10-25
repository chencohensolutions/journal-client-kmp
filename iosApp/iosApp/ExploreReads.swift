import SwiftUI
import SharedLogic

/// Where Explore and the map read the library: in the app from the library database, through the stories controller
/// (docs/plans/library-on-disk.md, step 6c); without one (the UI lab), from the items of the `LibraryState` given. As
/// Android's `ExploreReads`.
struct ExploreReads {
    let controller: StoriesController?

    /// Explore's lists for `stories` (its places, sources shown and home).
    @MainActor
    func summary(_ library: LibraryState, _ stories: StoriesState) async -> ExploreSummary? {
        if let controller { return try? await controller.explore(state: stories) }
        return await Task.detached(priority: .userInitiated) {
            ExploreKt.exploreOf(library: library.days.flatMap { $0.items }, places: stories.places, shown: stories.shownSources, known: stories.home)
        }.value
    }

    /// Everything `scope` takes in, of every source (the caller leaves out what `stories.shows` hides), newest first.
    @MainActor
    func scope(_ library: LibraryState, _ stories: StoriesState, _ scope: ExploreScope) async -> [MediaItem] {
        if let controller { return (try? await controller.scopeItems(scope: scope, state: stories)) ?? [] }
        return await Task.detached(priority: .userInitiated) {
            library.days.flatMap { $0.items }.filter { scope.matches(item: $0, places: stories.places) }
        }.value
    }

    /// What `scope` takes in that the sources setting shows: the viewer's list from an Explore page or the map.
    @MainActor
    func shown(_ library: LibraryState, _ stories: StoriesState, _ scope: ExploreScope) async -> [MediaItem] {
        await self.scope(library, stories, scope).filter { stories.shows(item: $0) }
    }

    /// The located photos shown, at a glance: their years and where the map opens.
    @MainActor
    func mapOverview(_ library: LibraryState, _ stories: StoriesState) async -> MapOverview? {
        if let controller { return try? await controller.mapOverview(state: stories) }
        return await Task.detached(priority: .userInitiated) {
            ExploreMapKt.mapOverviewOf(library: library.days.flatMap { $0.items }, places: stories.places, shown: stories.shownSources)
        }.value
    }

    /// The photos the map draws around `visible` at `zoom` for the years `from` to `to`, and how many are in view.
    @MainActor
    func mapArea(_ stories: StoriesState, _ overview: MapOverview, visible: GeoArea, zoom: Double, from: Int, to: Int) async -> MapArea? {
        if let controller {
            return try? await controller.mapArea(overview: overview, visible: visible, zoom: zoom, from: Int32(from), to: Int32(to), state: stories)
        }
        return await Task.detached(priority: .userInitiated) {
            ExploreMapKt.mapAreaOf(overview: overview, visible: visible, zoom: zoom, from: Int32(from), to: Int32(to))
        }.value
    }
}

private struct ExploreReadsKey: EnvironmentKey {
    static let defaultValue = ExploreReads(controller: nil)
}

extension EnvironmentValues {
    /// The app sets the database's (`LibraryRootView`); the UI lab keeps the items'.
    var exploreReads: ExploreReads {
        get { self[ExploreReadsKey.self] }
        set { self[ExploreReadsKey.self] = newValue }
    }
}
