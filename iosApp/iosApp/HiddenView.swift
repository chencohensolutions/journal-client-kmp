import LocalAuthentication
import SwiftUI
import SharedLogic

/// Settings → Private media → Private settings on a live `HiddenCollectionsController`, behind the phone's lock:
/// "Show private media" (this phone's), then what the server hides on every device. Asks the server when unlocked;
/// nothing of that is kept on this phone.
struct HiddenView: View {
    @ObservedObject var settings: SettingsViewModel
    @StateObject private var hidden = HiddenViewModel()

    var body: some View {
        PhoneLocked(title: "Private settings") {
            HiddenScreen(
                state: hidden.state,
                actions: HiddenActions(
                    retry: hidden.retry, set: hidden.set, setQuery: hidden.setQuery,
                    setNudityHidden: hidden.setNudityHidden, setNudityFromPercent: hidden.setNudityFromPercent,
                    setShowPrivate: settings.setShowPrivateMedia, show: hidden.show
                ),
                showPrivate: settings.state.privateMedia.show
            )
            .task { hidden.load() }
        }
    }
}

/// Bridges the shared Kotlin `HiddenCollectionsController` into SwiftUI; one per opening of the screen.
final class HiddenViewModel: ObservableObject {
    @Published private(set) var state: HiddenCollectionsState

    private let controller = HiddenCollectionsController(library: IosJournal.shared.serverLibrary())
    private var observation: ObservationHandle?

    init() {
        state = controller.currentState
        // The controller delivers updates on the main thread.
        observation = controller.observe { [weak self] newState in
            self?.state = newState
        }
    }

    deinit {
        observation?.cancel()
        controller.close()
    }

    func load() { controller.load() }
    func retry() { controller.retry() }
    func set(_ collection: HiddenCollections.Collection, _ hidden: Bool) { controller.set(collection: collection, hidden: hidden) }
    func setQuery(_ text: String) { controller.setQuery(text: text) }
    func show(_ kind: PrivateKinds.Kind, _ shown: Bool) { controller.show(kind: kind, shown: shown) }
    func setNudityHidden(_ hidden: Bool) { controller.setNudityHidden(hidden: hidden) }
    func setNudityFromPercent(_ percent: Int) { controller.setNudityFromPercent(percent: Int32(percent)) }
}

/// What Private settings can ask for; see `HiddenCollectionsController`.
struct HiddenActions {
    var retry: () -> Void
    /// Hides (true) or shows a collection; saved on the server at once, and ignored while a change is being saved.
    var set: (HiddenCollections.Collection, Bool) -> Void
    /// "Find an app".
    var setQuery: (String) -> Void
    /// Nudity hidden (true) or shown; like `set`.
    var setNudityHidden: (Bool) -> Void
    /// The score from which nudity is hidden, in percent; like `set`.
    var setNudityFromPercent: (Int) -> Void
    /// "Show private media", kept on this phone (not on the server).
    var setShowPrivate: (Bool) -> Void = { _ in }
    /// A kind, or a screenshot app, shown or hidden on every device; like `set`.
    var show: (PrivateKinds.Kind, Bool) -> Void = { _, _ in }
}

/// "Show private media" (this phone's), then what shows on all your devices (option c2 of the UI lab, Oct 27, 2026): one
/// list by kind with a Show switch each (on = shown), the hidden ones included, the most files first, Screenshots as
/// chips of just the app's name; then Nudity with its slider. Drawn from `state` alone, so the UI lab shows it too. As
/// Android's `HiddenContent`; see docs/screens/settings.md.
struct HiddenScreen: View {
    let state: HiddenCollectionsState
    let actions: HiddenActions
    var showPrivate = false
    /// The nudity slider's value while it is dragged; saved when let go.
    @State private var nudityFrom: Double?

    var body: some View {
        Form {
            // This phone's own choice (moved here from Settings, Oct 25, 2026); the rest is the server's, for every device.
            Section {
                Toggle(isOn: Binding(get: { showPrivate }, set: actions.setShowPrivate)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Show private media").foregroundStyle(LibraryColors.onBackground)
                        Text("Photos and videos you marked private. Off: hidden on this phone, and not downloaded.")
                            .font(.footnote)
                            .foregroundStyle(LibraryColors.muted)
                    }
                }
                .tint(LibraryColors.accent)
            } header: {
                Text("Private media")
            }
            .listRowBackground(LibraryColors.raised)
            switch state {
            case let ready as HiddenCollectionsStateReady:
                collections(ready)
            case is HiddenCollectionsStateUnreachable:
                Section {
                    Text("Can’t reach your server right now.").foregroundStyle(LibraryColors.onBackground)
                    Button("Try again", action: actions.retry).foregroundStyle(LibraryColors.onBackground)
                }
                .listRowBackground(LibraryColors.raised)
            default:
                Section {
                    Text("Loading…").foregroundStyle(LibraryColors.muted)
                }
                .listRowBackground(LibraryColors.raised)
            }
        }
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationTitle("Private settings")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
    }

    @ViewBuilder
    private func collections(_ ready: HiddenCollectionsStateReady) -> some View {
        let saving = ready.saving
        let show: (PrivateKinds.Kind, Bool) -> Void = { kind, shown in if !saving { actions.show(kind, shown) } }
        Section {
            ForEach(Array(ready.kinds.kinds.enumerated()), id: \.offset) { _, kind in
                if kind.type == .screenshots {
                    screenshotsRow(kind, apps: ready.kinds.screenshotApps, show: show)
                } else {
                    kindRow(kind, show: show)
                }
            }
        } header: {
            Text("Show on all your devices")
        } footer: {
            VStack(alignment: .leading, spacing: 6) {
                if ready.notChanged {
                    Text("Not changed: can’t reach your server. Try again when connected to it.").foregroundStyle(LibraryColors.warning)
                }
                Text("Turn a kind off to hide it on all your devices: not in the library, its counts or search. Nothing is deleted. This page opens only with the phone's lock.")
            }
        }
        .listRowBackground(LibraryColors.raised)
        nudity(ready.collections.nudity, saving: saving)
    }

    /// A kind's Show switch, with its icon and files; off says it is hidden on all devices.
    private func kindRow(_ kind: PrivateKinds.Kind, show: @escaping (PrivateKinds.Kind, Bool) -> Void) -> some View {
        let files = Int(kind.files).formatted()
        return Toggle(isOn: Binding(get: { kind.shown }, set: { show(kind, $0) })) {
            HStack(spacing: 14) {
                Image(systemName: kind.symbol).foregroundStyle(LibraryColors.muted).frame(width: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: kind.title).fontWeight(.semibold).foregroundStyle(LibraryColors.onBackground)
                    Group {
                        if kind.shown { Text(verbatim: files) } else { Text("\(files) · Hidden on all your devices") }
                    }
                    .font(.footnote)
                    .monospacedDigit()
                    .foregroundStyle(kind.shown ? LibraryColors.muted : LibraryColors.warning)
                }
            }
        }
        .tint(LibraryColors.accent)
    }

    /// Screenshots: its files, then a chip per app it was taken in, just the app's name.
    private func screenshotsRow(_ kind: PrivateKinds.Kind, apps: [PrivateKinds.Kind], show: @escaping (PrivateKinds.Kind, Bool) -> Void) -> some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 14) {
                Image(systemName: kind.symbol).foregroundStyle(LibraryColors.muted).frame(width: 22)
                VStack(alignment: .leading, spacing: 2) {
                    Text(verbatim: kind.title).fontWeight(.semibold).foregroundStyle(LibraryColors.onBackground)
                    Text("\(Int(kind.files).formatted()) · by the app they were taken in")
                        .font(.footnote)
                        .monospacedDigit()
                        .foregroundStyle(LibraryColors.muted)
                }
            }
            FlowLayout(spacing: 8) {
                ForEach(Array(apps.enumerated()), id: \.offset) { _, app in
                    AppPill(app: app) { show(app, !app.shown) }
                }
            }
            .padding(.leading, 36)
        }
        .padding(.vertical, 4)
    }

    /// Nudity: the Show switch (off, so hidden, by default), with how many files score that much (and how many aren't
    /// scanned yet), and while off, the score from which they are hidden, saved when let go.
    private func nudity(_ nudity: HiddenCollections.Nudity, saving: Bool) -> some View {
        let files = String(localized: "\(Int(nudity.files)) photos and videos")
        let count = nudity.notScanned > 0 ? files + " · " + String(localized: "\(Int(nudity.notScanned)) not scanned yet") : files
        let shown = Int(nudityFrom ?? Double(nudity.fromPercent))
        let range = HiddenCollections.NudityCompanion.shared.RANGE
        return Section {
            Toggle(isOn: Binding(get: { !nudity.hidden }, set: { if !saving { actions.setNudityHidden(!$0) } })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Show photos and videos with nudity").foregroundStyle(LibraryColors.onBackground)
                    Text(count).font(.footnote).monospacedDigit().foregroundStyle(LibraryColors.muted)
                }
            }
            .tint(LibraryColors.accent)
            if nudity.hidden {
                VStack(alignment: .leading, spacing: 8) {
                    HStack {
                        Text("Hide from").foregroundStyle(LibraryColors.onBackground)
                        Spacer()
                        Text("\(shown)%").monospacedDigit().foregroundStyle(LibraryColors.onBackground)
                    }
                    Slider(
                        value: Binding(get: { nudityFrom ?? Double(nudity.fromPercent) }, set: { nudityFrom = $0 }),
                        in: Double(range.first)...Double(range.last),
                        step: 5
                    ) { editing in
                        if !editing, let nudityFrom {
                            if !saving { actions.setNudityFromPercent(Int(nudityFrom)) }
                            self.nudityFrom = nil
                        }
                    }
                    .tint(LibraryColors.onBackground)
                    Text("How sure the server’s scan must be that a photo shows nudity to hide it. Lower hides more, including some that show none.")
                        .font(.footnote)
                        .foregroundStyle(LibraryColors.muted)
                }
            }
        } header: {
            Text("Nudity")
        }
        .listRowBackground(LibraryColors.raised)
    }
}

/// A screenshot app as a pill, just its name ("Bumble", "No app name") and its screenshots: filled, with a check, while
/// they are shown; outlined while hidden.
private struct AppPill: View {
    let app: PrivateKinds.Kind
    let toggle: () -> Void

    var body: some View {
        let on = app.shown
        Button(action: toggle) {
            HStack(spacing: 6) {
                if on { Image(systemName: "checkmark").font(.system(size: 12, weight: .semibold)) }
                Group {
                    if app.name.isEmpty { Text("No app name") } else { Text(verbatim: app.name) }
                }
                .font(.system(size: 14, weight: .semibold))
                Text(verbatim: Int(app.files).formatted())
                    .font(.system(size: 13))
                    .foregroundStyle(on ? LibraryColors.onInverseMuted : LibraryColors.muted)
            }
            .foregroundStyle(on ? LibraryColors.onInverse : LibraryColors.onBackground)
            .padding(.horizontal, 12)
            .frame(height: 36)
            .background {
                if on {
                    Capsule().fill(LibraryColors.inverse)
                } else {
                    Capsule().stroke(LibraryColors.muted.opacity(0.45))
                }
            }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(on ? [.isSelected] : [])
    }
}

extension PrivateKinds.Kind {
    /// The kind as the rest of the app names it: a folder by its name, Screenshots, Sent to you, the sound kinds.
    var title: String {
        switch type {
        case .folder: return MediaSource.companion.folder(name: name).title
        case .screenshots: return MediaSource.screenshots.title
        case .sentToYou: return MediaSource.received.title
        case .calls: return AudioKind.call.title
        case .voiceRecordings: return AudioKind.recording.title
        default: return AudioKind.voiceMessage.title
        }
    }

    var symbol: String {
        switch type {
        case .folder: return MediaSource.companion.folder(name: name).symbol
        case .screenshots: return MediaSource.screenshots.symbol
        case .sentToYou: return MediaSource.received.symbol
        case .calls: return AudioKind.call.symbol
        case .voiceRecordings: return AudioKind.recording.symbol
        default: return AudioKind.voiceMessage.symbol
        }
    }
}

/// Shows `content` only after the phone's own lock is passed (Face ID, Touch ID or the passcode), every time the screen
/// opens and again after the app was in the background. Being unlocked is never saved (`ScreenLock`). A phone with no
/// passcode can't open it.
struct PhoneLocked<Content: View>: View {
    let title: LocalizedStringKey
    @ViewBuilder let content: () -> Content
    @StateObject private var lock = PhoneLockModel()
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        Group {
            if lock.unlocked {
                content()
            } else {
                VStack(spacing: 16) {
                    Image(systemName: "lock.fill").font(.system(size: 36)).foregroundStyle(LibraryColors.muted)
                    Text(lock.secure ? "Locked. Unlock with your phone’s lock to see what is hidden." : "Set a passcode on this phone to open Private settings.")
                        .multilineTextAlignment(.center)
                        .foregroundStyle(LibraryColors.onBackground)
                    if lock.secure {
                        Button("Unlock") { Task { await lock.ask() } }
                            .buttonStyle(.borderedProminent)
                            .tint(LibraryColors.inverse)
                            .foregroundStyle(LibraryColors.onInverse)
                    }
                }
                .padding(32)
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(LibraryColors.background.ignoresSafeArea())
                .navigationTitle(title)
                .navigationBarTitleDisplayMode(.inline)
                .toolbarBackground(LibraryColors.background, for: .navigationBar)
            }
        }
        .task { await lock.ask() }
        .onChange(of: scenePhase) { _, phase in
            // The lock prompt itself only makes the app inactive; going to the background locks, coming back asks again.
            if phase == .background {
                lock.wentToBackground()
            } else if phase == .active, lock.askOnReturn {
                Task { await lock.ask() }
            }
        }
    }
}

/// The shared `ScreenLock` and iOS's prompt (`LAContext`).
@MainActor
final class PhoneLockModel: ObservableObject {
    @Published private(set) var unlocked = false
    /// The phone has a passcode (and maybe Face ID or Touch ID); without one the screen can't open.
    let secure = LAContext().canEvaluatePolicy(.deviceOwnerAuthentication, error: nil)
    /// It was locked by going to the background, so it asks again when the app comes back.
    private(set) var askOnReturn = false

    private let lock = ScreenLock()
    private var asking = false

    func ask() async {
        askOnReturn = false
        guard secure, !lock.isUnlocked, !asking else { return }
        asking = true
        let reason = String(localized: "Unlock to see what is hidden.")
        let passed = (try? await LAContext().evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
        asking = false
        if passed { lock.unlocked() }
        unlocked = lock.isUnlocked
    }

    func wentToBackground() {
        lock.wentToBackground()
        unlocked = lock.isUnlocked
        askOnReturn = true
    }
}
