import SwiftUI
import SharedLogic

/// A group: its cover, title and story text, then all its photos. See docs/screens/group.md.
struct GroupPageView: View {
    let story: StoryView?
    let group: GroupView?
    let onOpen: (MediaItem) -> Void
    let onRename: (String) -> Void
    let onSetText: (String) -> Void
    let onUngroup: () -> Void

    @Environment(\.dismiss) private var dismiss
    @State private var renaming = false
    @State private var newTitle = ""
    @State private var editing = false

    var body: some View {
        Group {
            if let story, let group {
                content(story, group)
            } else {
                // Ungrouped or deleted.
                Color.clear.onAppear { dismiss() }
            }
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button {
                        newTitle = group?.title ?? ""
                        renaming = true
                    } label: { Label("Rename", systemImage: "pencil") }
                    Button(action: onUngroup) { Label("Ungroup", systemImage: "square.on.square") }
                } label: {
                    Image(systemName: "ellipsis").foregroundStyle(.white)
                }
                .accessibilityLabel("More")
            }
        }
        .alert("Rename group", isPresented: $renaming) {
            TextField("Title", text: $newTitle)
            Button("Save") { onRename(newTitle) }
            Button("Cancel", role: .cancel) {}
        }
        .sheet(isPresented: $editing) {
            WritingEditor(title: group?.name ?? "", initial: group?.text ?? "", onSave: onSetText)
        }
    }

    private func content(_ story: StoryView, _ group: GroupView) -> some View {
        GeometryReader { geometry in
            let cell = (geometry.size.width - 4) / 3
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    MediaTileView(item: group.cover, badgeSize: 0)
                        .frame(width: geometry.size.width, height: 300)
                        .overlay(alignment: .bottom) {
                            LinearGradient(colors: [.clear, LibraryColors.background], startPoint: .top, endPoint: .bottom)
                                .frame(height: 120)
                        }
                    VStack(alignment: .leading, spacing: 4) {
                        Text(story.title.uppercased())
                            .font(.caption.weight(.semibold))
                            .tracking(0.7)
                            .foregroundStyle(LibraryColors.muted)
                        Text(group.name)
                            .font(.system(size: 28, weight: .bold))
                            .foregroundStyle(.white)
                            .accessibilityAddTraits(.isHeader)
                        // An untitled group already shows its time as the title.
                        Text([
                            group.items.first?.day.date.formatted(.dateTime.month(.abbreviated).day()),
                            group.title != nil ? timeRange(group.items) : nil,
                            "\(group.itemCount) photos",
                        ].compactMap { $0 }.joined(separator: " · "))
                            .font(.subheadline)
                            .foregroundStyle(LibraryColors.muted)
                    }
                    .padding(.horizontal, 20)
                    .offset(y: -36)
                    WritingBlock(text: group.text, emptyPrompt: "Write about this moment…") { editing = true }
                        .offset(y: -24)
                    LazyVGrid(columns: Array(repeating: GridItem(.fixed(cell), spacing: 2), count: 3), spacing: 2) {
                        ForEach(group.items, id: \.id) { item in
                            MediaTileView(item: item)
                                .frame(width: cell, height: cell)
                                .overlay(alignment: .topLeading) {
                                    if item.id == group.cover.id {
                                        Text("Cover")
                                            .font(.caption2.weight(.bold))
                                            .foregroundStyle(LibraryColors.ink)
                                            .padding(.horizontal, 8)
                                            .padding(.vertical, 3)
                                            .background(LibraryColors.light, in: Capsule())
                                            .padding(6)
                                    }
                                }
                                .onTapGesture { onOpen(item) }
                        }
                    }
                }
                .padding(.bottom, 32)
            }
            .scrollIndicators(.hidden)
            .ignoresSafeArea(edges: .top)
        }
    }
}
