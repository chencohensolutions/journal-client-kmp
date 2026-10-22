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
        usageObservation?.cancel()
        controller.close()
    }

    func setGroupingMode(_ mode: GroupingMode) { controller.setGroupingMode(mode: mode) }
    func setGapHours(_ hours: Int) { controller.setGapHours(hours: Int32(hours)) }
    /// Photos per page of search results; applies from the next page.
    func setSearchPageSize(_ size: Int) { controller.setSearchPageSize(size: Int32(size)) }
    func setSimilarityPercent(_ percent: Int) { controller.setSimilarityPercent(percent: Int32(percent)) }
    func resetSimilarity() { controller.resetSimilarity() }

    /// A source on or off everywhere (Show in the app, the Moments filter bar, Explore's sheet).
    func toggleFeedSource(_ source: MediaSource) { controller.toggleFeedSource(source: source) }
    /// "Say what's hidden": the "+6 WhatsApp" chips on the days of Moments.
    func setSayWhatsHidden(_ say: Bool) { controller.setSayWhatsHidden(say: say) }
    /// "Always show this day": the source's photos of these days show while it is hidden.
    func alwaysShow(days: [LocalDay], source: MediaSource) { controller.alwaysShow(days: days, source: source) }

    // MARK: Storage on this phone, and Private media

    /// What previews and downloaded originals of server photos take here; nil while counting.
    @Published private(set) var previewBytes: Int64?
    /// What the user's originals take up on the server (shown with backup); nil until it answered.
    @Published private(set) var serverUsage: StorageUsage?

    private let serverFiles = ServerImages.shared.files
    private var usageObservation: ObservationHandle?

    /// Settings opened: asks the server for its usage, and counts (after trimming to the limit) what is kept here.
    func refreshStorage() {
        if usageObservation == nil {
            usageObservation = serverFiles.observeUsage { [weak self] usage in self?.serverUsage = usage }
        }
        serverFiles.library.refreshUsage()
        countPreviews()
    }

    func setPreviewsEnabled(_ enabled: Bool) { controller.setPreviewsEnabled(enabled: enabled) }
    func setPreviewFormat(_ format: PreviewFormat) { controller.setPreviewFormat(format: format) }
    func setPreviewQuality(_ quality: PreviewQuality) { controller.setPreviewQuality(quality: quality) }

    /// The limit; a lower one applies at once (the oldest go first).
    func setPreviewLimit(_ gigabytes: Int) {
        controller.setPreviewCacheGigabytes(gigabytes: Int32(gigabytes))
        countPreviews()
    }

    /// "Clear": previews and downloaded originals of server photos; they are fetched again when shown.
    func clearPreviews() {
        previewBytes = nil
        _ = serverFiles.clear { [weak self] bytes in self?.previewBytes = bytes.int64Value }
    }

    private func countPreviews() {
        _ = serverFiles.usedBytes { [weak self] bytes in self?.previewBytes = bytes.int64Value }
    }

    func setShowPrivateMedia(_ show: Bool) { controller.setShowPrivateMedia(show: show) }
    func setPrivateThresholdPercent(_ percent: Int) { controller.setPrivateThresholdPercent(percent: Int32(percent)) }
    func setShowOnPhoneBadge(_ show: Bool) { controller.setShowOnPhoneBadge(show: show) }

    /// What the limit offers, in GB.
    static var previewLimitOptions: [Int] { SettingsState.Companion.shared.PREVIEW_CACHE_GB_OPTIONS.map { $0.intValue } }

    /// What the private media slider offers.
    static var privateRange: ClosedRange<Double> {
        let range = PrivateMedia.Companion.shared.RANGE
        return Double(range.first)...Double(range.last)
    }

    /// What the similarity slider offers.
    static var similarityRange: ClosedRange<Double> {
        let range = SimilarityThreshold.Companion.shared.RANGE
        return Double(range.first)...Double(range.last)
    }

    /// What the gap picker offers.
    static var gapHoursOptions: [Int] { TimelineGrouping.Companion.shared.GAP_HOURS_OPTIONS.map { $0.intValue } }
    static var searchPageOptions: [Int] { SettingsState.Companion.shared.SEARCH_PAGE_OPTIONS.map { $0.intValue } }
}
