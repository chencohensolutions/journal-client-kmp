import SwiftUI
import SharedLogic

/// A square-cropped photo or video with its video length and backup badge. Size it from outside.
struct MediaTileView: View {
    let item: MediaItem
    var badgeSize: CGFloat = 22

    @State private var thumbnail: UIImage?

    var body: some View {
        GeometryReader { geometry in
            ZStack {
                LibraryColors.raised
                if let thumbnail {
                    Image(uiImage: thumbnail)
                        .resizable()
                        .scaledToFill()
                        .frame(width: geometry.size.width, height: geometry.size.height)
                        .clipped()
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
                thumbnail = await PhotoLibraryImages.shared.image(id: item.id, pointSize: geometry.size)
            }
        }
        .contentShape(Rectangle())
        .accessibilityElement()
        .accessibilityLabel(item.accessibilityDescription)
        .accessibilityAddTraits(.isButton)
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
