import SwiftUI
import SharedLogic

// Settings › Backup › Copies on other disks (Oct 28, 2026, option a of docs/ux-history/backup/2026-10-28-copies.md), as
// on Android's CopiesScreen.kt: Backup's row, and the page it opens. The server keeps plain copies of the user's files
// on other disks (journal-server-go docs/copies.md); the counts come from its last compare of each copy with them.

/// Bridges the process's `ServerCopies` into SwiftUI: asked when Backup opens, and watched while the page shows.
final class CopiesViewModel: ObservableObject {
    static let shared = CopiesViewModel()

    @Published private(set) var state: ServerCopiesState
    private let copies = IosJournal.shared.serverCopies()
    private var observation: ObservationHandle?

    private init() {
        state = copies.currentState
        observation = copies.observe { [weak self] in self?.state = $0 }
    }

    func refresh() { copies.refresh() }
    /// Asks every 3 s while a pass or a compare goes, every 30 s otherwise; cancel the handle on leaving.
    func watch() -> ObservationHandle { copies.watch() }
    func copyNow(_ copy: DataCopy) { copies.doCopyNow(folder: copy.folder) }
    func stop(_ copy: DataCopy) { copies.stop(folder: copy.folder) }
}

/// The page on the live view model, watched while it shows.
struct CopiesView: View {
    @ObservedObject var copies: CopiesViewModel
    @State private var watch: ObservationHandle?

    var body: some View {
        CopiesScreen(copies: copies.state, copyNow: copies.copyNow, stop: copies.stop)
            .onAppear { watch = copies.watch() }
            .onDisappear {
                watch?.cancel()
                watch = nil
            }
    }
}

/// Copies on other disks, a row at the end of Backup's top card: how they all are in a line, opening their page.
struct CopiesRow<Destination: View>: View {
    let copies: ServerCopiesState
    @ViewBuilder let destination: () -> Destination

    var body: some View {
        if let summary = copies.summary {
            let warning = summary is CopiesSummaryNeedsALook
            let color = (copies.copies.map(\.health).max { $0.ordinal < $1.ordinal } ?? .ok).color
            NavigationLink { destination() } label: {
                HStack {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Copies on other disks").foregroundStyle(LibraryColors.onBackground)
                        Text(verbatim: summary.text).font(.footnote).foregroundStyle(warning ? color : LibraryColors.muted)
                    }
                    if warning {
                        Spacer()
                        Circle().fill(color).frame(width: 10, height: 10)
                    }
                }
            }
        }
    }
}

private extension CopiesSummary {
    var text: String {
        switch self {
        case let look as CopiesSummaryNeedsALook: return String(localized: "Needs a look: \(look.folder)")
        case let copying as CopiesSummaryCopying: return String(localized: "Copying to \(copying.folder)…")
        case let all as CopiesSummaryAllUpToDate: return String(localized: "All \(Int(all.count)) copies up to date")
        case let some as CopiesSummarySomeUpToDate: return String(localized: "\(Int(some.upToDate)) of \(Int(some.count)) copies up to date")
        default: return String(localized: "Comparing with your files…")
        }
    }
}

/// Settings › Backup › Copies on other disks: a line on what copies are, then a card per copy with its files in large
/// type, its bar, its state and warnings, and all that is known about it, with Copy now.
struct CopiesScreen: View {
    let copies: ServerCopiesState
    let copyNow: (DataCopy) -> Void
    let stop: (DataCopy) -> Void

    var body: some View {
        SettingsPage(title: "Copies on other disks") {
            Section {
            } footer: {
                VStack(alignment: .leading, spacing: 8) {
                    Text("Your server also keeps your files in these folders, so a failing disk doesn’t take them.")
                    if copies.loaded && copies.copies.isEmpty {
                        Text("No copies yet. They are set up on the server, in config.json.")
                    }
                }
            }
            ForEach(copies.copies, id: \.folder) { copy in
                Section {
                    CopyCard(copy: copy, acting: copies.acting, copyNow: copyNow, stop: stop)
                }
                .listRowBackground(LibraryColors.raised)
            }
            if copies.error != nil || copies.checkedAtMillis != nil {
                Section {
                } footer: {
                    if copies.error != nil {
                        Text("Can’t reach the server right now")
                    } else if let checked = copies.checkedAtMillis {
                        Text("Checked \(whenText(checked.int64Value))")
                    }
                }
            }
        }
    }
}

/// A copy's card. While a pass copies: "141 GB of 412 GB" with "34%" at the line's end, the bar in bytes, then the
/// time left and the files with Stop. Otherwise: the files the copy holds, the bar, its state with Copy now.
private struct CopyCard: View {
    let copy: DataCopy
    let acting: Bool
    let copyNow: (DataCopy) -> Void
    let stop: (DataCopy) -> Void

    var body: some View {
        let error = copy.health == .error
        VStack(alignment: .leading, spacing: 6) {
            HStack {
                Text(verbatim: copy.folder).font(.footnote.weight(.semibold)).foregroundStyle(LibraryColors.muted)
                Spacer()
                CopyMark(copy: copy)
            }
            if let running = copy.running, !running.counting {
                HStack(alignment: .lastTextBaseline, spacing: 6) {
                    Text(verbatim: fileSize(running.doneBytes)).font(.system(size: 28, weight: .semibold)).foregroundStyle(LibraryColors.onBackground)
                    Text("of \(fileSize(running.totalBytes))").font(.subheadline).foregroundStyle(LibraryColors.muted)
                    Spacer()
                    Text(verbatim: "\(Int(running.fraction * 100))%").font(.title3.weight(.semibold)).foregroundStyle(LibraryColors.onBackground)
                }
                .monospacedDigit()
                ProgressView(value: Double(running.fraction)).tint(LibraryColors.onBackground)
                buttonLine(
                    (eta(running).map { Text(verbatim: $0).foregroundColor(LibraryColors.onBackground) + Text(verbatim: " · ") } ?? Text(verbatim: ""))
                        + Text("\(Int(running.doneFiles).formatted()) of \(Int(running.totalFiles).formatted()) files"),
                    color: LibraryColors.muted, font: .footnote
                )
            } else {
                HStack(alignment: .lastTextBaseline, spacing: 6) {
                    Text(verbatim: Int(copy.heldFiles).formatted()).font(.system(size: 28, weight: .semibold)).foregroundStyle(LibraryColors.onBackground)
                    Text("of \(Int(copy.originals).formatted()) files").font(.subheadline).foregroundStyle(LibraryColors.muted)
                }
                .monospacedDigit()
                ProgressView(value: Double(copy.heldFiles) / Double(max(copy.originals, 1)))
                    .tint(error ? copy.health.color : LibraryColors.onBackground)
                buttonLine(Text(verbatim: status), color: error ? copy.health.color : LibraryColors.onBackground, font: .subheadline)
            }
            if copy.failedFiles > 0 {
                Text("\(Int(copy.failedFiles)) files couldn’t be copied. They’re tried again at the next pass.")
                    .font(.footnote).foregroundStyle(LibraryColors.warning)
            }
            if copy.sameDisk {
                Text("On the same disk as your server’s files: a failing disk would take both.")
                    .font(.footnote).foregroundStyle(LibraryColors.warning)
            }
            Divider().padding(.vertical, 4)
            VStack(alignment: .leading, spacing: 2) {
                ForEach(details, id: \.self) { Text(verbatim: $0) }
            }
            .font(.footnote)
            .foregroundStyle(LibraryColors.muted)
        }
        .padding(.vertical, 6)
    }

    /// A line with the card's button at its end: Stop while a pass goes, else Copy now (not while the folder is gone).
    private func buttonLine(_ text: Text, color: Color, font: Font) -> some View {
        HStack {
            text.font(font).foregroundStyle(color)
            Spacer(minLength: 8)
            if copy.running != nil {
                Button("Stop") { stop(copy) }.disabled(acting)
            } else if copy.problem != .folderMissing {
                Button("Copy now") { copyNow(copy) }.disabled(acting)
            }
        }
        .buttonStyle(.borderless)
        .foregroundStyle(LibraryColors.onBackground)
    }

    /// The copy's state in a line: can't be found, full, copying, comparing, up to date, or what it lacks.
    private var status: String {
        if copy.problem == .folderMissing {
            return String(localized: "Can’t find this folder since \(whenText(copy.problemSinceMillis?.int64Value ?? 0)). Is the disk plugged in?")
        }
        if copy.problem == .diskFull { return String(localized: "The disk is full: copying stopped.") }
        if copy.running != nil { return String(localized: "Finding what to copy…") }
        if copy.checkedAtMillis == nil { return String(localized: "Comparing with your files…") }
        if copy.missingFiles == 0 { return String(localized: "Up to date") }
        return String(localized: "\(Int(copy.missingFiles)) files to copy (\(fileSize(copy.missingBytes)))")
    }

    /// When it runs, its last pass, its disk's free space, and what only it holds.
    private var details: [String] {
        var lines = [copy.realtime ? String(localized: "As files arrive, and daily at \(copy.dailyAt)") : String(localized: "Daily at \(copy.dailyAt)")]
        if let pass = copy.lastPass {
            let at = whenText(pass.finishedAtMillis)
            let files = Int(pass.files).formatted()
            let size = fileSize(pass.bytes)
            lines.append(pass.stopped
                ? String(localized: "Last pass \(at), stopped · \(files) files, \(size)")
                : String(localized: "Last pass \(at) · \(files) files, \(size)"))
        } else {
            lines.append(String(localized: "No pass since the server started"))
        }
        if let free = copy.freeBytes { lines.append(String(localized: "\(fileSize(free.int64Value)) free on the disk")) }
        if copy.inCopyOnly > 0 { lines.append(String(localized: "\(Int(copy.inCopyOnly)) files are only in the copy (gone from the server; kept)")) }
        return lines
    }

    /// "About 48 min left", "About 2 h 10 min left", "Less than a minute left"; nil until the pass's rate is known.
    private func eta(_ running: CopyRunning) -> String? {
        let now = Int64(Date().timeIntervalSince1970 * 1000)
        guard let remaining = running.remainingMillis(nowMillis: now)?.int64Value else { return nil }
        let minutes = Int(remaining / 60_000)
        if minutes < 1 { return String(localized: "Less than a minute left") }
        if minutes < 60 { return String(localized: "About \(minutes) min left") }
        return String(localized: "About \(minutes / 60) h \(minutes % 60) min left")
    }
}

/// The copy's mark beside its name: a dot for what needs a look, a check when up to date; none while copying.
private struct CopyMark: View {
    let copy: DataCopy

    var body: some View {
        let health = copy.health
        if health == .error || (copy.running == nil && !copy.upToDate && health == .warn) {
            Circle().fill(health.color).frame(width: 10, height: 10)
        } else if copy.running == nil && copy.upToDate {
            Image(systemName: "checkmark").font(.footnote.weight(.semibold)).foregroundStyle(LibraryColors.onBackground)
        }
    }
}

private extension CopyHealth {
    var color: Color {
        switch self {
        case .busy: return LibraryColors.muted
        case .warn: return LibraryColors.warning
        case .error: return LibraryColors.error
        default: return LibraryColors.onBackground
        }
    }
}

/// A time today ("03:30"), or the day of another ("Oct 25").
private func whenText(_ millis: Int64) -> String {
    let date = Date(timeIntervalSince1970: TimeInterval(millis) / 1000)
    return Calendar.current.isDateInToday(date)
        ? date.formatted(date: .omitted, time: .shortened)
        : date.formatted(.dateTime.month(.abbreviated).day())
}
