import SwiftUI
import SharedLogic

// What the app shows of each source (Settings › Show in the app, the Moments filter bar, Explore's "Showing" sheet):
// one setting everywhere (ShownSources in the shared logic). Hiding never deletes anything and doesn't change backup.

/// A Kotlin map of counts per source as (source, count) pairs in `MediaSource` order, leaving out sources with none.
func sourceCountList(_ counts: [MediaSource: KotlinInt]) -> [(MediaSource, Int)] {
    MediaSource.entries.compactMap { source in
        let count = counts[source]?.intValue ?? 0
        return count > 0 ? (source, count) : nil
    }
}

/// Under Explore's choices: "Camera · Other · WhatsApp, Screenshots hidden" and Change, which opens the "Showing"
/// sheet. Sources with nothing in `counts` aren't named; nothing shows when there are no photos at all.
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
        let on = present.filter { shown.isShown(source: $0) }.map(\.title)
        let off = present.filter { !shown.isShown(source: $0) }.map(\.title)
        var parts = on
        if !off.isEmpty { parts.append(String(localized: "\(off.joined(separator: ", ")) hidden")) }
        return parts.joined(separator: " · ")
    }
}

/// Explore's "Showing" sheet: a switch per source, with how many of its photos are here (the whole library, the
/// map's, or a town's or year's page's). The same setting as Moments and All photos.
struct ShowingSheet: View {
    let shown: ShownSources
    let counts: [MediaSource: KotlinInt]
    let onToggle: (MediaSource) -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Showing")
                .font(.system(size: 20, weight: .bold))
                .foregroundStyle(LibraryColors.onBackground)
                .accessibilityAddTraits(.isHeader)
            Text("The same in Moments, Explore and All photos.")
                .font(.system(size: 13))
                .foregroundStyle(LibraryColors.muted)
                .padding(.top, 2)
            VStack(spacing: 0) {
                ForEach(MediaSource.entries, id: \.self) { source in
                    let count = counts[source]?.intValue ?? 0
                    Toggle(isOn: Binding(get: { shown.isShown(source: source) }, set: { _ in onToggle(source) })) {
                        VStack(alignment: .leading, spacing: 1) {
                            Text(source.title).foregroundStyle(LibraryColors.onBackground)
                            Group {
                                if count > 0 { Text("\(count) here") } else { Text(verbatim: "—") }
                            }
                            .font(.footnote)
                            .foregroundStyle(LibraryColors.muted)
                        }
                    }
                    .tint(LibraryColors.accent)
                    .padding(.vertical, 8)
                }
            }
            .padding(.top, 12)
            if (counts[.whatsapp]?.intValue ?? 0) > 0 {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "info.circle").font(.system(size: 13))
                    Text("Most WhatsApp photos have no location, so few of them are on the map either way. Hiding them mostly changes Years and the photos on a town's page.")
                        .font(.footnote)
                }
                .foregroundStyle(LibraryColors.muted)
                .padding(12)
                .background(LibraryColors.raised, in: RoundedRectangle(cornerRadius: 12))
                .padding(.top, 8)
            }
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 20)
        .padding(.top, 24)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(LibraryColors.background.ignoresSafeArea())
        .presentationDetents([.medium, .large])
        .presentationDragIndicator(.visible)
    }
}
