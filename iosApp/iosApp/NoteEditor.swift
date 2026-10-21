import SwiftUI
import SharedLogic

/// What the user writes about one photo: a title (above the photo in Moments) and a paragraph (under it). Below them,
/// once it was edited, "Edited 3 times · last …" opens History, the earlier versions, newest first; one can be read with
/// what changed after it highlighted, and restored (which keeps what it replaces as a version too).
struct NoteEditor: View {
    let note: PhotoNote?
    let onSave: (_ title: String, _ text: String) -> Void
    let onRestore: (NoteVersion) -> Void

    @State private var title: String
    @State private var text: String
    @State private var confirmingDiscard = false
    @Environment(\.dismiss) private var dismiss
    @FocusState private var focus: Field?

    private enum Field { case title, text }

    init(note: PhotoNote?, onSave: @escaping (_ title: String, _ text: String) -> Void, onRestore: @escaping (NoteVersion) -> Void) {
        self.note = note
        self.onSave = onSave
        self.onRestore = onRestore
        _title = State(initialValue: note?.title ?? "")
        _text = State(initialValue: note?.caption ?? "")
    }

    /// Compared as saved (trimmed), so spaces alone don't ask before discarding.
    private var changed: Bool {
        title.trimmingCharacters(in: .whitespacesAndNewlines) != (note?.title ?? "")
            || text.trimmingCharacters(in: .whitespacesAndNewlines) != (note?.caption ?? "")
    }

    var body: some View {
        NavigationStack {
            VStack(alignment: .leading, spacing: 0) {
                TextField("Title", text: $title)
                    .font(.system(size: 22, weight: .semibold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .focused($focus, equals: .title)
                    .submitLabel(.next)
                    .onSubmit { focus = .text }
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .padding(.bottom, 8)
                Divider().overlay(LibraryColors.muted.opacity(0.3)).padding(.horizontal, 16)
                ZStack(alignment: .topLeading) {
                    if text.isEmpty {
                        Text("Write about this photo…")
                            .font(.system(size: 18, design: .serif))
                            .foregroundStyle(LibraryColors.muted)
                            .padding(.horizontal, 17)
                            .padding(.top, 8)
                            .allowsHitTesting(false)
                    }
                    TextEditor(text: $text)
                        .font(.system(size: 18, design: .serif))
                        .scrollContentBackground(.hidden)
                        .focused($focus, equals: .text)
                        .padding(.horizontal, 12)
                }
                if let note, !note.history.isEmpty {
                    NavigationLink {
                        NoteHistoryView(note: note) { version in
                            onRestore(version)
                            dismiss()
                        }
                    } label: {
                        Label(editedLine(note), systemImage: "clock.arrow.circlepath")
                            .font(.footnote)
                            .foregroundStyle(LibraryColors.muted)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(16)
                    }
                }
            }
            .background(LibraryColors.background)
            .navigationTitle("Writing")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { if changed { confirmingDiscard = true } else { dismiss() } }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        onSave(title, text)
                        dismiss()
                    }
                }
            }
            .confirmationDialog("Discard changes?", isPresented: $confirmingDiscard, titleVisibility: .visible) {
                Button("Discard", role: .destructive) { dismiss() }
                Button("Keep editing", role: .cancel) {}
            }
            .onAppear { focus = .title }
        }
        .interactiveDismissDisabled(changed)
    }

    private func editedLine(_ note: PhotoNote) -> String {
        let count = note.history.count
        let last = noteDate(note.writtenAt ?? note.updatedAt)
        return count == 1
            ? String(localized: "Edited 1 time · last \(last)")
            : String(localized: "Edited \(count) times · last \(last)")
    }
}

/// The earlier versions of a photo's writing, newest first.
private struct NoteHistoryView: View {
    let note: PhotoNote
    let onRestore: (NoteVersion) -> Void

    var body: some View {
        let versions = Array(note.history.enumerated().reversed())
        List(versions, id: \.offset) { index, version in
            // What came after this version: the next one, or what is written now.
            let next = index + 1 < note.history.count ? note.history[index + 1] : nil
            NavigationLink {
                NoteVersionView(
                    version: version,
                    laterTitle: next?.title ?? note.title,
                    laterText: next?.text ?? note.caption,
                    onRestore: onRestore
                )
            } label: {
                VStack(alignment: .leading, spacing: 4) {
                    Text(noteDate(version.savedAt)).font(.caption).foregroundStyle(LibraryColors.muted)
                    if version.title.isEmpty {
                        Text("No title").foregroundStyle(LibraryColors.muted)
                    } else {
                        Text(version.title).fontWeight(.semibold).foregroundStyle(LibraryColors.onBackground)
                    }
                    if !version.text.isEmpty {
                        Text(version.text)
                            .font(.system(size: 15, design: .serif))
                            .lineLimit(2)
                            .foregroundStyle(LibraryColors.onBackground)
                    }
                }
                .padding(.vertical, 4)
            }
            .listRowBackground(LibraryColors.raised)
        }
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background)
        .navigationTitle("History")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// One earlier version in full, with what the version after it no longer has highlighted, and Restore.
private struct NoteVersionView: View {
    let version: NoteVersion
    let laterTitle: String
    let laterText: String
    let onRestore: (NoteVersion) -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 12) {
                Text(noteDate(version.savedAt)).font(.footnote).foregroundStyle(LibraryColors.muted)
                if version.title.isEmpty {
                    Text("No title").font(.system(size: 22, weight: .semibold)).foregroundStyle(LibraryColors.muted)
                } else {
                    Text(highlighted(version.title, laterTitle))
                        .font(.system(size: 22, weight: .semibold))
                        .foregroundStyle(LibraryColors.onBackground)
                }
                Text(highlighted(version.text, laterText))
                    .font(.system(size: 18, design: .serif))
                    .lineSpacing(5)
                    .foregroundStyle(LibraryColors.onBackground)
                Button { onRestore(version) } label: {
                    Text("Restore this version").fontWeight(.semibold).frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(LibraryColors.accent)
                .padding(.top, 8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
        }
        .background(LibraryColors.background)
        .navigationBarTitleDisplayMode(.inline)
    }

    /// [earlier] with the parts [later] no longer has on a warm background.
    private func highlighted(_ earlier: String, _ later: String) -> AttributedString {
        WritingDiffKt.changedParts(earlier: earlier, later: later).reduce(into: AttributedString()) { result, part in
            var run = AttributedString(part.text)
            if part.changed { run.backgroundColor = LibraryColors.warning.opacity(0.25) }
            result += run
        }
    }
}

/// An RFC 3339 time as "Oct 21, 2026 at 14:02" (the raw string if it can't be read). The shared code writes fractional
/// seconds, which the plain .iso8601 style rejects; older notes may have none.
private func noteDate(_ value: String) -> String {
    guard let date = (try? Date(value, strategy: Date.ISO8601FormatStyle(includingFractionalSeconds: true)))
        ?? (try? Date(value, strategy: .iso8601)) else { return value }
    return date.formatted(date: .abbreviated, time: .shortened)
}
