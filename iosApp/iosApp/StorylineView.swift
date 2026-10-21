import SwiftUI
import SharedLogic

/// The Storyline tab's photos, for a lazy stack of the home screen's: each photo or video the user wrote about, newest
/// first, like a single photo of Moments (the title above, the photo at its Moments crop, the paragraph under it),
/// with the day above the first of each day. Never a set: only the photo written on. See docs/screens/journal-home.md.
struct StorylineView: View {
    let items: [MediaItem]
    let note: (String) -> PhotoNote?
    let width: CGFloat
    /// Opens the viewer on a photo, swiping through the storyline.
    let openItem: (MediaItem) -> Void
    /// The long-press menu's Write, Crop, Tag and "Remove from storyline".
    let actions: FeedActions
    @ObservedObject var playback: FeedPlayback

    var body: some View {
        ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
            let newDay = index == 0 || items[index - 1].day != item.day
            VStack(spacing: 0) {
                if newDay {
                    Text(item.day.label)
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(LibraryColors.muted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16)
                        .padding(.top, index == 0 ? 4 : 22)
                        .padding(.bottom, 8)
                }
                entry(item)
            }
        }
    }

    private func entry(_ item: MediaItem) -> some View {
        // Videos aren't cropped (yet).
        let crop = item.isVideo ? nil : note(item.id)?.crops.moments
        let ratio = crop.map { CGFloat($0.ratioOn(photoRatio: Float(item.ratio))) } ?? item.ratio
        return VStack(spacing: 0) {
            FeedNoteTitle(title: note(item.id)?.title)
            FeedPhoto(item: item, size: CGSize(width: width, height: width / ratio), crop: crop, playback: playback)
                .zoomSource(ZoomID.item(item.id))
                .contentShape(Rectangle())
                .onTapGesture { openItem(item) }
                .contextMenu { menu(for: item) }
            FeedNoteText(caption: note(item.id)?.caption)
        }
        .padding(.bottom, 18)
    }

    @ViewBuilder
    private func menu(for item: MediaItem) -> some View {
        Section("\(item.day.label) · \(item.takenAt.formatted(date: .omitted, time: .shortened))") {
            Button { actions.write(item) } label: { Label("Edit what you wrote", systemImage: "pencil") }
            if !item.isVideo {
                Button { actions.crop(item) } label: { Label("Crop", systemImage: "crop") }
            }
            Button { actions.tag(item) } label: { Label("Tag", systemImage: "tag") }
            Button { actions.setInStoryline(item, false) } label: { Label("Remove from storyline", systemImage: "text.badge.minus") }
        }
    }
}
