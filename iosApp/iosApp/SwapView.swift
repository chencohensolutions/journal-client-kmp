import SharedLogic
import SwiftUI

// Settings › Backup › Swap with a friend (docs/screens/swap.md), as on Android's SwapScreen.kt: the user's swaps both
// ways and the offers; "Keep a friend's copy here" makes an invite (QR code, Share, countdown); "Keep my copy at a
// friend's" joins one (scan or paste, name, six words shown once or plain, two typed back, an offer back).

/// Bridges the process's `SwapController` into SwiftUI: asked when Backup opens, and watched while a Swap page shows.
final class SwapViewModel: ObservableObject {
    static let shared = SwapViewModel()

    @Published private(set) var state: SwapState
    let swaps = IosJournal.shared.swaps()
    private var observation: ObservationHandle?

    private init() {
        state = swaps.currentState
        observation = swaps.observe { [weak self] in self?.state = $0 }
    }

    func refresh() { swaps.refresh() }
    func watch() -> ObservationHandle { swaps.watch() }
    func now() -> Int64 { swaps.nowMillis() }
}

/// Backup's "Swap with a friend" row: how many friends, an offer waiting, or what it is.
struct SwapRow<Destination: View>: View {
    let swap: SwapState
    @ViewBuilder let destination: () -> Destination

    var body: some View {
        let friends = Set(swap.mine.map(\.name) + swap.kept.filter { !$0.revoked }.map(\.name)).count
        NavigationLink { destination() } label: {
            VStack(alignment: .leading, spacing: 2) {
                Text("Swap with a friend").foregroundStyle(LibraryColors.onBackground)
                Group {
                    if let offer = swap.offers.first {
                        Text("\(offer.name) offers to keep your copy")
                    } else if friends > 0 {
                        Text("\(friends) friends")
                    } else {
                        Text("Keep your copy at a friend’s, and theirs here")
                    }
                }
                .font(.footnote)
                .foregroundStyle(LibraryColors.muted)
            }
        }
    }
}

/// Asks the server while the page shows (every 3 s while an invite shows, else every 30 s), and ticks the clock.
private struct SwapWatched: ViewModifier {
    let model: SwapViewModel
    @Binding var now: Int64
    @State private var watch: ObservationHandle?
    private let timer = Timer.publish(every: 1, on: .main, in: .common).autoconnect()

    func body(content: Content) -> some View {
        content
            .onAppear {
                now = model.now()
                if watch == nil { watch = model.watch() }
            }
            .onDisappear {
                watch?.cancel()
                watch = nil
            }
            .onReceive(timer) { _ in now = model.now() }
    }
}

extension View {
    fileprivate func swapWatched(_ model: SwapViewModel, now: Binding<Int64>) -> some View { modifier(SwapWatched(model: model, now: now)) }
}

/// The Swap page on the live view model.
struct SwapView: View {
    @ObservedObject var model: SwapViewModel
    @State private var now: Int64 = 0
    @StateObject private var draft = JoinDraft()
    @State private var joining = false
    @State private var offering = false
    @State private var accepting: SwapOffer?
    @State private var removing: MySwap?
    @State private var revoking: KeptSwap?

    var body: some View {
        let state = model.state
        SettingsPage(title: "Swap with a friend") {
            Section {
            } footer: {
                Text("Keep a copy of your journal on a friend’s journal server, and theirs on yours. If a disk or a whole PC is lost, the copy at your friend’s brings it back.")
            }
            if state.unsupported {
                Section {
                } footer: {
                    Text("Your server can’t swap yet: it needs journal-server 1.1.0 or later.")
                }
            } else {
                Section {
                    Button {
                        draft.reset()
                        model.swaps.clearOutcome()
                        joining = true
                    } label: {
                        PageLabel(title: String(localized: "Keep my copy at a friend’s"), detail: String(localized: "Scan your friend’s QR code, or paste their link"))
                    }
                    if state.canKeepFriends {
                        Button {
                            model.swaps.closeInvite()
                            offering = true
                        } label: {
                            PageLabel(title: String(localized: "Keep a friend’s copy here"),
                                detail: String(localized: "\(fileSize(state.info?.freeSpace?.int64Value ?? 0)) free on your server"))
                        }
                    }
                }
                .listRowBackground(LibraryColors.raised)
                ForEach(state.offers, id: \.id) { offer in
                    Section {
                        OfferCard(offer: offer, now: now, acting: state.acting) {
                            draft.reset(offer: offer)
                            model.swaps.clearOutcome()
                            accepting = offer
                        } decline: {
                            model.swaps.decline(offer: offer)
                        }
                    }
                    .listRowBackground(LibraryColors.raised)
                }
                if !state.mine.isEmpty {
                    Section("My copy at friends’") {
                        ForEach(state.mine, id: \.name) { swap in MySwapRow(swap: swap, acting: state.acting) { removing = swap } }
                    }
                    .listRowBackground(LibraryColors.raised)
                }
                if !state.kept.isEmpty {
                    Section("Friends’ copies here") {
                        ForEach(state.kept, id: \.name) { swap in KeptSwapRow(swap: swap, acting: state.acting) { revoking = swap } }
                    }
                    .listRowBackground(LibraryColors.raised)
                }
                if state.info?.copyKey == CopyKeyKind.passphrase || PassphraseKeychain.kept {
                    Section("Copy passphrase") {
                        NavigationLink {
                            PhoneLocked(title: "Show my copy passphrase", reason: String(localized: "Unlock to see your copy passphrase."),
                                lockedText: "Locked. Unlock with Face ID or your passcode to see your copy passphrase.",
                                noPasscodeText: "Set a passcode on this iPhone to see your copy passphrase.") {
                                CopyPassphraseView(friend: state.mine.first { $0.encrypted }?.name ?? "", serverId: state.info?.serverId)
                            }
                        } label: {
                            SettingsLabel("Show my copy passphrase", detail: "Behind Face ID or your passcode")
                        }
                    }
                    .listRowBackground(LibraryColors.raised)
                }
                if state.error != nil {
                    Section {
                    } footer: {
                        Text("Can’t reach your server. What shows is from before.")
                    }
                }
            }
        }
        .navigationDestination(isPresented: $joining) { SwapThereView(model: model, draft: draft) }
        .navigationDestination(isPresented: $offering) { SwapHereView(model: model) }
        .navigationDestination(item: $accepting) { _ in SwapThereView(model: model, draft: draft) }
        .confirmationDialog(
            String(localized: "Stop keeping your copy at \(removing?.name ?? "")’s?"),
            isPresented: Binding(get: { removing != nil }, set: { if !$0 { removing = nil } }),
            titleVisibility: .visible
        ) {
            Button("Remove", role: .destructive) {
                if let swap = removing { model.swaps.remove(name: swap.name) }
                removing = nil
            }
        } message: {
            Text("Nothing more goes to \(removing?.name ?? "")’s server. The files already there stay until \(removing?.name ?? "") removes them.")
        }
        .confirmationDialog(
            String(localized: "Stop keeping \(revoking?.name ?? "")’s copy?"),
            isPresented: Binding(get: { revoking != nil }, set: { if !$0 { revoking = nil } }),
            titleVisibility: .visible
        ) {
            if let swap = revoking {
                if !swap.revoked {
                    Button("Revoke", role: .destructive) { model.swaps.revoke(name: swap.name, deleteFiles: false); revoking = nil }
                }
                Button(String(localized: "Revoke and delete their files (\(fileSize(swap.used)))"), role: .destructive) {
                    model.swaps.revoke(name: swap.name, deleteFiles: true)
                    revoking = nil
                }
            }
        } message: {
            Text("\(revoking?.name ?? "")’s server will be refused from now on.")
        }
        .swapWatched(model, now: $now)
    }
}

/// A row that opens a page, as a button: its title, a line, and a chevron.
private struct PageLabel: View {
    let title: String
    let detail: String

    var body: some View {
        HStack {
            SettingsLabel(verbatim: title, detail: detail)
            Spacer()
            Image(systemName: "chevron.forward").font(.footnote.weight(.semibold)).foregroundStyle(LibraryColors.muted)
        }
        .contentShape(Rectangle())
    }
}

/// "hen offers to keep your copy too (1 TB)", with the minutes left, Accept and Decline.
private struct OfferCard: View {
    let offer: SwapOffer
    let now: Int64
    let acting: Bool
    let accept: () -> Void
    let decline: () -> Void

    var body: some View {
        let minutes = Int(max(0, (offer.expiresAtMillis - now) / 60_000)) + 1
        VStack(alignment: .leading, spacing: 6) {
            Text("\(offer.name) offers to keep your copy too (\(fileSize(offer.quota)))").font(.headline).foregroundStyle(LibraryColors.onBackground)
            Text("Accept and your copy goes to \(offer.name)’s server as well. The offer lasts about \(minutes) more min.")
                .font(.footnote).foregroundStyle(LibraryColors.muted)
            HStack {
                Button("Accept", action: accept).buttonStyle(.borderedProminent).tint(LibraryColors.inverse).foregroundStyle(LibraryColors.onInverse)
                Button("Decline", action: decline).buttonStyle(.borderless).foregroundStyle(LibraryColors.onBackground)
            }
            .disabled(acting)
        }
        .padding(.vertical, 4)
    }
}

private struct MySwapRow: View {
    let swap: MySwap
    let acting: Bool
    let remove: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 2) {
                HStack(spacing: 6) {
                    Text(verbatim: swap.name).font(.headline).foregroundStyle(LibraryColors.onBackground)
                    if swap.encrypted { Image(systemName: "lock.fill").font(.caption).foregroundStyle(LibraryColors.muted) }
                }
                Text(verbatim: status).font(.footnote).foregroundStyle(swap.dataCopy?.problem != nil ? LibraryColors.error : LibraryColors.muted)
                Text(verbatim: swap.encrypted
                    ? String(localized: "Encrypted · up to \(fileSize(swap.quota))")
                    : String(localized: "Plain: your friend can see it · up to \(fileSize(swap.quota))"))
                    .font(.footnote).foregroundStyle(LibraryColors.muted)
            }
            Spacer()
            Button("Remove", action: remove).buttonStyle(.borderless).foregroundStyle(LibraryColors.onBackground).disabled(acting)
        }
    }

    private var status: String {
        guard let copy = swap.dataCopy else { return String(localized: "Can’t tell how the copy is doing") }
        if copy.problem == .peerUnreachable { return String(localized: "Your friend’s server can’t be reached") }
        if copy.problem == .quotaFull { return String(localized: "No room left on the friend’s server: copying stopped.") }
        if copy.running != nil { return String(localized: "Copying…") }
        if copy.checkedAtMillis == nil { return String(localized: "Comparing with your files…") }
        return String(localized: "\(Int(copy.heldFiles).formatted()) of \(Int(copy.originals).formatted()) files")
    }
}

private struct KeptSwapRow: View {
    let swap: KeptSwap
    let acting: Bool
    let revoke: () -> Void

    var body: some View {
        HStack {
            VStack(alignment: .leading, spacing: 4) {
                Text(verbatim: swap.name).font(.headline).foregroundStyle(LibraryColors.onBackground)
                Text("\(fileSize(swap.used)) of \(fileSize(swap.quota)) used").font(.footnote).foregroundStyle(LibraryColors.muted)
                ProgressView(value: Double(swap.fraction)).tint(LibraryColors.onBackground)
                if swap.revoked {
                    Text("Revoked: their server is refused. Their files are still here.").font(.footnote).foregroundStyle(LibraryColors.warning)
                }
            }
            Spacer(minLength: 12)
            Button(swap.revoked ? "Delete files" : "Revoke", action: revoke).buttonStyle(.borderless).foregroundStyle(LibraryColors.onBackground).disabled(acting)
        }
    }
}

// MARK: - Keep a friend's copy here

/// The friend's name and the quota, then the QR code with the code, the countdown, Share and Copy; once their server
/// joined, that and their offer back.
struct SwapHereView: View {
    @ObservedObject var model: SwapViewModel
    @State private var name = ""
    @State private var quota: Int64 = 0
    @State private var clock: Int64 = 0
    @State private var accepting: SwapOffer?
    @StateObject private var draft = JoinDraft()
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let state = model.state
        SettingsPage(title: "Keep a friend’s copy here") {
            if let invite = state.invite {
                Section {
                    inviteCard(invite)
                }
                .listRowBackground(LibraryColors.raised)
                if let offer = state.inviteOffer {
                    Section {
                        OfferCard(offer: offer, now: clock, acting: state.acting) {
                            draft.reset(offer: offer)
                            model.swaps.clearOutcome()
                            accepting = offer
                        } decline: {
                            model.swaps.decline(offer: offer)
                        }
                    }
                    .listRowBackground(LibraryColors.raised)
                }
                if invite.joined {
                    Section {
                        Button("Done") {
                            model.swaps.closeInvite()
                            dismiss()
                        }
                    }
                    .listRowBackground(LibraryColors.raised)
                }
            } else {
                let free = state.info?.freeSpace?.int64Value ?? 0
                Section {
                    TextField("Your friend’s name", text: $name).textInputAutocapitalization(.words)
                    if state.nameTaken(name: name) {
                        Text("You already have a friend of that name").font(.footnote).foregroundStyle(LibraryColors.error)
                    }
                } footer: {
                    Text("Your friend’s server keeps their copy on your server’s disk, up to the space you give them. You can’t see an encrypted copy, only its size.")
                }
                .listRowBackground(LibraryColors.raised)
                Section {
                    QuotaPicker(free: free, quota: $quota)
                } header: {
                    Text("How much to keep for them")
                } footer: {
                    Text("\(fileSize(free)) free on your server’s disk")
                }
                .listRowBackground(LibraryColors.raised)
                Section {
                    Button {
                        model.swaps.invite(name: name, quota: quota)
                    } label: {
                        HStack {
                            Text("Make the invite")
                            if state.acting { Spacer(); ProgressView() }
                        }
                    }
                    .disabled(name.trimmingCharacters(in: .whitespaces).isEmpty || state.nameTaken(name: name) || quota <= 0 || state.acting)
                } footer: {
                    if let error = state.error { Text(verbatim: error) }
                }
                .listRowBackground(LibraryColors.raised)
                .onAppear { if quota == 0 { quota = SwapQuota.shared.suggested(freeSpace: free) } }
            }
        }
        .navigationDestination(item: $accepting) { _ in SwapThereView(model: model, draft: draft) }
        .swapWatched(model, now: $clock)
    }

    @ViewBuilder
    private func inviteCard(_ invite: ShownInvite) -> some View {
        VStack(spacing: 10) {
            if invite.joined {
                Image(systemName: "checkmark.circle").font(.system(size: 40)).foregroundStyle(LibraryColors.onBackground)
                Text("\(invite.name)’s server joined").font(.headline).foregroundStyle(LibraryColors.onBackground)
                Text("Their copy is kept here now, up to \(fileSize(invite.quota)).").font(.footnote).foregroundStyle(LibraryColors.muted)
            } else if invite.expired(nowMillis: clock) {
                Text("The code expired. Make a new one while you talk.").font(.headline).multilineTextAlignment(.center)
                Button("Make a new invite") { model.swaps.closeInvite() }.buttonStyle(.borderless)
            } else {
                Text("Have \(invite.name) scan this in Journal").font(.headline).foregroundStyle(LibraryColors.onBackground).multilineTextAlignment(.center)
                if let image = qrImage(invite.link) {
                    Image(uiImage: image).interpolation(.none).resizable().frame(width: 240, height: 240)
                        .padding(8).background(Color.white, in: RoundedRectangle(cornerRadius: 8))
                        .accessibilityLabel("QR code of the invite")
                }
                Text(verbatim: invite.code).font(.title3.monospaced()).foregroundStyle(LibraryColors.onBackground)
                let left = invite.secondsLeft(nowMillis: clock)
                Text("Works for \(Int(left / 60)):\(String(format: "%02d", Int(left % 60))), once").font(.footnote).foregroundStyle(LibraryColors.muted).monospacedDigit()
                HStack {
                    Button("Share") {
                        presentShareSheet([String(localized: "Keep your journal’s copy on my server: open this in Journal within 10 minutes.\n\(invite.link)")])
                    }
                    .buttonStyle(.borderedProminent).tint(LibraryColors.inverse).foregroundStyle(LibraryColors.onInverse)
                    Button("Copy link") { copyToPasteboard(invite.link, sensitive: false) }.buttonStyle(.bordered).foregroundStyle(LibraryColors.onBackground)
                }
                Text("This page shows when \(invite.name)’s server joins.").font(.footnote).foregroundStyle(LibraryColors.muted).multilineTextAlignment(.center)
            }
        }
        .frame(maxWidth: .infinity)
        .padding(.vertical, 8)
    }
}

extension SwapOffer: @retroactive Identifiable {}

/// Chips of quotas up to the free space ("All 1.8 TB" last).
private struct QuotaPicker: View {
    let free: Int64
    @Binding var quota: Int64

    var body: some View {
        let choices = SwapQuota.shared.choices(freeSpace: free).map(\.int64Value)
        ScrollView(.horizontal, showsIndicators: false) {
            HStack {
                ForEach(choices, id: \.self) { q in
                    Button {
                        quota = q
                    } label: {
                        Text(verbatim: q == free ? String(localized: "All \(fileSize(q))") : fileSize(q))
                            .padding(.horizontal, 12).padding(.vertical, 6)
                            .background(q == quota ? LibraryColors.inverse : Color.clear, in: Capsule())
                            .overlay(Capsule().stroke(LibraryColors.muted.opacity(0.5)))
                            .foregroundStyle(q == quota ? LibraryColors.onInverse : LibraryColors.onBackground)
                    }
                    .buttonStyle(.plain)
                }
            }
        }
    }
}

// MARK: - Keep my copy at a friend's (and accepting an offer back)

enum JoinStep { case link, protect, words, check, result }

/// What the user has entered so far; kept while the pages show, never saved.
final class JoinDraft: ObservableObject, Identifiable {
    @Published var offer: SwapOffer?
    @Published var step: JoinStep = .link
    @Published var linkText = ""
    @Published var name = ""
    @Published var plain = false
    @Published var typed = ""
    @Published var suggested: SuggestedPassphrase?
    @Published var check1 = ""
    @Published var check2 = ""
    @Published var offerBack = false
    @Published var offerQuota: Int64 = 0

    @discardableResult
    func reset(offer: SwapOffer? = nil) -> JoinDraft {
        self.offer = offer
        step = offer == nil ? .link : .protect
        linkText = ""
        name = offer?.name ?? ""
        plain = false
        typed = ""
        suggested = nil
        check1 = ""
        check2 = ""
        offerBack = false
        offerQuota = 0
        return self
    }

    var link: SwapLink? { SwapLinkKt.parseSwapLink(text: linkText) }

    /// The passphrase joinSwap gets: the suggested words for a new copy key, else what was typed; nil for plain.
    func passphrase(_ kind: CopyKeyKind?) -> String? {
        if plain || kind == CopyKeyKind.keyFile { return nil }
        if kind == CopyKeyKind.none { return suggested?.text }
        let t = typed.trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : t
    }
}

struct SwapThereView: View {
    @ObservedObject var model: SwapViewModel
    @ObservedObject var draft: JoinDraft
    @State private var now: Int64 = 0
    @State private var scanning = false
    @State private var noScanner = false
    @State private var saved = false
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let state = model.state
        let kind = state.info?.copyKey
        Form {
            switch draft.step {
            case .link: linkStep(state)
            case .protect: protectStep(state, kind)
            case .words: wordsStep()
            case .check: checkStep(state)
            case .result: resultStep(state)
            }
        }
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationTitle(draft.offer.map { String(localized: "Keep my copy at \($0.name)’s") } ?? String(localized: "Keep my copy at a friend’s"))
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
        .sheet(isPresented: $scanning) {
            QRCodeScannerSheet { text in
                draft.linkText = text
                if draft.name.isEmpty, let from = SwapLinkKt.parseSwapLink(text: text)?.from { draft.name = from }
            }
        }
        .alert("This iPhone can’t scan here. Paste the link instead.", isPresented: $noScanner) { Button("OK") {} }
        .swapWatched(model, now: $now)
        .onChange(of: (state.outcome as? SwapOutcomeJoined)?.swap.name) { _, name in
            // A new copy key from the suggested words, or the same passphrase typed: kept in iCloud Keychain.
            guard name != nil, let joined = model.state.outcome as? SwapOutcomeJoined, joined.swap.encrypted else { return }
            let passphrase = draft.suggested?.text ?? draft.passphrase(CopyKeyKind.passphrase)
            if let passphrase { saved = PassphraseKeychain.save(passphrase, serverId: state.info?.serverId) }
        }
    }

    @ViewBuilder
    private func linkStep(_ state: SwapState) -> some View {
        Section {
            Button("Scan the QR code") {
                Task { if await QRCodeScannerSheet.canScan() { scanning = true } else { noScanner = true } }
            }
            TextField("journal://swap?…", text: $draft.linkText, axis: .vertical)
                .textInputAutocapitalization(.never).autocorrectionDisabled()
                .onChange(of: draft.linkText) { _, text in
                    if draft.name.isEmpty, let from = SwapLinkKt.parseSwapLink(text: text)?.from { draft.name = from }
                }
            if !draft.linkText.isEmpty && draft.link == nil {
                Text("That isn’t a swap link").font(.footnote).foregroundStyle(LibraryColors.error)
            }
            TextField("Your friend’s name", text: $draft.name).textInputAutocapitalization(.words)
            if state.nameTaken(name: draft.name) {
                Text("You already have a friend of that name").font(.footnote).foregroundStyle(LibraryColors.error)
            }
        } footer: {
            Text("Your friend makes an invite on their phone (Keep a friend’s copy here). Scan its QR code, or paste the link they sent.")
        }
        .listRowBackground(LibraryColors.raised)
        Section {
            Button("Next") { draft.step = .protect }
                .disabled(draft.link == nil || draft.name.trimmingCharacters(in: .whitespaces).isEmpty || state.nameTaken(name: draft.name))
        }
        .listRowBackground(LibraryColors.raised)
    }

    @ViewBuilder
    private func protectStep(_ state: SwapState, _ kind: CopyKeyKind?) -> some View {
        Section("How your copy is kept") {
            Picker(selection: $draft.plain) {
                SettingsLabel(verbatim: String(localized: "Encrypted"),
                    detail: String(localized: "\(draft.name) can’t see your photos, only their sizes. A passphrase opens the copy: lose it and the copy is lost.")).tag(false)
                SettingsLabel(verbatim: String(localized: "Plain, I trust this friend"),
                    detail: String(localized: "\(draft.name) could open your photos. Nothing to remember.")).tag(true)
            } label: {
                EmptyView()
            }
            .pickerStyle(.inline)
            .labelsHidden()
        }
        .listRowBackground(LibraryColors.raised)
        if !draft.plain {
            if kind == CopyKeyKind.keyFile {
                Section {} footer: { Text("Your server has a key file for your copies: nothing to type.") }
            } else if kind == CopyKeyKind.passphrase {
                Section {
                    SecureField("Passphrase", text: $draft.typed).textInputAutocapitalization(.never).autocorrectionDisabled()
                    if PassphraseKeychain.kept {
                        Button("Fill in from iCloud Keychain") { draft.typed = PassphraseKeychain.read() ?? "" }
                    }
                } footer: {
                    Text("Your copies are already encrypted with a passphrase. Type the same one.")
                }
                .listRowBackground(LibraryColors.raised)
            } else {
                Section {} footer: { Text("Next, your phone suggests a passphrase of six words.") }
            }
        }
        if draft.offer == nil && state.canKeepFriends {
            let free = state.info?.freeSpace?.int64Value ?? 0
            Section {
                Toggle(isOn: Binding(get: { draft.offerBack }, set: { on in
                    draft.offerBack = on
                    if on && draft.offerQuota == 0 { draft.offerQuota = SwapQuota.shared.suggested(freeSpace: free) }
                })) {
                    SettingsLabel(verbatim: String(localized: "Keep \(draft.name)’s copy here too"),
                        detail: String(localized: "\(draft.name)’s phone will offer it; they accept there."))
                }
                .tint(LibraryColors.accent)
                if draft.offerBack { QuotaPicker(free: free, quota: $draft.offerQuota) }
            }
            .listRowBackground(LibraryColors.raised)
        }
        Section {
            if !draft.plain && kind == CopyKeyKind.none {
                Button("Next") {
                    if draft.suggested == nil { draft.suggested = Passphrase.shared.suggest() }
                    draft.step = .words
                }
            } else {
                startButton(state, enabled: draft.plain || kind == CopyKeyKind.keyFile || (kind == CopyKeyKind.passphrase && !draft.typed.isEmpty))
            }
        }
        .listRowBackground(LibraryColors.raised)
    }

    @ViewBuilder
    private func wordsStep() -> some View {
        if let words = draft.suggested {
            Section {
                LazyVGrid(columns: [GridItem(.flexible(), alignment: .leading), GridItem(.flexible(), alignment: .leading)], spacing: 12) {
                    ForEach(Array(words.words.enumerated()), id: \.offset) { i, word in
                        Text(verbatim: "\(i + 1). \(word)").font(.system(size: 22, design: .monospaced)).foregroundStyle(LibraryColors.onBackground)
                    }
                }
                .environment(\.layoutDirection, .leftToRight)
                .padding(.vertical, 8)
            } footer: {
                Text("Your passphrase. It’s shown once: save it now. Your server never keeps it, nor does your friend’s.")
            }
            .listRowBackground(LibraryColors.raised)
            Section {
                Button(saved ? "Saved to iCloud Keychain" : "Save to iCloud Keychain") {
                    saved = PassphraseKeychain.save(words.text, serverId: model.state.info?.serverId)
                }
                Button("Recovery sheet") {
                    if let url = RecoverySheet(words: words.words, friend: draft.name, serverId: model.state.info?.serverId).write() {
                        presentShareSheet([url])
                    }
                }
                Button("Share") { presentShareSheet([String(localized: "My journal copy passphrase: \(words.text)")]) }
                Button("Copy") { copyToPasteboard(words.text, sensitive: true) }
            } footer: {
                Text("Without the passphrase nobody can open the copy, not even you.")
            }
            .listRowBackground(LibraryColors.raised)
            Section {
                Button("I’ve kept it") { draft.step = .check }
            }
            .listRowBackground(LibraryColors.raised)
        }
    }

    @ViewBuilder
    private func checkStep(_ state: SwapState) -> some View {
        if let words = draft.suggested {
            let right = words.typedBack(first: draft.check1, second: draft.check2)
            Section {
                TextField(String(localized: "Word \(Int(truncating: words.check[0]) + 1)"), text: $draft.check1)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                TextField(String(localized: "Word \(Int(truncating: words.check[1]) + 1)"), text: $draft.check2)
                    .textInputAutocapitalization(.never).autocorrectionDisabled()
                if !draft.check1.isEmpty && !draft.check2.isEmpty && !right {
                    Text("Those aren’t the words").font(.footnote).foregroundStyle(LibraryColors.error)
                }
            } footer: {
                Text("To be sure it was kept, type two of the words.")
            }
            .listRowBackground(LibraryColors.raised)
            Section {
                startButton(state, enabled: right)
                Button("Show the words again") { draft.step = .words }
            }
            .listRowBackground(LibraryColors.raised)
        }
    }

    @ViewBuilder
    private func resultStep(_ state: SwapState) -> some View {
        if let joined = state.outcome as? SwapOutcomeJoined {
            Section {
                VStack(spacing: 8) {
                    Image(systemName: "checkmark.circle").font(.system(size: 40))
                    Text("Your copy is going to \(joined.swap.name)’s server").font(.headline).multilineTextAlignment(.center)
                    Text(verbatim: joined.swap.encrypted
                        ? String(localized: "Encrypted, up to \(fileSize(joined.swap.quota)). The first pass starts now, then daily.")
                        : String(localized: "Plain, up to \(fileSize(joined.swap.quota)). The first pass starts now, then daily."))
                        .font(.footnote).foregroundStyle(LibraryColors.muted).multilineTextAlignment(.center)
                    if joined.offered {
                        Text("\(joined.swap.name)’s phone now offers to keep their copy here.").font(.footnote).foregroundStyle(LibraryColors.muted).multilineTextAlignment(.center)
                    }
                    if saved {
                        Text("The passphrase is saved in iCloud Keychain.").font(.footnote).foregroundStyle(LibraryColors.muted).multilineTextAlignment(.center)
                    }
                }
                .frame(maxWidth: .infinity)
                .foregroundStyle(LibraryColors.onBackground)
            }
            .listRowBackground(LibraryColors.raised)
            Section {
                Button("Done") {
                    model.swaps.clearOutcome()
                    dismiss()
                }
            }
            .listRowBackground(LibraryColors.raised)
        } else if let refused = state.outcome as? SwapOutcomeRefused {
            Section {
                Text(verbatim: refusalText(refused.reason, friend: draft.name)).foregroundStyle(LibraryColors.onBackground)
            }
            .listRowBackground(LibraryColors.raised)
            Section {
                Button("Back") {
                    model.swaps.clearOutcome()
                    draft.step = refusalStep(refused.reason, accepting: draft.offer != nil)
                }
            }
            .listRowBackground(LibraryColors.raised)
        } else {
            Section { HStack { Spacer(); ProgressView(); Spacer() } }.listRowBackground(LibraryColors.raised)
        }
    }

    private func startButton(_ state: SwapState, enabled: Bool) -> some View {
        Button {
            model.swaps.clearOutcome()
            let passphrase = draft.passphrase(state.info?.copyKey)
            draft.step = .result
            if let offer = draft.offer {
                model.swaps.accept(offer: offer, passphrase: passphrase, plain: draft.plain)
            } else if draft.offerBack && draft.offerQuota > 0 {
                model.swaps.join(link: draft.linkText, name: draft.name, passphrase: passphrase, plain: draft.plain, offerQuota: KotlinLong(value: draft.offerQuota))
            } else {
                model.swaps.joinWithoutOffer(link: draft.linkText, name: draft.name, passphrase: passphrase, plain: draft.plain)
            }
        } label: {
            HStack {
                Text("Start the copy")
                if state.acting { Spacer(); ProgressView() }
            }
        }
        .disabled(!enabled || state.acting)
    }
}

/// The step a refusal sends the user back to.
func refusalStep(_ reason: SwapRefusal, accepting: Bool) -> JoinStep {
    switch reason {
    case .invalidLink, .codeRefused, .unreachable, .ownServer, .nameTaken: return accepting ? .protect : .link
    default: return .protect
    }
}

func refusalText(_ reason: SwapRefusal, friend: String) -> String {
    switch reason {
    case .invalidLink: return String(localized: "That isn’t a swap link. Ask for a new invite.")
    case .codeRefused: return String(localized: "\(friend)’s server refused the code: it was used or expired. Ask \(friend) for a new invite.")
    case .unreachable: return String(localized: "\(friend)’s server can’t be reached. One of the two servers must be reachable from outside its home.")
    case .ownServer: return String(localized: "That invite is from your own server.")
    case .nameTaken: return String(localized: "You already have a friend called \(friend). Give another name.")
    case .needsPassphrase: return String(localized: "An encrypted copy needs the passphrase.")
    case .weakPassphrase: return String(localized: "That passphrase is too short: 24 characters, or 4 words and 20.")
    case .wrongPassphrase: return String(localized: "That isn’t the passphrase your copies are encrypted with.")
    case .noSpace: return String(localized: "Your server has no room for what you offered back. Offer less, or none.")
    case .offerGone: return String(localized: "\(friend)’s offer is gone: it lasts 10 minutes.")
    default: return String(localized: "Your server refused. Try again.")
    }
}

// MARK: - Show my copy passphrase

/// The passphrase from iCloud Keychain, behind Face ID: its words large, with Copy, Share and the recovery sheet.
struct CopyPassphraseView: View {
    let friend: String
    let serverId: String?
    @State private var passphrase: String? = PassphraseKeychain.read()

    var body: some View {
        SettingsPage(title: "Show my copy passphrase") {
            if let passphrase {
                Section {
                    Text(verbatim: passphrase).font(.system(size: 22, design: .monospaced)).foregroundStyle(LibraryColors.onBackground)
                        .environment(\.layoutDirection, .leftToRight)
                        .textSelection(.enabled)
                        .padding(.vertical, 8)
                } footer: {
                    Text("Without the passphrase nobody can open the copy, not even you.")
                }
                .listRowBackground(LibraryColors.raised)
                Section {
                    Button("Copy") { copyToPasteboard(passphrase, sensitive: true) }
                    Button("Share") { presentShareSheet([String(localized: "My journal copy passphrase: \(passphrase)")]) }
                    Button("Recovery sheet") {
                        if let url = RecoverySheet(words: passphrase.split(separator: " ").map(String.init), friend: friend, serverId: serverId).write() {
                            presentShareSheet([url])
                        }
                    }
                }
                .listRowBackground(LibraryColors.raised)
            } else {
                Section {} footer: {
                    Text("The passphrase isn’t in this iPhone’s Keychain. Turn on iCloud Keychain, or use your recovery sheet.")
                }
            }
        }
    }
}
