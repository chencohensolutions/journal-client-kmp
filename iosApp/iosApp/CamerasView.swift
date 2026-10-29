import SwiftUI
import SharedLogic

// Settings › Library › Your cameras, as on Android's CamerasScreen.kt: every camera that took the user's photos, the
// most photos first, each with a Mine switch. Turning one off asks first ("312 photos will move to Sent to you"); while
// the server moves them the row says how many are left, with a bar. See docs/screens/settings.md.

/// Bridges a `CamerasController` (one per opening of the page) into SwiftUI.
final class CamerasViewModel: ObservableObject {
    @Published private(set) var state: CamerasState
    private let controller: CamerasController
    private var observation: ObservationHandle?

    init() {
        controller = CamerasController(library: IosJournal.shared.serverLibrary())
        state = controller.currentState
        observation = controller.observe { [weak self] in self?.state = $0 }
    }

    deinit {
        observation?.cancel()
        controller.close()
    }

    func load() { controller.load() }
    func retry() { controller.retry() }
    func setMine(_ camera: CameraGroup, _ mine: Bool) { controller.setMine(camera: camera, mine: mine) }
}

/// The page on its own view model; `onState` gets each state, for Settings' row.
struct CamerasView: View {
    var onState: (CamerasState) -> Void = { _ in }
    @StateObject private var cameras = CamerasViewModel()

    var body: some View {
        CamerasScreen(state: cameras.state, setMine: cameras.setMine, retry: cameras.retry)
            .onAppear { cameras.load() }
            .onReceive(cameras.$state) { onState($0) }
    }
}

/// Drawn from the state alone.
struct CamerasScreen: View {
    let state: CamerasState
    let setMine: (CameraGroup, Bool) -> Void
    let retry: () -> Void
    /// The camera whose Mine was turned off, waiting for Move; by name, as the rows are.
    @State private var asking: String?

    var body: some View {
        let ready = state as? CamerasStateReady
        let confirming = asking.flatMap { name in ready?.cameras.first { $0.name == name } }
        SettingsPage(title: "Your cameras") {
            Section {
            } footer: {
                Text("Every camera that took your photos and videos, as their tags say. Turn off one that isn’t yours: its photos move to Sent to you, on all your devices.")
            }
            if let ready {
                if ready.notChanged {
                    Section {
                        Text("Not changed: can’t reach your server. Try again when connected to it.").foregroundStyle(LibraryColors.warning)
                    }
                    .listRowBackground(LibraryColors.raised)
                }
                Section {
                    ForEach(ready.cameras, id: \.name) { camera in
                        CameraRow(camera: camera, saving: ready.saving) { mine in
                            if mine { setMine(camera, true) } else { asking = camera.name }
                        }
                    }
                } footer: {
                    Text("A camera you haven’t marked counts as yours, so nothing moves until you choose.")
                }
                .listRowBackground(LibraryColors.raised)
            } else if state is CamerasStateUnreachable {
                Section {
                    Text("Can’t reach your server right now.").foregroundStyle(LibraryColors.onBackground)
                    Button("Try again", action: retry).foregroundStyle(LibraryColors.onBackground)
                }
                .listRowBackground(LibraryColors.raised)
            } else {
                Section {
                    Text("Loading…").foregroundStyle(LibraryColors.muted)
                }
                .listRowBackground(LibraryColors.raised)
            }
        }
        .alert(
            confirming.map { Text("\(Int($0.files)) photos will move to Sent to you") } ?? Text(verbatim: ""),
            isPresented: Binding(get: { confirming != nil }, set: { if !$0 { asking = nil } }),
            presenting: confirming
        ) { camera in
            Button("Move") {
                setMine(camera, false)
                asking = nil
            }
            Button("Cancel", role: .cancel) { asking = nil }
        } message: { camera in
            Text("They stay on your server and in the app, under Sent to you instead of Camera. Mark the \(camera.name) yours again to bring them back.")
        }
    }
}

/// A camera: its name, "1,240 · 2025–2026", whether its photos are sent to you, and Mine; while moving, what is left.
private struct CameraRow: View {
    let camera: CameraGroup
    let saving: Bool
    let setMine: (Bool) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            Toggle(isOn: Binding(get: { camera.mine }, set: setMine)) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: camera.name).fontWeight(.semibold).foregroundStyle(LibraryColors.onBackground)
                    Text(verbatim: [Int(camera.files).formatted(), years].compactMap { $0 }.joined(separator: " · "))
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundStyle(LibraryColors.muted)
                    if !camera.mine && camera.pending == 0 {
                        Text("Its photos are in Sent to you").font(.footnote).foregroundStyle(LibraryColors.muted)
                    }
                }
            }
            .tint(LibraryColors.accent)
            .disabled(saving)
            .accessibilityHint(Text("Mine"))
            if camera.pending > 0 {
                Text("Moving \(Int(camera.pending)) photos…").font(.footnote).foregroundStyle(LibraryColors.onBackground)
                ProgressView(value: 1 - Double(camera.pending) / Double(max(camera.files, 1)))
                    .tint(LibraryColors.onBackground)
            }
        }
        .padding(.vertical, 2)
    }

    /// "2025–2026", "2014"; nil without years. Isolated left to right, so Hebrew doesn't turn them around.
    private var years: String? {
        guard let first = camera.firstYear?.intValue else { return nil }
        let last = camera.lastYear?.intValue ?? first
        return first == last ? "\(first)" : "\u{2066}\(first)–\(last)\u{2069}"
    }
}

extension CamerasState {
    /// Your cameras' row: "4 cameras · 1 not yours", "4 cameras", "Moving 312 photos…", or "Which cameras were yours"
    /// before the list came.
    var rowText: String {
        guard let ready = self as? CamerasStateReady else { return String(localized: "Which cameras were yours") }
        if ready.pending > 0 { return String(localized: "Moving \(Int(ready.pending)) photos…") }
        let count = String(localized: "\(ready.cameras.count) cameras")
        let notYours = ready.cameras.filter { !$0.mine }.count
        return notYours == 0 ? count : "\(count) · " + String(localized: "\(notYours) not yours")
    }
}
