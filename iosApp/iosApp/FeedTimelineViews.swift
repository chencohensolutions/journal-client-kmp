import SwiftUI
import SharedLogic

// Going to a date in Moments: the month pill above the tabs and, tapped, the ruler it grows into (the month's days and
// a dial of months). The months and days are the shared FeedTimeline; these views draw them and pass the finger on.
// See docs/screens/moments.md, "Going to a date".

/// Ticks under the finger: a light one per day or month, a firmer one per year.
@MainActor
enum ScrubHaptics {
    static let tick = UISelectionFeedbackGenerator()
    static let year = UIImpactFeedbackGenerator(style: .medium)

    /// The tick for going from one month of `timeline` to another.
    static func step(_ timeline: FeedTimeline, from: Int, to: Int) {
        if timeline.crossesYear(from: Int32(from), to: Int32(to)) { year.impactOccurred() } else { tick.selectionChanged() }
    }
}

extension TimelineMonth {
    /// "October 2026".
    var title: String { "\(Calendar.current.monthSymbols[Int(month) - 1]) \(String(year))" }
}

/// Above the tabs: the pill, or the ruler while `open`. `current` is the month at the top of the feed; `go` jumps the
/// feed to a day. Arrows and swipes step from the month they last went to, not from the feed, which takes a moment
/// to settle after a jump.
///
/// The pill and the ruler are one card (`TimelineMorph`), laid out from one animated progress: its size goes from the
/// pill's to the ruler's with its bottom where it is, the ruler rides at its top, uncovered as it grows, and the month's
/// name is one text that moves in a straight line and grows from the pill's middle to the ruler's title. Both are always
/// there, so opening only starts the animation. As Android's TimelineBar.
struct TimelineBar: View {
    let timeline: FeedTimeline
    let current: Int?
    /// The day of the row at the top of the feed: the pill says it with its month ("24 October 2026").
    var day: LocalDay? = nil
    @Binding var open: Bool
    let go: (TimelineDay) -> Void

    /// The month last gone to, shown until the feed has settled on it.
    @State private var sent: Int?
    @State private var settle: Task<Void, Never>?
    /// 0 the pill, 1 the ruler.
    @State private var progress: CGFloat = 0
    @State private var width: CGFloat = 0
    @State private var panelHeight: CGFloat = 0
    @State private var titleSize: CGSize = .zero

    private var shown: Int { min(sent ?? current ?? 0, timeline.months.count - 1) }

    var body: some View {
        let title = name
        TimelineMorph(progress: progress, width: width, panelHeight: panelHeight, titleSize: titleSize, title: title) {
            TimelinePill(timeline: timeline, month: shown, title: title, step: { month in
                hold(month)
                go(timeline.landing(month: Int32(month)))
            }, open: { open = true })
        } panel: {
            TimelinePanel(timeline: timeline, month: shown, onDay: { day in
                hold(shown)
                go(day)
            }, onMonth: { month in
                hold(month)
                go(timeline.landing(month: Int32(month)))
            }, onClose: { open = false })
            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { panelHeight = $0 }
        }
        .frame(maxWidth: .infinity)
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
        .background {
            // The name's size in the ruler, for where it starts and ends.
            Text(verbatim: title).font(TimelineMorph<EmptyView, EmptyView>.titleFont).monospacedDigit().fixedSize().hidden()
                .onGeometryChange(for: CGSize.self) { $0.size } action: { titleSize = $0 }
        }
        .onAppear { progress = open ? 1 : 0 }
        .onChange(of: current) { _, now in
            // The feed got to the month gone to.
            if sent != nil, now == sent {
                settle?.cancel()
                sent = nil
            }
        }
        .onChange(of: open) { _, isOpen in
            // Android's FastOutSlowIn, over the same time.
            withAnimation(.timingCurve(0.4, 0, 0.2, 1, duration: 0.36)) { progress = isOpen ? 1 : 0 }
        }
    }

    /// The day with its month while the pill is closed and the feed has settled ("24 October 2026"); the month alone in
    /// the ruler and while the feed gets to another month.
    private var name: String {
        let month = timeline.months[shown]
        guard sent == nil, !open, let day, day.year == month.year, day.month == month.month else { return month.title }
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("dMMMMy")
        return formatter.string(from: day.date)
    }

    /// Shows `month` until the feed is there (`current`, see `.onChange` in `body`): a month further down is read first,
    /// which can take more than a moment, and showing the feed's old month meanwhile sent the dial back and forth (Oct 26,
    /// 2026). Given up after 4 s, in case the feed never gets there.
    private func hold(_ month: Int) {
        settle?.cancel()
        if current == month {
            sent = nil
            return
        }
        sent = month
        settle = Task { @MainActor in
            try? await Task.sleep(for: .seconds(4))
            if !Task.isCancelled { sent = nil }
        }
    }
}

/// The card the pill and the ruler share, laid out from `progress` (0 the pill, 1 the ruler), which SwiftUI animates
/// frame by frame. Offsets are from the left (the layout is left to right inside, and its parts get the real
/// direction back), so the name's straight line is the same in Hebrew.
private struct TimelineMorph<Pill: View, Panel: View>: View, Animatable {
    var progress: CGFloat
    let width: CGFloat
    let panelHeight: CGFloat
    let titleSize: CGSize
    let title: String
    @ViewBuilder let pill: Pill
    @ViewBuilder let panel: Panel
    @Environment(\.layoutDirection) private var direction

    static var titleFont: Font { .system(size: rulerTitleSize, weight: .semibold) }
    static var rulerTitleSize: CGFloat { 20 }
    static var pillTitleSize: CGFloat { 15 }
    private let pillSize = CGSize(width: 248, height: 40)
    private let gutter: CGFloat = 8
    private let padding: CGFloat = 16

    var animatableData: CGFloat {
        get { progress }
        set { progress = newValue }
    }

    var body: some View {
        let p = progress
        let panelWidth = max(width - 2 * gutter, pillSize.width)
        let cardWidth = lerp(pillSize.width, panelWidth, p)
        let cardHeight = lerp(pillSize.height, max(panelHeight, pillSize.height), p)
        let radius = lerp(20, 28, p)
        let cardX = (width - cardWidth) / 2
        // The name: from the middle of the pill at its size there to the ruler's title, scaled from its top-left, in a
        // straight line on the screen, worked out from the card's bottom, which doesn't move. On it the name stays inside
        // the card, above the ruler's lines that the card uncovers under it.
        let small = Self.pillTitleSize / Self.rulerTitleSize
        let fromX = width / 2 - titleSize.width * small / 2
        let toX = gutter + (direction == .rightToLeft ? panelWidth - padding - titleSize.width : padding)
        let fromAboveBottom = pillSize.height / 2 + titleSize.height * small / 2
        let toAboveBottom = panelHeight - padding
        ZStack(alignment: .topLeading) {
            RoundedRectangle(cornerRadius: radius)
                .fill(LibraryColors.raised)
                .shadow(color: .black.opacity(0.35), radius: lerp(12, 16, p), y: 8)
                .frame(width: cardWidth, height: cardHeight)
                .offset(x: cardX)
            // At the card's bottom, which stays put.
            pill
                .environment(\.layoutDirection, direction)
                .frame(width: pillSize.width, height: pillSize.height)
                .opacity(Double(max(0, 1 - p / 0.3)))
                .allowsHitTesting(p < 0.5)
                .offset(x: (width - pillSize.width) / 2, y: cardHeight - pillSize.height)
            // At the card's top, shown where the card is.
            panel
                .environment(\.layoutDirection, direction)
                .frame(width: panelWidth)
                .fixedSize(horizontal: false, vertical: true)
                .opacity(Double(min(1, max(0, (p - 0.45) / 0.55))))
                .mask(alignment: .topLeading) {
                    RoundedRectangle(cornerRadius: radius)
                        .frame(width: cardWidth, height: cardHeight)
                        .offset(x: cardX - gutter)
                }
                .allowsHitTesting(p > 0.5)
                .offset(x: gutter)
            Text(verbatim: title)
                .font(Self.titleFont)
                .monospacedDigit()
                .foregroundStyle(LibraryColors.onBackground)
                .fixedSize()
                .scaleEffect(lerp(small, 1, p), anchor: .topLeading)
                .offset(x: lerp(fromX, toX, p), y: cardHeight - lerp(fromAboveBottom, toAboveBottom, p))
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .frame(width: max(width, 0), height: cardHeight, alignment: .topLeading)
        .environment(\.layoutDirection, .leftToRight)
    }

    private func lerp(_ a: CGFloat, _ b: CGFloat, _ t: CGFloat) -> CGFloat { a + (b - a) * t }
}

/// "‹ October 2026 ›". The arrows go a month older or newer (held, they keep going); a swipe along the month's name
/// steps a month every 22 pt, right being newer; a tap on it opens the ruler.
private struct TimelinePill: View {
    let timeline: FeedTimeline
    let month: Int
    /// What the pill says: the month, or the day with it.
    let title: String
    let step: (Int) -> Void
    let open: () -> Void

    /// The month a swipe started on; reset when the swipe ends, also when the system cancels it.
    @State private var swipeStart: Int?
    @GestureState private var swiping = false
    private let swipeStep: CGFloat = 22

    var body: some View {
        let oldest = timeline.isOldest(month: Int32(month))
        let newest = timeline.isNewest(month: Int32(month))
        HStack(spacing: 0) {
            arrow("chevron.left", label: "Older month", by: -1).disabled(oldest)
            name
            arrow("chevron.right", label: "Newer month", by: 1).disabled(newest)
        }
        .buttonStyle(.plain)
        .foregroundStyle(LibraryColors.onBackground)
        .frame(width: 248, height: 40)
        .overlay(Capsule().stroke(LibraryColors.onBackground.opacity(swiping ? 0.45 : 0)))
        .onChange(of: swiping) { _, now in if !now { swipeStart = nil } }
        .animation(.snappy(duration: 0.15), value: month)
        .animation(.snappy(duration: 0.2), value: swiping)
    }

    /// The month's name: swiped along it steps months, tapped it opens the ruler.
    private var name: some View {
        let tap = TapGesture().onEnded { open() }
        // Its place only: the bar draws the name, which moves between here and the ruler's title.
        return Text(verbatim: title)
            .font(.system(size: 15, weight: .semibold))
            .monospacedDigit()
            .lineLimit(1)
            .foregroundStyle(Color.clear)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .contentShape(Rectangle())
            // A drag that never moves fails, and the tap gets its turn.
            .gesture(swipe.exclusively(before: tap))
            .accessibilityAddTraits(.isButton)
            .accessibilityHint("Shows the month's days and the other months")
            .accessibilityAction { open() }
    }

    /// Along the month's name: a month every `swipeStep`, right being newer.
    private var swipe: some Gesture {
        DragGesture(minimumDistance: 8)
            .updating($swiping) { _, state, _ in state = true }
            .onChanged { value in
                let start = swipeStart ?? month
                swipeStart = start
                go(Int(timeline.step(from: Int32(start), by: Int32((value.translation.width / swipeStep).rounded()))))
            }
    }

    /// Held, the system repeats the tap.
    private func arrow(_ symbol: String, label: LocalizedStringKey, by: Int32) -> some View {
        Button { go(Int(timeline.step(from: Int32(month), by: by))) } label: {
            Image(systemName: symbol)
                .font(.system(size: 13, weight: .bold))
                .frame(width: 44, height: 40)
                .contentShape(Rectangle())
        }
        .buttonRepeatBehavior(.enabled)
        .accessibilityLabel(label)
    }

    private func go(_ to: Int) {
        guard to != month else { return }
        ScrubHaptics.step(timeline, from: month, to: to)
        step(to)
    }
}

/// What the pill grows into: the month and its counts, its days, and the dial of months (the bar draws its card).
private struct TimelinePanel: View {
    let timeline: FeedTimeline
    let month: Int
    let onDay: (TimelineDay) -> Void
    let onMonth: (Int) -> Void
    let onClose: () -> Void
    private let closeSize: CGFloat = 36
    private let closeInset: CGFloat = 12

    var body: some View {
        let shown = timeline.months[month]
        VStack(spacing: 12) {
            HStack(alignment: .center) {
                VStack(alignment: .leading, spacing: 2) {
                    // Its place only: the bar draws the name.
                    Text(verbatim: shown.title)
                        .font(.system(size: 20, weight: .semibold))
                        .monospacedDigit()
                        .foregroundStyle(Color.clear)
                    Text("\(Int(shown.count)) photos · \(shown.days.count) days")
                        .font(.system(size: 13))
                        .monospacedDigit()
                        .foregroundStyle(LibraryColors.muted)
                }
                Spacer()
                // Room for the close button, pinned in the corner over it.
                Color.clear.frame(width: closeSize + closeInset - 16, height: 1)
            }
            DayStrip(timeline: timeline, month: month, onDay: onDay)
            MonthDial(timeline: timeline, month: month, onMonth: onMonth)
        }
        .foregroundStyle(LibraryColors.onBackground)
        .padding(16)
        // In the top corner, as far from the top as from the side.
        .overlay(alignment: .topTrailing) {
            Button(action: onClose) {
                Image(systemName: "chevron.down")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .frame(width: closeSize, height: closeSize)
                    .background(LibraryColors.onBackground.opacity(0.1), in: Circle())
            }
            .accessibilityLabel("Close")
            .padding(closeInset)
        }
        .animation(.snappy(duration: 0.15), value: month)
    }
}

/// The days of a month in a line, those with photos lit. Tap one or slide along: the feed goes to the lit day nearest
/// the finger.
private struct DayStrip: View {
    let timeline: FeedTimeline
    let month: Int
    let onDay: (TimelineDay) -> Void
    /// The day last gone to in this month.
    @State private var picked: Int?

    var body: some View {
        let shown = timeline.months[month]
        let count = Int(shown.length)
        GeometryReader { geometry in
            let w = geometry.size.width / CGFloat(count)
            HStack(spacing: 0) {
                ForEach(1...count, id: \.self) { day in
                    let lit = shown.dayOn(dayOfMonth: Int32(day)) != nil
                    let on = day == picked
                    VStack(spacing: 3) {
                        Circle()
                            .fill(lit ? LibraryColors.accent : LibraryColors.muted.opacity(0.25))
                            .frame(width: on ? 9 : 6, height: on ? 9 : 6)
                            .overlay(Circle().stroke(LibraryColors.onBackground, lineWidth: 1.5).padding(-3).opacity(on ? 1 : 0))
                        Text(verbatim: day == 1 || day % 5 == 0 ? "\(day)" : "")
                            .font(.system(size: 9))
                            .foregroundStyle(LibraryColors.muted)
                            .fixedSize()
                    }
                    .frame(width: w)
                }
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0).onChanged { value in
                let day = min(max(Int(value.location.x / w) + 1, 1), count)
                guard let nearest = timeline.nearestDay(month: Int32(month), dayOfMonth: Int32(day)), Int(nearest.day.day) != picked else { return }
                ScrubHaptics.tick.selectionChanged()
                picked = Int(nearest.day.day)
                onDay(nearest)
            })
        }
        .frame(height: 32)
        .animation(.snappy(duration: 0.15), value: picked)
        .onChange(of: month) { picked = nil }
        .accessibilityHidden(true)
    }
}

/// The months oldest to newest, left to right, a bar each as tall as its photos; the one at the needle is `month`.
/// Dragged like a dial: it follows the finger, a flick keeps it turning, and it settles on a month.
private struct MonthDial: View {
    let timeline: FeedTimeline
    let month: Int
    let onMonth: (Int) -> Void
    /// Where the dial is while it turns, in months from the oldest.
    @State private var at: CGFloat?
    @State private var dragStart: CGFloat?
    private let step: CGFloat = 16

    var body: some View {
        let count = timeline.months.count
        let peak = CGFloat(max(timeline.peak, 1))
        // Months from the oldest: the timeline's are newest first.
        let current = at ?? CGFloat(count - 1 - month)
        GeometryReader { geometry in
            let mid = geometry.size.width / 2
            let reach = Int(mid / step) + 2
            let lo = max(Int(current) - reach, 0), hi = max(min(Int(current) + reach, count - 1), lo)
            ZStack(alignment: .topLeading) {
                ForEach(lo...hi, id: \.self) { i in
                    let shown = timeline.months[count - 1 - i]
                    let picked = count - 1 - i == month
                    VStack(spacing: 4) {
                        Spacer(minLength: 0)
                        Capsule()
                            .fill(picked ? LibraryColors.onBackground : LibraryColors.muted.opacity(0.6))
                            .frame(width: picked ? 8 : 6, height: 6 + 40 * sqrt(CGFloat(shown.count) / peak))
                        Text(verbatim: shown.month == 1 ? String(shown.year) : shown.month == 7 ? "·" : "")
                            .font(.system(size: 10, weight: .bold))
                            .foregroundStyle(LibraryColors.muted)
                            .fixedSize()
                            .frame(width: step, height: 12)
                    }
                    .frame(width: step, height: geometry.size.height)
                    .position(x: mid + (CGFloat(i) - current) * step, y: geometry.size.height / 2)
                }
                Rectangle()
                    .fill(LibraryColors.accent)
                    .frame(width: 2, height: geometry.size.height - 16)
                    .position(x: mid, y: (geometry.size.height - 16) / 2)
            }
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 2)
                .onChanged { value in
                    let start = dragStart ?? current
                    dragStart = start
                    turn(to: start - value.translation.width / step)
                }
                .onEnded { value in
                    let start = dragStart ?? current
                    dragStart = nil
                    // A flick goes on as far as it would have slid, then settles on a month.
                    let target = min(max((start - value.predictedEndTranslation.width / step).rounded(), 0), CGFloat(count - 1))
                    withAnimation(.snappy(duration: 0.45)) { at = target }
                    pick(count - 1 - Int(target))
                    Task { @MainActor in
                        try? await Task.sleep(for: .milliseconds(500))
                        if dragStart == nil { at = nil }
                    }
                })
        }
        .frame(height: 66)
        .mask(LinearGradient(stops: [.init(color: .clear, location: 0), .init(color: .black, location: 0.12), .init(color: .black, location: 0.88), .init(color: .clear, location: 1)], startPoint: .leading, endPoint: .trailing))
        .accessibilityHidden(true)
    }

    /// Follows the finger; the month at the needle is gone to as it passes.
    private func turn(to position: CGFloat) {
        let count = timeline.months.count
        let clamped = min(max(position, 0), CGFloat(count - 1))
        at = clamped
        pick(count - 1 - Int(clamped.rounded()))
    }

    private func pick(_ to: Int) {
        guard to != month else { return }
        ScrubHaptics.step(timeline, from: month, to: to)
        onMonth(to)
    }
}

/// A feed row to scroll to, by its id in the lazy stack; a new one each time, so the same row can be asked for twice.
struct RowJump: Equatable {
    let id = UUID()
    let rowId: String
}

extension View {
    /// Scrolls to `request`'s row, its top at `anchor`. Goes on a scroll view's lazy stack.
    func jumps(to request: RowJump?, anchor: UnitPoint) -> some View {
        ScrollViewReader { proxy in
            onChange(of: request) { _, request in
                if let request { proxy.scrollTo(request.rowId, anchor: anchor) }
            }
        }
    }
}

extension Array where Element == FeedRow {
    /// Each row's id to its place in the feed and the key of the section it is in, for the month at the top.
    var sectionKeys: [String: (index: Int, section: String)] {
        var keys: [String: (index: Int, section: String)] = [:]
        var section: String?
        for (index, row) in enumerated() {
            switch row {
            case .heading(let s): section = s.key
            case .photo(let s, _, _), .band(let s, _, _), .recordingBar(let s, _), .sound(let s, _): section = s.key
            case .label: break
            }
            if let section { keys[row.id] = (index, section) }
        }
        return keys
    }
}
