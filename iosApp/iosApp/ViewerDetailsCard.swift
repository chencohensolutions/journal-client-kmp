import SwiftUI
import SharedLogic

/// The viewer's details card, under the original card: "Camera details" (camera, lens and exposure) for a photo,
/// "Video details" (codec, size, rate) for a video, read from the original on this phone (`OriginalDetails.swift`),
/// with a link that opens every tag by section. While a server photo's original is still downloading it says it shows
/// once it is here, and reads it then. Like Android's DetailsCard.
struct DetailsCard: View {
    let item: MediaItem

    private enum Loading {
        case reading
        /// The original isn't on this phone yet.
        case waiting
        case read(OriginalDetails)
        /// It couldn't be read: the card goes.
        case none
    }

    @State private var loading = Loading.reading
    @State private var open = false

    var body: some View {
        Group {
            switch loading {
            case .none:
                EmptyView()
            default:
                card
            }
        }
        .task(id: item.id) { await load() }
    }

    private var card: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(item.isVideo ? LocalizedStringKey("Video details") : LocalizedStringKey("Camera details")).font(.system(size: 13, weight: .semibold))
            switch loading {
            case .read(let details):
                content(details)
            case .waiting:
                Text("Shown when the original has downloaded")
                    .font(.system(size: 13))
                    .foregroundStyle(LibraryColors.onInverseMuted)
            default:
                ProgressView().tint(LibraryColors.onInverseMuted).frame(maxWidth: .infinity)
            }
        }
        .foregroundStyle(LibraryColors.onInverse)
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LibraryColors.panelCard, in: RoundedRectangle(cornerRadius: 16))
    }

    @ViewBuilder
    private func content(_ details: OriginalDetails) -> some View {
        if details.title != nil || details.subtitle != nil {
            VStack(alignment: .leading, spacing: 2) {
                if let title = details.title { Text(title).font(.system(size: 16, weight: .semibold)) }
                if let subtitle = details.subtitle {
                    Text(subtitle).font(.system(size: 13)).foregroundStyle(LibraryColors.onInverseMuted)
                }
            }
        }
        if !details.chips.isEmpty {
            FlowLayout(spacing: 6) {
                ForEach(Array(details.chips.enumerated()), id: \.offset) { _, chip in
                    Text(chip)
                        .font(.system(size: 13, weight: .medium))
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(LibraryColors.accent.opacity(0.10), in: RoundedRectangle(cornerRadius: 8))
                }
            }
        }
        if details.rowCount > 0 {
            Button { open.toggle() } label: {
                Text(linkLabel(details.rowCount))
                    .font(.system(size: 14, weight: .medium))
                    .foregroundStyle(LibraryColors.accent)
                    .padding(.vertical, 6)
            }
            if open {
                ForEach(details.sections) { section in
                    Text(section.name)
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(LibraryColors.onInverseMuted)
                        .padding(.top, 4)
                    ForEach(Array(section.rows.enumerated()), id: \.offset) { _, row in
                        HStack(alignment: .firstTextBaseline, spacing: 12) {
                            Text(row.label)
                                .font(.system(size: 13))
                                .foregroundStyle(LibraryColors.onInverseMuted)
                                .frame(width: 130, alignment: .leading)
                            Text(row.value)
                                .font(.system(size: 13, weight: .medium))
                                .frame(maxWidth: .infinity, alignment: .leading)
                                .textSelection(.enabled)
                        }
                    }
                }
            }
        }
    }

    private func linkLabel(_ count: Int) -> String {
        if item.isVideo { return open ? String(localized: "Hide video details") : String(localized: "All video details (\(count))") }
        return open ? String(localized: "Hide all EXIF") : String(localized: "All EXIF (\(count))")
    }

    /// Reads the original when it is on this phone; else waits for the viewer's download of it to end, and tries again.
    private func load() async {
        open = false
        loading = .reading
        if item.isOnDevice || item.isVideo {
            await read()
            return
        }
        let (ended, signal) = AsyncStream<Void>.makeStream(bufferingPolicy: .bufferingNewest(1))
        // Nil when no download runs: at once, and each time one ends.
        let watching = ServerImages.shared.observeOriginalProgress(item) { if $0 == nil { signal.yield() } }
        defer { watching.cancel() }
        for await _ in ended {
            if await ServerImages.shared.originalPath(item) != nil {
                await read()
                return
            }
            loading = .waiting
        }
    }

    private func read() async {
        guard let source = await OriginalDetailsReader.source(item) else {
            loading = item.isOnDevice || item.isVideo ? .none : .waiting
            return
        }
        let details = await OriginalDetailsReader.read(source, item: item)
        if Task.isCancelled { return }
        loading = details.map { .read($0) } ?? .none
    }
}
