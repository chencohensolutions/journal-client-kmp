package com.chencohensolution.journal

import com.chencohensolution.journal.server.IndexingApi
import com.chencohensolution.journal.server.IndexingRunState
import com.chencohensolution.journal.server.ServerIndexing
import com.chencohensolution.journal.server.ServerIndexingState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

/** A server whose run goes as the test sets it, counting what it was asked. */
private class FakeIndexingApi : IndexingApi {
    var now = ServerIndexingState(loaded = true)
    var statusCalls = 0
    var fail = false

    override suspend fun status(): ServerIndexingState {
        statusCalls++
        if (fail) throw IllegalStateException("can't reach the server")
        return now
    }

    override suspend fun start(): ServerIndexingState {
        now = now.copy(run = IndexingRunState.RUNNING, total = 10)
        return now
    }

    override suspend fun pause(): ServerIndexingState {
        now = now.copy(run = IndexingRunState.PAUSED)
        return now
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class ServerIndexingTest {
    @Test
    fun startAndPauseShowWhatTheServerAnswers() = runTest {
        val api = FakeIndexingApi()
        val indexing = ServerIndexing(api, StandardTestDispatcher(testScheduler))
        indexing.start()
        assertEquals(true, indexing.currentState.acting)
        runCurrent()
        assertEquals(IndexingRunState.RUNNING, indexing.currentState.run)
        assertFalse(indexing.currentState.acting)
        indexing.pause()
        runCurrent()
        assertEquals(IndexingRunState.PAUSED, indexing.currentState.run)
        indexing.close()
    }

    @Test
    fun watchingAsksOftenWhileARunGoesAndSeldomOtherwise() = runTest {
        val api = FakeIndexingApi()
        val indexing = ServerIndexing(api, StandardTestDispatcher(testScheduler), busyEveryMillis = 2_000, idleEveryMillis = 30_000)
        val watch = indexing.watch()
        runCurrent()
        assertEquals(1, api.statusCalls)
        advanceTimeBy(10_000)
        assertEquals(1, api.statusCalls, "idle: asked again before 30 s")
        api.now = api.now.copy(run = IndexingRunState.RUNNING, done = 3, total = 10)
        advanceTimeBy(20_001)
        assertEquals(2, api.statusCalls)
        advanceTimeBy(4_001)
        assertEquals(4, api.statusCalls, "running: asked every 2 s")
        assertEquals(0.3f, indexing.currentState.fraction)
        watch.cancel()
        advanceTimeBy(10_000)
        assertEquals(4, api.statusCalls, "asked after the watch ended")
        indexing.close()
    }

    @Test
    fun aFailedCallKeepsWhatWasShownWithTheError() = runTest {
        val api = FakeIndexingApi()
        api.now = ServerIndexingState(run = IndexingRunState.RUNNING, done = 4, total = 8, loaded = true)
        val indexing = ServerIndexing(api, StandardTestDispatcher(testScheduler))
        indexing.refresh()
        runCurrent()
        assertNull(indexing.currentState.error)
        api.fail = true
        indexing.refresh()
        runCurrent()
        assertEquals("can't reach the server", indexing.currentState.error)
        assertEquals(4, indexing.currentState.done)
        indexing.close()
    }

    @Test
    fun startingARunIsFollowedAtOnceNotAfterTheIdleWait() = runTest {
        val api = FakeIndexingApi()
        val indexing = ServerIndexing(api, StandardTestDispatcher(testScheduler), busyEveryMillis = 2_000, idleEveryMillis = 30_000)
        val watch = indexing.watch()
        runCurrent()
        assertEquals(1, api.statusCalls)
        indexing.start()
        runCurrent()
        assertEquals(2, api.statusCalls, "the watch didn't ask again after start")
        advanceTimeBy(2_001)
        assertEquals(3, api.statusCalls, "running: asked again 2 s later")
        watch.cancel()
        indexing.close()
    }
}
