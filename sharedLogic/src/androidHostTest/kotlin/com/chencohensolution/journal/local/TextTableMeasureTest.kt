package com.chencohensolution.journal.local

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.addJsonObject
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

// What the text in photos of 95,000 photos takes in memory on the phone, and how long a search takes. About 30% of
// photos have text, 1 to 15 lines each, a few (receipts, screenshots) 40 or more; Hebrew and English words, with
// numbers, prices and dates as receipts have. A measurement, not a check: it runs only with JOURNAL_MEASURE=1. On the
// PC, Oct 26, 2026: with the lines held whole and maps of words, 45 MB and 97 MB; with the lines read from their file
// and TextWords, 6 MB and 14 MB (plan B phase 7).
class TextTableMeasureTest {
    @Test
    fun theTextOfALargeLibrary() {
        if (System.getenv("JOURNAL_MEASURE") != "1") return
        val photos = 95_000
        val random = Random(2)
        val hebrew = List(12_000) { word(random, 'א', 'ת', 2..7) }
        val english = List(12_000) { word(random, 'a', 'z', 2..9) }
        val folder = Files.createTempDirectory("text").toOkioPath()
        val path = folder / "table.bin"
        val writing = LocalTable.of(TableHead(index = "ocr", model = "m", format = "jsonl", dim = 0), FileSystem.SYSTEM, folder)
        var withText = 0
        var lineCount = 0
        for (n in 0 until photos) {
            // Every photo read has a record; most have no lines.
            val lines = when {
                random.nextDouble() >= 0.3 -> 0
                random.nextDouble() < 0.05 -> 40 + random.nextInt(60)
                else -> 1 + random.nextInt(15)
            }
            if (lines > 0) withText++
            lineCount += lines
            val record = buildJsonObject {
                put("sha256", n.toString(16).padStart(64, '0'))
                putJsonArray("lines") {
                    repeat(lines) { addJsonObject { put("text", line(random, hebrew, english)); put("score", 0.9) } }
                }
            }
            writing.put(Json.encodeToString(record).encodeToByteArray())
        }
        writing.save(FileSystem.SYSTEM, path)

        val before = usedHeap()
        val started = System.nanoTime()
        val table = LocalTable.load(FileSystem.SYSTEM, path) as TextTable
        val loadMillis = (System.nanoTime() - started) / 1_000_000
        val tableHeld = usedHeap() - before
        val indexStarted = System.nanoTime()
        val words = wordIndex(table)
        val indexMillis = (System.nanoTime() - indexStarted) / 1_000_000
        val wordsHeld = usedHeap() - before - tableHeld
        val queries = listOf(hebrew[0], hebrew[5] + " " + english[3], english[1], "סה״כ", "12.90", "rec", "ו" + hebrew[2])
        queries.forEach { ocrFind(words, it) { true } }
        val searchStarted = System.nanoTime()
        var found = 0
        repeat(3) { queries.forEach { q -> found += ocrFind(words, q) { true }.size } }
        val searchMillis = (System.nanoTime() - searchStarted) / 1_000_000 / (3 * queries.size)
        val oneStarted = System.nanoTime()
        repeat(1000) { table.lines(((it * 97) % photos).toString(16).padStart(64, '0')) }
        val oneMicros = (System.nanoTime() - oneStarted) / 1_000 / 1000
        println(
            "TextTable, $photos photos, $withText with text, $lineCount lines: file ${table.bytes(FileSystem.SYSTEM, path) / 1_000_000} MB, " +
                "table held ${tableHeld / 1_000_000} MB (loaded in $loadMillis ms), word index held ${wordsHeld / 1_000_000} MB " +
                "(made in $indexMillis ms), a search $searchMillis ms ($found found), a photo's lines $oneMicros µs",
        )
        assertEquals(photos, table.size)
        FileSystem.SYSTEM.deleteRecursively(folder)
    }

    private fun word(random: Random, from: Char, to: Char, length: IntRange): String =
        String(CharArray(length.first + random.nextInt(length.last - length.first + 1)) { from + random.nextInt(to - from + 1) })

    /** A line as OCR reads it: a few words, common ones more often, and now and then a price, a date or a code. */
    private fun line(random: Random, hebrew: List<String>, english: List<String>): String {
        val words = 1 + random.nextInt(8)
        val vocabulary = if (random.nextDouble() < 0.6) hebrew else english
        return (0 until words).joinToString(" ") {
            when (random.nextInt(10)) {
                0 -> "${random.nextInt(1000)}.${random.nextInt(100).toString().padStart(2, '0')}"
                1 -> "${1 + random.nextInt(28)}/${1 + random.nextInt(12)}/20${10 + random.nextInt(17)}"
                2 -> random.nextInt(1_000_000).toString()
                // Zipf-like: a few words very often, most rarely.
                else -> vocabulary[(vocabulary.size * random.nextDouble() * random.nextDouble() * random.nextDouble()).toInt()]
            }
        }
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
