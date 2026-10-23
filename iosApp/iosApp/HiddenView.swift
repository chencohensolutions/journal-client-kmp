import LocalAuthentication
import SwiftUI
import SharedLogic

/// Settings → Private media → Hidden collections on a live `HiddenCollectionsController`, behind the phone's lock.
/// Asks the server when unlocked; nothing is kept on this phone.
struct HiddenView: View {
    @StateObject private var hidden = HiddenViewModel()

    var body: some View {
        PhoneLocked(title: "Hidden collections") {
            HiddenScreen(
                state: hidden.state,
                actions: HiddenActions(
                    retry: hidden.retry, set: hidden.set, setQuery: hidden.setQuery,
                    setNudityHidden: hidden.setNudityHidden, setNudityFromPercent: hidden.setNudityFromPercent
                )
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
    func setNudityHidden(_ hidden: Bool) { controller.setNudityHidden(hidden: hidden) }
    func setNudityFromPercent(_ percent: Int) { controller.setNudityFromPercent(percent: Int32(percent)) }
}

/// What Hidden collections can ask for; see `HiddenCollectionsController`.
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
}

/// Nudity, all of WhatsApp, and screenshots by app, hidden on every device by the server. Drawn from `state` alone, so
/// the UI lab shows it too. See docs/screens/settings.md.
struct HiddenScreen: View {
    let state: HiddenCollectionsState
    let actions: HiddenActions
    /// The nudity slider's value while it is dragged; saved when let go.
    @State private var nudityFrom: Double?

    var body: some View {
        Form {
            Section {
            } footer: {
                Text("Hidden here means hidden on all your devices: not in the library, its counts or search. Nothing is deleted; switch it off to see them again. Previews kept on this phone of what you hide are deleted.")
            }
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
        .navigationTitle("Hidden collections")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
    }

    @ViewBuilder
    private func collections(_ ready: HiddenCollectionsStateReady) -> some View {
        let shown = ready.collections
        if ready.notChanged {
            Section {
            } footer: {
                Text("Not changed: can’t reach your server. Try again when connected to it.").foregroundStyle(LibraryColors.warning)
            }
        }
        nudity(shown.nudity, saving: ready.saving)
        Section {
            row(Text("All WhatsApp photos and videos"), shown.whatsapp, count: String(localized: "\(Int(shown.whatsapp.files)) photos and videos"))
        } header: {
            Text("WhatsApp")
        }
        .listRowBackground(LibraryColors.raised)
        Section {
            row(Text("Without an app name"), shown.screenshotsWithoutApp, count: screenshots(shown.screenshotsWithoutApp))
            if shown.screenshotApps.isEmpty {
                Text("No screenshot on your server names its app yet.").font(.footnote).foregroundStyle(LibraryColors.muted)
            } else {
                HStack {
                    Image(systemName: "magnifyingglass").foregroundStyle(LibraryColors.muted)
                    TextField("Find an app", text: Binding(get: { ready.query }, set: actions.setQuery))
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                        .submitLabel(.search)
                        .foregroundStyle(LibraryColors.onBackground)
                }
                ForEach(ready.shownApps, id: \.name) { app in
                    row(Text(verbatim: app.name), app, count: screenshots(app))
                }
            }
        } header: {
            Text("Screenshots")
        }
        .listRowBackground(LibraryColors.raised)
    }

    /// Nudity: the switch, with how many files score that much (and how many aren't scanned yet), and while on, the
    /// score from which they are hidden, saved when let go.
    private func nudity(_ nudity: HiddenCollections.Nudity, saving: Bool) -> some View {
        let files = String(localized: "\(Int(nudity.files)) photos and videos")
        let count = nudity.notScanned > 0 ? files + " · " + String(localized: "\(Int(nudity.notScanned)) not scanned yet") : files
        let shown = Int(nudityFrom ?? Double(nudity.fromPercent))
        let range = HiddenCollections.NudityCompanion.shared.RANGE
        return Section {
            Toggle(isOn: Binding(get: { nudity.hidden }, set: { if !saving { actions.setNudityHidden($0) } })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Photos and videos with nudity").foregroundStyle(LibraryColors.onBackground)
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

    /// A collection's switch, with how many files it has; on means hidden.
    private func row(_ title: Text, _ collection: HiddenCollections.Collection, count: String) -> some View {
        Toggle(isOn: Binding(get: { collection.hidden }, set: { actions.set(collection, $0) })) {
            VStack(alignment: .leading, spacing: 2) {
                title.foregroundStyle(LibraryColors.onBackground)
                Text(count).font(.footnote).monospacedDigit().foregroundStyle(LibraryColors.muted)
            }
        }
        .tint(LibraryColors.accent)
    }

    private func screenshots(_ collection: HiddenCollections.Collection) -> String {
        String(localized: "\(Int(collection.files)) screenshots")
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
                    Text(lock.secure ? "Locked. Unlock with your phone’s lock to see what is hidden." : "Set a passcode on this phone to open Hidden collections.")
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
