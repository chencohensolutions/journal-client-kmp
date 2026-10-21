import SwiftUI
import SharedLogic

// Crops are only for looking: the photo itself is never changed. See docs/screens/crop.md; the geometry (moving,
// pinching, dragging a corner, fitting another screen) is PhotoCrops.kt in the shared logic.

/// Which photo the crop screen opens for, and on which crop.
struct CropRequest: Identifiable {
    let itemId: String
    let kind: CropKind
    var id: String { itemId }
}

extension View {
    /// Shows the crop screen full screen while `request` is set; Done hands the photo's new crops to `save`.
    func cropCover(_ request: Binding<CropRequest?>, stories: StoriesState, save: @escaping (_ itemId: String, _ crops: PhotoCrops) -> Void) -> some View {
        fullScreenCover(item: request) { crop in
            if let item = stories.itemsById[crop.itemId] {
                CropView(
                    item: item,
                    saved: stories.note(itemId: item.id)?.crops ?? PhotoCrops(moments: nil, portrait: nil, landscape: nil),
                    startKind: crop.kind,
                    onDone: { crops in
                        save(item.id, crops)
                        request.wrappedValue = nil
                    },
                    onClose: { request.wrappedValue = nil }
                )
                .preferredColorScheme(.dark)
            } else {
                // Deleted from the device while open.
                Color.black.onAppear { request.wrappedValue = nil }
            }
        }
    }
}

extension GeometryProxy {
    /// The size with the safe area added back: the whole screen, for a view that fills it.
    var screenSize: CGSize {
        CGSize(
            width: size.width + safeAreaInsets.leading + safeAreaInsets.trailing,
            height: size.height + safeAreaInsets.top + safeAreaInsets.bottom)
    }
}

extension MediaItem {
    /// The photo's shape (width / height), 4:3 when unknown.
    var ratio: Float { width > 0 && height > 0 ? Float(width) / Float(height) : 4 / 3 }
}

/// The shape (width / height) a crop of `kind` is locked to on a screen of `screenRatio` upright, or nil for any.
func lockedRatio(_ kind: CropKind, screenRatio: Float) -> Float? {
    switch kind {
    case .portrait: screenRatio
    case .landscape: 1 / screenRatio
    default: nil
    }
}

/// The crop screen: `saved` are the photo's crops, edited here until Done hands back the new ones (`onDone`). A mode
/// opened here is saved as shown, its suggestion too, unless it was reset; Moments' suggestion (the whole photo) is no
/// crop. Opens on `startKind`.
struct CropView: View {
    let item: MediaItem
    let saved: PhotoCrops
    let onDone: (PhotoCrops) -> Void
    let onClose: () -> Void

    @State private var kind: CropKind
    @State private var drafts: PhotoCrops
    /// The modes opened here (and not reset since), by name: their suggestion is kept on Done.
    @State private var opened: Set<String>
    /// This phone's screen held upright (width / height), whichever way it is held now.
    @State private var screenRatio: Float = 9 / 19.5

    init(item: MediaItem, saved: PhotoCrops, startKind: CropKind, onDone: @escaping (PhotoCrops) -> Void, onClose: @escaping () -> Void) {
        self.item = item
        self.saved = saved
        self.onDone = onDone
        self.onClose = onClose
        _kind = State(initialValue: startKind)
        _drafts = State(initialValue: saved)
        _opened = State(initialValue: [startKind.name])
    }

    private func suggestion(_ mode: CropKind) -> CropRect {
        switch mode {
        case .portrait: CropRect.companion.largest(ratio: screenRatio, photoRatio: item.ratio, around: drafts.moments ?? drafts.landscape)
        case .landscape: CropRect.companion.largest(ratio: 1 / screenRatio, photoRatio: item.ratio, around: drafts.moments ?? drafts.portrait)
        default: CropRect.companion.WHOLE
        }
    }

    /// A saved crop made for another screen is shown fitted to this one; an unset one starts from a suggestion.
    private var frame: CropRect {
        guard let rect = drafts.get(kind: kind) else { return suggestion(kind) }
        return lockedRatio(kind, screenRatio: screenRatio).map { rect.fittedTo(screenRatio: $0, photoRatio: item.ratio) } ?? rect
    }

    /// What Done saves: the drafts, plus the suggestion of each Portrait or Landscape opened and left as suggested.
    private var result: PhotoCrops {
        CropKind.entries.reduce(drafts) { crops, mode in
            crops.get(kind: mode) != nil || !opened.contains(mode.name) || mode == .moments ? crops : crops.with(kind: mode, rect: suggestion(mode))
        }
    }

    var body: some View {
        let result = result
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.system(size: 18, weight: .semibold)).frame(width: 44, height: 44)
                }
                .accessibilityLabel("Cancel")
                Text("Crop").font(.system(size: 17, weight: .bold))
                Spacer()
                Button("Reset") {
                    drafts = drafts.with(kind: kind, rect: nil)
                    opened.remove(kind.name)
                }
                .disabled(result.get(kind: kind) == nil)
                .foregroundStyle(result.get(kind: kind) == nil ? Color.white.opacity(0.35) : .white)
                .padding(.horizontal, 8)
                Button {
                    if result == saved { onClose() } else { onDone(result) }
                } label: {
                    Text("Done")
                        .font(.system(size: 14, weight: .semibold))
                        .padding(.horizontal, 18)
                        .frame(height: 36)
                        .background(LibraryColors.accent, in: Capsule())
                }
                .padding(.trailing, 8)
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 4)
            .padding(.vertical, 4)

            CropCanvas(item: item, frame: frame, locked: lockedRatio(kind, screenRatio: screenRatio)) { rect in
                drafts = drafts.with(kind: kind, rect: rect)
                opened.insert(kind.name)
            }
            .padding(.horizontal, 20)
            .padding(.vertical, 16)

            Text(hint)
                .font(.system(size: 13))
                .foregroundStyle(Color(red: 0xB8 / 255, green: 0xC2 / 255, blue: 0xBF / 255))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
            Text("The photo itself isn't changed.")
                .font(.system(size: 12))
                .foregroundStyle(Color(red: 0x7E / 255, green: 0x89 / 255, blue: 0x87 / 255))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 24)
                .padding(.vertical, 4)

            CropModes(kind: kind, saved: result, screenRatio: screenRatio) { mode in
                kind = mode
                opened.insert(mode.name)
            }
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
        }
        .background {
            // The whole screen, under the bars too.
            Color.black
                .ignoresSafeArea()
                .onGeometryChange(for: Float.self) { proxy in
                    let size = proxy.screenSize
                    guard size.width > 0, size.height > 0 else { return 9 / 19.5 }
                    return Float(min(size.width, size.height) / max(size.width, size.height))
                } action: { screenRatio = $0 }
        }
    }

    private var hint: LocalizedStringKey {
        switch kind {
        case .portrait: "Your screen's shape. Shown when you view it with the phone upright."
        case .landscape: "Your screen's shape, sideways. Shown when you view it with the phone turned."
        default: "Any shape. This is how the photo shows in Moments."
        }
    }
}

/// The photo fitted in the space, dimmed outside `frame`. Dragging moves the frame and pinching sizes it; the handles
/// drag a corner (keeping `locked`, the shape width / height, when set) or, for a free crop, a side too.
private struct CropCanvas: View {
    let item: MediaItem
    let frame: CropRect
    let locked: Float?
    let onChange: (CropRect) -> Void

    @State private var image: UIImage?
    @State private var lastPan: CGSize = .zero
    @State private var lastZoom: CGFloat = 1
    @State private var lastHandleDrag: CGSize = .zero

    var body: some View {
        GeometryReader { geometry in
            let photoRatio = CGFloat(item.ratio)
            let box = geometry.size
            let size = photoRatio >= box.width / max(box.height, 1)
                ? CGSize(width: box.width, height: box.width / photoRatio)
                : CGSize(width: box.height * photoRatio, height: box.height)
            ZStack(alignment: .topLeading) {
                if let image {
                    Image(uiImage: image).resizable().scaledToFill().frame(width: size.width, height: size.height).clipped()
                }
                CropFrame(frame: frame, free: locked == nil)
                    .contentShape(Rectangle())
                    .gesture(moveAndPinch(size).simultaneously(with: pinch()))
                ForEach(CropHandle.entries.filter { locked == nil || $0.isCorner }, id: \.name) { handle in
                    Color.clear
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                        .position(handlePoint(handle, size))
                        .gesture(resize(handle, size))
                }
            }
            .frame(width: size.width, height: size.height)
            .position(x: box.width / 2, y: box.height / 2)
            .task(id: item.id) {
                image = await PhotoLibraryImages.shared.image(id: item.id, pointSize: size)
            }
            // The crop is in the photo's coordinates, which don't mirror in Hebrew.
            .environment(\.layoutDirection, .leftToRight)
        }
    }

    private func handlePoint(_ handle: CropHandle, _ size: CGSize) -> CGPoint {
        let x = handle.left ? frame.x : handle.right ? frame.x + frame.w : frame.x + frame.w / 2
        let y = handle.top ? frame.y : handle.bottom ? frame.y + frame.h : frame.y + frame.h / 2
        return CGPoint(x: CGFloat(x) * size.width, y: CGFloat(y) * size.height)
    }

    // The gestures report totals; the shared functions take the change since the last report.
    private func moveAndPinch(_ size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                let dx = value.translation.width - lastPan.width
                let dy = value.translation.height - lastPan.height
                lastPan = value.translation
                onChange(frame.moved(dx: Float(dx / size.width), dy: Float(dy / size.height)))
            }
            .onEnded { _ in lastPan = .zero }
    }

    private func pinch() -> some Gesture {
        MagnifyGesture()
            .onChanged { value in
                let zoom = value.magnification / lastZoom
                lastZoom = value.magnification
                if zoom > 0 { onChange(frame.scaled(factor: Float(1 / zoom))) }
            }
            .onEnded { _ in lastZoom = 1 }
    }

    private func resize(_ handle: CropHandle, _ size: CGSize) -> some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { value in
                let dx = value.translation.width - lastHandleDrag.width
                let dy = value.translation.height - lastHandleDrag.height
                lastHandleDrag = value.translation
                onChange(frame.resized(
                    handle: handle,
                    dx: Float(dx / size.width),
                    dy: Float(dy / size.height),
                    photoRatio: item.ratio,
                    lockedRatio: locked.map { KotlinFloat(value: $0) }
                ))
            }
            .onEnded { _ in lastHandleDrag = .zero }
    }
}

/// Dims outside `frame` and draws its thirds, border and handles (corners; a free crop the middle of each side too).
private struct CropFrame: View {
    let frame: CropRect
    let free: Bool

    var body: some View {
        Canvas { context, size in
            let rect = CGRect(
                x: CGFloat(frame.x) * size.width, y: CGFloat(frame.y) * size.height,
                width: CGFloat(frame.w) * size.width, height: CGFloat(frame.h) * size.height)
            // Outside the frame: above, below, then the two sides between.
            var outside = Path()
            outside.addRect(CGRect(x: 0, y: 0, width: size.width, height: rect.minY))
            outside.addRect(CGRect(x: 0, y: rect.maxY, width: size.width, height: size.height - rect.maxY))
            outside.addRect(CGRect(x: 0, y: rect.minY, width: rect.minX, height: rect.height))
            outside.addRect(CGRect(x: rect.maxX, y: rect.minY, width: size.width - rect.maxX, height: rect.height))
            context.fill(outside, with: .color(.black.opacity(0.6)))

            var thirds = Path()
            for i in 1...2 {
                let x = rect.minX + rect.width * CGFloat(i) / 3
                let y = rect.minY + rect.height * CGFloat(i) / 3
                thirds.move(to: CGPoint(x: x, y: rect.minY))
                thirds.addLine(to: CGPoint(x: x, y: rect.maxY))
                thirds.move(to: CGPoint(x: rect.minX, y: y))
                thirds.addLine(to: CGPoint(x: rect.maxX, y: y))
            }
            context.stroke(thirds, with: .color(.white.opacity(0.35)), lineWidth: 1)
            context.stroke(Path(rect), with: .color(.white), lineWidth: 1.5)

            let arm: CGFloat = 18
            var handles = Path()
            for (corner, dx, dy) in [(CGPoint(x: rect.minX, y: rect.minY), 1.0, 1.0), (CGPoint(x: rect.maxX, y: rect.minY), -1.0, 1.0),
                                     (CGPoint(x: rect.minX, y: rect.maxY), 1.0, -1.0), (CGPoint(x: rect.maxX, y: rect.maxY), -1.0, -1.0)] {
                handles.move(to: CGPoint(x: corner.x + arm * dx, y: corner.y))
                handles.addLine(to: corner)
                handles.addLine(to: CGPoint(x: corner.x, y: corner.y + arm * dy))
            }
            if free {
                let half = arm / 2
                handles.move(to: CGPoint(x: rect.midX - half, y: rect.minY))
                handles.addLine(to: CGPoint(x: rect.midX + half, y: rect.minY))
                handles.move(to: CGPoint(x: rect.midX - half, y: rect.maxY))
                handles.addLine(to: CGPoint(x: rect.midX + half, y: rect.maxY))
                handles.move(to: CGPoint(x: rect.minX, y: rect.midY - half))
                handles.addLine(to: CGPoint(x: rect.minX, y: rect.midY + half))
                handles.move(to: CGPoint(x: rect.maxX, y: rect.midY - half))
                handles.addLine(to: CGPoint(x: rect.maxX, y: rect.midY + half))
            }
            context.stroke(handles, with: .color(.white), style: StrokeStyle(lineWidth: 3.5, lineCap: .square, lineJoin: .miter))
        }
    }
}

/// Moments (any shape), Portrait and Landscape (the screen's shape), each with its shape drawn and a dot once set.
private struct CropModes: View {
    let kind: CropKind
    let saved: PhotoCrops
    let screenRatio: Float
    let onSelect: (CropKind) -> Void

    var body: some View {
        HStack(spacing: 4) {
            ForEach(CropKind.entries, id: \.name) { mode in
                let on = mode == kind
                let ink = on ? LibraryColors.paperInk : Color.white
                Button { onSelect(mode) } label: {
                    VStack(spacing: 6) {
                        ShapeIcon(ratio: lockedRatio(mode, screenRatio: screenRatio)).stroke(ink, lineWidth: 1.8).frame(width: 24, height: 24)
                        HStack(spacing: 5) {
                            Text(label(mode)).font(.system(size: 13, weight: .semibold)).foregroundStyle(ink)
                            if saved.get(kind: mode) != nil {
                                Circle()
                                    .fill(on ? LibraryColors.accent : Color(red: 0x8F / 255, green: 0xD3 / 255, blue: 0xC7 / 255))
                                    .frame(width: 6, height: 6)
                            }
                        }
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .background(on ? Color.white : .clear, in: RoundedRectangle(cornerRadius: 14))
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(on ? .isSelected : [])
            }
        }
        .padding(4)
        .background(Color.white.opacity(0.08), in: RoundedRectangle(cornerRadius: 18))
    }

    private func label(_ mode: CropKind) -> LocalizedStringKey {
        switch mode {
        case .portrait: "Portrait"
        case .landscape: "Landscape"
        default: "Moments"
        }
    }
}

/// A rectangle of `ratio` (width / height); nil draws a free shape: four corners only.
private struct ShapeIcon: Shape {
    let ratio: Float?

    func path(in rect: CGRect) -> Path {
        guard let ratio = ratio.map({ CGFloat($0) }) else {
            let frame = rect.insetBy(dx: rect.width * 0.05, dy: rect.height * 0.16)
            let arm = rect.width * 0.22
            var path = Path()
            for (corner, dx, dy) in [(CGPoint(x: frame.minX, y: frame.minY), 1.0, 1.0), (CGPoint(x: frame.maxX, y: frame.minY), -1.0, 1.0),
                                     (CGPoint(x: frame.minX, y: frame.maxY), 1.0, -1.0), (CGPoint(x: frame.maxX, y: frame.maxY), -1.0, -1.0)] {
                path.move(to: CGPoint(x: corner.x + arm * dx, y: corner.y))
                path.addLine(to: corner)
                path.addLine(to: CGPoint(x: corner.x, y: corner.y + arm * dy))
            }
            return path
        }
        let w = ratio >= 1 ? rect.width : rect.height * ratio
        let h = ratio >= 1 ? rect.width / ratio : rect.height
        return Path(roundedRect: CGRect(x: rect.midX - w / 2, y: rect.midY - h / 2, width: w, height: h), cornerRadius: 2)
    }
}
