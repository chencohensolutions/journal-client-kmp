import AVKit
import SwiftUI
import SharedLogic

/// What the viewer can ask its parent to do.
struct ViewerActions {
    var close: () -> Void
    var setWriting: (_ itemId: String, _ title: String, _ text: String) -> Void
    /// Back to an earlier version of the photo's writing.
    var restoreWriting: (_ itemId: String, _ version: NoteVersion) -> Void
    /// "Remove" (false) or "Add back" (true) in the details' Storyline.
    var setInStoryline: (_ itemId: String, _ inStoryline: Bool) -> Void
    var toggleTag: (_ itemId: String, _ tag: String, _ add: Bool) -> Void
    /// The photo now shown, on opening and on each swipe: the one that zooms back into its tile on closing.
    var showing: (_ itemId: String) -> Void
    /// The map screen, close up on where a photo was taken (a tap on the details' map), over the viewer.
    var openMap: (_ at: GeoPoint) -> Void
    /// "Show in Moments": the viewer and every screen over the home close, and Moments lands on the photo; nil when the
    /// viewer was opened from Moments.
    var showInMoments: ((_ itemId: String) -> Void)?
    /// Marks the photo private on the server (true), or no longer private. False when the server couldn't be reached.
    var setPrivate: (_ item: MediaItem, _ isPrivate: Bool) async -> Bool
    /// Leaving a photo: where it was zoomed in (the part on screen; nil: fitted), so it opens there next time.
    var setZoom: (_ itemId: String, _ zoom: CropRect?) -> Void
    /// The details opened on a photo: find what the server found in it (people, text, similar photos).
    var findIn: (MediaItem) -> Void = { _ in }
    /// In the details: a person's page, a similar photo, all similar photos.
    var openPerson: (String) -> Void = { _ in }
    var openSimilar: (MediaItem) -> Void = { _ in }
    var moreSimilar: () -> Void = {}
    /// A face's sheet: This is Mom, Not Mom, Who is it?
    var faces = FaceActions()
    /// The named people, for a face's "Who is it?".
    var people: [Person] = []
}

/// One photo or video at a time, swiping through `items`. See docs/screens/viewer.md.
struct MediaViewerView: View {
    let items: [MediaItem]
    let stories: StoriesState
    let actions: ViewerActions
    /// What the server found in the photo the details show (asked for with `actions.findIn`).
    var findings: PhotoFindings = PhotoFindings.companion.NONE

    @State private var currentId: String
    @State private var showInfo = false
    /// A face tapped in the details.
    @State private var faceSheet: FoundFace?
    /// The bars and buttons over the photo: hidden at first, shown by a tap for 3 seconds, hidden by the next tap.
    @State private var chrome = false
    @State private var choosingTag = false
    @State private var editingCaption = false
    /// The whole photo instead of its crop, until turned back (kept while swiping).
    @State private var showWhole = false
    @State private var screenSize = CGSize(width: 9, height: 19.5)
    /// Each photo's zoom, starting where it was left last time (kept in its note) and kept while the viewer is open.
    @StateObject private var zooms = ZoomModels()
    /// The photo shown is zoomed in: it doesn't page, and a swipe down doesn't close the viewer.
    @State private var zoomed = false
    /// Share is getting the original ready.
    @State private var sharing = false
    @State private var shareFailed = false

    /// `chrome` and `showInfo` open it with the buttons or the details showing (the UI lab).
    init(
        items: [MediaItem], startId: String, stories: StoriesState, actions: ViewerActions, findings: PhotoFindings = PhotoFindings.companion.NONE,
        chrome: Bool = false, showInfo: Bool = false
    ) {
        self.items = items
        self.stories = stories
        self.actions = actions
        self.findings = findings
        _currentId = State(initialValue: startId)
        _chrome = State(initialValue: chrome)
        _showInfo = State(initialValue: showInfo)
    }

    /// Closing keeps the zoom of the photo shown in its note first.
    private func onClose() {
        saveZoom(of: currentId)
        actions.close()
    }

    private func zoomModel(_ item: MediaItem) -> PhotoZoomModel {
        zooms.model(for: item.id, saved: stories.note(itemId: item.id)?.zoom)
    }

    /// The photo's zoom goes into its note when it changed (nil, once back where it opens).
    private func saveZoom(of itemId: String) {
        guard let item = items.first(where: { $0.id == itemId }), !item.isVideo, let model = zooms.existing(itemId) else { return }
        let part = model.zoomedPart
        if !sameZoom(part, stories.note(itemId: itemId)?.zoom) { actions.setZoom(itemId, part) }
    }

    /// The same zoom to within a hair (a zoom shown again is worked out anew, so it is rarely exactly equal).
    private func sameZoom(_ a: CropRect?, _ b: CropRect?) -> Bool {
        guard let a, let b else { return a == nil && b == nil }
        let e: Float = 0.002
        return abs(a.x - b.x) < e && abs(a.y - b.y) < e && abs(a.w - b.w) < e && abs(a.h - b.h) < e
    }

    // A photo's portrait crop shows with the phone upright, its landscape one sideways, fitted to this screen.
    private var screenRatio: Float { Float(screenSize.width / max(screenSize.height, 1)) }
    private var cropKind: CropKind { screenRatio <= 1 ? .portrait : .landscape }

    private func cropOf(_ item: MediaItem) -> CropRect? {
        guard !item.isVideo else { return nil }
        return stories.note(itemId: item.id)?.crops.get(kind: cropKind)?.fittedTo(screenRatio: screenRatio, photoRatio: item.photoRatio)
    }

    var body: some View {
        let index = items.firstIndex { $0.id == currentId } ?? 0
        ZStack {
            Color.black
                .ignoresSafeArea()
                .onGeometryChange(for: CGSize.self) { $0.screenSize } action: { screenSize = $0 }
            if items.isEmpty {
                // The item was deleted from the device while open.
                Color.clear.onAppear(perform: onClose)
            } else {
                TabView(selection: $currentId) {
                    ForEach(items, id: \.id) { item in
                        Group {
                            if item.isVideo {
                                VideoPage(item: item, isCurrent: item.id == currentId, onTap: toggleChrome)
                            } else {
                                // Its own taps: one shows the bars, two step the zoom.
                                PhotoPage(item: item, crop: showWhole ? nil : cropOf(item), zoom: zoomModel(item), onTap: toggleChrome)
                            }
                        }
                        .tag(item.id)
                    }
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .ignoresSafeArea()
            }
        }
        .overlay(alignment: .top) {
            if chrome { topBar(index: index) }
        }
        .overlay(alignment: .topTrailing) {
            if let item = items[safe: index], !item.isVideo {
                MinimapOverlay(model: zoomModel(item), chrome: chrome, hidden: showInfo).id(item.id)
            }
        }
        .background {
            if let item = items[safe: index], !item.isVideo {
                ZoomFlag(model: zoomModel(item), zoomed: $zoomed).id(item.id)
            }
        }
        .overlay(alignment: .top) {
            if chrome, !showInfo, !zoomed, let item = items[safe: index], cropOf(item) != nil {
                CropChip(showingWhole: showWhole) { showWhole.toggle() }
                    .padding(.top, 60)
            }
        }
        .overlay(alignment: .bottom) {
            if chrome, !showInfo, let item = items[safe: index] {
                HStack(spacing: 10) {
                    ViewerAction(icon: "square.and.pencil", label: "Write") { editingCaption = true }
                    ViewerAction(icon: "info.circle", label: "Details") { showInfo = true }
                    ShareButton(busy: sharing) { share(item) }
                }
                .padding(.bottom, 20)
            }
            if showInfo, let item = items[safe: index] {
                // Slides up from the bottom edge, fading in; down and out on closing.
                infoPanel(item: item)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: chrome)
        // Every way the panel opens or closes (Details, a tap on the photo, back) animates, and Write/Details fade
        // back with it.
        .animation(.easeInOut(duration: 0.25), value: showInfo)
        // They stay while the details panel, a sheet or the note editor is open, or Share gets the original, and for
        // the full time again after it closes.
        .task(id: [chrome, showInfo, choosingTag, editingCaption, sharing]) {
            guard chrome, !showInfo, !choosingTag, !editingCaption, !sharing else { return }
            try? await Task.sleep(for: .seconds(3))
            if !Task.isCancelled { chrome = false }
        }
        .onChange(of: currentId, initial: true) { old, new in
            actions.showing(new)
            if old != new { saveZoom(of: old) }
            // A photo zoomed in last time opens as usual, then glides to that zoom once it has landed: after the
            // viewer's zoom transition, or the swipe to it.
            guard let item = items.first(where: { $0.id == new }), !item.isVideo else { return }
            let model = zoomModel(item)
            guard model.hasSaved else { return }
            let delay = old == new ? 0.5 : 0.15
            DispatchQueue.main.asyncAfter(deadline: .now() + delay) { if currentId == new { model.resume() } }
        }
        .onDisappear { saveZoom(of: currentId) }
        .interactiveDismissDisabled(zoomed)
        // Immersive: the home indicator goes too.
        .persistentSystemOverlays(.hidden)
        .sheet(item: Binding(get: { faceSheet.map(IdentifiedFound.init) }, set: { if $0 == nil { faceSheet = nil } })) { item in
            FaceSheet(found: item.found, people: actions.people, actions: actions.faces) { faceSheet = nil }
                .preferredColorScheme(.dark)
        }
        .sheet(isPresented: $choosingTag) {
            if let item = items[safe: index] {
                TagSheet(
                    itemIds: [item.id],
                    stories: stories,
                    onToggle: { tag, add in actions.toggleTag(item.id, tag, add) },
                    onCreate: { tag in actions.toggleTag(item.id, tag, true) }
                )
            }
        }
        .sheet(isPresented: $editingCaption) {
            if let item = items[safe: index] {
                NoteEditor(
                    note: stories.note(itemId: item.id),
                    onSave: { actions.setWriting(item.id, $0, $1) },
                    onRestore: { actions.restoreWriting(item.id, $0) }
                )
            }
        }
        .statusBarHidden(!showInfo)
        .alert("Couldn’t get the original to share. Check the connection to your server.", isPresented: $shareFailed) {
            Button("OK", role: .cancel) {}
        }
    }

    /// Share: the original file to the system share sheet, once it is here (`ShareOriginal`).
    private func share(_ item: MediaItem) {
        sharing = true
        Task {
            let file = await ShareOriginal.file(for: item)
            sharing = false
            if let file { ShareOriginal.present(file) } else { shareFailed = true }
        }
    }

    private func toggleChrome() {
        showInfo = false
        chrome.toggle()
    }

    private func topBar(index: Int) -> some View {
        HStack {
            // Back closes the details panel first.
            Button { if showInfo { showInfo = false } else { onClose() } } label: {
                Image(systemName: "chevron.backward").font(.system(size: 18, weight: .semibold)).frame(width: 44, height: 44)
            }
            .accessibilityLabel("Back")
            if let item = items[safe: index] {
                if let showInMoments = actions.showInMoments {
                    // Not opened from Moments: the day is a chip that shows the photo there.
                    HStack(spacing: 6) {
                        Text("\(index + 1) of \(items.count) ·")
                            .font(.subheadline)
                            .foregroundStyle(Color(white: 0.75))
                        DayChip(day: item.day.label) { showInMoments(item.id) }
                    }
                } else {
                    Text("\(index + 1) of \(items.count) · \(item.day.label)")
                        .font(.subheadline)
                        .foregroundStyle(Color(white: 0.75))
                }
            }
            Spacer()
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 4)
        .background(LibraryColors.topFade.ignoresSafeArea())
    }

    private func infoPanel(item: MediaItem) -> some View {
        InfoPanel(
            item: item,
            findings: findings.itemId == item.id ? findings : PhotoFindings.companion.NONE,
            findingsCards: FindingsCards(
                findings: findings.itemId == item.id ? findings : PhotoFindings.companion.NONE,
                openPerson: actions.openPerson, tapFace: { faceSheet = $0 }, openSimilar: actions.openSimilar, moreSimilar: actions.moreSimilar
            ),
            note: stories.note(itemId: item.id),
            onAddTag: { choosingTag = true },
            onRemoveTag: { actions.toggleTag(item.id, $0, false) },
            setInStoryline: { actions.setInStoryline(item.id, $0) },
            location: stories.places.location(item: item).map { ($0, stories.places.name(point: $0)) },
            openMap: actions.openMap,
            setPrivate: actions.setPrivate
        )
        .task(id: item.id) { actions.findIn(item) }
    }
}

/// Longest side, in pixels, of a server photo's original decoded for the viewer (Android's VIEWER_MAX_SIDE, and the
/// preview size kept before a photo leaves the phone).
private let serverViewerMaxSide = 2560

/// The photo fitted on the screen, or at its `crop` (which has the screen's shape), filling it; zoomable from there
/// (`ViewerZoom.swift`). Once zoomed in, the original is decoded at its own size and takes the place of what showed.
private struct PhotoPage: View {
    let item: MediaItem
    let crop: CropRect?
    @ObservedObject var zoom: PhotoZoomModel
    let onTap: () -> Void

    @State private var image: UIImage?
    @State private var full: UIImage?

    var body: some View {
        if item.hasOriginalHere {
            libraryPhoto
        } else {
            ServerPhotoPage(item: item, crop: crop, zoom: zoom, onTap: onTap)
        }
    }

    private var libraryPhoto: some View {
        GeometryReader { geometry in
            ZStack {
                if let shown = full ?? image {
                    ZoomablePhoto(image: shown, model: zoom, pixelSide: pixelSide(of: item, shown), home: crop, onTap: onTap)
                } else {
                    Color.clear.contentShape(Rectangle()).onTapGesture(perform: onTap)
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .task(id: "\(item.id)|\(crop != nil)") {
                // A quick low-quality image first, then the sharp one, large enough for a crop to stay sharp.
                if image == nil {
                    image = await PhotoLibraryImages.shared.image(item: item, pointSize: CGSize(width: 200, height: 200))
                    zoom.picture = image
                }
                let size = crop.map { CGSize(width: geometry.size.width / CGFloat($0.w), height: geometry.size.height / CGFloat($0.h)) }
                    ?? geometry.size
                if let sharp = await PhotoLibraryImages.shared.image(item: item, pointSize: size) {
                    image = sharp
                }
            }
            .task(id: zoom.isZoomed) {
                guard zoom.isZoomed, full == nil else { return }
                full = await PhotoLibraryImages.shared.original(item: item, maxSide: zoomDecodeSide(item))
                zoom.atOriginalQuality = full != nil
            }
        }
        // The whole screen, so a crop (the screen's shape) fills it.
        .ignoresSafeArea()
        // The zoom is in the photo's coordinates, which don't mirror in Hebrew.
        .environment(\.layoutDirection, .leftToRight)
    }
}

/// The longer side of `item`'s own pixels, which "original size" shows one to one; the image's when not known.
private func pixelSide(of item: MediaItem, _ image: UIImage) -> CGFloat {
    let side = CGFloat(max(item.width, item.height))
    return side > 0 ? side : max(image.size.width, image.size.height) * image.scale
}

/// A photo only on the server: the best image of it already on this phone shows at once (asking the server for
/// nothing), while its original downloads into the cache (kept there) and then fades in over it. While it downloads,
/// a cloud at the top fills with its progress (only after a moment, and not at all when the original is here
/// already). When the phone can't decode the original, a preview at that size is asked for instead; offline, the
/// cached image stays.
private struct ServerPhotoPage: View {
    let item: MediaItem
    let crop: CropRect?
    @ObservedObject var zoom: PhotoZoomModel
    let onTap: () -> Void

    @State private var cached: UIImage?
    @State private var original: UIImage?
    @State private var full: UIImage?
    @State private var downloading = false
    @State private var showCloud = false
    @State private var progress: Double = 0

    var body: some View {
        ZStack {
            if let shown = full ?? original ?? cached {
                ZoomablePhoto(image: shown, model: zoom, pixelSide: pixelSide(of: item, shown), home: crop, onTap: onTap)
            } else {
                Color.clear.contentShape(Rectangle()).onTapGesture(perform: onTap)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .overlay(alignment: .topLeading) {
            if showCloud {
                DownloadCloud(progress: progress).padding(.top, 64).padding(.leading, 14).transition(.opacity)
            }
        }
        .ignoresSafeArea()
        .environment(\.layoutDirection, .leftToRight)
        .task(id: item.id) {
            cached = await ServerImages.shared.cached(item)
            zoom.picture = cached
            downloading = !(await ServerImages.shared.hasOriginal(item))
            let watching = ServerImages.shared.observeOriginalProgress(item) { if let now = $0 { progress = now } }
            defer { watching.cancel() }
            let image = await ServerImages.shared.viewer(item, maxSide: serverViewerMaxSide)
            original = image
            if zoom.picture == nil { zoom.picture = image }
            downloading = false
        }
        .task(id: downloading) {
            if downloading {
                try? await Task.sleep(for: .milliseconds(300))
                if Task.isCancelled { return }
            }
            withAnimation(.easeInOut(duration: 0.2)) { showCloud = downloading }
        }
        .task(id: zoom.isZoomed) {
            guard zoom.isZoomed, full == nil else { return }
            // Decoded again from the downloaded original, at its own size.
            full = await ServerImages.shared.fullOriginal(item, maxSide: zoomDecodeSide(item))
            zoom.atOriginalQuality = full != nil
        }
    }
}

/// The minimap at the top while the photo shown is zoomed in: as it moves and 1.5 seconds after, and with the bars.
private struct MinimapOverlay: View {
    @ObservedObject var model: PhotoZoomModel
    let chrome: Bool
    /// The details panel is open.
    let hidden: Bool

    @State private var moving = false

    var body: some View {
        ZStack {
            if let picture = model.picture, let part = model.visible, model.isZoomed, !hidden, chrome || moving {
                ZoomMinimap(image: picture, part: part, sharp: model.atOriginalQuality).transition(.opacity)
            }
        }
        .animation(.easeInOut(duration: 0.2), value: chrome || moving)
        .padding(.top, 52)
        .padding(.trailing, 12)
        .task(id: model.moves) {
            moving = true
            try? await Task.sleep(for: .seconds(1.5))
            if !Task.isCancelled { moving = false }
        }
    }
}

/// Tells the viewer whether the photo shown is zoomed in.
private struct ZoomFlag: View {
    @ObservedObject var model: PhotoZoomModel
    @Binding var zoomed: Bool

    var body: some View {
        Color.clear.onChange(of: model.isZoomed, initial: true) { zoomed = model.isZoomed }
    }
}

/// The day in the top bar as a chip ("Thu, Oct 8 ›"): Show in Moments.
private struct DayChip: View {
    let day: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                Text(day).font(.system(size: 14, weight: .semibold))
                Image(systemName: "chevron.right").font(.system(size: 14))
            }
            .foregroundStyle(.white)
            .padding(.leading, 10)
            .padding(.trailing, 6)
            .padding(.vertical, 5)
            .background(Color.white.opacity(0.14), in: Capsule())
        }
        .accessibilityLabel("Show in Moments")
        .accessibilityValue(day)
    }
}

/// "Cropped · Show all" over a cropped photo, or "Show crop" while the whole photo shows.
private struct CropChip: View {
    let showingWhole: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 6) {
                Image(systemName: "crop").font(.system(size: 13))
                if !showingWhole {
                    Text("Cropped ·").font(.system(size: 13)).foregroundStyle(Color(white: 0.75))
                }
                Text(showingWhole ? "Show crop" : "Show all").font(.system(size: 13, weight: .semibold))
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 14)
            .padding(.vertical, 8)
            .background(LibraryColors.pillScrim, in: Capsule())
        }
    }
}

/// A round-ended button over the photo: Write, Details.
private struct ViewerAction: View {
    let icon: String
    let label: LocalizedStringKey
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(label, systemImage: icon)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(.white)
                .padding(.horizontal, 18)
                .frame(height: 44)
                .background(LibraryColors.pillScrim, in: Capsule())
        }
    }
}

/// The round Share button after the pills; a spinner while the original is fetched.
private struct ShareButton: View {
    let busy: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            ZStack {
                if busy {
                    ProgressView().tint(.white)
                } else {
                    Image(systemName: "square.and.arrow.up").font(.system(size: 18, weight: .semibold))
                }
            }
            .foregroundStyle(.white)
            .frame(width: 44, height: 44)
            .background(LibraryColors.pillScrim, in: Circle())
        }
        .disabled(busy)
        .accessibilityLabel("Share")
    }
}

/// Shows the poster frame until Play is tapped; stops when swiped away. A video only on the server streams from its
/// original, and what was fetched stays cached (`ServerVideos`); when it can't play, a line over the poster says why.
private struct VideoPage: View {
    let item: MediaItem
    let isCurrent: Bool
    /// A tap on the poster, or on the playing video (which also shows the player's own controls).
    var onTap: () -> Void = {}

    @State private var player: AVPlayer?
    @State private var poster: UIImage?
    @State private var failure: String?
    @State private var status: NSKeyValueObservation?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let player {
                    VideoPlayer(player: player)
                        .simultaneousGesture(TapGesture().onEnded(onTap))
                } else {
                    if let poster {
                        Image(uiImage: poster).resizable().scaledToFit()
                    }
                    Button(action: play) {
                        Image(systemName: "play.fill")
                            .font(.system(size: 30))
                            .foregroundStyle(.white)
                            .frame(width: 72, height: 72)
                            .background(LibraryColors.badgeScrim, in: Circle())
                    }
                    .accessibilityLabel("Play video")
                }
                if let failure {
                    Text(failure)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(.white)
                        .padding(.horizontal, 14)
                        .padding(.vertical, 10)
                        .background(LibraryColors.pillScrim, in: RoundedRectangle(cornerRadius: 14))
                        .padding(.horizontal, 24)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .padding(.bottom, 96)
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .contentShape(Rectangle())
            .onTapGesture { if player == nil { onTap() } }
            .task(id: item.id) {
                // A video only on the server shows the poster already kept (a tile's), asking for no new size.
                poster = item.hasOriginalHere
                    ? await PhotoLibraryImages.shared.image(item: item, pointSize: geometry.size)
                    : await ServerImages.shared.cached(item)            }
        }
        .onChange(of: isCurrent) { _, current in
            if !current { stop() }
        }
    }

    private func play() {
        failure = nil
        Task {
            guard let playerItem = await PhotoLibraryImages.shared.playerItem(item: item) else {
                if !item.hasOriginalHere {
                    failure = String(localized: "This video can only be played once the phone is registered with your server.")
                }
                return
            }
            // Offline, say: back to the poster, with Play to try again.
            status = playerItem.observe(\.status, options: [.new]) { playerItem, _ in
                guard playerItem.status == .failed else { return }
                DispatchQueue.main.async {
                    stop()
                    failure = String(localized: "The video can’t be played right now. Is the server reachable?")
                }
            }
            let player = AVPlayer(playerItem: playerItem)
            self.player = player
            player.play()
        }
    }

    private func stop() {
        status?.invalidate()
        status = nil
        player?.pause()
        player = nil
    }
}

/// Date, tags, storyline, where it was taken (on a map), the original (where it is, its file facts) and its details
/// read from the original file (camera and exposure, codecs), as Android's ViewerInfoPanel. Writing is reached from the
/// viewer's Write button.
private struct InfoPanel: View {
    let item: MediaItem
    let findings: PhotoFindings
    let findingsCards: FindingsCards
    let note: PhotoNote?
    let onAddTag: () -> Void
    let onRemoveTag: (String) -> Void
    /// "Remove" (false) or "Add back" (true).
    let setInStoryline: (Bool) -> Void
    let location: (GeoPoint, PlaceName?)?
    let openMap: (GeoPoint) -> Void
    let setPrivate: (MediaItem, Bool) async -> Bool

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(item.takenAt.formatted(date: .complete, time: .omitted))
                        .font(.title3.weight(.bold))
                    Text(item.takenAt.formatted(date: .omitted, time: .shortened))
                        .font(.subheadline)
                        .foregroundStyle(LibraryColors.onInverseMuted)
                }
                VStack(alignment: .leading, spacing: 6) {
                    Text("Tags").font(.caption).foregroundStyle(LibraryColors.onInverseMuted)
                    FlowLayout(spacing: 6) {
                        ForEach(note?.tags ?? [], id: \.self) { tag in
                            HStack(spacing: 4) {
                                Text(tag).font(.subheadline.weight(.medium))
                                Button { onRemoveTag(tag) } label: {
                                    Image(systemName: "xmark").font(.caption2.weight(.bold)).frame(width: 28, height: 32)
                                }
                                .foregroundStyle(LibraryColors.onInverseMuted)
                                .accessibilityLabel("Remove tag \(tag)")
                            }
                            .padding(.leading, 12)
                            .background(LibraryColors.accent.opacity(0.12), in: Capsule())
                        }
                        Button(action: onAddTag) { Label("Tag", systemImage: "plus") }
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(LibraryColors.accent)
                            .frame(height: 32)
                    }
                }
                // Only a photo with writing can be in the storyline.
                if let note, note.hasWriting {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Storyline").font(.caption).foregroundStyle(LibraryColors.onInverseMuted)
                        HStack(spacing: 16) {
                            Text(note.inStoryline ? "In the storyline" : "Not in the storyline").font(.subheadline.weight(.medium))
                            if note.inStoryline {
                                Button("Remove") { setInStoryline(false) }.foregroundStyle(LibraryColors.error)
                            } else {
                                Button("Add back") { setInStoryline(true) }.foregroundStyle(LibraryColors.accent)
                            }
                        }
                        .font(.subheadline.weight(.semibold))
                    }
                }
                if !findings.isEmpty { findingsCards }
                if let (point, name) = location {
                    Button { openMap(point) } label: { LocationCard(point: point, name: name) }
                        .buttonStyle(.plain)
                        .accessibilityLabel("Open in the map")
                }
                OriginalCard(item: item, setPrivate: setPrivate)
                DetailsCard(item: item)
            }
            .foregroundStyle(LibraryColors.onInverse)
            .padding(20)
            .padding(.bottom, 8)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(maxHeight: 560)
        .fixedSize(horizontal: false, vertical: true)
        .background(LibraryColors.inverse, in: UnevenRoundedRectangle(topLeadingRadius: 24, topTrailingRadius: 24))
        .ignoresSafeArea(edges: .bottom)
    }
}

/// "HEIC", "JPEG", "DNG (RAW)", "MOV video"; the MIME type itself for others.
func typeName(_ mimeType: String) -> String {
    guard let kind = MediaFactsKt.mediaKind(mimeType: mimeType) else { return mimeType }
    if kind.video { return String(localized: "\(kind.name) video") }
    return kind.raw ? "\(kind.name) (RAW)" : kind.name
}

extension MediaSource {
    var label: String {
        if let folder { return folder }
        switch self {
        case .whatsapp: return String(localized: "WhatsApp")
        case .screenshots: return String(localized: "Screenshots")
        case .other: return String(localized: "Other")
        default: return String(localized: "Camera")
        }
    }
}

struct Detail: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label).font(.caption).foregroundStyle(LibraryColors.onInverseMuted)
            Text(value).font(.subheadline.weight(.medium))
        }
    }
}

private extension Array {
    subscript(safe index: Int) -> Element? { indices.contains(index) ? self[index] : nil }
}
