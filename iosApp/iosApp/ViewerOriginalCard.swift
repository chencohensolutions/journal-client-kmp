import SwiftUI
import SharedLogic

// The viewer's "The original" card: where the original is, its facts, what is kept on this phone of a photo only on
// the server, and Mark private. Like Android's OriginalCard (ViewerInfoPanel.kt). The viewer downloads originals by
// itself, so there is no "Download original"; "Delete from this phone" is gone too (Settings' Free up space remains).

/// Where the original is (this phone, the server, or both; upload progress), its facts (file, size, dimensions,
/// length, type, source), what is kept here of a photo only on the server, and "Mark private" for a photo the server
/// has.
struct OriginalCard: View {
    /// "Saved in: Telegram" for a folder's kind, else "From: Camera"; "· Imported" for what the server's import placed.
    @ViewBuilder private var sourceFacts: some View {
        let kind = item.source.kind
        let imported = item.source.isImported ? String(localized: "Imported") : nil
        if let folder = kind.folderName {
            Detail(label: "Saved in", value: [folder, imported].compactMap { $0 }.joined(separator: " · "))
        } else {
            Detail(label: "From", value: [kind.title, imported].compactMap { $0 }.joined(separator: " · "))
        }
    }

    let item: MediaItem
    let setPrivate: (MediaItem, Bool) async -> Bool

    @State private var confirmingPrivate = false
    /// What is being asked of the server (private or not) until it answers; nil when nothing is.
    @State private var markingPrivate: Bool?
    @State private var message: String?
    /// The camera its tags name, as the server read them (imported files too); nil without tags or a server copy.
    @State private var camera: String?

    var body: some View {
        let uploading = item.backup as? BackupStatusUploading
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 10) {
                ZStack {
                    Circle().fill(LibraryColors.accent.opacity(0.12)).frame(width: 28, height: 28)
                    if item.hasOriginalHere || !item.backup.isBackedUp {
                        BackupBadge(status: item.backup, size: 22, originalHere: item.hasOriginalHere)
                    } else {
                        Image(systemName: "icloud").font(.system(size: 14, weight: .semibold)).foregroundStyle(LibraryColors.accent)
                    }
                }
                Text(item.originalLabel).font(.subheadline.weight(.semibold))
                Spacer()
                if let uploading {
                    Text("\(Int(uploading.progress * 100))%").font(.subheadline.weight(.semibold)).foregroundStyle(LibraryColors.accent)
                }
            }
            if let uploading {
                ProgressView(value: Double(uploading.progress)).tint(LibraryColors.accent)
            }

            InfoHeading(text: "Original")
            FactGrid {
                if let fileName = item.fileName { Detail(label: "File", value: fileName) }
                if let size = item.sizeBytes { Detail(label: "Size", value: fileSize(size.int64Value)) }
                if item.width > 0 && item.height > 0 { Detail(label: "Dimensions", value: pixelsText(Int(item.width), Int(item.height))) }
                if let duration = item.duration { Detail(label: "Length", value: formatDuration(duration)) }
                if let mimeType = item.mimeType { Detail(label: "Type", value: typeName(mimeType)) }
                sourceFacts
                if let camera { Detail(label: "Camera", value: camera) }
            }
            .task(id: item.id) {
                let library = IosJournal.shared.serverLibrary()
                camera = nil
                guard let sha = try? await library.sha256Of(item: item), let file = try? await library.file(sha256: sha) else { return }
                camera = file.camera
            }
            if !item.hasOriginalHere { ShownHereFacts(item: item) }

            // Only a photo the server has can be marked: the mark is kept there, for every device.
            if let marking = markingPrivate {
                HStack(spacing: 8) {
                    ProgressView().controlSize(.small).tint(LibraryColors.accent)
                    Text(marking ? "Marking private…" : "Unmarking private…").foregroundStyle(LibraryColors.onInverseMuted)
                }
                .frame(height: 40)
                .padding(.horizontal, 12)
            } else if item.sha256 != nil && item.backup.isBackedUp {
                if item.isPrivate {
                    OriginalButton(icon: "eye", label: "Unmark private") { markPrivate(false) }
                } else {
                    OriginalButton(icon: "lock", label: "Mark private") { confirmingPrivate = true }
                }
            }
        }
        .foregroundStyle(LibraryColors.onInverse)
        .padding(14)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LibraryColors.panelCard, in: RoundedRectangle(cornerRadius: 16))
        .alert("Mark private?", isPresented: $confirmingPrivate) {
            Button("Mark private") { markPrivate(true) }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("It’s hidden on all your devices while Show private media is off, and nothing of it is downloaded. Its edits and paired files are marked too. You can unmark it with Show private media on.")
        }
        .alert(message ?? "", isPresented: Binding(get: { message != nil }, set: { if !$0 { message = nil } })) {
            Button("OK", role: .cancel) {}
        }
    }

    private func markPrivate(_ isPrivate: Bool) {
        markingPrivate = isPrivate
        Task {
            if !(await setPrivate(item, isPrivate)) {
                message = String(localized: "Not changed: can’t reach your server. Try again when connected to it.")
            }
            markingPrivate = nil
        }
    }
}

/// A photo or video only on the server, as this phone has it (`PhoneStorage.keptHere`). "Shown on this phone": what
/// the viewer shows, the original when it was downloaded, else the largest preview, with its size against the
/// original's. "Kept on this phone": every size and format kept, and the original.
private struct ShownHereFacts: View {
    let item: MediaItem
    @State private var kept: KeptHere?

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            if let here = kept {
                let shown = here.previews.first
                InfoHeading(text: "Shown on this phone")
                FactGrid {
                    if !item.isVideo, here.originalBytes != nil {
                        Detail(label: "Format", value: String(localized: "Original"))
                    } else if let shown {
                        Detail(label: "Format", value: keptFormat(shown))
                        Detail(label: "Pixels", value: pixelsText(Int(shown.width), Int(shown.height)))
                        Detail(label: "Size", value: sizeText(shown.bytes))
                    } else {
                        Detail(label: "Preview on this phone", value: String(localized: "None yet"))
                    }
                }
                InfoHeading(text: "Kept on this phone")
                if here.isEmpty {
                    Text("Nothing yet").font(.subheadline).foregroundStyle(LibraryColors.onInverseMuted)
                } else {
                    VStack(alignment: .leading, spacing: 2) {
                        ForEach(Array(here.previews.enumerated()), id: \.offset) { _, preview in
                            Text("\(preview.width) × \(preview.height) · \(keptFormat(preview)) · \(fileSize(preview.bytes))")
                        }
                        if let original = here.originalBytes {
                            Text("Original · \(sizeText(original.int64Value))")
                        }
                        if let video = here.videoBytes.map({ fileSize($0.int64Value) }) {
                            if let original = item.sizeBytes?.int64Value, original > 0 {
                                Text("Video · \(video) of \(fileSize(original)) cached")
                            } else {
                                Text("Video · \(video) cached")
                            }
                        }
                        Text("\(fileSize(here.totalBytes)) in all")
                            .font(.footnote)
                            .foregroundStyle(LibraryColors.onInverseMuted)
                            .padding(.top, 2)
                    }
                    .font(.subheadline)
                }
            }
        }
        .task(id: item.sha256) {
            kept = await awaiting { done in ServerImages.shared.files.keptHere(item: item, onResult: done) }
        }
    }

    /// "635 kB · 4% of the original".
    private func sizeText(_ bytes: Int64) -> String {
        let size = fileSize(bytes)
        guard let percent = MediaFactsKt.percentOf(part: bytes, whole: item.sizeBytes?.int64Value ?? 0) else { return size }
        return String(localized: "\(size) · \(percent) of the original")
    }

    /// "JPEG XL · High", "JPEG · made on this phone".
    private func keptFormat(_ preview: KeptPreview) -> String {
        let quality = preview.madeHere ? String(localized: "made on this phone") : preview.quality?.label
        return [preview.format?.label, quality].compactMap { $0 }.joined(separator: " · ")
    }
}

/// A section title in the card ("Original", "Shown on this phone").
private struct InfoHeading: View {
    let text: LocalizedStringKey

    var body: some View {
        Text(text).font(.footnote.weight(.semibold)).padding(.top, 4)
    }
}

/// Facts two to a row.
private struct FactGrid<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)], alignment: .leading, spacing: 12) {
            content
        }
    }
}

private struct OriginalButton: View {
    let icon: String
    let label: LocalizedStringKey
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Label(label, systemImage: icon).font(.subheadline.weight(.semibold)).foregroundStyle(LibraryColors.accent)
        }
        .frame(height: 40)
    }
}

/// "4000 × 3000 (12 MP)".
private func pixelsText(_ width: Int, _ height: Int) -> String {
    guard let megapixels = MediaFactsKt.megapixels(width: Int32(width), height: Int32(height)) else { return "\(width) × \(height)" }
    return "\(width) × \(height) (\(megapixels.intValue) MP)"
}

/// "4.2 MB", as Android writes file sizes.
func fileSize(_ bytes: Int64) -> String { ByteCountFormatter.string(fromByteCount: bytes, countStyle: .file) }
