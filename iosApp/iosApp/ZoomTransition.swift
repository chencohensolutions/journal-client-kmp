import SwiftUI

/// Ids of the zoom transitions: a photo opening the viewer.
enum ZoomID {
    static func item(_ id: String) -> String { "item|\(id)" }
}

/// The photo the open viewer shows; `session` is new each time the viewer opens.
struct ViewerShowing: Equatable {
    let session: UUID
    let itemId: String
}

/// Where a screen shows a photo: the row of its lazy stack, which the stack can always scroll to, and the tile in it.
struct PhotoPlace {
    let section: AnyHashable
    let tile: String
}

extension EnvironmentValues {
    /// Set by the library root, which presents the screens that zoom out of tiles.
    @Entry var zoomNamespace: Namespace.ID?
    /// Set by the library root while the viewer is open.
    @Entry var viewerShowing: ViewerShowing?
}

extension View {
    /// A tile or card that the screen it opens zooms out of, and back into when closed.
    func zoomSource(_ id: String) -> some View { modifier(ZoomSource(id: id)).id(id) }

    /// Keeps the tile of the photo the viewer shows in view as it is swiped, so closing the viewer zooms back into it.
    /// Goes on a scroll view's lazy stack; `place` finds a photo on this screen, or nil.
    func followsViewer(_ place: @escaping (String) -> PhotoPlace?) -> some View {
        ScrollViewReader { proxy in modifier(FollowViewer(proxy: proxy, place: place)) }
    }
}

private struct ZoomSource: ViewModifier {
    @Environment(\.zoomNamespace) private var namespace
    let id: String

    func body(content: Content) -> some View {
        if let namespace {
            content.matchedTransitionSource(id: id, in: namespace)
        } else {
            content
        }
    }
}

private struct FollowViewer: ViewModifier {
    @Environment(\.viewerShowing) private var showing
    let proxy: ScrollViewProxy
    let place: (String) -> PhotoPlace?

    @State private var session: UUID?
    @State private var section: AnyHashable?

    func body(content: Content) -> some View {
        content.onChange(of: showing) { _, showing in
            guard let showing else { return }
            let target = place(showing.itemId)
            defer {
                session = showing.session
                section = target?.section
            }
            // Just opened, from a tile in view.
            guard showing.session == session, let target else { return }
            if target.section == section {
                // Its row is laid out, so the stack finds the tile.
                proxy.scrollTo(target.tile)
            } else {
                // A lazy stack lays out only rows near the screen: first the row, then the tile in it.
                proxy.scrollTo(target.section)
                DispatchQueue.main.async { proxy.scrollTo(target.tile) }
            }
        }
    }
}
