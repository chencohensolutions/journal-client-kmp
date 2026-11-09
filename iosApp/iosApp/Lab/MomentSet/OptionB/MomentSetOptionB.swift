#if DEBUG

import SwiftUI

/// Option b, "Viewer with a scrubber": tapping a set in Moments opens it straight in the viewer, on its main photo.
/// Swipes cross-fade one photo at a time; for many, the scrubber under the photo holds all of them in one row and a
/// finger slid along it flips through as fast as it moves. The grid button at the top shows them all as squares.
/// Make main, Play and Recompose are under the photo.
struct MomentSetScreenB: View {
    @State var set: MomentSetState
    private let start: Int?
    private let playing: Bool
    private let recomposing: Bool

    init(set: MomentSetState, at start: Int? = nil, playing: Bool = false, recomposing: Bool = false) {
        _set = State(initialValue: set)
        self.start = start
        self.playing = playing
        self.recomposing = recomposing
    }

    var body: some View {
        // Over the lab's back button, as option a's viewer is, so it doesn't sit on the close button (shake goes back).
        Color.black.ignoresSafeArea()
            .toolbar(.hidden, for: .navigationBar)
            .fullScreenCover(isPresented: .constant(true)) {
                SetViewer(set: $set, index: start ?? set.mainIndex, strip: true, playing: playing, recomposing: recomposing) {}
            }
    }
}

#endif
