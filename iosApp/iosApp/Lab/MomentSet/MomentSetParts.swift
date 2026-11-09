#if DEBUG

import SwiftUI
import UIKit

// Parts shared by the options for a set of photos (lab round "moment-set", Nov 9, 2026): the set's state, a stand-in
// picture, the cross-fading photo, the slideshow, the scrubber, the viewer and the recompose screen. Each option
// (OptionA, OptionB, OptionC) is a different way into them. What moves into the app on promotion: the state (into
// the shared logic, from the notes' `front` and Moments crops) and the views, with SetPicture replaced by the app's
// photo views.

// MARK: - State

/// A photo of a set, as the shared logic would give it: its shape and its Moments crop (the part shown, from 0 to 1
/// of the photo's width and height; nil shows it whole).
struct SetPhoto: Identifiable, Equatable {
    let id: String
    /// Its place in the burst, for the stand-in picture.
    let index: Int
    let of: Int
    /// Width over height.
    let aspect: CGFloat
    var crop: CGRect?
    /// The stand-in scene: 0 the beach, 1 the park.
    var scene = 0

    /// Width over height as shown, at its crop.
    var shownAspect: CGFloat {
        guard let crop else { return aspect }
        return aspect * crop.width / crop.height
    }
}

/// A set (near-duplicates or merged by hand) as a page would show it. `mainId` is the photo the set shows in Moments
/// and opens on (the note's `front` today, which turning the set sets).
struct MomentSetState {
    var place: String
    var when: String
    var photos: [SetPhoto]
    var mainId: String

    var mainIndex: Int { photos.firstIndex { $0.id == mainId } ?? 0 }
}

extension CGRect {
    static let whole = CGRect(x: 0, y: 0, width: 1, height: 1)
    var isWhole: Bool { abs(minX) < 0.001 && abs(minY) < 0.001 && abs(width - 1) < 0.001 && abs(height - 1) < 0.001 }

    /// Moved back inside the photo, its size kept.
    func keptInside() -> CGRect {
        let w = min(width, 1), h = min(height, 1)
        return CGRect(x: Swift.min(Swift.max(minX, 0), 1 - w), y: Swift.min(Swift.max(minY, 0), 1 - h), width: w, height: h)
    }
}

/// The largest rect of `aspect` inside `size`, centered.
func setFitted(aspect: CGFloat, in size: CGSize) -> CGRect {
    let w = min(size.width, size.height * aspect)
    let h = w / aspect
    return CGRect(x: (size.width - w) / 2, y: (size.height - h) / 2, width: w, height: h)
}

func setHaptic() { UISelectionFeedbackGenerator().selectionChanged() }

// MARK: - Pictures

/// A set's photo at its crop (or whole), filling the space it is given: give it the photo's shown shape.
struct SetPicture: View {
    let photo: SetPhoto
    var cropped = true

    var body: some View {
        GeometryReader { geo in
            let crop = cropped ? (photo.crop ?? .whole) : .whole
            let fullW = geo.size.width / crop.width
            let fullH = geo.size.height / crop.height
            SetScene(index: photo.index, of: photo.of, scene: photo.scene)
                .frame(width: fullW, height: fullH)
                .offset(x: -crop.minX * fullW, y: -crop.minY * fullH)
                .frame(width: geo.size.width, height: geo.size.height, alignment: .topLeading)
        }
        .clipped()
    }
}

/// A stand-in for a photo of a burst: someone running along a beach at sunset, a little further in each shot, so
/// cross-fades and the slideshow read like the real thing. Scene 1 is a park, for the photos around the set.
struct SetScene: View {
    let index: Int
    let of: Int
    var scene = 0

    var body: some View {
        Canvas { ctx, size in
            let w = size.width, h = size.height, s = min(w, h)
            let t = of > 1 ? CGFloat(index) / CGFloat(of - 1) : 0
            let park = scene == 1
            let sky = park
                ? [Color(red: 0.72, green: 0.84, blue: 0.93), Color(red: 0.86, green: 0.92, blue: 0.95)]
                : [Color(red: 0.98, green: 0.78 - 0.12 * t, blue: 0.60 - 0.1 * t), Color(red: 0.55, green: 0.68, blue: 0.80)]
            ctx.fill(Path(CGRect(origin: .zero, size: size)),
                     with: .linearGradient(Gradient(colors: sky), startPoint: CGPoint(x: w / 2, y: 0), endPoint: CGPoint(x: w / 2, y: h * 0.6)))
            let r = s * 0.10
            let sunY = park ? h * 0.2 : h * (0.16 + 0.22 * t)
            ctx.fill(Path(ellipseIn: CGRect(x: w * 0.74 - r, y: sunY - r, width: 2 * r, height: 2 * r)), with: .color(.white.opacity(0.75)))
            if park {
                ctx.fill(Path(CGRect(x: 0, y: h * 0.58, width: w, height: h * 0.42)), with: .color(Color(red: 0.47, green: 0.64, blue: 0.40)))
                for k in 0..<3 {
                    let x = w * (0.15 + 0.3 * CGFloat(k))
                    ctx.fill(Path(CGRect(x: x - s * 0.015, y: h * 0.45, width: s * 0.03, height: h * 0.15)), with: .color(Color(red: 0.4, green: 0.3, blue: 0.22)))
                    ctx.fill(Path(ellipseIn: CGRect(x: x - s * 0.1, y: h * 0.32, width: s * 0.2, height: s * 0.2)), with: .color(Color(red: 0.30, green: 0.50, blue: 0.32)))
                }
                return
            }
            ctx.fill(Path(CGRect(x: 0, y: h * 0.55, width: w, height: h * 0.16)), with: .color(Color(red: 0.30, green: 0.50, blue: 0.62)))
            ctx.fill(Path(CGRect(x: 0, y: h * 0.70, width: w, height: h * 0.30)), with: .color(Color(red: 0.89, green: 0.80, blue: 0.66)))
            // The runner: further along each shot, off the ground every other one.
            let x = w * (0.16 + 0.68 * t)
            let jump = index % 2 == 1 ? h * 0.05 : 0
            let ground = h * 0.80
            let feet = ground - jump
            ctx.fill(Path(ellipseIn: CGRect(x: x - s * 0.06, y: ground, width: s * 0.12, height: s * 0.022)), with: .color(.black.opacity(0.15)))
            ctx.fill(Path(roundedRect: CGRect(x: x - s * 0.04, y: feet - s * 0.17, width: s * 0.08, height: s * 0.15), cornerRadius: s * 0.03),
                     with: .color(Color(red: 0.78, green: 0.25, blue: 0.22)))
            ctx.fill(Path(ellipseIn: CGRect(x: x - s * 0.035, y: feet - s * 0.245, width: s * 0.07, height: s * 0.07)),
                     with: .color(Color(red: 0.36, green: 0.24, blue: 0.18)))
        }
    }
}

/// A square tile of a set's photo at its crop, with a star on the main one and a crop mark on a recomposed one.
struct SetTile: View {
    let photo: SetPhoto
    let isMain: Bool
    var current = false

    var body: some View {
        Color.clear
            .aspectRatio(1, contentMode: .fit)
            .overlay(SetPicture(photo: photo).aspectRatio(photo.shownAspect, contentMode: .fill))
            .clipped()
            .overlay(alignment: .topLeading) {
                if isMain {
                    Image(systemName: "star.fill")
                        .font(.system(size: 10, weight: .bold))
                        .foregroundStyle(LibraryColors.paperInk)
                        .padding(4)
                        .background(LibraryColors.paper, in: Circle())
                        .padding(5)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                if photo.crop != nil {
                    Image(systemName: "crop")
                        .font(.system(size: 10, weight: .semibold))
                        .foregroundStyle(LibraryColors.photoText)
                        .padding(4)
                        .background(LibraryColors.badgeScrim, in: Circle())
                        .padding(5)
                }
            }
            .overlay { if current { Rectangle().strokeBorder(.white, lineWidth: 3) } }
    }
}

// MARK: - Cross-fade and slideshow

/// The set's photos one at a time on black, fitted, at their crop. A swipe changes the photo with a quick cross-fade
/// (0.12 s) instead of a slide, so going through many feels like flipping.
struct SetFadePhoto: View {
    let photos: [SetPhoto]
    @Binding var index: Int
    var onTap: () -> Void = {}
    @Environment(\.layoutDirection) private var direction

    var body: some View {
        let photo = photos[index]
        ZStack {
            Color.black
            SetPicture(photo: photo)
                .aspectRatio(photo.shownAspect, contentMode: .fit)
                .id(photo.id)
                .transition(.opacity)
        }
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
        .gesture(
            DragGesture(minimumDistance: 20).onEnded { value in
                let dx = value.translation.width
                guard abs(dx) > abs(value.translation.height), abs(dx) > 40 else { return }
                let forward = (dx < 0) == (direction == .leftToRight)
                let next = index + (forward ? 1 : -1)
                guard photos.indices.contains(next) else { return }
                withAnimation(.easeOut(duration: 0.12)) { index = next }
            }
        )
    }
}

/// Plays the set while `playing`: the next photo every `seconds`, cross-fading, from the one shown, round and round.
struct SetSlideshow: ViewModifier {
    @Binding var playing: Bool
    @Binding var index: Int
    let count: Int
    let seconds: Double

    func body(content: Content) -> some View {
        content.task(id: playing ? seconds : 0) {
            guard playing else { return }
            while !Task.isCancelled {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                if Task.isCancelled || !playing { break }
                withAnimation(.easeInOut(duration: min(0.3, seconds * 0.4))) { index = (index + 1) % count }
            }
        }
    }
}

/// Fast · Normal · Slow for the slideshow.
struct SetSpeedPicker: View {
    @Binding var seconds: Double

    var body: some View {
        HStack(spacing: 2) {
            choice("Fast", 0.35)
            choice("Normal", 0.8)
            choice("Slow", 2)
        }
        .padding(3)
        .background(LibraryColors.pillScrim, in: Capsule())
    }

    private func choice(_ title: LocalizedStringKey, _ value: Double) -> some View {
        let on = abs(seconds - value) < 0.01
        return Button { seconds = value } label: {
            Text(title, tableName: "MomentSet")
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(on ? LibraryColors.paperInk : LibraryColors.photoText)
                .padding(.horizontal, 12)
                .frame(height: 30)
                .background(on ? LibraryColors.paper : .clear, in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Scrubber

/// Every photo of the set in one row across the screen, however many; sliding a finger along it flips straight to
/// the photo under it (no fade: as fast as the finger), with a tick each time. The one shown stands out, larger.
struct SetScrubber: View {
    let photos: [SetPhoto]
    let mainId: String
    @Binding var index: Int

    var body: some View {
        GeometryReader { geo in
            let w = geo.size.width / CGFloat(photos.count)
            ZStack(alignment: .topLeading) {
                HStack(spacing: 0) {
                    ForEach(photos) { photo in
                        SetPicture(photo: photo)
                            .aspectRatio(photo.shownAspect, contentMode: .fill)
                            .frame(width: w, height: 40)
                            .clipped()
                            .overlay(alignment: .top) {
                                if photo.id == mainId { Circle().fill(LibraryColors.paper).frame(width: 5, height: 5).padding(.top, 3) }
                            }
                    }
                }
                .opacity(0.75)
                .clipShape(RoundedRectangle(cornerRadius: 6))
                .padding(.top, 8)
                let shownWidth: CGFloat = 38
                SetPicture(photo: photos[index])
                    .aspectRatio(photos[index].shownAspect, contentMode: .fill)
                    .frame(width: shownWidth, height: 56)
                    .clipped()
                    .overlay(RoundedRectangle(cornerRadius: 4).strokeBorder(.white, lineWidth: 2))
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                    .shadow(radius: 4)
                    .offset(x: min(max(CGFloat(index) * w + w / 2 - shownWidth / 2, 0), geo.size.width - shownWidth))
            }
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0).onChanged { value in
                    let i = min(max(Int(value.location.x / w), 0), photos.count - 1)
                    if i != index {
                        index = i
                        setHaptic()
                    }
                }
            )
        }
        .frame(height: 56)
        .environment(\.layoutDirection, .leftToRight)
    }
}

// MARK: - Viewer

/// The set full screen on black: swipes cross-fade, the main star, Recompose and a big Play in the middle. With
/// `strip`, the scrubber of every photo is above them and a grid button at the top shows them all.
struct SetViewer: View {
    @Binding var set: MomentSetState
    let strip: Bool
    let onClose: () -> Void

    @State private var index: Int
    @State private var playing: Bool
    @State private var seconds = 0.8
    @State private var recomposing: Bool
    @State private var grid = false

    init(set: Binding<MomentSetState>, index: Int, strip: Bool, playing: Bool = false, recomposing: Bool = false, onClose: @escaping () -> Void) {
        _set = set
        self.strip = strip
        self.onClose = onClose
        _index = State(initialValue: index)
        _playing = State(initialValue: playing)
        _recomposing = State(initialValue: recomposing)
    }

    var body: some View {
        let photo = set.photos[index]
        ZStack {
            SetFadePhoto(photos: set.photos, index: $index) { if playing { playing = false } }
                .ignoresSafeArea()
            VStack(spacing: 0) {
                topBar.opacity(playing ? 0 : 1)
                Spacer()
                if playing { playingBar } else { bottomBar(photo) }
            }
        }
        .background(Color.black)
        .foregroundStyle(LibraryColors.photoText)
        .tint(LibraryColors.photoText)
        .animation(.easeInOut(duration: 0.2), value: playing)
        .modifier(SetSlideshow(playing: $playing, index: $index, count: set.photos.count, seconds: seconds))
        .fullScreenCover(isPresented: $recomposing) {
            SetRecompose(photos: set.photos, startIndex: index, onDone: apply) { recomposing = false }
        }
        .sheet(isPresented: $grid) { gridSheet }
    }

    private var topBar: some View {
        HStack {
            Button(action: onClose) { Image(systemName: "xmark").font(.system(size: 17, weight: .semibold)).frame(width: 44, height: 44) }
                .accessibilityLabel(Text("Close", tableName: "MomentSet"))
            Spacer()
            VStack(spacing: 1) {
                Text(verbatim: set.place).font(.system(size: 15, weight: .semibold))
                Text("\(index + 1) of \(set.photos.count)", tableName: "MomentSet").font(.system(size: 12)).foregroundStyle(LibraryColors.photoTextMuted)
            }
            Spacer()
            if strip {
                Button { grid = true } label: { Image(systemName: "square.grid.3x3").font(.system(size: 17, weight: .semibold)).frame(width: 44, height: 44) }
                    .accessibilityLabel(Text("All photos", tableName: "MomentSet"))
            } else {
                Color.clear.frame(width: 44, height: 44)
            }
        }
        .padding(.horizontal, 8)
        .background(LibraryColors.labelFade.ignoresSafeArea())
    }

    private func bottomBar(_ photo: SetPhoto) -> some View {
        let isMain = photo.id == set.mainId
        return VStack(spacing: 14) {
            if strip { SetScrubber(photos: set.photos, mainId: set.mainId, index: $index).padding(.horizontal, 16) }
            HStack(alignment: .center) {
                barButton(isMain ? "Main" : "Make main", icon: isMain ? "star.fill" : "star") { set.mainId = photo.id }
                Spacer()
                Button { playing = true } label: {
                    Image(systemName: "play.fill")
                        .font(.system(size: 24))
                        .foregroundStyle(LibraryColors.paperInk)
                        .frame(width: 64, height: 64)
                        .background(LibraryColors.paper, in: Circle())
                }
                .accessibilityLabel(Text("Play", tableName: "MomentSet"))
                Spacer()
                barButton("Recompose", icon: "crop") { recomposing = true }
            }
            .padding(.horizontal, 28)
        }
        .padding(.bottom, 8)
        .padding(.top, 24)
        .background(LibraryColors.photoFade.ignoresSafeArea())
    }

    private func barButton(_ title: LocalizedStringKey, icon: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 20))
                Text(title, tableName: "MomentSet").font(.system(size: 12, weight: .medium))
            }
            .frame(width: 92)
        }
        .buttonStyle(.plain)
    }

    private var playingBar: some View {
        VStack(spacing: 12) {
            // How far through the set: a thin line under the photo.
            GeometryReader { geo in
                Capsule().fill(.white.opacity(0.25))
                    .overlay(alignment: .leading) {
                        Capsule().fill(.white).frame(width: geo.size.width * CGFloat(index + 1) / CGFloat(set.photos.count))
                    }
            }
            .frame(height: 3)
            HStack {
                SetSpeedPicker(seconds: $seconds)
                Spacer()
                Button { playing = false } label: {
                    Image(systemName: "pause.fill")
                        .font(.system(size: 18))
                        .frame(width: 48, height: 48)
                        .background(LibraryColors.pillScrim, in: Circle())
                }
                .accessibilityLabel(Text("Stop", tableName: "MomentSet"))
            }
        }
        .padding(.horizontal, 16)
        .padding(.bottom, 8)
    }

    private var gridSheet: some View {
        ScrollView {
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 4), spacing: 2) {
                ForEach(Array(set.photos.enumerated()), id: \.element.id) { i, photo in
                    SetTile(photo: photo, isMain: photo.id == set.mainId, current: i == index)
                        .onTapGesture {
                            index = i
                            grid = false
                        }
                }
            }
            .padding(.top, 16)
        }
        .background(LibraryColors.background)
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }

    private func apply(_ crops: [String: CGRect]) { set.applyCrops(crops) }
}

extension MomentSetState {
    mutating func applyCrops(_ crops: [String: CGRect]) {
        for i in photos.indices {
            if let crop = crops[photos[i].id] { photos[i].crop = crop.isWhole ? nil : crop }
        }
    }
}

// MARK: - Recompose

/// A new composition for each photo of the set, as the crop screen's Moments crop: the photo whole, dimmed outside
/// the frame; drag moves the frame, pinch sizes it, the shapes set it. The strip goes to the set's other photos
/// without leaving, and **Same frame for all** gives every photo this frame, so the set flips without jumping.
struct SetRecompose: View {
    let photos: [SetPhoto]
    let onDone: ([String: CGRect]) -> Void
    let onClose: () -> Void

    @State private var index: Int
    @State private var frames: [String: CGRect]
    @State private var dragStart: CGRect?
    @State private var pinchStart: CGRect?

    init(photos: [SetPhoto], startIndex: Int, onDone: @escaping ([String: CGRect]) -> Void, onClose: @escaping () -> Void) {
        self.photos = photos
        self.onDone = onDone
        self.onClose = onClose
        _index = State(initialValue: startIndex)
        _frames = State(initialValue: Dictionary(uniqueKeysWithValues: photos.map { ($0.id, $0.crop ?? .whole) }))
    }

    var body: some View {
        let photo = photos[index]
        let frame = frames[photo.id] ?? .whole
        VStack(spacing: 0) {
            HStack {
                Button(action: onClose) { Image(systemName: "xmark").font(.system(size: 17, weight: .semibold)).frame(width: 44, height: 44) }
                    .accessibilityLabel(Text("Close", tableName: "MomentSet"))
                Spacer()
                Text("Recompose", tableName: "MomentSet").font(.headline)
                Spacer()
                Button {
                    onDone(frames)
                    onClose()
                } label: {
                    Text("Done", tableName: "MomentSet").font(.system(size: 16, weight: .bold)).frame(height: 44).padding(.horizontal, 8)
                }
            }
            .padding(.horizontal, 8)

            GeometryReader { geo in
                let fit = setFitted(aspect: photo.aspect, in: geo.size)
                let box = CGRect(
                    x: fit.minX + frame.minX * fit.width, y: fit.minY + frame.minY * fit.height,
                    width: frame.width * fit.width, height: frame.height * fit.height
                )
                ZStack(alignment: .topLeading) {
                    SetPicture(photo: photo, cropped: false)
                        .frame(width: fit.width, height: fit.height)
                        .position(x: fit.midX, y: fit.midY)
                    Path { p in
                        p.addRect(fit)
                        p.addRect(box)
                    }
                    .fill(Color.black.opacity(0.6), style: FillStyle(eoFill: true))
                    Path { p in
                        for k in 1...2 {
                            let x = box.minX + box.width * CGFloat(k) / 3, y = box.minY + box.height * CGFloat(k) / 3
                            p.move(to: CGPoint(x: x, y: box.minY)); p.addLine(to: CGPoint(x: x, y: box.maxY))
                            p.move(to: CGPoint(x: box.minX, y: y)); p.addLine(to: CGPoint(x: box.maxX, y: y))
                        }
                    }
                    .stroke(.white.opacity(0.45), lineWidth: 0.5)
                    Path { p in p.addRect(box) }.stroke(.white, lineWidth: 2)
                }
                .frame(width: geo.size.width, height: geo.size.height)
                .contentShape(Rectangle())
                .gesture(
                    DragGesture().onChanged { value in
                        let start = dragStart ?? frame
                        if dragStart == nil { dragStart = frame }
                        var moved = start
                        moved.origin.x += value.translation.width / fit.width
                        moved.origin.y += value.translation.height / fit.height
                        frames[photo.id] = moved.keptInside()
                    }
                    .onEnded { _ in dragStart = nil }
                )
                .simultaneousGesture(
                    MagnificationGesture().onChanged { scale in
                        let start = pinchStart ?? frame
                        if pinchStart == nil { pinchStart = frame }
                        let most = min(1 / start.width, 1 / start.height)
                        let k = min(max(scale, 0.2 / start.width), most)
                        let size = CGSize(width: start.width * k, height: start.height * k)
                        frames[photo.id] = CGRect(x: start.midX - size.width / 2, y: start.midY - size.height / 2, width: size.width, height: size.height).keptInside()
                    }
                    .onEnded { _ in pinchStart = nil }
                )
            }
            .padding(16)

            Text("The photo itself isn’t changed.", tableName: "MomentSet")
                .font(.footnote)
                .foregroundStyle(LibraryColors.photoTextMuted)
                .padding(.bottom, 10)

            HStack(spacing: 8) {
                shape("Original", nil, photo: photo)
                shape("Square", 1, photo: photo)
                shape("4:5", 4.0 / 5.0, photo: photo)
                shape("Wide", 16.0 / 9.0, photo: photo)
            }
            .padding(.bottom, 12)

            ScrollView(.horizontal) {
                HStack(spacing: 4) {
                    ForEach(Array(photos.enumerated()), id: \.element.id) { i, p in
                        SetPicture(photo: p, cropped: false)
                            .aspectRatio(p.aspect, contentMode: .fill)
                            .frame(width: 40, height: 52)
                            .clipped()
                            .overlay(RoundedRectangle(cornerRadius: 3).strokeBorder(.white, lineWidth: i == index ? 2 : 0))
                            .overlay(alignment: .bottom) {
                                if !(frames[p.id] ?? .whole).isWhole { Circle().fill(.white).frame(width: 5, height: 5).padding(.bottom, 3) }
                            }
                            .clipShape(RoundedRectangle(cornerRadius: 3))
                            .onTapGesture { index = i }
                    }
                }
                .padding(.horizontal, 16)
            }
            .scrollIndicators(.hidden)
            .frame(height: 52)

            HStack {
                Button { frames[photo.id] = .whole } label: { Text("Reset", tableName: "MomentSet") }
                Spacer()
                Button {
                    for p in photos where p.aspect == photo.aspect { frames[p.id] = frame }
                    setHaptic()
                } label: {
                    Label { Text("Same frame for all", tableName: "MomentSet") } icon: { Image(systemName: "square.on.square") }
                }
            }
            .font(.system(size: 15, weight: .semibold))
            .padding(.horizontal, 20)
            .padding(.vertical, 14)
        }
        .background(Color.black)
        .foregroundStyle(LibraryColors.photoText)
        .tint(LibraryColors.photoText)
    }

    /// A shape chip: the largest frame of that shape around the frame's middle (Original: the whole photo).
    private func shape(_ title: LocalizedStringKey, _ aspect: CGFloat?, photo: SetPhoto) -> some View {
        Button {
            guard let aspect else {
                frames[photo.id] = .whole
                return
            }
            let current = frames[photo.id] ?? .whole
            let ratio = aspect / photo.aspect
            let size = ratio <= 1 ? CGSize(width: ratio, height: 1) : CGSize(width: 1, height: 1 / ratio)
            frames[photo.id] = CGRect(x: current.midX - size.width / 2, y: current.midY - size.height / 2, width: size.width, height: size.height).keptInside()
        } label: {
            Text(title, tableName: "MomentSet")
                .font(.system(size: 13, weight: .semibold))
                .padding(.horizontal, 12)
                .frame(height: 30)
                .background(.white.opacity(0.14), in: Capsule())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Sample data

/// The lab's set: 36 shots of one run along the beach (more than anyone swipes through), a few already recomposed,
/// and two park photos from the same afternoon for the feed around it.
enum MomentSetLab {
    static func beach() -> MomentSetState {
        let count = 36
        var photos = (0..<count).map { SetPhoto(id: "beach-\($0)", index: $0, of: count, aspect: 3.0 / 4.0) }
        photos[14].crop = CGRect(x: 0.2, y: 0.38, width: 0.6, height: 0.48)
        photos[22].crop = CGRect(x: 0.25, y: 0.3, width: 0.75, height: 0.75 * 3 / 4 * 1.0)
        return MomentSetState(place: "Herzliya beach", when: "Sat, Nov 7 · 17:02 – 17:09", photos: photos, mainId: "beach-14")
    }

    static let parkBefore = SetPhoto(id: "park-1", index: 0, of: 1, aspect: 4.0 / 3.0, scene: 1)
    static let parkAfter = SetPhoto(id: "park-2", index: 0, of: 1, aspect: 3.0 / 4.0, scene: 1)
}

#endif
