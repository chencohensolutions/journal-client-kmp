import SwiftUI

/// The UI lab, on when the app is launched with `-uiLab YES` (`scripts/ui-lab-ios.sh`, or the scheme's arguments in
/// Xcode), and in Xcode's previews, which have no photo library either; never in a release build.
enum UiLab {
    #if DEBUG
    static let isOn = UserDefaults.standard.bool(forKey: "uiLab") || ProcessInfo.processInfo.environment["XCODE_RUNNING_FOR_PREVIEWS"] == "1"
    #else
    static let isOn = false
    #endif
}

#if DEBUG

/// The UI lab (debug builds only): a library of the app's screens and of suggested designs for them, with made-up
/// data (`LabData` in the shared logic) and placeholder pictures, without the server, the photo library or enrollment.
/// The first page lists the screens (`labScreens`); a screen lists its options, the first being what the app has now.
/// Taps that would reach a controller do nothing. Shake (⌃⌘Z in the Simulator) goes back a page.
/// `./scripts/ui-lab-ios.sh <screen> [option]` opens one directly. See docs/previews.md. As Android's UiLabActivity.
struct UiLabView: View {
    @State private var screenId: String? = UserDefaults.standard.string(forKey: "labScreen").flatMap { id in
        labScreens.contains { $0.id == id } ? id : nil
    }
    @State private var optionId: String? = UserDefaults.standard.string(forKey: "labOption")

    var body: some View {
        let screen = labScreens.first { $0.id == screenId }
        let option = screen?.options.first { $0.id == optionId }
        Group {
            if let option {
                NavigationStack { option.content() }
            } else if let screen {
                LabList(
                    title: screen.title,
                    rows: screen.options.enumerated().map { i, option in LabRow(title: option.title, note: i == 0 ? "Now in the app" : option.note) },
                    onBack: { screenId = nil },
                    onOpen: { optionId = screen.options[$0].id }
                )
            } else {
                LabList(title: "UI lab", rows: labScreens.map { LabRow(title: $0.title, note: optionCount($0.options.count)) }, onBack: nil) {
                    screenId = labScreens[$0].id
                    optionId = nil
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .labShake)) { _ in
            if optionId != nil { optionId = nil } else { screenId = nil }
        }
    }
}

/// A screen in the lab. `options` starts with the screen as the app has it, then the suggestions.
struct LabScreen {
    let id: String
    let title: String
    let options: [LabOption]
}

/// One way to draw a screen: the current one or a suggestion. `id` is what `ui-lab-ios.sh` takes.
struct LabOption {
    let id: String
    let title: String
    var note = ""
    let content: () -> AnyView

    init<Content: View>(id: String, title: String, note: String = "", @ViewBuilder content: @escaping () -> Content) {
        self.id = id
        self.title = title
        self.note = note
        self.content = { AnyView(content()) }
    }
}

private struct LabRow {
    let title: String
    let note: String
}

private func optionCount(_ n: Int) -> String { n == 1 ? "Current only" : "Current + \(n - 1) suggestion\(n > 2 ? "s" : "")" }

private struct LabList: View {
    let title: String
    let rows: [LabRow]
    let onBack: (() -> Void)?
    let onOpen: (Int) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                if let onBack {
                    Button(action: onBack) { Label("UI lab", systemImage: "chevron.left") }
                        .foregroundStyle(LibraryColors.muted)
                        .padding(.horizontal, 20)
                }
                Text(title)
                    .font(.system(size: 28, weight: .bold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .padding(.horizontal, 20)
                    .padding(.top, 16)
                    .padding(.bottom, 8)
                ForEach(Array(rows.enumerated()), id: \.offset) { i, row in
                    Button { onOpen(i) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(row.title).font(.system(size: 17)).foregroundStyle(LibraryColors.onBackground)
                            if !row.note.isEmpty { Text(row.note).font(.system(size: 13)).foregroundStyle(LibraryColors.muted) }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 20)
                        .padding(.vertical, 12)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                }
            }
        }
        .background(LibraryColors.background.ignoresSafeArea())
    }
}

extension Notification.Name {
    static let labShake = Notification.Name("labShake")
}

extension UIWindow {
    /// The lab's way back: a shake (⌃⌘Z in the Simulator).
    open override func motionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        if motion == .motionShake && UiLab.isOn { NotificationCenter.default.post(name: .labShake, object: nil) }
        super.motionEnded(motion, with: event)
    }
}

#Preview("UI lab") {
    UiLabView()
}

#endif
