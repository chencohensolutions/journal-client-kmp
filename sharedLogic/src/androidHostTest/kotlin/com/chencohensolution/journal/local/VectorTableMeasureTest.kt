package com.chencohensolution.journal.local

import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// What a search table of 95,000 photos (SigLIP's 768 numbers) takes in memory, and how long a search reads it (plan B
// phase 7). A measurement, not a check: it writes 73 MB, so it runs only with JOURNAL_MEASURE=1.
class VectorTableMeasureTest {
    @Test
    fun aLargeTableIsReadNotHeld() {
        if (System.getenv("JOURNAL_MEASURE") != "1") return
        val rows = 95_000
        val dim = 768
        val folder = Files.createTempDirectory("vectors").toOkioPath()
        val path = folder / "table.bin"
        val random = Random(1)
        val writing = VectorTable.create(TableHead(index = "search", model = "m", format = "vectors", dim = dim), FileSystem.SYSTEM, folder)
        for (n in 0 until rows) writing.set(n.toString(16).padStart(64, '0'), FloatArray(dim) { random.nextFloat() - 0.5f })
        writing.save(FileSystem.SYSTEM, path)

        val before = usedHeap()
        val started = System.nanoTime()
        val table = LocalTable.load(FileSystem.SYSTEM, path) as VectorTable
        val loadMillis = (System.nanoTime() - started) / 1_000_000
        val held = usedHeap() - before
        val query = FloatArray(dim) { random.nextFloat() - 0.5f }
        table.top(query, 200) { true }
        val searches = 5
        val searchStarted = System.nanoTime()
        repeat(searches) { table.top(query, 200) { true } }
        val searchMillis = (System.nanoTime() - searchStarted) / 1_000_000 / searches
        println("VectorTable, $rows rows of $dim: file ${table.bytes(FileSystem.SYSTEM, path) / 1_000_000} MB, held ${held / 1_000_000} MB, opened in $loadMillis ms, a search $searchMillis ms")
        assertEquals(rows, table.size)
        FileSystem.SYSTEM.deleteRecursively(folder)
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
