import SwiftUI

// The home screen's top bar (the title row and the bar under it) slides away as the list scrolls down and comes back
// as it scrolls up (asked Oct 24, 2026). As Android's TopBar.

/// What follows the scroll every frame, kept apart so that only the title row redraws (and the bottom controls, when
/// the list turns between going up and down).
@Observable final class HomeScroll {
    /// How far the title row has slid up: zero (shown) to minus its height (hidden).
    private(set) var titleShift: CGFloat = 0
    /// The title row's own height, once measured.
    var titleHeight: CGFloat = 0
    /// The tabs and the backup pill slid away, while the list goes down; the month pill stays (asked Oct 24, 2026).
    private(set) var bottomHidden = false

    /// The list moved by `delta` (down is positive); at its top the title always shows.
    func scrolled(by delta: CGFloat, atTop: Bool) {
        let shift = atTop ? 0 : min(0, max(-titleHeight, titleShift - delta))
        if shift != titleShift { titleShift = shift }
        let hide = atTop ? false : delta > 2 ? true : delta < -2 ? false : bottomHidden
        if hide != bottomHidden { withAnimation(.snappy(duration: 0.25)) { bottomHidden = hide } }
    }

    /// Shows the title and the bottom controls again (another tab, search closed, the ruler opened, picking).
    func showTitle() {
        if titleShift != 0 { titleShift = 0 }
        if bottomHidden { withAnimation(.snappy(duration: 0.25)) { bottomHidden = false } }
    }
}

/// The title row sliding away with the scroll: its bottom shows as it shrinks, fading out.
struct CollapsingTitle<Content: View>: View {
    let scroll: HomeScroll
    @ViewBuilder let content: () -> Content

    var body: some View {
        let height = scroll.titleHeight
        content()
            .fixedSize(horizontal: false, vertical: true)
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { scroll.titleHeight = $0 }
            .frame(height: height > 0 ? max(0, height + scroll.titleShift) : nil, alignment: .bottom)
            .clipped()
            .opacity(height > 0 ? 1 + scroll.titleShift / height : 1)
    }
}
