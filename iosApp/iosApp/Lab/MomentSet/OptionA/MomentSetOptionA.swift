#if DEBUG

import SwiftUI

/// Option a, "Set page": tapping a set in Moments opens its page. The main photo large at the top with Play on it,
/// then every photo of the set in a grid of squares (the main one starred, recomposed ones marked). A tile opens the
/// viewer, where swipes cross-fade; holding a tile gives Make main, Recompose and Take out of this set.
struct MomentSetScreenA: View {
    @State var set: MomentSetState
    @State private var viewer: Int?
    @State private var viewerPlaying = false
    @State private var recompose: Int?

    init(set: MomentSetState, viewerAt: Int? = nil, playing: Bool = false, recomposeAt: Int? = nil) {
        _set = State(initialValue: set)
        _viewer = State(initialValue: viewerAt)
        _viewerPlaying = State(initialValue: playing)
        _recompose = State(initialValue: recomposeAt)
    }

    var body: some View {
        let main = set.photos[set.mainIndex]
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header
                Button { open(set.mainIndex) } label: {
                    SetPicture(photo: main)
                        .aspectRatio(main.shownAspect, contentMode: .fit)
                        .frame(maxWidth: .infinity)
                        .overlay(alignment: .bottomLeading) { mainPill }
                        .overlay(alignment: .bottomTrailing) { playButton }
                }
                .buttonStyle(.plain)

                HStack {
                    Text("\(set.photos.count) photos", tableName: "MomentSet").font(.system(size: 15, weight: .semibold))
                    Spacer()
                    Text("Hold a photo for more", tableName: "MomentSet").font(.system(size: 12)).foregroundStyle(LibraryColors.muted)
                }
                .padding(.horizontal, 16)
                .padding(.top, 18)
                .padding(.bottom, 8)

                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2) {
                    ForEach(Array(set.photos.enumerated()), id: \.element.id) { i, photo in
                        SetTile(photo: photo, isMain: photo.id == set.mainId)
                            .contentShape(Rectangle())
                            .onTapGesture { open(i) }
                            .contextMenu { menu(photo, at: i) }
                    }
                }
            }
            .padding(.bottom, 32)
        }
        .background(LibraryColors.background)
        .foregroundStyle(LibraryColors.onBackground)
        .fullScreenCover(isPresented: Binding(get: { viewer != nil }, set: { if !$0 { viewer = nil } })) {
            SetViewer(set: $set, index: viewer ?? 0, strip: false, playing: viewerPlaying) { viewer = nil }
        }
        .fullScreenCover(isPresented: Binding(get: { recompose != nil }, set: { if !$0 { recompose = nil } })) {
            SetRecompose(photos: set.photos, startIndex: recompose ?? 0, onDone: { set.applyCrops($0) }) { recompose = nil }
        }
    }

    private var header: some View {
        VStack(alignment: .leading, spacing: 4) {
            Image(systemName: "chevron.backward")
                .font(.system(size: 18, weight: .semibold))
                .frame(width: 44, height: 44, alignment: .leading)
            Text(verbatim: set.place).font(.system(size: 26, weight: .bold))
            Text(verbatim: set.when).font(.system(size: 14)).foregroundStyle(LibraryColors.muted)
        }
        .padding(.horizontal, 16)
        .padding(.top, 36)
        .padding(.bottom, 14)
    }

    private var mainPill: some View {
        HStack(spacing: 4) {
            Image(systemName: "star.fill").font(.system(size: 11, weight: .bold))
            Text("Main", tableName: "MomentSet").font(.system(size: 12, weight: .semibold))
        }
        .foregroundStyle(LibraryColors.paperInk)
        .padding(.horizontal, 10)
        .frame(height: 26)
        .background(LibraryColors.paper, in: Capsule())
        .padding(12)
    }

    private var playButton: some View {
        Button {
            viewerPlaying = true
            viewer = set.mainIndex
        } label: {
            Image(systemName: "play.fill")
                .font(.system(size: 20))
                .foregroundStyle(LibraryColors.paperInk)
                .frame(width: 52, height: 52)
                .background(LibraryColors.paper, in: Circle())
                .shadow(color: .black.opacity(0.3), radius: 6)
        }
        .accessibilityLabel(Text("Play", tableName: "MomentSet"))
        .padding(12)
    }

    @ViewBuilder
    private func menu(_ photo: SetPhoto, at i: Int) -> some View {
        Button { set.mainId = photo.id } label: { Label { Text("Make main", tableName: "MomentSet") } icon: { Image(systemName: "star") } }
        Button { recompose = i } label: { Label { Text("Recompose", tableName: "MomentSet") } icon: { Image(systemName: "crop") } }
        Button(role: .destructive) {
            if photo.id != set.mainId { set.photos.remove(at: i) }
        } label: {
            Label { Text("Take out of this set", tableName: "MomentSet") } icon: { Image(systemName: "minus.circle") }
        }
    }

    private func open(_ i: Int) {
        viewerPlaying = false
        viewer = i
    }
}

#endif
