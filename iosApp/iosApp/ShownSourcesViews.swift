import SwiftUI
import SharedLogic

// What the app shows of each source (Settings › Show in the app, the Moments filter bar, Explore's "Showing" sheet):
// one setting everywhere (ShownSources in the shared logic). Hiding never deletes anything and doesn't change backup.

/// A Kotlin map of counts per source as (source, count) pairs in `MediaSource` order, leaving out sources with none.
func sourceCountList(_ counts: [MediaSource: KotlinInt]) -> [(MediaSource, Int)] {
    MediaSource.companion.choices(present: Set(counts.keys), chosen: []).compactMap { source in
        let count = counts[source]?.intValue ?? 0
        return count > 0 ? (source, count) : nil
    }
}

/// Show in the app as every screen changes it, set once by the library root: the setting, what Private settings hides
/// on every device (nil until it was opened in this run), and the actions the pills share.
struct ShownSourcesChoice {
    var shown: ShownSources? = nil
    var hiddenEverywhere: HiddenCollections? = nil
    /// "Imported", all of it, on or off.
    var toggleImported: () -> Void = {}
    /// Private settings, behind the phone's lock.
    var openPrivate: () -> Void = {}
}

private struct ShownSourcesChoiceKey: EnvironmentKey {
    static let defaultValue = ShownSourcesChoice()
}

extension EnvironmentValues {
    var shownSourcesChoice: ShownSourcesChoice {
        get { self[ShownSourcesChoiceKey.self] }
        set { self[ShownSourcesChoiceKey.self] = newValue }
    }
}

/// Under Explore's choices: "Camera · Imported · WhatsApp, Screenshots hidden" and Change, which opens the "Showing"
/// sheet; what was imported is said once, as "Imported". Sources with nothing in `counts` aren't named; nothing shows
/// when there are no photos at all.
struct ShownSourcesLine: View {
    let shown: ShownSources
    let counts: [MediaSource: KotlinInt]
    let onChange: () -> Void

    var body: some View {
        let present = sourceCountList(counts).map(\.0)
        if !present.isEmpty {
            Button(action: onChange) {
                HStack(spacing: 8) {
                    Text(verbatim: line(present))
                        .font(.system(size: 13))
                        .foregroundStyle(LibraryColors.onBackground)
                        .lineLimit(2)
                        .multilineTextAlignment(.leading)
                        .frame(maxWidth: .infinity, alignment: .leading)
                    Text("Change")
                        .font(.system(size: 13, weight: .bold))
                        .foregroundStyle(LibraryColors.onBackground)
                }
                .padding(.horizontal, 14)
                .padding(.vertical, 10)
                .background(LibraryColors.hiddenTint, in: RoundedRectangle(cornerRadius: 12))
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    private func line(_ present: [MediaSource]) -> String {
        let imported = String(localized: "Imported")
        let anyImported = present.contains { $0.isImported }
        var on = present.filter { !$0.isImported && shown.isShown(source: $0) }.map(\.title)
        if shown.importedShown && present.contains(where: { $0.isImported && shown.isShown(source: $0) }) { on.append(imported) }
        var off = present.filter { !$0.isImported && !shown.isShown(source: $0) }.map(\.title)
        if anyImported && !shown.importedShown { off.append(imported) }
        var parts = on
        if !off.isEmpty { parts.append(String(localized: "\(off.joined(separator: ", ")) hidden")) }
        return parts.joined(separator: " · ")
    }
}

/// The "Showing" sheet of the Moments filter bar's "+N" and of Explore: Show in the app's pills (option b2 of the UI
/// lab, Oct 27, 2026) over `counts`, photos per source where it was opened, the same setting everywhere. With the
/// WhatsApp note in Explore, when it has photos here.
struct ShowingSheet: View {
    let shown: ShownSources
    let counts: [MediaSource: KotlinInt]
    let onToggle: (MediaSource) -> Void
    var whatsAppNote = true
    @Environment(\.shownSourcesChoice) private var choice
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        let shown = choice.shown ?? self.shown
        let pills = ShownSourcesPills.companion.of(shown: shown, counts: counts, hidden: choice.hiddenEverywhere)
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                Text("Showing")
                    .font(.system(size: 20, weight: .bold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .accessibilityAddTraits(.isHeader)
                Text("The same in Moments and Explore.")
                    .font(.system(size: 13))
                    .foregroundStyle(LibraryColors.muted)
                    .padding(.top, 2)
                    .padding(.bottom, 12)
                ShownPillsSection(
                    pills: pills, toggle: onToggle, toggleImported: choice.toggleImported,
                    openPrivate: {
                        dismiss()
                        choice.openPrivate()
                    }
                )
                if whatsAppNote && ((counts[.whatsapp]?.intValue ?? 0) > 0 || !shown.isShown(source: .whatsapp)) {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: "info.circle").font(.system(size: 13))
                        Text("Most WhatsApp photos have no location, so few of them are on the map either way. Hiding them mostly changes Years and the photos on a town's page.")
                            .font(.footnote)
                    }
                    .foregroundStyle(LibraryColors.muted)
                    .padding(12)
                    .background(LibraryColors.raised, in: RoundedRectangle(cornerRadius: 12))
                    .padding(.top, 12)
                }
            }
            .padding(.horizontal, 20)
            .padding(.top, 24)
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .background(LibraryColors.background.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}

/// The phones' sources, then "Imported" with its kinds in a tinted box while on, the line on Other, and what Private
/// settings hides on every device (or, before it was opened, what it can hide), with "Private settings ›". A source it
/// hides whole is a dimmed pill with a lock that opens it; one it hides in part has a lock after its count.
struct ShownPillsSection: View {
    let pills: ShownSourcesPills
    let toggle: (MediaSource) -> Void
    let toggleImported: () -> Void
    let openPrivate: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            FlowLayout(spacing: 8) {
                ForEach(pills.fromPhones, id: \.source.id) { pill in pillView(pill, small: false) }
                if pills.hasImported {
                    FilterBarChip(
                        title: String(localized: "Imported"), symbol: "shippingbox", count: Int(pills.importedCount).formatted(),
                        on: pills.importedShown, keepIcon: true, action: toggleImported
                    )
                }
            }
            if pills.importedShown && pills.hasImported {
                VStack(alignment: .leading, spacing: 8) {
                    Label("Of what was imported", systemImage: "shippingbox")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(LibraryColors.muted)
                    FlowLayout(spacing: 6) {
                        ForEach(pills.importedKinds, id: \.source.id) { pill in pillView(pill, small: true) }
                    }
                }
                .padding(12)
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(LibraryColors.raised, in: RoundedRectangle(cornerRadius: 14))
            }
            if pills.fromPhones.contains(where: { $0.source == .other }) || pills.importedKinds.contains(where: { $0.source.kind == .other }) {
                Text("\(MediaSource.other.title): files whose name and folder say nothing: downloads, saved images, files from a computer.")
                    .font(.footnote)
                    .foregroundStyle(LibraryColors.muted)
            }
            Button(action: openPrivate) {
                HStack(alignment: .top, spacing: 10) {
                    Image(systemName: "lock.fill").font(.system(size: 13)).padding(.top, 2)
                    VStack(alignment: .leading, spacing: 4) {
                        Text(verbatim: hiddenLine).font(.footnote).multilineTextAlignment(.leading)
                        HStack(spacing: 2) {
                            Text("Private settings").bold()
                            Image(systemName: "chevron.right").font(.system(size: 11, weight: .bold))
                        }
                        .font(.footnote)
                        .foregroundStyle(LibraryColors.onBackground)
                    }
                }
                .foregroundStyle(LibraryColors.muted)
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
        }
    }

    @ViewBuilder
    private func pillView(_ pill: ShownSourcesPills.Pill, small: Bool) -> some View {
        if pill.allHiddenEverywhere {
            FilterBarChip(title: pill.source.title, symbol: "lock.fill", on: false, small: small, action: openPrivate).opacity(0.5)
        } else {
            FilterBarChip(
                title: pill.source.title, symbol: pill.source.symbol, count: Int(pill.count).formatted(), on: pill.shown, small: small,
                locked: pill.hiddenEverywhere > 0
            ) { toggle(pill.source) }
        }
    }

    /// "Hidden on all your devices: WhatsApp, 1,640 more Screenshots. Not counted here." once Private settings was
    /// opened; before, or when it hides none of these sources, what it can hide.
    private var hiddenLine: String {
        let whole = pills.hiddenWhole
        let part = pills.hiddenInPart
        guard pills.privateKnown, !(whole.isEmpty && part.isEmpty) else {
            return String(localized: "Private settings can hide apps, screenshots of chosen apps and nudity on all your devices. Those files never reach this phone and aren't counted here.")
        }
        let names = whole.map(\.title) + part.sorted { $0.key.compareTo(other: $1.key) < 0 }.map { source, n in
            String(localized: "\(n.intValue.formatted()) more \(source.title)")
        }
        return String(localized: "Hidden on all your devices: \(names.joined(separator: ", ")). Not counted here.")
    }
}
