package com.chencohensolution.journal.local

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.settings.JournalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** The onboarding's Models on this phone step. */
data class ModelsOnboardingState(
    /** The step shows: the device just enrolled, Continue wasn't pressed, and the server offers a model (or hasn't said yet). */
    val show: Boolean = false,
    /** The same models as Settings › Models on this phone. */
    val models: PhoneModelsState = PhoneModelsState(),
    /** The Search by words option is checked (its model and index download, on Wi-Fi when Only on Wi-Fi is on). */
    val searchByWords: Boolean = true,
)

/**
 * The step after enrollment and photo access that downloads the models the app runs here (Nov 8, 2026,
 * docs/screens/enrollment.md "Models on this phone"), on [PhoneModels], the same state and downloads as Settings ›
 * Models on this phone. Photo similarity starts at once, whatever the network (11 MB); Search by words is an option,
 * checked at first, that follows Only on Wi-Fi. Continue never waits: what is downloading goes on in the background.
 * The step is skipped when the server offers no model, or can't be reached; the Models page is there for later.
 */
class ModelsOnboarding internal constructor(
    private val settings: JournalSettings,
    private val models: PhoneModels,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val due = MutableStateFlow(settings.modelsOnboardingDue)
    private val searchByWords = MutableStateFlow(true)
    private var started = false

    val state: StateFlow<ModelsOnboardingState> = combine(due, models.state, searchByWords) { due, models, words ->
        ModelsOnboardingState(show = due && !(models.known && models.nothingOffered), models = models, searchByWords = words)
    }.stateIn(scope, SharingStarted.Eagerly, ModelsOnboardingState())

    /** Same as `state.value`, typed for Swift. */
    val currentState: ModelsOnboardingState get() = state.value

    init {
        // The server answered that it offers nothing (or couldn't be reached): the step is done without showing.
        scope.launch {
            combine(due, models.state) { due, models -> due && models.known && models.nothingOffered }.collect { skip ->
                if (skip) finish()
            }
        }
    }

    /** The device enrolled: the step shows after photo access. */
    fun enrolled() {
        settings.modelsOnboardingDue = true
        started = false
        due.value = true
    }

    /** The step showed: asks the server what it offers, starts the photo model, and Search by words if checked. */
    fun start() {
        if (started) return
        started = true
        models.refresh()
        models.download(PhoneModelKind.PHOTOS, force = true)
        if (searchByWords.value) models.download(PhoneModelKind.WORDS)
    }

    /** The Search by words option: checked downloads it (on Wi-Fi when Only on Wi-Fi is on), unchecked stops and deletes it. */
    fun setSearchByWords(on: Boolean) {
        searchByWords.value = on
        if (on) models.download(PhoneModelKind.WORDS) else models.delete(PhoneModelKind.WORDS)
    }

    fun setWifiOnly(on: Boolean) = models.setWifiOnly(on)

    /** Continue: the step is done; downloads go on in the background, and the Models page shows them. */
    fun finish() {
        settings.modelsOnboardingDue = false
        due.value = false
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (ModelsOnboardingState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() = scope.cancel()
}
