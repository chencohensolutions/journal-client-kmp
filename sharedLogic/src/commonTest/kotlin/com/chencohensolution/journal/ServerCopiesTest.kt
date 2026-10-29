package com.chencohensolution.journal

import com.chencohensolution.journal.server.CopiesApi
import com.chencohensolution.journal.server.CopiesSummary
import com.chencohensolution.journal.server.CopyHealth
import com.chencohensolution.journal.server.CopyPass
import com.chencohensolution.journal.server.CopyProblem
import com.chencohensolution.journal.server.CopyRunning
import com.chencohensolution.journal.server.DataCopy
import com.chencohensolution.journal.server.ServerCopies
import com.chencohensolution.journal.server.ServerCopiesState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An up-to-date copy, as the server sends one after a pass. */
private fun copy(folder: String = "E:\\Journal copy\\hen") = DataCopy(
    folder = folder, realtime = true, dailyAt = "03:30", sameDisk = false,
    originals = 100, copiedFiles = 100, missingFiles = 0, missingBytes = 0, inCopyOnly = 0, freeBytes = 1_000_000,
    checkedAtMillis = 1_000, checking = false, running = null, lastPass = CopyPass(900, 10, 5_000, 0),
    problem = null, problemSinceMillis = null,
)

/** A server whose copies are as the test sets them, counting what it was asked. */
private class FakeCopiesApi : CopiesApi {
    var now = listOf(copy())
    var calls = 0
    var started = mutableListOf<String>()
    var fail = false

    override suspend fun copies(): List<DataCopy> {
        calls++
        if (fail) throw IllegalStateException("can't reach the server")
        return now
    }

    override suspend fun start(folder: String): DataCopy {
        started += folder
        now = now.map { if (it.folder == folder) it.copy(running = CopyRunning(2_000, counting = true)) else it }
        return now.first { it.folder == folder }
    }

    override suspend fun stop(folder: String): DataCopy {
        stopped += folder
        now = now.map {
            if (it.folder == folder) it.copy(running = null, lastPass = CopyPass(3_000, 4, 400, 0, stopped = true)) else it
        }
        return now.first { it.folder == folder }
    }

    var stopped = mutableListOf<String>()
}

@OptIn(ExperimentalCoroutinesApi::class)
class ServerCopiesTest {
    @Test
    fun healthIsTheWorstOfACopysState() {
        assertEquals(CopyHealth.OK, copy().health)
        assertEquals(CopyHealth.BUSY, copy().copy(copiedFiles = 90, missingFiles = 10).health)
        assertEquals(CopyHealth.BUSY, copy().copy(running = CopyRunning(0)).health)
        assertEquals(CopyHealth.WARN, copy().copy(sameDisk = true).health)
        assertEquals(CopyHealth.WARN, copy().copy(lastPass = CopyPass(900, 10, 5_000, 2)).health)
        assertEquals(CopyHealth.ERROR, copy().copy(problem = CopyProblem.FOLDER_MISSING, sameDisk = true).health)
        assertEquals(CopyHealth.ERROR, copy().copy(problem = CopyProblem.DISK_FULL).health)
    }

    @Test
    fun theSummarySaysTheWorstFirst() {
        val e = copy("E:\\copy")
        val f = copy("F:\\hen")
        assertNull(ServerCopiesState().summary)
        assertEquals(CopiesSummary.AllUpToDate(2), ServerCopiesState(listOf(e, f)).summary)
        assertEquals(CopiesSummary.SomeUpToDate(1, 2), ServerCopiesState(listOf(e, f.copy(missingFiles = 3))).summary)
        assertEquals(CopiesSummary.Copying("F:\\hen"), ServerCopiesState(listOf(e, f.copy(running = CopyRunning(0)))).summary)
        assertEquals(
            CopiesSummary.NeedsALook("F:\\hen"),
            ServerCopiesState(listOf(e.copy(running = CopyRunning(0)), f.copy(problem = CopyProblem.FOLDER_MISSING))).summary,
        )
        assertEquals(CopiesSummary.NeedsALook("E:\\copy"), ServerCopiesState(listOf(e.copy(sameDisk = true), f)).summary)
        assertEquals(CopiesSummary.Checking, ServerCopiesState(listOf(e.copy(checkedAtMillis = null, checking = true))).summary)
    }

    @Test
    fun aCopyOnAFriendsServerNeedsALookWhenOutOfReachOrFull() {
        val dana = copy("dana").copy(onPeer = true)
        assertEquals(CopyHealth.OK, dana.health)
        val unreachable = dana.copy(problem = CopyProblem.PEER_UNREACHABLE, problemSinceMillis = 500, freeBytes = null)
        assertEquals(CopyHealth.ERROR, unreachable.health)
        assertEquals(CopyHealth.ERROR, dana.copy(problem = CopyProblem.QUOTA_FULL).health)
        assertEquals(CopiesSummary.NeedsALook("dana"), ServerCopiesState(listOf(copy(), unreachable)).summary)
        // Copy now tries a friend's server again (it may be back), but not a folder that can't be found.
        assertTrue(unreachable.canCopyNow)
        assertFalse(copy().copy(problem = CopyProblem.FOLDER_MISSING).canCopyNow)
        assertFalse(dana.copy(running = CopyRunning(0)).canCopyNow)
    }

    @Test
    fun whileAPassCopiesTheCopyHoldsAllButWhatIsLeft() {
        // The compare found 0 (a new copy); the pass counted 100 to copy and has 40 in place, 4 of 10 GB written.
        val c = copy().copy(copiedFiles = 0, missingFiles = 100)
        assertEquals(0, c.copy(running = CopyRunning(0, counting = true)).heldFiles)
        val copying = c.copy(running = CopyRunning(0, totalFiles = 100, totalBytes = 10_000, doneFiles = 40, doneBytes = 4_000))
        assertEquals(40, copying.heldFiles)
        assertEquals(0.4f, copying.running!!.fraction)
        assertEquals(0, c.heldFiles, "without a pass: the compare's count")
    }

    @Test
    fun timeLeftIsThePassRateSoFar() {
        // 140 of 412 GB in 25 minutes: the 272 GB left take about 48.6 minutes more.
        val running = CopyRunning(0, totalBytes = 412_000_000_000, doneBytes = 140_000_000_000)
        assertEquals(2_914, running.remainingMillis(25 * 60_000)!! / 1_000)
        assertNull(running.copy(doneBytes = 0).remainingMillis(1_000))
        assertNull(CopyRunning(0, counting = true).remainingMillis(1_000))
    }

    @Test
    fun stopEndsThePassAndSaysSo() = runTest {
        val api = FakeCopiesApi()
        api.now = listOf(copy().copy(running = CopyRunning(0, totalFiles = 10, totalBytes = 1_000)))
        val copies = ServerCopies(api, StandardTestDispatcher(testScheduler))
        copies.refresh()
        runCurrent()
        copies.stop("E:\\Journal copy\\hen")
        runCurrent()
        assertEquals(listOf("E:\\Journal copy\\hen"), api.stopped)
        val copy = copies.currentState.copies.single()
        assertNull(copy.running)
        assertTrue(copy.lastPass!!.stopped)
        copies.close()
    }

    @Test
    fun theOldestCompareIsWhatWasChecked() {
        val state = ServerCopiesState(listOf(copy("a").copy(checkedAtMillis = 5_000), copy("b").copy(checkedAtMillis = 3_000)))
        assertEquals(3_000, state.checkedAtMillis)
    }

    @Test
    fun watchingAsksOftenWhileAPassGoesAndSeldomOtherwise() = runTest {
        val api = FakeCopiesApi()
        val copies = ServerCopies(api, StandardTestDispatcher(testScheduler), busyEveryMillis = 3_000, idleEveryMillis = 30_000)
        val watch = copies.watch()
        runCurrent()
        assertEquals(1, api.calls)
        assertTrue(copies.currentState.loaded)
        advanceTimeBy(10_000)
        assertEquals(1, api.calls, "idle: asked again before 30 s")
        api.now = listOf(copy().copy(checking = true))
        advanceTimeBy(20_001)
        assertEquals(2, api.calls)
        advanceTimeBy(6_001)
        assertEquals(4, api.calls, "comparing: asked every 3 s")
        watch.cancel()
        advanceTimeBy(60_000)
        assertEquals(4, api.calls, "asked after the watch ended")
        copies.close()
    }

    @Test
    fun copyNowShowsThePassAndIsFollowedAtOnce() = runTest {
        val api = FakeCopiesApi()
        val copies = ServerCopies(api, StandardTestDispatcher(testScheduler), busyEveryMillis = 3_000, idleEveryMillis = 30_000)
        val watch = copies.watch()
        runCurrent()
        copies.copyNow("E:\\Journal copy\\hen")
        assertTrue(copies.currentState.acting)
        runCurrent()
        assertEquals(listOf("E:\\Journal copy\\hen"), api.started)
        assertFalse(copies.currentState.acting)
        assertEquals(CopiesSummary.Copying("E:\\Journal copy\\hen"), copies.currentState.summary)
        assertEquals(2, api.calls, "the watch didn't ask again after Copy now")
        advanceTimeBy(3_001)
        assertEquals(3, api.calls, "copying: asked again 3 s later")
        watch.cancel()
        copies.close()
    }

    @Test
    fun aFailedCallKeepsWhatWasShownWithTheError() = runTest {
        val api = FakeCopiesApi()
        val copies = ServerCopies(api, StandardTestDispatcher(testScheduler))
        copies.refresh()
        runCurrent()
        api.fail = true
        copies.refresh()
        runCurrent()
        assertEquals("can't reach the server", copies.currentState.error)
        assertEquals(1, copies.currentState.copies.size)
        copies.close()
    }
}
