import SwiftUI
import SharedLogic

/// A photo or video cropped to fill its tile (around `crop`), with its video length and backup badge. Size it from
/// outside.
struct MediaTileView: View {
    let item: MediaItem
    var badgeSize: CGFloat = 22
    var crop: TileCrop = .none

    @State private var thumbnail: UIImage?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                LibraryColors.raised
                if let thumbnail {
                    CroppedImage(image: thumbnail, size: geometry.size, crop: crop)
                }
            }
            .overlay(alignment: .topTrailing) {
                if item.isVideo {
                    DurationChip(duration: item.duration).padding(5)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                BackupBadge(status: item.backup, size: badgeSize).padding(5)
            }
            .task(id: item.id) {
                // Zoomed in, the tile shows a part of a larger image.
                let size = CGSize(width: geometry.size.width * crop.zoom, height: geometry.size.height * crop.zoom)
                thumbnail = await PhotoLibraryImages.shared.image(id: item.id, pointSize: size)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement()
        .accessibilityLabel(item.accessibilityDescription)
        .accessibilityAddTraits(.isButton)
    }
}

/// `image` scaled to cover `size`, anchored at the crop's focus point and zoomed around it, clipped to `size`.
private struct CroppedImage: View {
    let image: UIImage
    let size: CGSize
    let crop: TileCrop

    var body: some View {
        let width = max(image.size.width, 1)
        let height = max(image.size.height, 1)
        let scale = max(size.width / width, size.height / height)
        let filled = CGSize(width: width * scale, height: height * scale)
        Image(uiImage: image)
            .resizable()
            .frame(width: filled.width, height: filled.height)
            .offset(x: (size.width - filled.width) * crop.x, y: (size.height - filled.height) * crop.y)
            .frame(width: size.width, height: size.height, alignment: .topLeading)
            .scaleEffect(crop.zoom, anchor: UnitPoint(x: crop.x, y: crop.y))
            .clipped()
            // The focus point is in the photo's own coordinates: not mirrored in Hebrew.
            .environment(\.layoutDirection, .leftToRight)
    }
}

/// Marks a video: a play icon, plus its length when known.
struct DurationChip: View {
    let duration: TimeInterval?

    var body: some View {
        HStack(spacing: 2) {
            Image(systemName: "play.fill").font(.system(size: 8))
            if let duration {
                Text(formatDuration(duration)).font(.system(size: 11, weight: .semibold))
            }
        }
        .foregroundStyle(.white)
        .padding(.leading, 5)
        .padding(.trailing, duration == nil ? 5 : 6)
        .padding(.vertical, 3)
        .background(LibraryColors.badgeScrim, in: Capsule())
    }
}

/// The corner badge; see docs/screens/README.md for what each state means.
struct BackupBadge: View {
    let status: BackupStatus
    let size: CGFloat

    var body: some View {
        ZStack {
            Circle().fill(background)
            symbol
        }
        .frame(width: size, height: size)
    }

    private var background: Color {
        switch status {
        case is BackupStatusUploading: return LibraryColors.accent
        case is BackupStatusBackedUp: return LibraryColors.accent.opacity(0.85)
        case is BackupStatusFailed: return LibraryColors.error
        default: return LibraryColors.badgeScrim
        }
    }

    @ViewBuilder private var symbol: some View {
        let inner = size * 0.6
        switch status {
        case is BackupStatusQueued:
            Circle()
                .stroke(.white, style: StrokeStyle(lineWidth: 2, dash: [2.5, 2.5]))
                .frame(width: inner, height: inner)
        case let uploading as BackupStatusUploading:
            ZStack {
                Circle().stroke(.white.opacity(0.35), lineWidth: 2.5)
                Circle()
                    .trim(from: 0, to: CGFloat(uploading.progress))
                    .stroke(.white, style: StrokeStyle(lineWidth: 2.5, lineCap: .round))
                    .rotationEffect(.degrees(-90))
            }
            .frame(width: inner, height: inner)
        case is BackupStatusBackedUp:
            Image(systemName: "checkmark").font(.system(size: inner * 0.75, weight: .bold)).foregroundStyle(.white)
        case is BackupStatusFailed:
            Text("!").font(.system(size: inner, weight: .bold)).foregroundStyle(.white)
        default:
            Image(systemName: "icloud.slash").font(.system(size: inner * 0.7, weight: .semibold)).foregroundStyle(.white)
        }
    }
}
