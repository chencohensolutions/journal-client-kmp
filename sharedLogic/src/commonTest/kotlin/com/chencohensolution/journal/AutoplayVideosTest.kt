package com.chencohensolution.journal

import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.SettingsController
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class AutoplayTestStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** The viewer's videos playing by themselves (the slideshow bar's button), kept on the device. */
class AutoplayVideosTest {
    @Test
    fun onUntilTurnedOffAndKept() = runTest {
        val store = AutoplayTestStore()
        assertTrue(JournalSettings(store).autoplayVideos)
        val settings = SettingsController(JournalSettings(store), StandardTestDispatcher(testScheduler))
        assertTrue(settings.currentState.autoplayVideos)

        settings.setAutoplayVideos(false)
        assertFalse(settings.currentState.autoplayVideos)
        assertFalse(JournalSettings(store).autoplayVideos)
        assertFalse(SettingsController(JournalSettings(store), StandardTestDispatcher(testScheduler)).currentState.autoplayVideos)

        settings.setAutoplayVideos(true)
        assertTrue(JournalSettings(store).autoplayVideos)
        settings.close()
    }
}
