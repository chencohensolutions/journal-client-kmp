import SwiftUI

/// Ids of the zoom transitions: a photo opening the viewer, a story card or a group tile opening its page.
enum ZoomID {
    static func item(_ id: String) -> String { "item|\(id)" }
    static func story(_ id: String) -> String { "story|\(id)" }
    static func group(_ id: String) -> String { "group|\(id)" }
}

extension EnvironmentValues {
    /// Set by the library root, which presents the screens that zoom out of tiles.
    @Entry var zoomNamespace: Namespace.ID?
}

extension View {
    /// A tile or card that the screen it opens zooms out of, and back into when closed.
    func zoomSource(_ id: String) -> some View { modifier(ZoomSource(id: id)) }
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
