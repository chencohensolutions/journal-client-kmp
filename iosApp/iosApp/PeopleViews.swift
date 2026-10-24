import SwiftUI
import SharedLogic

// People: the faces the server found (docs/screens/people.md), as Android's PeopleViews.kt. Explore's People list, a
// person's page and "Who's this?" (naming a group), on the shared PeopleController and PersonController.

/// `face` in a circle `size` across: a crop of its photo from the server (a drawn face in the UI lab).
struct FaceAvatar: View {
    let face: Face
    let size: CGFloat
    @Environment(\.displayScale) private var scale
    @State private var image: UIImage?

    var body: some View {
        ZStack {
            LibraryColors.raised
            if let image {
                Image(uiImage: image).resizable().scaledToFill()
            } else if UiLab.isOn {
                LabFace(key: face.personId ?? face.sha256)
            }
        }
        .frame(width: size, height: size)
        .clipShape(Circle())
        .task(id: face.id) {
            guard !UiLab.isOn else { return }
            image = await ServerImages.shared.face(face, pixels: Int(size * scale))
        }
        .accessibilityHidden(true)
    }
}

/// A face drawn for the UI lab, colored by whose it is.
private struct LabFace: View {
    let key: String

    var body: some View {
        let tones: [Color] = [.init(red: 0.95, green: 0.79, blue: 0.65), .init(red: 0.85, green: 0.64, blue: 0.49), .init(red: 0.71, green: 0.48, blue: 0.33), .init(red: 0.55, green: 0.35, blue: 0.24)]
        let tone = tones[abs(key.hashValue) % tones.count]
        GeometryReader { g in
            ZStack {
                Color(red: 0.56, green: 0.7, blue: 0.65)
                Ellipse().fill(tone).frame(width: g.size.width * 0.52, height: g.size.height * 0.62).offset(y: -g.size.height * 0.04)
            }
        }
    }
}

/// A person or group in a grid: the face, the name (none for a group), and how many photos.
private struct PersonCell: View {
    let person: Person
    let onOpen: () -> Void

    var body: some View {
        Button(action: onOpen) {
            VStack(spacing: 0) {
                FaceAvatar(face: person.cover, size: 72)
                if let name = person.name {
                    Text(name).font(.system(size: 14, weight: .semibold)).foregroundStyle(LibraryColors.onBackground).lineLimit(1).padding(.top, 6)
                }
                Text(person.files, format: .number).font(.system(size: 12)).foregroundStyle(LibraryColors.muted)
                    .padding(.top, person.name == nil ? 6 : 0)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 6)
        }
        .buttonStyle(.plain)
    }
}

/// `people` four to a row.
private struct PeopleGrid: View {
    let people: [Person]
    let onOpen: (Person) -> Void

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 0), count: 4), spacing: 0) {
            ForEach(people, id: \.id) { person in PersonCell(person: person) { onOpen(person) } }
        }
        .padding(.horizontal, 8)
    }
}

/// Explore's People: the named people, then "Who's this?" with the groups to name; while the server still reads files
/// for faces, a line says how far it is.
struct PeopleListView: View {
    let people: PeopleState
    let actions: ExploreActions
    @State private var allGroups = false

    var body: some View {
        let list = people.list
        let empty = list.people.isEmpty && list.groups.isEmpty
        VStack(alignment: .leading, spacing: 0) {
            if people.stillReading {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Still reading your photos for faces: \(Int(list.read).formatted()) of \(Int(list.total).formatted())")
                        .font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
                    ProgressView(value: Double(list.read), total: Double(max(list.total, 1))).tint(LibraryColors.onBackground)
                }
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            }
            if empty && people.status == .loading {
                PeopleMessage(text: String(localized: "Loading…"))
            } else if empty && people.status == .unreachable {
                PeopleRetry(text: String(localized: "Can’t reach your server right now."), retry: actions.reloadPeople)
            } else if empty && people.status == .failed {
                PeopleRetry(text: String(localized: "The server couldn’t find people: \(people.error ?? "")"), retry: actions.reloadPeople)
            } else if empty {
                PeopleMessage(text: String(localized: "No faces found yet. Your server finds them as it reads your photos."))
            } else if list.people.isEmpty {
                PeopleMessage(text: String(localized: "No one is named yet. Name a group below, and their photos are found from then on."))
            } else {
                PeopleGrid(people: list.people) { actions.openPerson($0.id) }
            }
            if !list.groups.isEmpty {
                HStack(alignment: .center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Who’s this?").font(.system(size: 20, weight: .bold)).foregroundStyle(LibraryColors.onBackground).accessibilityAddTraits(.isHeader)
                        Text("Name a group once, and their new photos are found too.").font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
                    }
                    Spacer()
                    if list.groups.count > 8 {
                        Button(allGroups ? String(localized: "See fewer") : String(localized: "See all \(list.groups.count)")) { allGroups.toggle() }
                            .font(.system(size: 14, weight: .semibold)).foregroundStyle(LibraryColors.onBackground)
                    }
                }
                .padding(.leading, 16)
                .padding(.trailing, 12)
                .padding(.top, 24)
                PeopleGrid(people: allGroups ? list.groups : Array(list.groups.prefix(8))) { actions.openPerson($0.id) }
            }
        }
        .task { actions.loadPeople() }
    }
}

private struct PeopleMessage: View {
    let text: String

    var body: some View {
        Text(text).font(.system(size: 15)).foregroundStyle(LibraryColors.muted).frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 24).padding(.vertical, 24)
    }
}

private struct PeopleRetry: View {
    let text: String
    let retry: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text(text).font(.system(size: 15)).foregroundStyle(LibraryColors.muted)
            Button("Try again", action: retry).foregroundStyle(LibraryColors.onBackground)
        }
        .padding(.horizontal, 24).padding(.vertical, 24)
    }
}

/// Newest / Best first, for town, year and person pages.
struct SortChips: View {
    let sort: PageSort
    let onSort: (PageSort) -> Void

    var body: some View {
        HStack(spacing: 8) {
            ExploreChip(label: String(localized: "Newest"), on: sort == .newest) { onSort(.newest) }
            ExploreChip(label: String(localized: "Best first"), on: sort == .best) { onSort(.best) }
        }
    }
}

/// Photos three to a row, square, in their order; `hold` adds a long-press menu.
struct PhotoGrid<Menu: View>: View {
    let items: [MediaItem]
    let open: (MediaItem) -> Void
    @ViewBuilder var menu: (MediaItem) -> Menu

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 2), count: 3), spacing: 2) {
            ForEach(items, id: \.id) { item in
                MediaTileView(item: item)
                    .aspectRatio(1, contentMode: .fill)
                    .clipped()
                    .zoomSource(ZoomID.item(item.id))
                    .contentShape(Rectangle())
                    .onTapGesture { open(item) }
                    .contextMenu { menu(item) }
            }
        }
    }
}

extension PhotoGrid where Menu == EmptyView {
    init(items: [MediaItem], open: @escaping (MediaItem) -> Void) {
        self.init(items: items, open: open) { _ in EmptyView() }
    }
}

/// What a person's page can ask for; see `PersonController`.
struct PersonActions {
    var setQuery: (String) -> Void
    var setSort: (PageSort) -> Void
    /// The page of someone they are often with.
    var openPerson: (String) -> Void
    /// The viewer on a photo, swiping through the page's photos in their order.
    var openItem: (MediaItem) -> Void
    var rename: (String) -> Void
    var merge: (String) -> Void
    var forget: () -> Void
    /// "Not Mom" on a photo.
    var notThem: (MediaItem) -> Void
    var retry: () -> Void
    /// Edit faces › Done: the faces marked ✗ aren't them.
    var notThemFaces: ([Face]) -> Void = { _ in }
    /// "Who is it?" after one face was marked.
    var faces = FaceActions()
}

/// A person's page: their best photo with the name, ⋯ (Rename, Merge with…, Forget), a search of their photos, the
/// sort, who they are often with, then their photos by day (or best first, or best match). Press and hold a photo for
/// "Not Mom".
struct PersonPageView: View {
    let state: PersonState
    let others: [Person]
    let actions: PersonActions

    @State private var query = ""
    @State private var renaming = false
    @State private var newName = ""
    @State private var merging = false
    @State private var forgetting = false
    @State private var notThem: MediaItem?
    @State private var editing = false
    @State private var marked = Set<String>()
    @State private var askWho: Face?

    var body: some View {
        let name = state.person?.name ?? ""
        GeometryReader { geometry in
            ScrollView {
                LazyVStack(alignment: .leading, spacing: 0) {
                    PageCover(title: name, subtitle: subtitle, photo: cover, top: geometry.safeAreaInsets.top)
                    PageSearchField(text: $query, hint: String(localized: "Search \(name)’s photos"))
                        .padding(.horizontal, 16).padding(.top, 14)
                        .onChange(of: query) { _, text in actions.setQuery(text) }
                    SortChips(sort: state.sort, onSort: actions.setSort).padding(.horizontal, 16).padding(.top, 12)
                    if !state.together.isEmpty && state.query.isEmpty {
                        TogetherRow(name: name, together: state.together, open: actions.openPerson)
                    }
                    PageStatus(state: state, retry: actions.retry)
                    if editing {
                        Text("Tap the faces that aren’t \(name). Each one you mark also keeps faces like it away from them.")
                            .font(.footnote).foregroundStyle(LibraryColors.muted).padding(.horizontal, 16).padding(.vertical, 10)
                        EditFacesGrid(state: state, marked: $marked)
                    } else if state.sort == .newest && state.query.isEmpty {
                        ForEach(byDay, id: \.0) { day, items in
                            Text(day).font(.system(size: 15, weight: .bold)).foregroundStyle(LibraryColors.onBackground)
                                .padding(.horizontal, 16).padding(.top, 16).padding(.bottom, 6)
                            PhotoGrid(items: items, open: actions.openItem) { item in notThemButton(item, name: name) }
                        }
                    } else {
                        PhotoGrid(items: state.items, open: actions.openItem) { item in notThemButton(item, name: name) }
                            .padding(.top, 12)
                    }
                }
                .padding(.bottom, 48)
            }
            .scrollIndicators(.hidden)
            .ignoresSafeArea(edges: .top)
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbarColorScheme(.dark, for: .navigationBar)
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Menu {
                    Button("Edit faces") { editing = true }
                    Button("Rename") { newName = name; renaming = true }
                    Button("Merge with…") { merging = true }
                    Button("Forget this person", role: .destructive) { forgetting = true }
                } label: { Image(systemName: "ellipsis") }
                .accessibilityLabel("More")
            }
        }
        .alert("Rename", isPresented: $renaming) {
            TextField("Name", text: $newName)
            Button("Cancel", role: .cancel) {}
            Button("Save") { actions.rename(newName) }
        }
        .alert("Forget \(name)?", isPresented: $forgetting) {
            Button("Cancel", role: .cancel) {}
            Button("Forget", role: .destructive, action: actions.forget)
        } message: {
            Text("The name goes. The photos stay, and the faces go back to Who’s this?")
        }
        .alert("Not \(name) in this photo?", isPresented: Binding(get: { notThem != nil }, set: { if !$0 { notThem = nil } })) {
            Button("Cancel", role: .cancel) {}
            Button("Not \(name)") { if let item = notThem { actions.notThem(item) } }
        } message: {
            Text("This face won’t be given to \(name) again. The photo stays in your library.")
        }
        .safeAreaInset(edge: .bottom) {
            if editing {
                HStack(spacing: 10) {
                    Text("\(marked.count) marked not \(name)").font(.subheadline)
                    Spacer()
                    Button("Cancel") { editing = false; marked = [] }
                    Button("Done") {
                        let faces = state.itemFaces.compactMap { $0.second }.filter { marked.contains($0.id) }
                        actions.notThemFaces(faces)
                        if faces.count == 1 { askWho = faces.first }
                        editing = false
                        marked = []
                    }
                    .fontWeight(.bold)
                    .buttonStyle(.borderedProminent)
                    .tint(LibraryColors.inverse)
                    .foregroundStyle(LibraryColors.onInverse)
                }
                .foregroundStyle(LibraryColors.onBackground)
                .padding(.horizontal, 16).padding(.vertical, 10)
                .background(LibraryColors.background)
            }
        }
        .sheet(item: Binding(get: { askWho.map(IdentifiedFace.init) }, set: { if $0 == nil { askWho = nil } })) { item in
            FaceSheet(found: FoundFace(face: item.face, person: nil), people: others.filter { !$0.isGroup }, actions: actions.faces, asking: true) {
                askWho = nil
            }
        }
        .sheet(isPresented: $merging) {
            MergeSheet(name: name, others: others) { other in
                merging = false
                actions.merge(other.id)
            }
        }
    }

    private func notThemButton(_ item: MediaItem, name: String) -> some View {
        Button("Not \(name)", systemImage: "person.crop.circle.badge.xmark") { notThem = item }
    }

    private var cover: MediaItem? {
        let images = state.items.filter { !$0.isVideo }
        return ExploreKt.bestLooking(items: images) ?? images.first
    }

    private var subtitle: String {
        let count = Int(state.person?.files ?? Int32(state.items.count))
        var line = String(localized: "\(count) photos")
        if let first = state.firstYear, let last = state.lastYear {
            line += " · " + (first == last ? String(first.intValue) : yearRange(Int(first.intValue), Int(last.intValue)))
        }
        return line
    }

    private var byDay: [(String, [MediaItem])] {
        var days: [(String, [MediaItem])] = []
        for item in state.items {
            let label = item.takenAt.formatted(date: .abbreviated, time: .omitted)
            if days.last?.0 == label { days[days.count - 1].1.append(item) } else { days.append((label, [item])) }
        }
        return days
    }
}

/// Loading, offline, "Not changed", or nothing found, under a page's header.
private struct PageStatus: View {
    let state: PersonState
    let retry: () -> Void

    var body: some View {
        if state.notChanged {
            Text("Not changed: can’t reach your server. Try again when connected to it.")
                .font(.system(size: 13)).foregroundStyle(LibraryColors.warning).padding(.horizontal, 16).padding(.vertical, 8)
        } else if state.items.isEmpty {
            switch state.status {
            case .loading: PeopleMessage(text: String(localized: "Loading…"))
            case .unreachable: PeopleRetry(text: String(localized: "Can’t reach your server right now."), retry: retry)
            case .failed: PeopleRetry(text: String(localized: "The server couldn’t find people: \(state.error ?? "")"), retry: retry)
            default: if !state.query.isEmpty { PeopleMessage(text: String(localized: "Nothing found. Try other words.")) }
            }
        }
    }
}

/// A rounded search field on a page: "Search Mom's photos".
struct PageSearchField: View {
    @Binding var text: String
    let hint: String

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(LibraryColors.muted)
            TextField(hint, text: $text).submitLabel(.search).foregroundStyle(LibraryColors.onBackground)
            if !text.isEmpty {
                Button { text = "" } label: { Image(systemName: "xmark.circle.fill") }
                    .foregroundStyle(LibraryColors.muted).accessibilityLabel("Clear")
            }
        }
        .padding(.horizontal, 14)
        .frame(height: 44)
        .background(LibraryColors.raised, in: Capsule())
    }
}

/// "Often with Mom": faces with their names and how many photos they share.
private struct TogetherRow: View {
    let name: String
    let together: [Together]
    let open: (String) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("Often with \(name)").font(.system(size: 16, weight: .bold)).foregroundStyle(LibraryColors.onBackground)
                .padding(.horizontal, 16).accessibilityAddTraits(.isHeader)
            ScrollView(.horizontal) {
                HStack(spacing: 0) {
                    ForEach(together, id: \.person.id) { other in
                        Button { open(other.person.id) } label: {
                            VStack(spacing: 2) {
                                FaceAvatar(face: other.person.cover, size: 52)
                                Text(other.person.name ?? "?").font(.system(size: 13, weight: .semibold)).foregroundStyle(LibraryColors.onBackground).lineLimit(1)
                                Text("\(other.photos)").font(.system(size: 12)).foregroundStyle(LibraryColors.muted)
                            }
                            .frame(width: 76)
                            .padding(.vertical, 6)
                        }
                        .buttonStyle(.plain)
                    }
                }
                .padding(.horizontal, 8)
            }
            .scrollIndicators(.hidden)
            Divider().padding(.top, 8)
        }
        .padding(.top, 20)
    }
}

/// Merge with…: the other people, then the groups; a tap merges that one into this person.
private struct MergeSheet: View {
    let name: String
    let others: [Person]
    let onMerge: (Person) -> Void

    var body: some View {
        NavigationStack {
            List(others, id: \.id) { other in
                Button { onMerge(other) } label: { PersonRow(person: other) }
            }
            .navigationTitle("Merge with \(name)")
            .navigationBarTitleDisplayMode(.inline)
            .safeAreaInset(edge: .top) {
                Text("Pick someone who is also \(name). Their photos join, under this name.")
                    .font(.footnote).foregroundStyle(.secondary).padding(.horizontal, 20).padding(.vertical, 8)
            }
        }
        .presentationDetents([.medium, .large])
    }
}

/// A person or group in a list: the face, the name ("Unnamed" for a group) and the count.
private struct PersonRow: View {
    let person: Person
    var trailing: String?

    var body: some View {
        HStack(spacing: 14) {
            FaceAvatar(face: person.cover, size: 44)
            VStack(alignment: .leading, spacing: 2) {
                Text(person.name ?? String(localized: "Unnamed")).font(.system(size: 16, weight: .semibold))
                    .foregroundStyle(person.name == nil ? LibraryColors.muted : LibraryColors.onBackground)
                Text("\(person.files) photos").font(.system(size: 13)).foregroundStyle(LibraryColors.muted)
            }
            Spacer()
            if let trailing { Text(trailing).font(.system(size: 13, weight: .semibold)).foregroundStyle(LibraryColors.onBackground) }
        }
    }
}

/// What "Who's this?" can ask for; see `PersonController`.
struct GroupActions {
    var setName: (String) -> Void
    var name: (String) -> Void
    var join: (String) -> Void
    var openItem: (MediaItem) -> Void
    var retry: () -> Void
}

/// "Who's this?": the group's faces, four to a row, and at the bottom a name field. While typing, the people whose
/// names match are listed above it ("Mom · 812 photos · Joins Mom"), then "New person “Mo”".
struct GroupPageView: View {
    let state: PersonState
    let actions: GroupActions
    @State private var name = ""
    @Environment(\.displayScale) private var scale

    var body: some View {
        let typed = name.trimmingCharacters(in: .whitespaces)
        let ready = !typed.isEmpty && !state.saving
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("In \(Int(state.person?.files ?? Int32(state.items.count)).formatted()) photos").font(.system(size: 14)).foregroundStyle(LibraryColors.muted)
                    .padding(.horizontal, 20).padding(.bottom, 10)
                PageStatus(state: state, retry: actions.retry)
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4), spacing: 6) {
                    ForEach(state.itemFaces, id: \.first!.id) { pair in
                        FaceSquare(face: pair.second!)
                            .onTapGesture { actions.openItem(pair.first!) }
                    }
                }
                .padding(.horizontal, 14)
            }
            .padding(.bottom, 24)
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationTitle("Who’s this?")
        .safeAreaInset(edge: .bottom) {
            VStack(alignment: .leading, spacing: 0) {
                if state.notChanged {
                    Text("Not changed: can’t reach your server. Try again when connected to it.").font(.system(size: 13)).foregroundStyle(LibraryColors.warning)
                        .padding(.horizontal, 20).padding(.bottom, 8)
                }
                if !typed.isEmpty {
                    ForEach(state.matches, id: \.id) { person in
                        Button { if !state.saving { actions.join(person.id) } } label: {
                            PersonRow(person: person, trailing: String(localized: "Joins \(person.name ?? "")"))
                        }
                        .buttonStyle(.plain).padding(.horizontal, 24).padding(.vertical, 6)
                    }
                    if !state.matches.contains(where: { $0.name?.caseInsensitiveCompare(typed) == .orderedSame }) {
                        Button { actions.name(typed) } label: {
                            HStack(spacing: 14) {
                                Image(systemName: "plus").frame(width: 44, height: 44).overlay(Circle().stroke(LibraryColors.muted.opacity(0.5)))
                                Text("New person “\(typed)”").font(.system(size: 16, weight: .semibold))
                            }
                            .foregroundStyle(LibraryColors.onBackground)
                        }
                        .disabled(!ready).buttonStyle(.plain).padding(.horizontal, 24).padding(.vertical, 8)
                    }
                    Divider().padding(.vertical, 6)
                } else {
                    Text("Give them a name once. Their photos are found from then on, as they come in.")
                        .font(.system(size: 13)).foregroundStyle(LibraryColors.muted).padding(.horizontal, 20).padding(.bottom, 10)
                }
                HStack(spacing: 10) {
                    TextField("Name", text: $name)
                        .submitLabel(.done)
                        .onSubmit { if ready { actions.name(typed) } }
                        .onChange(of: name) { _, text in actions.setName(text) }
                        .padding(.horizontal, 16).frame(height: 52)
                        .background(LibraryColors.raised, in: RoundedRectangle(cornerRadius: 14))
                        .overlay(RoundedRectangle(cornerRadius: 14).stroke(LibraryColors.muted.opacity(0.35)))
                    Button("Name") { actions.name(typed) }
                        .font(.system(size: 16, weight: .bold))
                        .foregroundStyle(ready ? LibraryColors.onInverse : LibraryColors.muted)
                        .padding(.horizontal, 18).frame(height: 52)
                        .background(ready ? LibraryColors.inverse : LibraryColors.raised, in: RoundedRectangle(cornerRadius: 14))
                        .disabled(!ready)
                }
                .padding(.horizontal, 16)
            }
            .padding(.vertical, 12)
            .background(LibraryColors.background)
        }
    }
}

/// A face filling a square.
private struct FaceSquare: View {
    let face: Face
    @Environment(\.displayScale) private var scale
    @State private var image: UIImage?

    var body: some View {
        GeometryReader { g in
            ZStack {
                LibraryColors.raised
                if let image { Image(uiImage: image).resizable().scaledToFill() } else if UiLab.isOn { LabFace(key: face.personId ?? face.sha256) }
            }
            .frame(width: g.size.width, height: g.size.width)
            .clipped()
            .task(id: face.id) {
                guard !UiLab.isOn else { return }
                image = await ServerImages.shared.face(face, pixels: Int(g.size.width * scale))
            }
        }
        .aspectRatio(1, contentMode: .fit)
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }
}

/// Looks like this › More: the similar photos, three to a row, most alike first.
struct SimilarPageView: View {
    let items: [MediaItem]
    let open: (MediaItem) -> Void

    var body: some View {
        ScrollView {
            if items.isEmpty { PeopleMessage(text: String(localized: "Nothing found. Try other words.")) }
            PhotoGrid(items: items, open: open)
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationTitle("Looks like this")
    }
}

// MARK: - The viewer's details

/// What the server found in a photo, in the viewer's details: the people in it, the text read in it, and photos that
/// look like it. As Android's ViewerFindings.kt.
struct FindingsCards: View {
    let findings: PhotoFindings
    var openPerson: (String) -> Void = { _ in }
    /// A face tapped: its sheet (This is Mom, Not Mom, Who is it?).
    var tapFace: (FoundFace) -> Void = { _ in }
    var openSimilar: (MediaItem) -> Void = { _ in }
    var moreSimilar: () -> Void = {}

    var body: some View {
        if !findings.people.isEmpty {
            FindingsCard(title: String(localized: "People in this photo")) {
                ScrollView(.horizontal) {
                    HStack(spacing: 4) {
                        ForEach(findings.people, id: \.face.id) { found in
                            let name = found.person?.name ?? found.face.personName
                            Button { tapFace(found) } label: {
                                VStack(spacing: 4) {
                                    FaceAvatar(face: found.face, size: 52)
                                        .overlay(alignment: .bottomTrailing) {
                                            if name == nil {
                                                Text("?").font(.system(size: 12, weight: .bold)).foregroundStyle(LibraryColors.onInverse)
                                                    .frame(width: 20, height: 20).background(LibraryColors.inverse, in: Circle())
                                            }
                                        }
                                    Text(name ?? String(localized: "Who’s this?")).font(.system(size: 13, weight: .medium)).lineLimit(1)
                                        .foregroundStyle(name == nil ? LibraryColors.onInverseMuted : LibraryColors.onInverse)
                                }
                                .frame(width: 72)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
        }
        if !findings.text.isEmpty {
            FindingsCard(title: String(localized: "Text in this photo")) {
                Button { UIPasteboard.general.string = findings.text.joined(separator: "\n") } label: {
                    Label("Copy", systemImage: "doc.on.doc").font(.subheadline.weight(.semibold))
                }
                .foregroundStyle(LibraryColors.accent)
            } content: {
                VStack(alignment: .leading, spacing: 4) {
                    ForEach(Array(findings.text.enumerated()), id: \.offset) { _, line in
                        Text(line).font(.system(size: 15)).frame(maxWidth: .infinity, alignment: isRTL(line) ? .trailing : .leading)
                            .multilineTextAlignment(isRTL(line) ? .trailing : .leading)
                            .textSelection(.enabled)
                    }
                }
            }
        }
        if !findings.similar.isEmpty {
            FindingsCard(title: String(localized: "Looks like this")) {
                Button(action: moreSimilar) { Label("More", systemImage: "chevron.right").labelStyle(TrailingIcon()).font(.subheadline.weight(.semibold)) }
                    .foregroundStyle(LibraryColors.accent)
            } content: {
                ScrollView(.horizontal) {
                    HStack(spacing: 6) {
                        ForEach(findings.similar.prefix(8), id: \.id) { item in
                            MediaTileView(item: item, badgeSize: 0).frame(width: 76, height: 76).clipShape(RoundedRectangle(cornerRadius: 10))
                                .onTapGesture { openSimilar(item) }
                        }
                    }
                }
                .scrollIndicators(.hidden)
            }
        }
    }

    /// A line mostly in Hebrew or Arabic reads right to left.
    private func isRTL(_ line: String) -> Bool {
        let letters = line.unicodeScalars.filter { CharacterSet.letters.contains($0) }
        let rtl = letters.filter { (0x0590...0x08FF).contains($0.value) }
        return rtl.count * 2 > letters.count
    }
}

private struct TrailingIcon: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 4) { configuration.title; configuration.icon }
    }
}

private struct FindingsCard<Trailing: View, Content: View>: View {
    let title: String
    @ViewBuilder var trailing: () -> Trailing
    @ViewBuilder var content: () -> Content

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text(title).font(.system(size: 13, weight: .semibold))
                Spacer()
                trailing()
            }
            content()
        }
        .padding(14)
        .background(LibraryColors.panelCard, in: RoundedRectangle(cornerRadius: 16))
    }
}

extension FindingsCard where Trailing == EmptyView {
    init(title: String, @ViewBuilder content: @escaping () -> Content) {
        self.init(title: title, trailing: { EmptyView() }, content: content)
    }
}


// MARK: - Correcting faces

/// What a face's sheet can do; see `PeopleController`'s corrections.
struct FaceActions {
    var confirm: (Face, String) -> Void = { _, _ in }
    var notPerson: (Face, String) -> Void = { _, _ in }
    var move: (Face, String) -> Void = { _, _ in }
    var name: (Face, String) -> Void = { _, _ in }
    var openPerson: (String) -> Void = { _ in }
}

/// A face tapped in a photo's details: for a named person "This is Mom ✓", "Not Mom" (which asks "Who is it?") and
/// Open Mom's page; for a face no one is named for, "Who is this?" at once. As Android's `FaceSheet`.
struct FaceSheet: View {
    let found: FoundFace
    let people: [Person]
    let actions: FaceActions
    let done: () -> Void
    @State private var asking: Bool
    @State private var newName = ""

    init(found: FoundFace, people: [Person], actions: FaceActions, asking: Bool = false, done: @escaping () -> Void) {
        self.found = found
        self.people = people
        self.actions = actions
        self.done = done
        let named = found.person.flatMap { $0.isGroup ? nil : $0 }
        _asking = State(initialValue: asking || named == nil)
    }

    private var person: Person? { found.person.flatMap { $0.isGroup ? nil : $0 } }

    var body: some View {
        VStack(spacing: 10) {
            FaceAvatar(face: found.face, size: 112).padding(.top, 20)
            if !asking, let person {
                Text(person.name ?? "").font(.title3.weight(.bold))
                VStack(spacing: 0) {
                    row("checkmark", String(localized: "This is \(person.name ?? "")")) { actions.confirm(found.face, person.id); done() }
                    row("xmark", String(localized: "Not \(person.name ?? "")")) { asking = true }
                    row("chevron.right", String(localized: "Open \(person.name ?? "")’s page")) { actions.openPerson(person.id); done() }
                }
            } else {
                Text(person != nil ? "Who is it?" : "Who is this?").font(.title3.weight(.bold))
                ScrollView(.horizontal) {
                    HStack(spacing: 0) {
                        ForEach(people.filter { $0.id != person?.id }, id: \.id) { other in
                            Button { actions.move(found.face, other.id); done() } label: {
                                VStack(spacing: 4) {
                                    FaceAvatar(face: other.cover, size: 52)
                                    Text(other.name ?? "").font(.system(size: 13)).lineLimit(1)
                                }
                                .frame(width: 76)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                    .padding(.horizontal, 12)
                }
                .scrollIndicators(.hidden)
                HStack(spacing: 10) {
                    TextField("New name", text: $newName)
                        .submitLabel(.done)
                        .onSubmit { submitName() }
                        .padding(.horizontal, 14).frame(height: 48)
                        .background(LibraryColors.raised, in: RoundedRectangle(cornerRadius: 12))
                    Button("Name", action: submitName).fontWeight(.bold).disabled(newName.trimmingCharacters(in: .whitespaces).isEmpty)
                }
                .padding(.horizontal, 16)
                Button(person != nil ? String(localized: "Skip: just not them") : String(localized: "Skip")) {
                    if let person { actions.notPerson(found.face, person.id) }
                    done()
                }
                .foregroundStyle(LibraryColors.muted)
                .padding(.top, 4)
            }
        }
        .foregroundStyle(LibraryColors.onBackground)
        .frame(maxWidth: .infinity)
        .padding(.bottom, 16)
        .presentationDetents([.medium])
    }

    private func submitName() {
        let typed = newName.trimmingCharacters(in: .whitespaces)
        guard !typed.isEmpty else { return }
        actions.name(found.face, typed)
        done()
    }

    private func row(_ symbol: String, _ label: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 16) {
                Image(systemName: symbol).frame(width: 24)
                Text(label).font(.system(size: 16, weight: .semibold))
                Spacer()
            }
            .padding(.horizontal, 24).padding(.vertical, 14)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

/// Edit faces on a person's page: their faces, four to a row; a tap marks one ✗ (not them) or takes the mark back.
struct EditFacesGrid: View {
    let state: PersonState
    @Binding var marked: Set<String>

    var body: some View {
        LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4), spacing: 6) {
            ForEach(state.itemFaces, id: \.second!.id) { pair in
                let face = pair.second!
                FaceSquare(face: face)
                    .overlay {
                        if marked.contains(face.id) {
                            ZStack {
                                LibraryColors.badgeScrim
                                Image(systemName: "xmark").font(.system(size: 16, weight: .bold)).foregroundStyle(.white)
                                    .frame(width: 34, height: 34).background(LibraryColors.error, in: Circle())
                            }
                            .clipShape(RoundedRectangle(cornerRadius: 10))
                            .accessibilityLabel("Marked not them")
                        }
                    }
                    .onTapGesture {
                        if marked.contains(face.id) { marked.remove(face.id) } else { marked.insert(face.id) }
                    }
            }
        }
        .padding(.horizontal, 14)
    }
}

/// A face for `.sheet(item:)`.
struct IdentifiedFace: Identifiable {
    let face: Face
    var id: String { face.id }
}

/// A found face for `.sheet(item:)`.
struct IdentifiedFound: Identifiable {
    let found: FoundFace
    var id: String { found.face.id }
}
