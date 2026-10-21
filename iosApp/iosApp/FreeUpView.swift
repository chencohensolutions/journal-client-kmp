import SwiftUI
import SharedLogic

/// Free up space, opened from Settings: one button that checks everything this phone backed up (from the chosen
/// sources), with "Older than" and "Keep the newest" under More options. Each file is hashed on the phone and on the
/// server; those that pass wait for Move to trash, and iOS's own prompt, to go to Recently Deleted. See
/// docs/screens/settings.md.
struct FreeUpView: View {
    @ObservedObject var freeUp: FreeUpViewModel
    /// The library reads PhotoKit again once photos have left it.
    let refreshLibrary: () -> Void
    @State private var moreOptions: Bool?
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        let state = freeUp.state
        Form {
            summarySection(state)
            Section {
                ForEach(MediaSource.entries, id: \.self) { source in
                    Toggle(source.title, isOn: Binding(get: { state.sources.contains(source) }, set: { _ in freeUp.toggleSource(source) }))
                        .tint(LibraryColors.accent)
                }
            } header: {
                Text("From")
            }
            .listRowBackground(LibraryColors.raised)
            Section {
                DisclosureGroup(
                    isExpanded: Binding(get: { moreOptions ?? !(state.rule is FreeUpRuleEverything) }, set: { moreOptions = $0 })
                ) {
                    ruleOptions(state.rule)
                } label: {
                    Text("More options").fontWeight(.semibold).foregroundStyle(LibraryColors.onBackground)
                }
                .tint(LibraryColors.muted)
            }
            .listRowBackground(LibraryColors.raised)
        }
        .task { freeUp.refreshPlan() }
        // Back in the app (photos taken or deleted meanwhile): counted again.
        .onChange(of: scenePhase) { _, phase in if phase == .active { freeUp.refreshPlan() } }
        .scrollContentBackground(.hidden)
        .background(LibraryColors.background.ignoresSafeArea())
        .navigationTitle("Free up space")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(LibraryColors.background, for: .navigationBar)
    }

    /// The size in large type, what the rule takes, the button, and what the run is doing or did.
    private func summarySection(_ state: FreeUpState) -> some View {
        let plan = state.plan
        let canFree = plan.map { !$0.groups.isEmpty } ?? false
        return Section {
            VStack(alignment: .leading, spacing: 4) {
                Group {
                    if let plan, canFree {
                        Text(formatBytes(plan.bytes)).font(.system(size: 34, weight: .semibold))
                    } else {
                        Text(plan == nil ? "Counting…" : "Nothing to free up").font(.title3.weight(.semibold))
                    }
                }
                .foregroundStyle(LibraryColors.onBackground)
                Text(ruleSummary(state.rule)).foregroundStyle(LibraryColors.onBackground)
                if let plan {
                    Text(canFree ? planDetails(plan) : String(localized: "Nothing to remove. Only photos already backed up are removed."))
                        .font(.subheadline)
                        .foregroundStyle(LibraryColors.muted)
                }
                Button(action: freeUp.freeUp) {
                    Text(canFree ? String(localized: "Free up \(formatBytes(plan?.bytes ?? 0))") : String(localized: "Free up space"))
                        .font(.headline)
                        .frame(maxWidth: .infinity, minHeight: 52)
                }
                .buttonStyle(.plain)
                .foregroundStyle(LibraryColors.onInverse)
                .background(LibraryColors.inverse, in: Capsule())
                .opacity(freeUp.running || !canFree ? 0.4 : 1)
                .disabled(freeUp.running || !canFree)
                .padding(.top, 12)
                Text("Each file is hashed again on this phone and on your server first. Then you confirm them all at once, and they go to Recently Deleted, where they stay for 30 days.")
                    .font(.footnote)
                    .foregroundStyle(LibraryColors.muted)
                    .padding(.top, 8)
                if let status = freeUp.statusText {
                    Text(status).font(.subheadline).monospacedDigit().foregroundStyle(LibraryColors.onBackground).padding(.top, 8)
                }
            }
            .padding(.vertical, 8)
            if !freeUp.running && state.waitingCount > 0 {
                Button("Move to trash") {
                    Task { await freeUp.moveWaitingToTrash(refreshLibrary: refreshLibrary) }
                }
                .disabled(freeUp.confirming)
                .foregroundStyle(LibraryColors.onBackground)
            }
        }
        .listRowBackground(LibraryColors.raised)
    }

    /// Everything backed up, Older than, Keep the newest; each choice's values show under it while it is chosen.
    @ViewBuilder private func ruleOptions(_ rule: FreeUpRule) -> some View {
        Picker("Free up", selection: Binding(get: { RuleKind(rule) }, set: { kind in
            if kind != RuleKind(rule) { freeUp.setRule(kind.defaultRule) }
        })) {
            RuleLabel(title: "Everything backed up", detail: "Every photo and video this phone backed up to your server.")
                .tag(RuleKind.everything)
            RuleLabel(title: "Older than", detail: "Removes photos and videos taken before then.")
                .tag(RuleKind.olderThan)
            RuleLabel(title: "Keep the newest", detail: "Keeps the newest photos and videos up to this size and removes the older ones.")
                .tag(RuleKind.keepNewest)
        }
        .pickerStyle(.inline)
        .labelsHidden()
        if let older = rule as? FreeUpRuleOlderThan {
            Picker("Older than", selection: Binding(get: { Int(older.days) }, set: { freeUp.setRule(FreeUpRuleOlderThan(days: Int32($0))) })) {
                ForEach(FreeUpRuleCompanion.shared.DAYS_OPTIONS.map(\.intValue), id: \.self) { Text(ageText($0)).tag($0) }
            }
            .foregroundStyle(LibraryColors.onBackground)
        }
        if let keep = rule as? FreeUpRuleKeepNewest {
            Picker("Keep the newest", selection: Binding(get: { Int(keep.gigabytes) }, set: { freeUp.setRule(FreeUpRuleKeepNewest(gigabytes: Int32($0))) })) {
                ForEach(FreeUpRuleCompanion.shared.GIGABYTES_OPTIONS.map(\.intValue), id: \.self) { Text("\($0) GB").tag($0) }
            }
            .foregroundStyle(LibraryColors.onBackground)
        }
    }
}

/// The three rules, as the picker's tags; a newly chosen one starts at Android's defaults (1 year, 10 GB).
private enum RuleKind: Hashable {
    case everything, olderThan, keepNewest

    init(_ rule: FreeUpRule) {
        switch rule {
        case is FreeUpRuleOlderThan: self = .olderThan
        case is FreeUpRuleKeepNewest: self = .keepNewest
        default: self = .everything
        }
    }

    var defaultRule: FreeUpRule {
        switch self {
        case .everything: return FreeUpRuleEverything.shared
        case .olderThan: return FreeUpRuleOlderThan(days: 365)
        case .keepNewest: return FreeUpRuleKeepNewest(gigabytes: 10)
        }
    }
}

private struct RuleLabel: View {
    let title: LocalizedStringKey
    let detail: LocalizedStringKey

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).foregroundStyle(LibraryColors.onBackground)
            Text(detail).font(.footnote).foregroundStyle(LibraryColors.muted)
        }
    }
}

/// What the rule takes: "Photos and videos already on your server", "Taken more than 1 year ago", "All but the newest 10 GB".
private func ruleSummary(_ rule: FreeUpRule) -> String {
    switch rule {
    case let older as FreeUpRuleOlderThan: return String(localized: "Taken more than \(ageText(Int(older.days))) ago")
    case let keep as FreeUpRuleKeepNewest: return String(localized: "All but the newest \(String(localized: "\(Int(keep.gigabytes)) GB"))")
    default: return String(localized: "Photos and videos already on your server")
    }
}

/// "3 months", "1 year".
private func ageText(_ days: Int) -> String {
    days % 365 == 0 ? String(localized: "\(days / 365) years") : String(localized: "\(days / 30) months")
}

/// "1,240 photos, 38 videos · Mar 2019 – Oct 2025".
private func planDetails(_ plan: FreeUpPlan) -> String {
    let photos = Int(plan.photos)
    let videos = Int(plan.videos)
    let counts = [
        photos > 0 ? String(localized: "\(photos) photos") : nil,
        videos > 0 ? String(localized: "\(videos) videos") : nil,
    ].compactMap { $0 }.joined(separator: ", ")
    guard let range = plan.takenRange, let oldest = range.first, let newest = range.second else { return counts }
    func month(_ millis: KotlinLong) -> String {
        Date(timeIntervalSince1970: Double(millis.int64Value) / 1000).formatted(.dateTime.month(.abbreviated).year())
    }
    let from = month(oldest)
    let to = month(newest)
    return from == to ? "\(counts) · \(from)" : "\(counts) · \(from) – \(to)"
}
