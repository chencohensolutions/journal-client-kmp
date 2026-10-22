package com.chencohensolution.journal.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether a screen behind the phone's own lock (fingerprint, face or PIN) may show. It starts locked, opens once the
 * platform's prompt is passed, and locks again when the app goes to the background. It is never saved, so a screen
 * brought back after the app was closed asks again. The prompt itself is the platform's.
 */
class ScreenLock {
    private val _state = MutableStateFlow(false)

    /** Whether the screen is unlocked. */
    val state: StateFlow<Boolean> = _state.asStateFlow()

    /** Same as `state.value`, for Swift. */
    val isUnlocked: Boolean get() = _state.value

    /** The phone's lock was passed. */
    fun unlocked() {
        _state.value = true
    }

    /** The app went to the background: ask again when it comes back. */
    fun wentToBackground() {
        _state.value = false
    }
}
