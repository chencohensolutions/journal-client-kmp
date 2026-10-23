package com.chencohensolution.journal.settings

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.PreviewFormat
import com.chencohensolution.journal.server.PreviewOptions
import com.chencohensolution.journal.server.PreviewQuality
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class GroupingMode {
    /** One row per calendar day (device time zone). */
    BY_DAY,

    /** A new row starts after [TimelineGrouping.gapHours] without photos; a row can cross midnight. */
    BY_GAP,
}

/** How the Moments tab splits photos into rows (see `Moment`). */
data class TimelineGrouping(
    val mode: GroupingMode = GroupingMode.BY_DAY,
    /** Used by [GroupingMode.BY_GAP]; kept while [GroupingMode.BY_DAY] is chosen, so switching back keeps it. */
    val gapHours: Int = DEFAULT_GAP_HOURS,
) {
    val gapMillis: Long get() = gapHours * 3_600_000L

    companion object {
        const val DEFAULT_GAP_HOURS = 12

        /** What the Settings screen offers. */
        val GAP_HOURS_OPTIONS: List<Int> = listOf(2, 4, 6, 8, 12, 24, 48)

        /** Anything stored outside this falls back to [DEFAULT_GAP_HOURS]. */
        val GAP_HOURS_RANGE: IntRange = 1..72
    }
}

/**
 * How alike two photos must be for a "Look alike" suggestion: the cosine similarity of their image embeddings, as a
 * percentage. The default depends on the platform's image model.
 */
data class SimilarityThreshold(val percent: Int = DEFAULT_PERCENT, val defaultPercent: Int = DEFAULT_PERCENT) {
    val isDefault: Boolean get() = percent == defaultPercent

    companion object {
        const val DEFAULT_PERCENT = 85

        /** What the Settings screen offers; anything stored outside falls back to the default. */
        val RANGE: IntRange = 50..99
    }
}

/**
 * Photos the user marked private: unless [show] (off by default), they are left out of the library everywhere, and
 * nothing of them is fetched (no preview, no original). Device photos the server marked are left out too. Nudity is
 * hidden by the server instead (Hidden collections, see HiddenCollections).
 */
data class PrivateMedia(val show: Boolean = false) {
    /** Whether a photo the user marked private ([isPrivate]) or not is hidden. */
    fun hides(isPrivate: Boolean): Boolean = !show && isPrivate
}

/** The user's choices on the Settings screen, and the Moments tab's filter bar. */
data class SettingsState(
    val grouping: TimelineGrouping = TimelineGrouping(),
    val similarity: SimilarityThreshold = SimilarityThreshold(),
    /** What the app shows of each source (Settings › Show in the app, and the Moments filter bar). */
    val shownSources: ShownSources = ShownSources(),
    /** How much the previews of server photos may take up on this device (Settings → Storage on this phone). */
    val previewCacheGigabytes: Int = JournalSettings.DEFAULT_PREVIEW_CACHE_GB,
    /** How server photos are shown: previews (format, quality) or their originals. */
    val previewOptions: PreviewOptions = PreviewOptions(),
    /** "Show private media". */
    val privateMedia: PrivateMedia = PrivateMedia(),
    /**
     * Whether a photo's corner badge shows the phone once it is on this phone and backed up (off by default: only
     * photos still to upload, uploading or failed have a badge).
     */
    val showOnPhoneBadge: Boolean = false,
    /** Photos per page of search results; scrolling near the end shows the next page. */
    val searchPageSize: Int = JournalSettings.DEFAULT_SEARCH_PAGE_SIZE,
) {
    companion object {
        /** What the Settings screen offers for [searchPageSize] (the server sends at most 1000 at a time). */
        val SEARCH_PAGE_OPTIONS: List<Int> = listOf(50, 100, 200, 500, 1000)

        /** What the Settings screen offers for [previewCacheGigabytes]. */
        val PREVIEW_CACHE_GB_OPTIONS: List<Int> = listOf(1, 2, 5, 10, 20, 50)
    }
}

/** The Settings screen's logic: reads and saves the user's choices on this device. */
class SettingsController internal constructor(
    private val settings: JournalSettings,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    /** The platform image model's own threshold ([SimilarityThreshold.defaultPercent]). */
    private val defaultSimilarityPercent: Int = SimilarityThreshold.DEFAULT_PERCENT,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    private val _state = MutableStateFlow(
        SettingsState(
            grouping = settings.grouping,
            similarity = similarity(settings.similarityPercent),
            shownSources = settings.shownSources,
            previewCacheGigabytes = settings.previewCacheGigabytes,
            previewOptions = settings.previewOptions,
            privateMedia = settings.privateMedia,
            showOnPhoneBadge = settings.showOnPhoneBadge,
            searchPageSize = settings.searchPageSize,
        ),
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

    private val _privateMedia = MutableStateFlow(_state.value.privateMedia)

    /** [SettingsState.privateMedia] alone, for the library to follow. */
    val privateMedia: StateFlow<PrivateMedia> = _privateMedia.asStateFlow()

    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: SettingsState get() = _state.value

    fun setGroupingMode(mode: GroupingMode) = changeGrouping { it.copy(mode = mode) }

    /** Values outside [TimelineGrouping.GAP_HOURS_RANGE] are ignored. */
    fun setGapHours(hours: Int) {
        if (hours !in TimelineGrouping.GAP_HOURS_RANGE) return
        changeGrouping { it.copy(gapHours = hours) }
    }

    /** Values outside [SimilarityThreshold.RANGE] are ignored. */
    fun setSimilarityPercent(percent: Int) {
        if (percent !in SimilarityThreshold.RANGE) return
        settings.similarityPercent = percent.takeIf { it != defaultSimilarityPercent }
        _state.update { it.copy(similarity = similarity(percent)) }
    }

    /** Turns a source on or off everywhere (Settings › Show in the app, and the Moments filter bar). */
    fun toggleFeedSource(source: MediaSource) = changeShownSources { shown ->
        shown.copy(sources = if (source in shown.sources) shown.sources - source else shown.sources + source)
    }

    /** "Say what's hidden": the "+6 WhatsApp" chips on the days of Moments. */
    fun setSayWhatsHidden(say: Boolean) = changeShownSources { it.copy(sayWhatsHidden = say) }

    /** "Always show this day": [source]'s photos of [days] show even while the source is hidden. */
    fun alwaysShow(days: List<LocalDay>, source: MediaSource) =
        changeShownSources { shown -> shown.copy(alwaysShown = shown.alwaysShown + days.map { DaySource(it, source) }) }

    private fun changeShownSources(transform: (ShownSources) -> ShownSources) {
        val shown = transform(_state.value.shownSources)
        settings.shownSources = shown
        _state.update { it.copy(shownSources = shown) }
    }

    /** Photos per page of search results; values outside 1–1000 are ignored. Applies from the next page. */
    fun setSearchPageSize(size: Int) {
        if (size !in 1..1000) return
        settings.searchPageSize = size
        _state.update { it.copy(searchPageSize = size) }
    }

    /** The previews' limit; the platform trims them to it. Values below 1 are ignored. */
    fun setPreviewCacheGigabytes(gigabytes: Int) {
        if (gigabytes < 1) return
        settings.previewCacheGigabytes = gigabytes
        _state.update { it.copy(previewCacheGigabytes = gigabytes) }
    }

    /** Previews from the server on, or off (originals are downloaded instead). */
    fun setPreviewsEnabled(enabled: Boolean) = changePreviews { it.copy(enabled = enabled) }

    fun setPreviewFormat(format: PreviewFormat) = changePreviews { it.copy(format = format) }

    fun setPreviewQuality(quality: PreviewQuality) = changePreviews { it.copy(quality = quality) }

    /** The first image's format (Settings → Advanced). */
    fun setFirstImageFormat(format: PreviewFormat) = changePreviews { it.copy(firstFormat = format) }

    /** The first image's width in percent of the window's; 0 for none. Values outside 0..100 are ignored. */
    fun setFirstImageWidth(percent: Int) {
        if (percent in 0..100) changePreviews { it.copy(firstWidthPercent = percent) }
    }

    private fun changePreviews(transform: (PreviewOptions) -> PreviewOptions) {
        val options = transform(_state.value.previewOptions)
        settings.previewOptions = options
        _state.update { it.copy(previewOptions = options) }
    }

    /** "Show private media" on, or off (photos marked private are hidden). */
    fun setShowPrivateMedia(show: Boolean) {
        val privateMedia = PrivateMedia(show)
        settings.privateMedia = privateMedia
        _state.update { it.copy(privateMedia = privateMedia) }
        _privateMedia.value = privateMedia
    }

    /** The phone badge on photos that are on this phone and backed up, on or off. */
    fun setShowOnPhoneBadge(show: Boolean) {
        settings.showOnPhoneBadge = show
        _state.update { it.copy(showOnPhoneBadge = show) }
    }

    /** Back to the platform's own threshold. */
    fun resetSimilarity() {
        settings.similarityPercent = null
        _state.update { it.copy(similarity = similarity(null)) }
    }

    private fun similarity(percent: Int?) = SimilarityThreshold(
        percent = percent?.takeIf { it in SimilarityThreshold.RANGE } ?: defaultSimilarityPercent,
        defaultPercent = defaultSimilarityPercent,
    )

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (SettingsState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() {
        scope.cancel()
    }

    private fun changeGrouping(transform: (TimelineGrouping) -> TimelineGrouping) {
        val grouping = transform(_state.value.grouping)
        settings.grouping = grouping
        _state.update { it.copy(grouping = grouping) }
    }
}
