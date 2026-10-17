import SwiftUI
import SharedLogic

/// Every tag, most recently used first, with a search field. See docs/screens/tags.md.
struct TagsView: View {
    let stories: StoriesState
    let onOpen: (String) -> Void

    @State private var query = ""

    var body: some View {
        let tags = query.isBlank ? stories.tags : stories.searchTags(query: query)
        List {
            if stories.tags.isEmpty {
                Text("No tags yet. Open a photo, show its details and tap + Tag.")
                    .foregroundStyle(LibraryColors.muted)
                    .listRowBackground(Color.clear)
            }
            ForEach(tags, id: \.name) { tag in
                Button { onOpen(tag.name) } label: {
                    HStack(spacing: 14) {
                        TagThumbnail(tag: tag, size: 56)
                        VStack(alignment: .leading, spacing: 2) {
                            Text(tag.name).font(.body.weight(.semibold)).foregroundStyle(.white)
                            Text(tag.count == 1 ? "1 photo" : "\(tag.count) photos")
                                .font(.footnote)
                                .foregroundStyle(LibraryColors.muted)
                        }
                    }
                }
                .listRowBackground(Color.clear)
            }
        }
        .listStyle(.plain)
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background.ignoresSafeArea())
        .searchable(text: $query, prompt: "Search tags")
        .navigationTitle("Tags")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }
}

/// The photos with one tag, by day, newest first; tapping opens the viewer on them.
struct TagView: View {
    let tag: String
    let stories: StoriesState
    let onOpen: (MediaItem) -> Void

    var body: some View {
        let days = stories.tagged(tag: tag)
        let count = days.reduce(0) { $0 + $1.items.count }
        GeometryReader { geometry in
            let cell = (geometry.size.width - 4) / 3
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 2) {
                    Text(count == 1 ? "1 photo" : "\(count) photos")
                        .font(.subheadline)
                        .foregroundStyle(LibraryColors.muted)
                        .padding(.horizontal, 16)
                        .padding(.bottom, 10)
                    if days.isEmpty {
                        Text("No photos on this device have this tag.")
                            .foregroundStyle(LibraryColors.muted)
                            .padding(16)
                    }
                    ForEach(days, id: \.day) { day in
                        DayGrid(day: day, label: day.day.label, cell: cell, onOpen: onOpen)
                    }
                }
                .padding(.bottom, 32)
            }
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationTitle(tag)
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
    }
}
