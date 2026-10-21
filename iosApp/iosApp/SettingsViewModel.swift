import Foundation
import SharedLogic

/// Bridges the shared Kotlin `SettingsController` (the user's choices on this device) into SwiftUI.
final class SettingsViewModel: ObservableObject {
    @Published private(set) var state: SettingsState

    /// Shared with `StoriesViewModel`, whose timeline follows the grouping setting.
    let controller: SettingsController
    private var observation: ObservationHandle?

    init() {
        controller = IosJournal.shared.createSettingsController()
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

    func setGroupingMode(_ mode: GroupingMode) { controller.setGroupingMode(mode: mode) }
    func setGapHours(_ hours: Int) { controller.setGapHours(hours: Int32(hours)) }
    func setSimilarityPercent(_ percent: Int) { controller.setSimilarityPercent(percent: Int32(percent)) }
    func resetSimilarity() { controller.resetSimilarity() }

    /// Moments: a source of the filter bar on or off, and a trip card not offered again.
    func toggleFeedSource(_ source: MediaSource) { controller.toggleFeedSource(source: source) }
    func dismissTrip(_ key: String) { controller.dismissTrip(key: key) }

    /// What the similarity slider offers.
    static var similarityRange: ClosedRange<Double> {
        let range = SimilarityThreshold.Companion.shared.RANGE
        return Double(range.first)...Double(range.last)
    }

    /// What the gap picker offers.
    static var gapHoursOptions: [Int] { TimelineGrouping.Companion.shared.GAP_HOURS_OPTIONS.map { $0.intValue } }
}
