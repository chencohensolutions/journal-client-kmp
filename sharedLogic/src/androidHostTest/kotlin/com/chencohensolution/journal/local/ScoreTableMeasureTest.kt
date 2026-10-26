package com.chencohensolution.journal.local

import okio.Buffer
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// What the aesthetic scores of 95,000 photos take in memory: as kept now (ShaScores, shared with the library) and as
// they were (a map of hex strings to numbers, held twice). A measurement, not a check: it runs only with JOURNAL_MEASURE=1.
class ScoreTableMeasureTest {
    @Test
    fun scoresAreSmall() {
        if (System.getenv("JOURNAL_MEASURE") != "1") return
        val rows = 95_000
        val random = Random(1)
        val shas = Array(rows) { ByteArray(32).also(random::nextBytes) }

        var before = usedHeap()
        val map = HashMap<String, Float>()
        for (sha in shas) map[sha.hex()] = random.nextFloat()
        val copy = HashMap(map)
        val mapBytes = usedHeap() - before

        before = usedHeap()
        val buffer = Buffer()
        for (sha in shas) buffer.write(sha)
        val compact = ShaScores.of(buffer.readByteArray(), FloatArray(rows) { random.nextFloat() })
        val compactBytes = usedHeap() - before

        val started = System.nanoTime()
        for (sha in shas) compact[sha.hex()]
        val lookupNanos = (System.nanoTime() - started) / rows
        println("Scores of $rows photos: as a map held twice ${mapBytes / 1_000_000} MB, compact ${compactBytes / 1_000_000} MB, a lookup $lookupNanos ns")
        assertEquals(rows, compact.size)
        assertEquals(map.size, copy.size)
    }

    private fun usedHeap(): Long {
        repeat(3) {
            System.gc()
            Thread.sleep(100)
        }
        val runtime = Runtime.getRuntime()
        return runtime.totalMemory() - runtime.freeMemory()
    }
}
