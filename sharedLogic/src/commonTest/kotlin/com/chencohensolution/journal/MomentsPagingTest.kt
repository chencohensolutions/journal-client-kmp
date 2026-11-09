package com.chencohensolution.journal

import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.settings.MomentsPaging
import com.chencohensolution.journal.settings.SettingsController
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class PagingTestStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String): String? = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

/** Settings › Advanced › Loading Moments: the two values kept on the device. */
class MomentsPagingTest {
    @Test
    fun defaultsUntilChosen() {
        assertEquals(MomentsPaging(30, 40), JournalSettings(PagingTestStore()).momentsPaging)
        assertTrue(MomentsPaging().isDefault)
    }

    @Test
    fun keptResetAndOutOfRangeIgnored() = runTest {
        val store = PagingTestStore()
        val settings = SettingsController(JournalSettings(store), StandardTestDispatcher(testScheduler))
        settings.setMomentsRowsPerPage(60)
        settings.setMomentsLoadAhead(10)
        assertEquals(MomentsPaging(60, 10), settings.currentState.momentsPaging)
        assertEquals(MomentsPaging(60, 10), JournalSettings(store).momentsPaging)
        assertEquals(MomentsPaging(60, 10), SettingsController(JournalSettings(store), StandardTestDispatcher(testScheduler)).currentState.momentsPaging)

        settings.setMomentsRowsPerPage(0)
        settings.setMomentsLoadAhead(5000)
        assertEquals(MomentsPaging(60, 10), settings.currentState.momentsPaging)

        settings.resetMomentsPaging()
        assertTrue(settings.currentState.momentsPaging.isDefault)
        assertEquals(MomentsPaging(), JournalSettings(store).momentsPaging)
        settings.close()
    }

    @Test
    fun theViewersPhotosMadeReadyAheadAreFiveUntilChosen() = runTest {
        val store = PagingTestStore()
        assertEquals(5, JournalSettings(store).momentsPaging.viewerAhead)
        val settings = SettingsController(JournalSettings(store), StandardTestDispatcher(testScheduler))
        settings.setViewerAhead(8)
        assertEquals(8, settings.currentState.momentsPaging.viewerAhead)
        assertEquals(8, JournalSettings(store).momentsPaging.viewerAhead)
        assertTrue(!settings.currentState.momentsPaging.isDefault)

        // None is a choice; past ten is ignored, and so is an unreadable value kept on the device.
        settings.setViewerAhead(0)
        assertEquals(0, JournalSettings(store).momentsPaging.viewerAhead)
        settings.setViewerAhead(11)
        assertEquals(0, settings.currentState.momentsPaging.viewerAhead)
        store.values["viewer_ahead"] = "many"
        assertEquals(5, JournalSettings(store).momentsPaging.viewerAhead)

        settings.resetMomentsPaging()
        assertEquals(5, settings.currentState.momentsPaging.viewerAhead)
        settings.close()
    }

    @Test
    fun anUnreadableValueFallsBackOnItsOwn() {
        val store = PagingTestStore()
        store.values["moments_rows_per_page"] = "lots"
        store.values["moments_load_ahead"] = "80"
        assertEquals(MomentsPaging(30, 80), JournalSettings(store).momentsPaging)
    }
}
