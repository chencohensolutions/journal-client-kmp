package com.chencohensolution.journal.local

import okio.Buffer
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

// What a faces table of 120,000 faces in 40,000 photos (SFace's 128 numbers) takes in memory, how long it takes to open
// and to read a face's vector (plan B phase 7), beside what the same faces took held as before (a box and a vector
// array a file, by sha256). A measurement, not a check: it writes 63 MB, so it runs only with JOURNAL_MEASURE=1.
class FaceTableMeasureTest {
    @Test
    fun aLargeFacesTableHoldsOnlyTheBoxes() {
        if (System.getenv("JOURNAL_MEASURE") != "1") return
        val files = 40_000
        val dim = 128
        val folder = Files.createTempDirectory("faces").toOkioPath()
        val path = folder / "table.bin"
        val random = Random(1)
        val shas = (0 until files).map { it.toString(16).padStart(64, '0') }
        // One to five faces a file, three on average: 120,000 in all.
        val counts = IntArray(files) { 1 + it % 5 }
        val writing = FaceTable.create(TableHead(index = "faces", model = "m", format = "faces", dim = dim), FileSystem.SYSTEM, folder)
        for (f in 0 until files) {
            val record = Buffer().write(hexBytes(shas[f])).writeShortLe(counts[f])
            repeat(counts[f]) { repeat(15 + dim) { record.writeIntLe((random.nextFloat() - 0.5f).toBits()) } }
            writing.put(record.readByteArray())
        }
        writing.save(FileSystem.SYSTEM, path)
        val faces = counts.sum()

        // As before: each file's boxes and vectors in two maps.
        var before = usedHeap()
        val boxes = HashMap<String, FloatArray>()
        val vectors = HashMap<String, FloatArray>()
        for (f in 0 until files) {
            boxes[shas[f]] = FloatArray(counts[f] * 4) { random.nextFloat() }
            vectors[shas[f]] = FloatArray(counts[f] * dim) { random.nextFloat() }
        }
        val heldBefore = usedHeap() - before
        assertEquals(files, boxes.size + vectors.size - files)
        boxes.clear()
        vectors.clear()

        before = usedHeap()
        val started = System.nanoTime()
        val table = LocalTable.load(FileSystem.SYSTEM, path) as FaceTable
        val loadMillis = (System.nanoTime() - started) / 1_000_000
        val held = usedHeap() - before

        // Opened again, with the code warmed up and the file in the system's cache.
        val againStarted = System.nanoTime()
        LocalTable.load(FileSystem.SYSTEM, path) as FaceTable
        val againMillis = (System.nanoTime() - againStarted) / 1_000_000
        val lookups = 2_000
        repeat(200) { table.vector(shas[random.nextInt(files)], 0) }
        val lookupStarted = System.nanoTime()
        repeat(lookups) {
            val f = random.nextInt(files)
            assertNotNull(table.vector(shas[f], random.nextInt(counts[f])))
            assertNotNull(table.box(shas[f], 0))
        }
        val lookupMicros = (System.nanoTime() - lookupStarted) / 1_000 / lookups
        println(
            "FaceTable, $faces faces in $files files of $dim: file ${table.bytes(FileSystem.SYSTEM, path) / 1_000_000} MB, " +
                "held ${held / 100_000 / 10.0} MB (as before: ${heldBefore / 100_000 / 10.0} MB), opened in $loadMillis ms (again $againMillis ms), " +
                "a face's box and vector $lookupMicros µs",
        )
        assertEquals(files, table.size)
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
