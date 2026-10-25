package com.chencohensolution.journal

import com.chencohensolution.journal.backup.BackupActivity
import com.chencohensolution.journal.backup.BackupProgress
import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.connection.ConnectionStatus
import kotlinx.coroutines.flow.MutableStateFlow
import com.chencohensolution.journal.connection.JournalState
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.LibraryController
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private class FakeLibrary(
    var access: PhotoAccess = PhotoAccess.FULL,
    var items: List<MediaItem> = emptyList(),
    var failure: Exception? = null,
) : MediaLibrary {
    override fun access() = access
    override suspend fun loadItems(): List<MediaItem> = failure?.let { throw it } ?: items
}

private class LibraryTestStore : KeyValueStore {
    private val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private fun item(
    id: String,
    day: LocalDay,
    takenAt: Long,
    type: MediaType = MediaType.IMAGE,
    size: Long? = 100,
    backup: BackupStatus = BackupStatus.NotBackedUp,
) = MediaItem(id, type, takenAt, day, if (type == MediaType.VIDEO) 1_000 else null, 10, 10, size, "$id.jpg", backup)

private val OCT_8 = LocalDay(2026, 10, 8)
private val OCT_16 = LocalDay(2026, 10, 16)
private val NOV_1 = LocalDay(2026, 11, 1)

class LibraryControllerTest {
    private fun TestScope.controller(library: FakeLibrary, store: KeyValueStore = LibraryTestStore()) =
        LibraryController(library, JournalSettings(store), StandardTestDispatcher(testScheduler))

    @Test
    fun groupsByDayNewestFirst() = runTest {
        val library = FakeLibrary(
            items = listOf(
                item("a", OCT_8, 1),
                item("b", OCT_16, 5),
                item("c", NOV_1, 9),
                item("d", OCT_16, 7),
            )
        )
        val controller = controller(library)
        controller.refresh()
        advanceUntilIdle()

        val days = controller.currentState.days
        assertEquals(listOf(NOV_1, OCT_16, OCT_8), days.map { it.day })
        assertEquals(listOf("d", "b"), days[1].items.map { it.id })
        assertEquals(4, controller.currentState.itemCount)
        assertFalse(controller.currentState.loading)
    }

    @Test
    fun countsWhatIsNotBackedUp() = runTest {
        val library = FakeLibrary(
            items = listOf(
                item("a", OCT_8, 1, size = 300),
                item("b", OCT_8, 2, size = null),
                item("c", OCT_8, 3, size = 50, backup = BackupStatus.BackedUp),
                item("d", OCT_8, 4, size = 20, backup = BackupStatus.Uploading(0.5f)),
            )
        )
        val controller = controller(library)
        controller.refresh()
        advanceUntilIdle()

        assertEquals(3, controller.currentState.notBackedUpCount)
        assertEquals(320, controller.currentState.notBackedUpBytes)
    }

    @Test
    fun deniedBeforeAskingIsNotDetermined() = runTest {
        val store = LibraryTestStore()
        val library = FakeLibrary(access = PhotoAccess.DENIED, items = listOf(item("a", OCT_8, 1)))
        val controller = controller(library, store)
        controller.refresh()
        advanceUntilIdle()

        assertEquals(PhotoAccess.NOT_DETERMINED, controller.currentState.access)
        assertTrue(controller.currentState.days.isEmpty())

        controller.onAccessRequested()
        advanceUntilIdle()
        assertEquals(PhotoAccess.DENIED, controller.currentState.access)
        assertTrue(controller.accessRequested)
        assertTrue(JournalSettings(store).photoAccessRequested)
    }

    @Test
    fun grantingAccessLoadsTheLibrary() = runTest {
        val library = FakeLibrary(access = PhotoAccess.DENIED, items = listOf(item("a", OCT_8, 1)))
        val controller = controller(library)

        library.access = PhotoAccess.LIMITED
        controller.onAccessRequested()
        advanceUntilIdle()

        assertEquals(PhotoAccess.LIMITED, controller.currentState.access)
        assertEquals(1, controller.currentState.itemCount)
    }

    @Test
    fun revokingAccessClearsItems() = runTest {
        val library = FakeLibrary(items = listOf(item("a", OCT_8, 1)))
        val controller = controller(library)
        controller.refresh()
        advanceUntilIdle()

        library.access = PhotoAccess.DENIED
        controller.refresh()
        advanceUntilIdle()

        assertEquals(PhotoAccess.DENIED, controller.currentState.access)
        assertEquals(0, controller.currentState.itemCount)
    }

    @Test
    fun skipAccessRemembersTheAnswer() = runTest {
        val library = FakeLibrary(access = PhotoAccess.DENIED)
        val controller = controller(library)
        assertEquals(PhotoAccess.NOT_DETERMINED, controller.currentState.access)

        controller.skipAccess()
        assertEquals(PhotoAccess.DENIED, controller.currentState.access)
    }

    @Test
    fun loadFailureIsReported() = runTest {
        val controller = controller(FakeLibrary(failure = IllegalStateException("disk gone")))
        controller.refresh()
        advanceUntilIdle()

        assertEquals("disk gone", controller.currentState.loadError)
        assertFalse(controller.currentState.loading)
    }
}

class ServerHealthTest {
    private fun health(status: ConnectionStatus) = JournalState("https://x/query", "alias", status).health

    @Test
    fun onlyProblemsAreReported() {
        assertEquals(ServerHealth.OK, health(ConnectionStatus.Connected))
        assertEquals(ServerHealth.OK, health(ConnectionStatus.Connecting("now", emptyList())))
        assertEquals(ServerHealth.OK, health(ConnectionStatus.NotConnected))
        assertEquals(ServerHealth.OFFLINE, health(ConnectionStatus.Error("timeout")))
        assertEquals(ServerHealth.OFFLINE, health(ConnectionStatus.Failed(502)))
        assertEquals(ServerHealth.REJECTED, health(ConnectionStatus.Failed(401)))
        assertEquals(ServerHealth.REJECTED, health(ConnectionStatus.MissingIdentity))
    }

    @Test
    fun backupRatesDontRebuildTheLibraryButStatusChangesDo() = runTest {
        val backup = MutableStateFlow(BackupState(settings = BackupSettings(enabled = true)))
        val library = FakeLibrary(items = listOf(item("a", OCT_8, 1), item("b", OCT_8, 2)))
        val controller = LibraryController(library, JournalSettings(LibraryTestStore()), StandardTestDispatcher(testScheduler), backup = backup)
        controller.refresh()
        advanceUntilIdle()

        fun running(fraction: Float, rate: Long) = BackupState(
            settings = BackupSettings(enabled = true),
            activity = BackupActivity.Running(BackupProgress(0, 2, 10, 100, currentId = "a", currentFraction = fraction, bytesPerSecond = rate)),
        )
        backup.value = running(0.41f, 1_000)
        advanceUntilIdle()
        val first = controller.currentState
        assertEquals(BackupStatus.Uploading(0.41f), first.days.single().items.first { it.id == "a" }.backup)

        // Only the rate and a sliver of progress changed: the same state, so nothing redraws.
        backup.value = running(0.42f, 5_000)
        advanceUntilIdle()
        assertTrue(first === controller.currentState)

        // The ring moved a step: a new state, and the photo that didn't change is the same object.
        backup.value = running(0.60f, 5_000)
        advanceUntilIdle()
        val second = controller.currentState
        assertFalse(first === second)
        assertTrue(first.days.single().items.first { it.id == "b" } === second.days.single().items.first { it.id == "b" })
    }
}
