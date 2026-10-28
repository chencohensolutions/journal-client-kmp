package com.chencohensolution.journal

import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.SettingsController
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class BadgeTestStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

class PhoneBadgeTest {
    @Test
    fun offByDefaultAndKeptWhenTurnedOn() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = BadgeTestStore()
        val settings = SettingsController(JournalSettings(store), dispatcher)
        assertFalse(settings.currentState.showOnPhoneBadge)

        settings.setShowOnPhoneBadge(true)
        assertTrue(settings.currentState.showOnPhoneBadge)
        assertTrue(SettingsController(JournalSettings(store), dispatcher).currentState.showOnPhoneBadge)
    }
}

class ShareJpegXlTest {
    @Test
    fun jpegXlIsSharedAsJpegUntilTurnedOff() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val store = BadgeTestStore()
        val settings = SettingsController(JournalSettings(store), dispatcher)
        assertTrue(settings.currentState.shareJpegXlAsJpeg)

        settings.setShareJpegXlAsJpeg(false)
        assertFalse(settings.currentState.shareJpegXlAsJpeg)
        assertFalse(SettingsController(JournalSettings(store), dispatcher).currentState.shareJpegXlAsJpeg)
    }
}
