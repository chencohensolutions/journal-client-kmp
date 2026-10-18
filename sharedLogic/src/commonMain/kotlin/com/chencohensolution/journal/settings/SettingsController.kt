package com.chencohensolution.journal.settings

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.stories.MomentsFeed
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

/** The user's choices on the Settings screen, and the Moments tab's filter bar and trip answers. */
data class SettingsState(
    val grouping: TimelineGrouping = TimelineGrouping(),
    val similarity: SimilarityThreshold = SimilarityThreshold(),
    val feedSources: Set<MediaSource> = MomentsFeed.DEFAULT_SOURCES,
    val dismissedTrips: Set<String> = emptySet(),
)

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
            feedSources = settings.feedSources,
            dismissedTrips = settings.dismissedTrips,
        ),
    )
    val state: StateFlow<SettingsState> = _state.asStateFlow()

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

    /** Turns a source on or off in the Moments tab's filter bar. */
    fun toggleFeedSource(source: MediaSource) {
        val current = _state.value.feedSources
        val sources = if (source in current) current - source else current + source
        settings.feedSources = sources
        _state.update { it.copy(feedSources = sources) }
    }

    /** "Not now" on a trip suggestion: it isn't offered again. */
    fun dismissTrip(key: String) {
        val trips = _state.value.dismissedTrips + key
        settings.dismissedTrips = trips
        _state.update { it.copy(dismissedTrips = trips) }
    }

    /**
     * The photo a swipe set in Moments was last turned to ([setKey]: the set's first photo's id), so it opens there next
     * time; not part of [state], as nothing else changes with it.
     */
    fun feedPage(setKey: String): String? = settings.feedPage(setKey)

    /** Remembers the photo a swipe set is turned to; its first photo is not kept (it is where a set opens anyway). */
    fun setFeedPage(setKey: String, itemId: String) {
        val value = itemId.takeIf { it != setKey }
        if (settings.feedPage(setKey) != value) settings.setFeedPage(setKey, value)
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
