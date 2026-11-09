#if DEBUG

import SwiftUI

/// Option c, "Flip in the feed": the set stays one photo in Moments, at its main photo, with "36" in the corner and a
/// thin line showing where in the set the photo is. Hold the photo and slide sideways to flip through the set right
/// there (a tick per photo; letting go keeps it); the small Play plays it in place. A tap opens the viewer with the
/// scrubber (option b's).
struct MomentSetScreenC: View {
    @State var set: MomentSetState
    @State private var shown: Int
    @State private var scrubFrom: Int?
    @State private var playing: Bool
    @State private var viewer = false
    @Environment(\.layoutDirection) private var direction

    init(set: MomentSetState, playing: Bool = false, shown: Int? = nil) {
        _set = State(initialValue: set)
        _shown = State(initialValue: shown ?? set.mainIndex)
        _playing = State(initialValue: playing)
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                divider
                label("Hod HaSharon park", "15:40")
                SetPicture(photo: MomentSetLab.parkBefore)
                    .aspectRatio(MomentSetLab.parkBefore.aspect, contentMode: .fit)
                label("Herzliya beach", "17:02 – 17:09")
                setCard
                Text("Hold and slide to flip", tableName: "MomentSet")
                    .font(.system(size: 12))
                    .foregroundStyle(LibraryColors.muted)
                    .padding(.horizontal, 16)
                    .padding(.top, 6)
                label(nil, "17:30")
                SetPicture(photo: MomentSetLab.parkAfter)
                    .aspectRatio(MomentSetLab.parkAfter.aspect, contentMode: .fit)
            }
            .padding(.bottom, 32)
        }
        .background(LibraryColors.background)
        .foregroundStyle(LibraryColors.onBackground)
        .modifier(SetSlideshow(playing: $playing, index: $shown, count: set.photos.count, seconds: 0.5))
        .fullScreenCover(isPresented: $viewer) {
            SetViewer(set: $set, index: shown, strip: true) { viewer = false }
        }
    }

    private var setCard: some View {
        let main = set.photos[set.mainIndex]
        let photo = set.photos[shown]
        return ZStack {
            Color.black
            SetPicture(photo: photo)
                .aspectRatio(photo.shownAspect, contentMode: .fit)
                .id(photo.id)
                .transition(.opacity)
        }
        .aspectRatio(main.shownAspect, contentMode: .fit)
        .clipped()
        .overlay(alignment: .topTrailing) { countBadge }
        .overlay(alignment: .top) {
            if scrubFrom != nil {
                Text("\(shown + 1) of \(set.photos.count)", tableName: "MomentSet")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(LibraryColors.photoText)
                    .padding(.horizontal, 10)
                    .frame(height: 26)
                    .background(LibraryColors.pillScrim, in: Capsule())
                    .padding(.top, 12)
            }
        }
        .overlay(alignment: .bottomLeading) { playButton }
        .overlay(alignment: .bottom) { whereInSet }
        .contentShape(Rectangle())
        .onTapGesture {
            playing = false
            viewer = true
        }
        .gesture(
            LongPressGesture(minimumDuration: 0.2)
                .sequenced(before: DragGesture(minimumDistance: 0))
                .onChanged { value in
                    guard case .second(true, let drag) = value else { return }
                    if scrubFrom == nil {
                        scrubFrom = shown
                        playing = false
                        setHaptic()
                    }
                    guard let drag, let from = scrubFrom else { return }
                    let dx = direction == .leftToRight ? drag.translation.width : -drag.translation.width
                    let i = min(max(from + Int(dx / 10), 0), set.photos.count - 1)
                    if i != shown {
                        shown = i
                        setHaptic()
                    }
                }
                .onEnded { _ in scrubFrom = nil }
        )
        .animation(.easeOut(duration: 0.12), value: shown)
    }

    private var countBadge: some View {
        HStack(spacing: 5) {
            Image(systemName: "square.stack").font(.system(size: 11, weight: .semibold))
            Text(verbatim: "\(set.photos.count)").font(.system(size: 12, weight: .semibold))
        }
        .foregroundStyle(LibraryColors.photoText)
        .padding(.horizontal, 9)
        .padding(.vertical, 5)
        .background(LibraryColors.badgeScrim, in: Capsule())
        .padding(10)
    }

    private var playButton: some View {
        Button { playing.toggle() } label: {
            Image(systemName: playing ? "pause.fill" : "play.fill")
                .font(.system(size: 14))
                .foregroundStyle(LibraryColors.photoText)
                .frame(width: 36, height: 36)
                .background(LibraryColors.badgeScrim, in: Circle())
        }
        .accessibilityLabel(Text(playing ? "Stop" : "Play", tableName: "MomentSet"))
        .padding(10)
    }

    /// A thin line along the bottom with a mark at the photo shown, and a dot where the main one is.
    private var whereInSet: some View {
        GeometryReader { geo in
            let w = geo.size.width - 120
            let step = w / CGFloat(max(set.photos.count - 1, 1))
            ZStack(alignment: .leading) {
                Capsule().fill(.white.opacity(0.35)).frame(width: w, height: 2)
                Circle().fill(.white.opacity(0.8)).frame(width: 5, height: 5).offset(x: CGFloat(set.mainIndex) * step - 2.5)
                Capsule().fill(.white).frame(width: 3, height: 12).offset(x: CGFloat(shown) * step - 1.5)
            }
            .frame(width: w, height: 12)
            .position(x: geo.size.width / 2, y: geo.size.height - 28)
        }
        .allowsHitTesting(false)
        .environment(\.layoutDirection, .leftToRight)
    }

    private var divider: some View {
        VStack(spacing: 6) {
            Text(verbatim: "Sat, Nov 7")
                .font(.system(size: 13, weight: .semibold))
                .padding(.horizontal, 12)
                .frame(height: 26)
                .background(LibraryColors.raised, in: Capsule())
            Text(verbatim: "Hod HaSharon → Herzliya · 38 photos").font(.system(size: 12)).foregroundStyle(LibraryColors.muted)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 40)
        .padding(.bottom, 4)
    }

    private func label(_ place: String?, _ time: String) -> some View {
        HStack(spacing: 6) {
            if let place { Text(verbatim: "📍 \(place)").font(.system(size: 14, weight: .bold)) }
            Text(verbatim: time).font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
        }
        .padding(.horizontal, 16)
        .padding(.top, 18)
        .padding(.bottom, 8)
    }
}

#endif
