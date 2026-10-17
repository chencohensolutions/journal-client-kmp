import SwiftUI
import SharedLogic

/// The library screens are always dark, like a photo app, whatever the system appearance.
enum LibraryColors {
    static let background = Color(red: 0x0F / 255, green: 0x14 / 255, blue: 0x13 / 255)
    static let raised = Color(red: 0x1E / 255, green: 0x27 / 255, blue: 0x25 / 255)
    static let muted = Color(red: 0xA9 / 255, green: 0xB4 / 255, blue: 0xB1 / 255)
    static let accent = Color(red: 0x1F / 255, green: 0x5F / 255, blue: 0x5B / 255)
    static let light = Color(red: 0xF5 / 255, green: 0xF6 / 255, blue: 0xF4 / 255)
    static let ink = Color(red: 0x17 / 255, green: 0x20 / 255, blue: 0x1F / 255)
    static let inkMuted = Color(red: 0x5E / 255, green: 0x68 / 255, blue: 0x66 / 255)
    static let warning = Color(red: 0xF2 / 255, green: 0xB8 / 255, blue: 0x4B / 255)
    static let error = Color(red: 0xC2 / 255, green: 0x41 / 255, blue: 0x0C / 255)
    static let badgeScrim = background.opacity(0.6)

    /// Behind the floating top bar: dark at the edge, fading into the content.
    static let topFade = LinearGradient(
        colors: [background.opacity(0.95), background.opacity(0.75), .clear], startPoint: .top, endPoint: .bottom)
    static let bottomFade = LinearGradient(colors: [.clear, background.opacity(0.9)], startPoint: .top, endPoint: .bottom)
    static let labelFade = LinearGradient(colors: [background.opacity(0.55), .clear], startPoint: .top, endPoint: .bottom)
}

extension LocalDay {
    var date: Date {
        Calendar.current.date(from: DateComponents(year: Int(year), month: Int(month), day: Int(day))) ?? Date()
    }

    /// "Today", "Yesterday", "Tue, Oct 7", or "Tue, Oct 7, 2025" for other years.
    var label: String {
        let calendar = Calendar.current
        let date = self.date
        if calendar.isDateInToday(date) { return "Today" }
        if calendar.isDateInYesterday(date) { return "Yesterday" }
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

    /// "Photo, only on this device" for VoiceOver.
    var accessibilityDescription: String {
        "\(isVideo ? "Video" : "Photo"), \(backup.label.lowercased())"
    }
}

extension BackupStatus {
    var label: String {
        switch self {
        case is BackupStatusQueued: return "Waiting to upload"
        case is BackupStatusUploading: return "Uploading to your server"
        case is BackupStatusBackedUp: return "Backed up"
        case let failed as BackupStatusFailed: return "Upload failed: \(failed.reason)"
        default: return "Only on this device"
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
        .background(LibraryColors.background.opacity(0.7), in: Capsule())
    }
}

/// The floating segmented bar at the bottom of the library screens.
struct SegmentedBar<Value: Equatable>: View {
    let options: [(Value, String)]
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
                        .foregroundStyle(selected ? LibraryColors.ink : .white)
                        .background(selected ? LibraryColors.light : .clear, in: Capsule())
                }
                .accessibilityAddTraits(selected ? [.isSelected] : [])
            }
        }
        .padding(4)
        .background(LibraryColors.raised.opacity(0.95), in: Capsule())
        .shadow(color: .black.opacity(0.35), radius: 12, y: 8)
    }
}

/// The bottom controls: the backup summary (when something isn't backed up) above a bar.
struct BottomControls<Bar: View>: View {
    let notBackedUp: Int
    @ViewBuilder let bar: Bar

    var body: some View {
        VStack(spacing: 10) {
            if notBackedUp > 0 {
                Label("\(notBackedUp) not backed up", systemImage: "icloud.slash")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(.white)
                    .padding(.horizontal, 14)
                    .frame(height: 36)
                    .background(LibraryColors.light.opacity(0.16), in: Capsule())
            }
            bar
        }
        .padding(.top, 48)
        .padding(.bottom, 8)
        .frame(maxWidth: .infinity)
        .background(LibraryColors.bottomFade.ignoresSafeArea())
    }
}
