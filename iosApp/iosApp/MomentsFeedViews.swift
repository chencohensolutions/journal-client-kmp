import SwiftUI
import SharedLogic

// The Moments tab: every photo reads like a story that arranged itself (see MomentsFeed in the shared logic).
// Photos are full width at their own shape; near-duplicates are one photo you swipe. See docs/screens/moments.md.

/// What the feed's rows can ask for. A screen showing a feed (Moments, a town or year of Explore) sets what it offers.
struct FeedActions {
    /// A photo was tapped; the viewer swipes through its row.
    var openItem: (FeedSection, MediaItem) -> Void = { _, _ in }
    var saveTrip: (FeedTrip) -> Void = { _ in }
    var dismissTrip: (_ key: String) -> Void = { _ in }
    /// From a photo's long-press menu.
    var write: (MediaItem) -> Void = { _ in }
    /// Opens the crop screen on the photo's Moments crop.
    var crop: (MediaItem) -> Void = { _ in }
    var addToStory: (MediaItem) -> Void = { _ in }
    var tag: (MediaItem) -> Void = { _ in }
    var takeOutOfSet: (MediaItem) -> Void = { _ in }
    /// A swipe set (its photos, in order) was turned to a photo: it opens there next time.
    var turnSet: (_ itemIds: [String], _ itemId: String) -> Void = { _, _ in }
    /// Off where a long press offers nothing (Explore's pages).
    var menus = true
}

/// One item of the feed's lazy stack: a trip card, a row's divider, a moment's label, or one photo or near-duplicate
/// set. A photo per item keeps the stack lazy: a moment of a hundred photos lays out only the ones in view.
enum FeedRow: Identifiable {
    case trip(FeedTrip)
    case heading(FeedSection)
    /// `showPlace`: a camera moment whose place differs from the camera moment above it in its row.
    case label(FeedMoment, showPlace: Bool)
    case photo(FeedSection, FeedBlock)

    var id: String {
        switch self {
        case .trip(let trip): return trip.key
        case .heading(let section): return "heading-\(section.key)"
        case .label(let moment, _): return "label-\(moment.key)"
        case .photo(_, let block): return "photo-\(block.key)"
        }
    }
}

extension FeedBlock {
    /// The key a block is picked and remembered by: its first photo's id.
    var key: String { items[0].id }
}

extension MomentsFeed {
    /// The feed as stack items; work it out once per feed.
    var rows: [FeedRow] {
        entries.flatMap { entry -> [FeedRow] in
            if let trip = entry as? FeedEntryTrip { return [.trip(trip.trip)] }
            guard let section = (entry as? FeedEntrySection)?.section else { return [] }
            var place: String?
            var rows: [FeedRow] = [.heading(section)]
            for moment in section.moments {
                let camera = moment.source == .camera
                rows.append(.label(moment, showPlace: camera && moment.place != nil && moment.place != place))
                if camera { place = moment.place }
                rows += moment.shownBlocks.map { .photo(section, $0) }
            }
            return rows
        }
    }
}

extension Array where Element == FeedRow {
    /// The row and photo that show `itemId`, for landing from the viewer.
    func place(of itemId: String) -> PhotoPlace? {
        lazy.compactMap { row -> PhotoPlace? in
            guard case .photo(_, let block) = row, block.items.contains(where: { $0.id == itemId }) else { return nil }
            return PhotoPlace(section: row.id, tile: ZoomID.item(itemId))
        }.first
    }

    /// The block that shows `itemId`.
    func block(of itemId: String) -> FeedBlock? {
        lazy.compactMap { row -> FeedBlock? in
            guard case .photo(_, let block) = row, block.items.contains(where: { $0.id == itemId }) else { return nil }
            return block
        }.first
    }
}

/// The feed's rows, for a lazy stack of the screen's. A photo's note (`note`, by id) is written above it, and its
/// Moments crop sets its shape. While `selection` has keys, tapping a photo or set picks it instead.
struct FeedRowsView: View {
    let rows: [FeedRow]
    let note: (String) -> PhotoNote?
    /// The width of the feed: its photos' size is known without measuring them.
    let width: CGFloat
    let actions: FeedActions
    @Binding var selection: Set<String>
    @ObservedObject var playback: FeedPlayback

    var body: some View {
        ForEach(rows) { row in
            switch row {
            case .trip(let trip):
                FeedTripCard(trip: trip, onSave: { actions.saveTrip(trip) }, onDismiss: { actions.dismissTrip(trip.key) })
            case .heading(let section):
                FeedDayDivider(section: section)
            case let .label(moment, showPlace):
                FeedMomentLabel(moment: moment, showPlace: showPlace)
            case let .photo(section, block):
                if let single = block as? FeedBlockSingle {
                    singlePhoto(single.item, block: block, in: section)
                } else {
                    FeedSet(
                        block: block,
                        width: width,
                        note: note,
                        selected: selection.isEmpty ? nil : selection.contains(block.key),
                        playback: playback,
                        onTap: { item in tap(item, block: block, in: section) },
                        menu: { item in menu(for: item, block: block) },
                        turnSet: actions.turnSet
                    )
                    .padding(.bottom, tileGap)
                }
            }
        }
    }

    private func singlePhoto(_ item: MediaItem, block: FeedBlock, in section: FeedSection) -> some View {
        // Videos aren't cropped (yet).
        let crop = item.isVideo ? nil : note(item.id)?.crops.moments
        let ratio = crop.map { CGFloat($0.ratioOn(photoRatio: Float(item.ratio))) } ?? item.ratio
        return VStack(spacing: 0) {
            FeedNoteText(caption: note(item.id)?.caption)
            FeedPhoto(item: item, size: CGSize(width: width, height: width / ratio), crop: crop, playback: playback)
                .overlay { if !selection.isEmpty { SelectionOverlay(selected: selection.contains(block.key)) } }
                .zoomSource(ZoomID.item(item.id))
                .contentShape(Rectangle())
                .onTapGesture { tap(item, block: block, in: section) }
                .contextMenu { menu(for: item, block: block) }
                .modifier(ReportsVideo(active: item.isVideo, key: block.key, itemId: item.id, playback: playback))
        }
        .padding(.bottom, tileGap)
    }

    private func tap(_ item: MediaItem, block: FeedBlock, in section: FeedSection) {
        if selection.isEmpty {
            actions.openItem(section, item)
        } else if selection.contains(block.key) {
            selection.remove(block.key)
        } else {
            selection.insert(block.key)
        }
    }

    /// A photo's long-press menu; none while picking.
    @ViewBuilder
    private func menu(for item: MediaItem, block: FeedBlock) -> some View {
        if selection.isEmpty && actions.menus {
            Section("\(item.day.label) · \(item.takenAt.formatted(date: .omitted, time: .shortened))") {
                let written = note(item.id)?.caption.isBlank == false
                Button { actions.write(item) } label: {
                    Label(written ? "Edit what you wrote" : "Write about it", systemImage: "pencil")
                }
                if !item.isVideo {
                    Button { actions.crop(item) } label: { Label("Crop", systemImage: "crop") }
                }
                Button { actions.addToStory(item) } label: { Label("Add to story", systemImage: "plus") }
                Button { actions.tag(item) } label: { Label("Tag", systemImage: "tag") }
                Button { selection.insert(block.key) } label: { Label("Select", systemImage: "checkmark.circle") }
                if block is FeedBlockSimilar {
                    Button { actions.takeOutOfSet(item) } label: { Label("Take out of this set", systemImage: "minus.circle") }
                }
            }
        }
    }
}

/// Between photos of the feed.
let tileGap: CGFloat = 2

extension MediaItem {
    /// Its shape (width / height), 4:3 when unknown.
    var ratio: CGFloat { width > 0 && height > 0 ? CGFloat(width) / CGFloat(height) : 4 / 3 }
}

/// Tells the feed's playback where a block that shows (or can turn to) a video is on screen (`active`), and which
/// video it shows (`itemId`, nil for a photo).
private struct ReportsVideo: ViewModifier {
    let active: Bool
    let key: String
    let itemId: String?
    let playback: FeedPlayback

    func body(content: Content) -> some View {
        if active {
            content
                .onGeometryChange(for: CGRect.self) { $0.frame(in: .global) } action: { frame in
                    playback.report(block: key, itemId: itemId, frame: frame)
                }
                .onDisappear { playback.remove(block: key) }
        } else {
            content
        }
    }
}

/// `item` filling `size` (its shape, or its Moments `crop`'s): a photo, or a video's poster with its length, playing
/// in place while the feed's playback says so. The poster stays until the video's first frame is drawn.
struct FeedPhoto: View {
    let item: MediaItem
    let size: CGSize
    var crop: CropRect? = nil
    @ObservedObject var playback: FeedPlayback

    @State private var image: UIImage?
    @State private var showing = false
    @State private var failed = false

    var body: some View {
        let playing = item.isVideo && playback.playingId == item.id
        ZStack(alignment: .topLeading) {
            LibraryColors.raised
            if playing {
                InlineVideo(item: item, muted: playback.muted, onFirstFrame: { showing = true }, onFailed: { failed = true })
            }
            if let image {
                picture(image).opacity(playing && showing && !failed ? 0 : 1)
            }
        }
        .frame(width: size.width, height: size.height)
        .clipped()
        .overlay(alignment: .bottomTrailing) {
            if playing && !failed {
                MuteButton(muted: playback.muted, onToggle: playback.toggleMute).padding(4)
            } else {
                BackupBadge(status: item.backup, size: 26, originalHere: item.hasOriginalHere).padding(10)
            }
        }
        .overlay(alignment: .bottomLeading) {
            if item.isVideo { DurationChip(duration: item.duration).padding(10) }
        }
        .onChange(of: playing) {
            // Start over each time it starts playing.
            showing = false
            failed = false
        }
        .task(id: item.id) {
            // A quick small image first, then one as sharp as the screen.
            let full = fullSize
            image = await PhotoLibraryImages.shared.image(item: item, pointSize: CGSize(width: 160, height: 160), quality: .fastFormat)
            if let sharp = await PhotoLibraryImages.shared.image(item: item, pointSize: full) { image = sharp }
        }
        .accessibilityElement()
        .accessibilityLabel(item.accessibilityDescription)
        .accessibilityAddTraits(.isButton)
    }

    /// The whole photo's size on screen: the box, or larger by the crop.
    private var fullSize: CGSize {
        guard let crop else { return size }
        return CGSize(width: size.width / CGFloat(crop.w), height: size.height / CGFloat(crop.h))
    }

    @ViewBuilder
    private func picture(_ image: UIImage) -> some View {
        if let crop {
            let full = fullSize
            Image(uiImage: image)
                .resizable()
                .frame(width: full.width, height: full.height)
                .offset(x: -CGFloat(crop.x) * full.width, y: -CGFloat(crop.y) * full.height)
        } else {
            Image(uiImage: image).resizable().scaledToFill().frame(width: size.width, height: size.height)
        }
    }
}

/// Near-duplicates as one photo at the first one's shape; swipe for the others ("2 / 4" and dots), each with its own
/// note above. Opens on the photo it was last turned to, and turns to the photo the viewer shows, so closing the
/// viewer lands on its own page.
private struct FeedSet: View {
    let block: FeedBlock
    let width: CGFloat
    let note: (String) -> PhotoNote?
    /// While picking: whether this set is picked; nil when not picking.
    let selected: Bool?
    @ObservedObject var playback: FeedPlayback
    let onTap: (MediaItem) -> Void
    let menu: (MediaItem) -> AnyView
    let turnSet: (_ itemIds: [String], _ itemId: String) -> Void

    @State private var current: String?
    @Environment(\.viewerShowing) private var viewerShowing

    init<Menu: View>(
        block: FeedBlock, width: CGFloat, note: @escaping (String) -> PhotoNote?, selected: Bool?, playback: FeedPlayback,
        onTap: @escaping (MediaItem) -> Void, menu: @escaping (MediaItem) -> Menu,
        turnSet: @escaping (_ itemIds: [String], _ itemId: String) -> Void
    ) {
        self.block = block
        self.width = width
        self.note = note
        self.selected = selected
        self.playback = playback
        self.onTap = onTap
        self.menu = { AnyView(menu($0)) }
        self.turnSet = turnSet
        // Where it was turned to while the app runs, else the photo saved in its notes, else its first.
        let start = playback.pages[block.key] ?? block.items.first { note($0.id)?.front == true }?.id
        _current = State(initialValue: start ?? block.items.first?.id)
    }

    var body: some View {
        let items = block.items
        let index = items.firstIndex { $0.id == current } ?? 0
        let height = width / items[0].ratio
        VStack(spacing: 0) {
            FeedNoteText(caption: note(items[index].id)?.caption)
            ScrollView(.horizontal) {
                LazyHStack(spacing: 0) {
                    ForEach(items, id: \.id) { item in
                        ZStack {
                            Color.black
                            FeedPhoto(item: item, size: fitted(item, height: height), playback: playback)
                                .zoomSource(ZoomID.item(item.id))
                        }
                        .frame(width: width, height: height)
                        .contentShape(Rectangle())
                        .onTapGesture { onTap(item) }
                        .contextMenu { menu(item) }
                    }
                }
                .scrollTargetLayout()
            }
            .scrollPosition(id: $current)
            .scrollTargetBehavior(.paging)
            .scrollIndicators(.hidden)
            .frame(width: width, height: height)
            .overlay(alignment: .topTrailing) { position(index, of: items.count) }
            .overlay(alignment: .bottom) { dots(index, of: items.count) }
            .overlay { if let selected { SelectionOverlay(selected: selected) } }
            .modifier(ReportsVideo(
                active: items.contains { $0.isVideo }, key: block.key, itemId: items[index].isVideo ? items[index].id : nil, playback: playback
            ))
        }
        .onAppear { prefetch(around: index, height: height) }
        .onChange(of: current) { _, id in
            guard let id, let item = items.first(where: { $0.id == id }) else { return }
            playback.showPage(id, isVideo: item.isVideo, of: block.key)
            turnSet(items.map(\.id), id)
            prefetch(around: items.firstIndex(of: item) ?? 0, height: height)
        }
        .onChange(of: viewerShowing) { _, showing in
            if let id = showing?.itemId, id != current, items.contains(where: { $0.id == id }) { current = id }
        }
    }

    /// A photo of another shape than the set's first fits inside the page, at its own shape.
    private func fitted(_ item: MediaItem, height: CGFloat) -> CGSize {
        item.ratio >= block.items[0].ratio
            ? CGSize(width: width, height: width / item.ratio)
            : CGSize(width: height * item.ratio, height: height)
    }

    /// The photos beside the one shown load ahead, so a swipe either way shows a ready photo.
    private func prefetch(around index: Int, height: CGFloat) {
        let items = block.items
        for neighbor in [index - 1, index + 1] where items.indices.contains(neighbor) {
            let item = items[neighbor]
            PhotoLibraryImages.shared.prefetch(ids: [item.id], pointSize: fitted(item, height: height))
        }
    }

    private func position(_ index: Int, of count: Int) -> some View {
        HStack(spacing: 5) {
            Image(systemName: "square.stack").font(.system(size: 11, weight: .semibold))
            Text("\(index + 1) / \(count)").font(.system(size: 12, weight: .semibold))
        }
        .foregroundStyle(LibraryColors.photoText)
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(LibraryColors.badgeScrim, in: Capsule())
        .padding(10)
        .accessibilityElement(children: .combine)
    }

    /// A set longer than eight shows eight dots, the last one standing for the rest.
    private func dots(_ index: Int, of count: Int) -> some View {
        HStack(spacing: 5) {
            ForEach(0..<min(count, maxDots), id: \.self) { dot in
                let current = dot == min(index, maxDots - 1)
                Circle()
                    .fill(.white.opacity(current ? 1 : 0.5))
                    .frame(width: current ? 7 : 6, height: current ? 7 : 6)
            }
        }
        .padding(.bottom, 10)
        .accessibilityHidden(true)
    }
}

private let maxDots = 8

/// The photo's note above it, in the serif of written text: up to four lines, then "more" (tap to read it all).
/// Nothing when it has none.
private struct FeedNoteText: View {
    let caption: String?

    @State private var expanded = false
    @State private var shownHeight: CGFloat = 0
    @State private var fullHeight: CGFloat = 0

    var body: some View {
        if let caption, !caption.isBlank {
            let cut = fullHeight > shownHeight + 1
            VStack(alignment: .leading, spacing: 2) {
                text(caption)
                    .lineLimit(expanded ? nil : noteLines)
                    .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { shownHeight = $0 }
                    .background(alignment: .topLeading) {
                        text(caption)
                            .fixedSize(horizontal: false, vertical: true)
                            .hidden()
                            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { fullHeight = $0 }
                    }
                if cut && !expanded {
                    Text("more").font(.system(size: 13, weight: .semibold)).foregroundStyle(LibraryColors.muted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .onTapGesture { if cut || expanded { expanded.toggle() } }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            .padding(.bottom, 12)
        }
    }

    private func text(_ caption: String) -> some View {
        Text(caption)
            .font(.system(size: 16, design: .serif))
            .lineSpacing(5)
            .foregroundStyle(LibraryColors.onBackground)
    }
}

/// A note longer than this shows "more".
private let noteLines = 4

/// The line between rows, "Today · Tue, Oct 20" on it, then the towns visited and the count ("Tel Aviv → Jaffa · 21 photos").
private struct FeedDayDivider: View {
    let section: FeedSection

    var body: some View {
        let line = LibraryColors.muted.opacity(0.3)
        VStack(spacing: 4) {
            HStack(spacing: 0) {
                Rectangle().fill(line).frame(height: 1)
                Text(label)
                    .font(.system(size: 13, weight: .bold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .lineLimit(1)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(LibraryColors.raised, in: Capsule())
                    .padding(.horizontal, 12)
                    .layoutPriority(1)
                    .accessibilityAddTraits(.isHeader)
                Rectangle().fill(line).frame(height: 1)
            }
            Text(summary)
                .font(.system(size: 12))
                .foregroundStyle(LibraryColors.muted)
                .lineLimit(1)
        }
        .padding(.horizontal, 16)
        .padding(.top, 28)
        .padding(.bottom, 2)
    }

    /// The row's days, never its times: "Sat, Oct 4", or "Oct 3 – Oct 4" for a row over two days. "Today" and
    /// "Yesterday" get their date too ("Today · Tue, Oct 20"), as older days show only it.
    private var label: String {
        let span = section.span
        if span.oldestDay != span.newestDay { return String(localized: "\(span.oldestDay.label) – \(span.newestDay.label)") }
        let day = span.newestDay.label
        let date = span.newestDay.date
        guard Calendar.current.isDateInToday(date) || Calendar.current.isDateInYesterday(date) else { return day }
        let formatter = DateFormatter()
        formatter.setLocalizedDateFormatFromTemplate("EEEMMMd")
        return String(localized: "\(day) · \(formatter.string(from: date))")
    }

    /// A long day shows where it started: three towns at most.
    private var summary: String {
        let count = String(localized: "\(section.items.count) photos")
        let towns = section.places.prefix(3).joined(separator: " → ")
        return towns.isEmpty ? count : String(localized: "\(towns) · \(count)")
    }
}

/// A moment's label above its photos. Camera: its place ("📍 Jaffa Port") where it differs from the camera moment above
/// (`showPlace`), then the time ("18:10 – 19:05"). Other sources: "WhatsApp · 6 · 14:02 – 17:30".
private struct FeedMomentLabel: View {
    let moment: FeedMoment
    let showPlace: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            if moment.source == .camera {
                if showPlace, let place = moment.place {
                    HStack(spacing: 6) {
                        Image(systemName: "mappin.and.ellipse").font(.system(size: 13, weight: .semibold)).frame(width: 16)
                        Text(place).font(.system(size: 15, weight: .bold)).lineLimit(1)
                    }
                    .foregroundStyle(LibraryColors.onBackground)
                }
                Text(time)
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(LibraryColors.muted)
                    .padding(.leading, 22)
            } else {
                HStack(spacing: 6) {
                    Image(systemName: moment.source.symbol).font(.system(size: 12, weight: .semibold))
                    Text("\(String(localized: "\(moment.source.title) · \(moment.items.count)")) · \(time)")
                        .font(.system(size: 13, weight: .semibold))
                        .lineLimit(1)
                }
                .foregroundStyle(LibraryColors.muted)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 16)
        .padding(.top, 22)
        .padding(.bottom, 8)
    }

    /// "18:10 – 19:05", or "18:10" when it starts and ends in one minute.
    private var time: String {
        let start = clockTime(moment.startMillis), end = clockTime(moment.endMillis)
        return start == end ? start : String(localized: "\(start) – \(end)")
    }
}

/// "9:14" or "9:14 AM", as the locale writes it.
func clockTime(_ millis: Int64) -> String {
    Date(timeIntervalSince1970: TimeInterval(millis) / 1000).formatted(date: .omitted, time: .shortened)
}

/// Days away from home, offered as one story; "Not now" doesn't offer it again.
private struct FeedTripCard: View {
    let trip: FeedTrip
    let onSave: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                MediaTileView(item: trip.cover, badgeSize: 0)
                    .frame(width: 52, height: 52)
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 1) {
                    Text("Looks like a trip").font(.system(size: 12, weight: .semibold)).foregroundStyle(LibraryColors.onInverseMuted)
                    Text(trip.place ?? String(localized: "A trip"))
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(LibraryColors.onInverse)
                        .lineLimit(1)
                    Text([
                        dayRange(trip.firstDay, trip.lastDay),
                        String(localized: "\(Int(trip.days)) days"),
                        String(localized: "\(trip.items.count) photos"),
                    ].joined(separator: " · "))
                    .font(.system(size: 13))
                    .foregroundStyle(LibraryColors.onInverseMuted)
                }
            }
            HStack(spacing: 8) {
                Button(action: onSave) {
                    Text("Save as story")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(.white)
                        .frame(maxWidth: .infinity, minHeight: 40)
                        .background(LibraryColors.accent, in: Capsule())
                }
                Button(action: onDismiss) {
                    Text("Not now")
                        .font(.system(size: 14, weight: .semibold))
                        .foregroundStyle(LibraryColors.onInverse)
                        .padding(.horizontal, 18)
                        .frame(height: 40)
                        .overlay(Capsule().stroke(LibraryColors.onInverseMuted.opacity(0.5)))
                }
            }
        }
        .padding(14)
        .background(LibraryColors.inverse, in: RoundedRectangle(cornerRadius: 18))
        .padding(.horizontal, 12)
        .padding(.top, 24)
    }
}

/// The filter bar: each source with its count, on (filled, with a check) or off (outlined). A source with no photos
/// is hidden unless it is on.
struct FeedSourceBar: View {
    let feed: MomentsFeed
    let onToggle: (MediaSource) -> Void

    var body: some View {
        let shown = feed.shown
        let counts = feed.counts
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(MediaSource.entries.filter { shown.contains($0) || (counts[$0]?.intValue ?? 0) > 0 }, id: \.self) { source in
                    let on = shown.contains(source)
                    Button { onToggle(source) } label: {
                        HStack(spacing: 6) {
                            Image(systemName: on ? "checkmark" : source.symbol).font(.system(size: 12, weight: .semibold))
                            Text(source.title).font(.system(size: 13, weight: .semibold))
                            Text("\(counts[source]?.intValue ?? 0)")
                                .font(.system(size: 13))
                                .foregroundStyle(on ? LibraryColors.onInverseMuted : LibraryColors.muted.opacity(0.7))
                        }
                        .foregroundStyle(on ? LibraryColors.onInverse : LibraryColors.muted)
                        .padding(.horizontal, 12)
                        .frame(height: 34)
                        .background {
                            if on {
                                Capsule().fill(LibraryColors.inverse)
                            } else {
                                Capsule().stroke(LibraryColors.muted.opacity(0.45))
                            }
                        }
                    }
                    .accessibilityAddTraits(on ? [.isSelected] : [])
                }
            }
            .padding(.horizontal, 16)
        }
        .scrollIndicators(.hidden)
    }
}

/// While picking, in place of the tabs: Cancel, "2 selected", and Merge once two or more are picked.
struct FeedSelectionBar: View {
    let count: Int
    let onCancel: () -> Void
    let onMerge: () -> Void

    var body: some View {
        let canMerge = count >= 2
        HStack(spacing: 6) {
            Button(action: onCancel) {
                Text("Cancel").padding(.horizontal, 14).frame(height: 40)
            }
            .foregroundStyle(LibraryColors.onInverse)
            Text("\(count) selected").foregroundStyle(LibraryColors.onInverseMuted).padding(.horizontal, 6)
            Button(action: onMerge) {
                Text("Merge")
                    .foregroundStyle(.white)
                    .padding(.horizontal, 20)
                    .frame(height: 40)
                    .background(canMerge ? LibraryColors.accent : LibraryColors.onInverseMuted.opacity(0.3), in: Capsule())
            }
            .disabled(!canMerge)
        }
        .font(.subheadline.weight(.semibold))
        .padding(6)
        .background(LibraryColors.inverse, in: Capsule())
        .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
    }
}

extension MediaSource {
    var title: String {
        switch self {
        case .whatsapp: return String(localized: "WhatsApp")
        case .screenshots: return String(localized: "Screenshots")
        case .other: return String(localized: "Other")
        default: return String(localized: "Camera")
        }
    }

    var symbol: String {
        switch self {
        case .whatsapp: return "bubble.left"
        case .screenshots: return "iphone"
        case .other: return "arrow.down.to.line"
        default: return "camera"
        }
    }
}
