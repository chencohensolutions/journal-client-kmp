import SwiftUI
import SharedLogic

/// A photo or video cropped to fill its tile (around `crop`), with its video length and backup badge. Size it from
/// outside.
struct MediaTileView: View {
    let item: MediaItem
    var badgeSize: CGFloat = 22
    var crop: TileCrop = .none
    @Environment(\.showOnPhoneBadge) private var showOnPhoneBadge

    @State private var thumbnail: UIImage?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                LibraryColors.raised
                if let thumbnail {
                    TileImage(image: thumbnail, size: geometry.size, crop: crop)
                }
            }
            .overlay(alignment: .topTrailing) {
                if item.isVideo {
                    DurationChip(duration: item.duration).padding(5)
                }
            }
            .overlay(alignment: .bottomTrailing) {
                BackupBadge(status: item.backup, size: badgeSize, originalHere: item.hasOriginalHere, showOnPhone: showOnPhoneBadge)
                    .padding(5)
            }
            .task(id: item.id) {
                // Zoomed in, the tile shows a part of a larger image.
                let size = CGSize(width: geometry.size.width * crop.zoom, height: geometry.size.height * crop.zoom)
                // A photo only on the server shows its quick image first, while the sharp one comes.
                if !item.isOnDevice, thumbnail == nil, ServerImages.shared.inMemory(item) == nil {
                    thumbnail = await ServerImages.shared.first(item, windowPixels: Int(windowPixels))
                    // The sharp one once the list stops here: a tile scrolled past cancels this.
                    if thumbnail != nil {
                        try? await Task.sleep(for: .milliseconds(300))
                        if Task.isCancelled { return }
                    }
                }
                if let sharp = await PhotoLibraryImages.shared.image(item: item, pointSize: size) { thumbnail = sharp }
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement()
        .accessibilityLabel(item.accessibilityDescription)
        .accessibilityAddTraits(.isButton)
    }
}

/// The window's width in pixels: quick images are a part of it, the same on every screen.
var windowPixels: CGFloat {
    let scene = UIApplication.shared.connectedScenes.first as? UIWindowScene
    let screen = scene?.screen
    return (screen?.bounds.width ?? 400) * (screen?.scale ?? 3)
}

/// `image` scaled to cover `size`, anchored at the crop's focus point and zoomed around it, clipped to `size`.
struct TileImage: View {
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

extension EnvironmentValues {
    /// Settings → Badges, "Show the phone badge", for the tiles and Moments; set by the library root.
    @Entry var showOnPhoneBadge = false
}

/// The corner badge; see docs/screens/README.md for what each state means. None when the original is only on the
/// server (`originalHere` false), or backed up while the phone badge is off (`showOnPhone` false).
struct BackupBadge: View {
    let status: BackupStatus
    let size: CGFloat
    /// False for a photo only on the server: backed up, it has no badge.
    var originalHere = true
    /// False to leave out the phone a backed-up photo on this phone would have.
    var showOnPhone = true

    var body: some View {
        if !(status.isBackedUp && !(originalHere && showOnPhone)) {
            ZStack {
                Circle().fill(background)
                symbol
            }
            .frame(width: size, height: size)
        }
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
            // On this phone, and on the server.
            Image(systemName: "iphone").font(.system(size: inner * 0.8, weight: .semibold)).foregroundStyle(.white)
        case is BackupStatusFailed:
            Text("!").font(.system(size: inner, weight: .bold)).foregroundStyle(.white)
        default:
            Image(systemName: "icloud.slash").font(.system(size: inner * 0.7, weight: .semibold)).foregroundStyle(.white)
        }
    }
}

/// How a tile crops its photo: anchored at the focus point (0...1 of the photo from the left and from the top),
/// `zoom` times closer than filling the tile. Anchored there, the point sits at the same fraction of the tile, so
/// zooming around that fraction keeps it in view.
struct TileCrop: Equatable {
    var x: CGFloat = 0.5
    var y: CGFloat = 0.5
    var zoom: CGFloat = 1

    static let none = TileCrop()
}
