import SwiftUI
import SharedLogic

/// Tags photos: recent tags before typing, every matching tag while typing, with "Create" for a new one.
struct TagSheet: View {
    let itemIds: [String]
    let stories: StoriesState
    let onToggle: (_ tag: String, _ add: Bool) -> Void
    let onCreate: (_ tag: String) -> Void

    @State private var query = ""

    var body: some View {
        let exists = stories.tags.contains { $0.name.caseInsensitiveCompare(query.trimmed) == .orderedSame }
        PickerSheet(title: "Tags", prompt: "Search tags, or type a new one", query: $query,
                    createLabel: query.isBlank || exists ? nil : String(localized: "Create tag “\(query.trimmed)”"),
                    createDetail: nil,
                    onCreate: { onCreate(query.trimmed) }) {
            let list = query.isBlank ? stories.tags : stories.searchTags(query: query)
            if list.isEmpty && query.isBlank {
                Text("No tags yet. Type one above to create it.").foregroundStyle(.secondary)
            } else if !list.isEmpty {
                Section(query.isBlank ? "Recent tags" : "Tags") {
                    ForEach(list, id: \.name) { tag in
                        let applied = itemIds.allSatisfy { id in
                            stories.note(itemId: id)?.tags.contains { $0.caseInsensitiveCompare(tag.name) == .orderedSame } == true
                        }
                        PickerRow(title: tag.name, detail: String(localized: "\(Int(tag.count)) photos"), checked: applied) {
                            onToggle(tag.name, !applied)
                        } leading: {
                            TagThumbnail(tag: tag, size: 44)
                        }
                    }
                }
            }
        }
    }
}

private struct PickerSheet<Content: View>: View {
    let title: LocalizedStringKey
    let prompt: LocalizedStringKey
    @Binding var query: String
    let createLabel: String?
    let createDetail: String?
    let onCreate: () -> Void
    @ViewBuilder let content: Content

    @Environment(\.dismiss) private var dismiss
    @FocusState private var searching: Bool

    var body: some View {
        NavigationStack {
            List {
                if let createLabel {
                    Button(action: onCreate) {
                        HStack(spacing: 14) {
                            Image(systemName: "plus")
                                .font(.headline)
                                .foregroundStyle(.white)
                                .frame(width: 44, height: 44)
                                .background(LibraryColors.accent, in: RoundedRectangle(cornerRadius: 10))
                            VStack(alignment: .leading, spacing: 2) {
                                Text(createLabel).font(.body.weight(.bold)).foregroundStyle(.primary)
                                if let createDetail { Text(createDetail).font(.footnote).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
                content
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always), prompt: prompt)
            .searchFocused($searching)
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } }
            }
            // Ready to type: most of the time the answer is a search or a new name.
            .onAppear { searching = true }
        }
        .presentationDetents([.large])
    }
}

private struct PickerRow<Leading: View>: View {
    let title: String
    let detail: String
    let checked: Bool
    let action: () -> Void
    @ViewBuilder let leading: Leading

    var body: some View {
        Button(action: action) {
            HStack(spacing: 14) {
                leading.frame(width: 52)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.body.weight(.semibold)).foregroundStyle(.primary)
                    if !detail.isEmpty { Text(detail).font(.footnote).foregroundStyle(.secondary) }
                }
                Spacer()
                if checked {
                    Image(systemName: "checkmark").foregroundStyle(LibraryColors.accent).accessibilityLabel("Added")
                }
            }
        }
        .accessibilityAddTraits(checked ? [.isSelected] : [])
    }
}

/// The newest photo with the tag, or a tag icon.
struct TagThumbnail: View {
    let tag: TagSummary
    let size: CGFloat

    var body: some View {
        ZStack {
            LibraryColors.accent.opacity(0.15)
            if let latest = tag.latest {
                MediaTileView(item: latest, badgeSize: 0)
            } else {
                Image(systemName: "tag").foregroundStyle(LibraryColors.accent)
            }
        }
        .frame(width: size, height: size)
        .clipShape(RoundedRectangle(cornerRadius: 10))
        .accessibilityHidden(true)
    }
}

extension String {
    var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    var isBlank: Bool { trimmed.isEmpty }
}
