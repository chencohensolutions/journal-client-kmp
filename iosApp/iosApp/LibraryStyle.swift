import SwiftUI
import SharedLogic

/// Library colors, light or dark with the system appearance (the viewer is always dark). Things drawn over photos
/// (the photo* colors, scrims and labelFade) are the same in both: white on a dark scrim reads on any photo.
enum LibraryColors {
    static let background = dynamic(light: 0xF4F5F3, dark: 0x0F1413)
    static let raised = dynamic(light: 0xFFFFFF, dark: 0x1E2725)
    /// Text and icons on `background` and `raised`.
    static let onBackground = dynamic(light: 0x17201F, dark: 0xFFFFFF)
    static let muted = dynamic(light: 0x5E6866, dark: 0xA9B4B1)
    static let accent = hex(0x1F5F5B)
    /// A surface that stands out from the page: light on dark, dark on light (cards, the selected tab, Undo).
    static let inverse = dynamic(light: 0x17201F, dark: 0xF5F6F4)
    static let onInverse = dynamic(light: 0xF5F6F4, dark: 0x17201F)
    static let onInverseMuted = dynamic(light: 0xA9B4B1, dark: 0x5E6866)
    static let warning = dynamic(light: 0xB45309, dark: 0xF2B84B)
    static let error = hex(0xC2410C)
    /// Behind what the sources setting hides or says about: Explore's "… hidden · Change", Moments' chips and bands.
    static let hiddenTint = accent.opacity(0.35)

    private static let scrim = hex(0x0F1413)
    static let badgeScrim = scrim.opacity(0.6)
    static let pillScrim = scrim.opacity(0.7)
    static let photoText = Color.white
    static let photoTextMuted = Color(white: 0.9)
    /// Light chips over photos (the calendar block, "Cover").
    static let paper = hex(0xF5F6F4)
    static let paperInk = hex(0x17201F)

    /// Behind the floating top bar: the page color at the edge, fading into the content.
    static let topFade = LinearGradient(
        colors: [background.opacity(0.95), background.opacity(0.75), .clear], startPoint: .top, endPoint: .bottom)
    static let bottomFade = LinearGradient(colors: [.clear, background.opacity(0.9)], startPoint: .top, endPoint: .bottom)
    /// Under text written over the top or bottom of a photo.
    static let labelFade = LinearGradient(colors: [scrim.opacity(0.55), .clear], startPoint: .top, endPoint: .bottom)
    static let photoFade = LinearGradient(colors: [.clear, scrim.opacity(0.9)], startPoint: .top, endPoint: .bottom)

    private static func hex(_ rgb: UInt32) -> Color { Color(uiColor: uiColor(rgb)) }

    private static func dynamic(light: UInt32, dark: UInt32) -> Color {
        Color(uiColor: UIColor { $0.userInterfaceStyle == .dark ? uiColor(dark) : uiColor(light) })
    }

    private static func uiColor(_ rgb: UInt32) -> UIColor {
        UIColor(red: CGFloat(rgb >> 16 & 0xFF) / 255, green: CGFloat(rgb >> 8 & 0xFF) / 255, blue: CGFloat(rgb & 0xFF) / 255, alpha: 1)
    }
}

extension LocalDay {
    var date: Date {
        Calendar.current.date(from: DateComponents(year: Int(year), month: Int(month), day: Int(day))) ?? Date()
    }

    /// "Today", "Yesterday", "Tue, Oct 7", or "Tue, Oct 7, 2025" for other years.
    var label: String {
        let calendar = Calendar.current
        let date = self.date
        if calendar.isDateInToday(date) { return String(localized: "Today") }
        if calendar.isDateInYesterday(date) { return String(localized: "Yesterday") }
        let formatter = DateFormatter()
        let sameYear = calendar.component(.year, from: date) == calendar.component(.year, from: Date())
        formatter.setLocalizedDateFormatFromTemplate(sameYear ? "EEEMMMd" : "EEEMMMdyyyy")
        return formatter.string(from: date)
    }
}

extension MediaItem {
    var isVideo: Bool { type == .video }
    var takenAt: Date { Date(timeIntervalSince1970: TimeInterval(takenAtMillis) / 1000) }
    var duration: TimeInterval? { durationMillis.map { TimeInterval($0.int64Value) / 1000 } }

    /// Where the original is: on this phone and the server, only on one of them, or on its way.
    var originalLabel: String {
        guard backup.isBackedUp else { return backup.label }
        if !hasOriginalHere { return String(localized: "Only on your server") }
        if !isOnDevice { return String(localized: "Downloaded to this phone, and on your server") }
        return String(localized: "On this phone and on your server")
    }

    /// "Photo, only on this device" for VoiceOver.
    var accessibilityDescription: String {
        let status = originalLabel.lowercased()
        return isVideo ? String(localized: "Video, \(status)") : String(localized: "Photo, \(status)")
    }
}

extension BackupStatus {
    var label: String {
        switch self {
        case is BackupStatusQueued: return String(localized: "Waiting to upload")
        case is BackupStatusUploading: return String(localized: "Uploading to your server")
        case is BackupStatusBackedUp: return String(localized: "Backed up")
        case let failed as BackupStatusFailed: return String(localized: "Upload failed: \(failed.reason)")
        default: return String(localized: "Only on this device")
        }
    }

    var isBackedUp: Bool { self is BackupStatusBackedUp }
}

/// "0:42", "12:05", "1:02:09".
func formatDuration(_ seconds: TimeInterval) -> String {
    let total = Int(seconds)
    let hours = total / 3600, minutes = (total % 3600) / 60, secs = total % 60
    return hours > 0
        ? String(format: "%d:%02d:%02d", hours, minutes, secs)
        : String(format: "%d:%02d", minutes, secs)
}

/// Lays its children out in rows, wrapping to the next row when one is full (tag chips).
struct FlowLayout: Layout {
    var spacing: CGFloat = 6

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let rows = arrange(subviews, width: proposal.width ?? .infinity)
        return CGSize(width: proposal.width ?? rows.map(\.width).max() ?? 0, height: rows.last.map { $0.y + $0.height } ?? 0)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        for row in arrange(subviews, width: bounds.width) {
            var x = bounds.minX
            for index in row.indices {
                let size = subviews[index].sizeThatFits(.unspecified)
                subviews[index].place(at: CGPoint(x: x, y: bounds.minY + row.y + (row.height - size.height) / 2), proposal: .unspecified)
                x += size.width + spacing
            }
        }
    }

    private struct Row {
        var indices: [Int] = []
        var width: CGFloat = 0
        var height: CGFloat = 0
        var y: CGFloat = 0
    }

    private func arrange(_ subviews: Subviews, width: CGFloat) -> [Row] {
        var rows = [Row()]
        for index in subviews.indices {
            let size = subviews[index].sizeThatFits(.unspecified)
            if !rows[rows.count - 1].indices.isEmpty && rows[rows.count - 1].width + spacing + size.width > width {
                let last = rows[rows.count - 1]
                rows.append(Row(y: last.y + last.height + spacing))
            }
            var row = rows[rows.count - 1]
            row.width += (row.indices.isEmpty ? 0 : spacing) + size.width
            row.height = max(row.height, size.height)
            row.indices.append(index)
            rows[rows.count - 1] = row
        }
        return rows
    }
}

/// Shown only while the server can't be reached; nothing is shown when it can.
struct OfflinePill: View {
    var body: some View {
        Label {
            Text("Offline").font(.footnote.weight(.semibold))
        } icon: {
            Image(systemName: "icloud.slash").foregroundStyle(LibraryColors.warning)
        }
        .foregroundStyle(.white)
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background(LibraryColors.pillScrim, in: Capsule())
    }
}

/// The floating segmented bar at the bottom of the library screens.
struct SegmentedBar<Value: Equatable>: View {
    let options: [(Value, LocalizedStringKey)]
    @Binding var selection: Value

    var body: some View {
        HStack(spacing: 2) {
            ForEach(options.indices, id: \.self) { index in
                let (value, label) = options[index]
                let selected = value == selection
                Button { selection = value } label: {
                    Text(label)
                        .font(.subheadline.weight(.semibold))
                        .padding(.horizontal, 16)
                        .frame(height: 40)
                        .foregroundStyle(selected ? LibraryColors.onInverse : LibraryColors.onBackground)
                        .background(selected ? LibraryColors.inverse : .clear, in: Capsule())
                }
                .accessibilityAddTraits(selected ? [.isSelected] : [])
            }
        }
        .padding(4)
        .background(LibraryColors.raised.opacity(0.95), in: Capsule())
        .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
    }
}

/// The bottom controls: the backup summary (`backupSummary`: the running backup, or what isn't backed up) above a bar.
struct BottomControls<Bar: View>: View {
    let summary: String?
    @ViewBuilder let bar: Bar

    var body: some View {
        VStack(spacing: 10) {
            if let summary {
                Label(summary, systemImage: "icloud.slash")
                    .monospacedDigit()
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(LibraryColors.onBackground)
                    .padding(.horizontal, 14)
                    .frame(height: 36)
                    .background(LibraryColors.onBackground.opacity(0.12), in: Capsule())
            }
            bar
        }
        .padding(.top, 48)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
        .background(LibraryColors.bottomFade.ignoresSafeArea())
    }
}
