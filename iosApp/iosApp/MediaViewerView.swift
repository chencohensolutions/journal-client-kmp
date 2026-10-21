import AVKit
import SwiftUI
import SharedLogic

/// What the viewer can ask its parent to do.
struct ViewerActions {
    var close: () -> Void
    var openGroup: (_ groupId: String) -> Void
    var setCover: (_ itemId: String) -> Void
    var removeFromGroup: (_ itemId: String) -> Void
    var toggleStory: (_ storyId: String, _ itemId: String, _ add: Bool) -> Void
    var createStory: (_ title: String, _ itemId: String) -> Void
    var setCaption: (_ itemId: String, _ text: String) -> Void
    var toggleTag: (_ itemId: String, _ tag: String, _ add: Bool) -> Void
    /// The photo now shown, on opening and on each swipe: the one that zooms back into its tile on closing.
    var showing: (_ itemId: String) -> Void
}

/// One photo or video at a time, swiping through `items`. `openedFrom` is the story the viewer was opened from, if
/// any, and `group` the group within it. See docs/screens/viewer.md.
struct MediaViewerView: View {
    let items: [MediaItem]
    let stories: StoriesState
    let openedFrom: StoryView?
    let group: GroupView?
    let actions: ViewerActions

    @State private var currentId: String
    @State private var showInfo = false
    @State private var choosingStory = false
    @State private var choosingTag = false
    @State private var editingCaption = false

    init(items: [MediaItem], startId: String, stories: StoriesState, openedFrom: StoryView?, group: GroupView?, actions: ViewerActions) {
        self.items = items
        self.stories = stories
        self.openedFrom = openedFrom
        self.group = group
        self.actions = actions
        _currentId = State(initialValue: startId)
    }

    private var onClose: () -> Void { actions.close }

    var body: some View {
        let index = items.firstIndex { $0.id == currentId } ?? 0
        ZStack {
            Color.black.ignoresSafeArea()
            if items.isEmpty {
                // The item was deleted from the device while open.
                Color.clear.onAppear(perform: onClose)
            } else {
                TabView(selection: $currentId) {
                    ForEach(items, id: \.id) { item in
                        Group {
                            if item.isVideo {
                                VideoPage(item: item, isCurrent: item.id == currentId)
                            } else {
                                PhotoPage(item: item)
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
            HStack {
                Button(action: onClose) {
                    Image(systemName: "chevron.backward").font(.system(size: 18, weight: .semibold)).frame(width: 44, height: 44)
                }
                .accessibilityLabel("Back")
                if let item = items[safe: index] {
                    Text("\(index + 1) of \(items.count) · \(item.day.label)")
                        .font(.subheadline)
                        .foregroundStyle(Color(white: 0.75))
                }
                Spacer()
                // In a story or a group, a group inside it is one stop, shown as its cover; this opens the rest of it.
                if let item = items[safe: index],
                   let coverOf = group.map({ $0.coverStop(itemId: item.id) }) ?? openedFrom?.coverStop(itemId: item.id) {
                    Button { actions.openGroup(coverOf.id) } label: {
                        Label("\(Int(coverOf.itemCount)) · Open", systemImage: "square.on.square")
                            .font(.subheadline.weight(.semibold))
                    }
                }
                Button { showInfo.toggle() } label: {
                    Image(systemName: "info.circle")
                        .font(.system(size: 20))
                        .frame(width: 44, height: 44)
                        .background(showInfo ? Color.white.opacity(0.16) : .clear, in: Circle())
                }
                .accessibilityLabel("Details")
            }
            .foregroundStyle(.white)
            .padding(.horizontal, 4)
            .background(LibraryColors.topFade.ignoresSafeArea())
        }
        .overlay(alignment: .bottom) {
            if showInfo, let item = items[safe: index] {
                let memberOf = stories.storiesContaining(itemId: item.id)
                InfoPanel(
                    item: item,
                    note: stories.note(itemId: item.id),
                    onEditCaption: { editingCaption = true },
                    onAddTag: { choosingTag = true },
                    onRemoveTag: { actions.toggleTag(item.id, $0, false) },
                    memberOf: memberOf,
                    removable: openedFrom.flatMap { from in memberOf.contains { $0.id == from.id } ? from : nil },
                    onAddToStory: { choosingStory = true },
                    onRemove: { actions.toggleStory($0.id, item.id, false) },
                    group: group,
                    onSetCover: { actions.setCover(item.id) },
                    onRemoveFromGroup: { actions.removeFromGroup(item.id) }
                )
            }
        }
        .onChange(of: currentId, initial: true) { actions.showing(currentId) }
        .sheet(isPresented: $choosingStory) {
            if let item = items[safe: index] {
                AddToStorySheet(
                    itemIds: [item.id],
                    stories: stories,
                    onToggle: { storyId, add in actions.toggleStory(storyId, item.id, add) },
                    onCreate: { title in
                        choosingStory = false
                        actions.createStory(title, item.id)
                    }
                )
            }
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
                WritingEditor(title: String(localized: "Note"), initial: stories.note(itemId: item.id)?.caption ?? "") {
                    actions.setCaption(item.id, $0)
                }
            }
        }
        .statusBarHidden(!showInfo)
    }
}

private struct PhotoPage: View {
    let item: MediaItem
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let image {
                    Image(uiImage: image).resizable().scaledToFit()
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .task(id: item.id) {
                // A quick low-quality image first, then the sharp one.
                image = await PhotoLibraryImages.shared.image(id: item.id, pointSize: CGSize(width: 200, height: 200))
                if let full = await PhotoLibraryImages.shared.image(id: item.id, pointSize: geometry.size) {
                    image = full
                }
            }
        }
    }
}

/// Shows the poster frame until Play is tapped; stops when swiped away.
private struct VideoPage: View {
    let item: MediaItem
    let isCurrent: Bool

    @State private var player: AVPlayer?
    @State private var poster: UIImage?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                if let player {
                    VideoPlayer(player: player)
                } else {
                    if let poster {
                        Image(uiImage: poster).resizable().scaledToFit()
                    }
                    Button {
                        Task {
                            if let playerItem = await PhotoLibraryImages.shared.playerItem(id: item.id) {
                                let player = AVPlayer(playerItem: playerItem)
                                self.player = player
                                player.play()
                            }
                        }
                    } label: {
                        Image(systemName: "play.fill")
                            .font(.system(size: 30))
                            .foregroundStyle(.white)
                            .frame(width: 72, height: 72)
                            .background(LibraryColors.badgeScrim, in: Circle())
                    }
                    .accessibilityLabel("Play video")
                }
            }
            .frame(width: geometry.size.width, height: geometry.size.height)
            .task(id: item.id) {
                poster = await PhotoLibraryImages.shared.image(id: item.id, pointSize: geometry.size)
            }
        }
        .onChange(of: isCurrent) { _, current in
            if !current {
                player?.pause()
                player = nil
            }
        }
    }
}

/// Date, backup status, stories and file details of the current item.
private struct InfoPanel: View {
    let item: MediaItem
    let note: PhotoNote?
    let onEditCaption: () -> Void
    let onAddTag: () -> Void
    let onRemoveTag: (String) -> Void
    let memberOf: [StoryView]
    let removable: StoryView?
    let onAddToStory: () -> Void
    let onRemove: (StoryView) -> Void
    let group: GroupView?
    let onSetCover: () -> Void
    let onRemoveFromGroup: () -> Void

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
                Button(action: onEditCaption) {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Note").font(.caption).foregroundStyle(LibraryColors.onInverseMuted)
                        Text(note?.caption.isBlank == false ? note!.caption : String(localized: "Write about this photo…"))
                            .font(.system(size: 16, design: .serif))
                            .foregroundStyle(note?.caption.isBlank == false ? LibraryColors.onInverse : LibraryColors.onInverseMuted)
                            .multilineTextAlignment(.leading)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(14)
                    .background(.white, in: RoundedRectangle(cornerRadius: 14))
                }
                .accessibilityHint("Edit")
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
                HStack(spacing: 10) {
                    BackupBadge(status: item.backup, size: 22)
                    Text(item.backup.label).font(.subheadline.weight(.semibold))
                    Spacer()
                    if let uploading = item.backup as? BackupStatusUploading {
                        Text("\(Int(uploading.progress * 100))%")
                            .font(.subheadline.weight(.semibold))
                            .foregroundStyle(LibraryColors.accent)
                    }
                }
                .padding(14)
                .background(.white, in: RoundedRectangle(cornerRadius: 16))
                if let group {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Group").font(.caption).foregroundStyle(LibraryColors.onInverseMuted)
                        Text(group.name).font(.subheadline.weight(.medium))
                        HStack(spacing: 16) {
                            if group.cover.id == item.id {
                                Text("This is the cover").foregroundStyle(LibraryColors.onInverseMuted)
                            } else {
                                Button("Set as cover", action: onSetCover).foregroundStyle(LibraryColors.accent)
                            }
                            Button(action: onRemoveFromGroup) { Label("Remove from group", systemImage: "minus") }
                                .foregroundStyle(LibraryColors.error)
                        }
                        .font(.subheadline.weight(.semibold))
                        .padding(.top, 4)
                    }
                } else {
                    VStack(alignment: .leading, spacing: 4) {
                        Text("Stories").font(.caption).foregroundStyle(LibraryColors.onInverseMuted)
                        Text(memberOf.isEmpty ? String(localized: "Not in any story") : memberOf.map(\.name).joined(separator: ", "))
                            .font(.subheadline.weight(.medium))
                        HStack(spacing: 16) {
                            Button(action: onAddToStory) { Label("Add to story", systemImage: "plus") }
                                .foregroundStyle(LibraryColors.accent)
                            if let removable {
                                Button { onRemove(removable) } label: { Label("Remove from \(removable.name)", systemImage: "minus") }
                                    .foregroundStyle(LibraryColors.error)
                            }
                        }
                        .font(.subheadline.weight(.semibold))
                        .padding(.top, 4)
                    }
                }
                LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)], spacing: 12) {
                    if let fileName = item.fileName { Detail(label: "File", value: fileName) }
                    if let size = item.sizeBytes { Detail(label: "Size", value: ByteCountFormatter.string(fromByteCount: size.int64Value, countStyle: .file)) }
                    if item.width > 0 && item.height > 0 { Detail(label: "Dimensions", value: "\(item.width) × \(item.height)") }
                    if let duration = item.duration { Detail(label: "Length", value: formatDuration(duration)) }
                    if let mimeType = item.mimeType { Detail(label: "Type", value: typeName(mimeType)) }
                    if item.isOnDevice { Detail(label: "From", value: item.source.label) }
                }
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
private func typeName(_ mimeType: String) -> String {
    guard let kind = MediaFactsKt.mediaKind(mimeType: mimeType) else { return mimeType }
    if kind.video { return String(localized: "\(kind.name) video") }
    return kind.raw ? "\(kind.name) (RAW)" : kind.name
}

private extension MediaSource {
    var label: String {
        switch self {
        case .whatsapp: return String(localized: "WhatsApp")
        case .screenshots: return String(localized: "Screenshots")
        case .other: return String(localized: "Other")
        default: return String(localized: "Camera")
        }
    }
}

private struct Detail: View {
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
