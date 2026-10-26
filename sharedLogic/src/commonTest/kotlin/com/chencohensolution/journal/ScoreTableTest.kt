package com.chencohensolution.journal

import com.chencohensolution.journal.local.LocalTable
import com.chencohensolution.journal.local.ScoreTable
import com.chencohensolution.journal.local.ShaScores
import com.chencohensolution.journal.local.TableHead
import com.chencohensolution.journal.local.hexBytes
import okio.Buffer
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The aesthetic scores kept on this phone: compact, shared with the library as they are. */
class ScoreTableTest {
    private fun sha(n: Int) = n.toString(16).padStart(64, '0')

    private fun record(n: Int, score: Float): ByteArray = hexBytes(sha(n)) + Buffer().writeIntLe(score.toBits()).readByteArray()

    private val head = TableHead(index = "aesthetic", model = "m", format = "vectors", dim = 1)

    @Test
    fun scoresAreFoundByTheirSha256() {
        val shas = Buffer()
        val values = FloatArray(1000) { it / 100f }
        for (n in 999 downTo 0) shas.write(hexBytes(sha(n)))
        val scores = ShaScores.of(shas.readByteArray(), values.reversedArray())
        assertEquals(1000, scores.size)
        for (n in 0 until 1000) assertEquals(n / 100f, scores[sha(n)], "row $n")
        assertNull(scores[sha(1000)])
        assertNull(scores["not a sha256"])
        assertFalse(scores.containsKey("z".repeat(64)))
        assertEquals(1000, scores.entries.size)
    }

    @Test
    fun changesMakeNewScoresAndLeaveTheOldAsTheyWere() {
        val old = ShaScores.of(hexBytes(sha(1)) + hexBytes(sha(2)), floatArrayOf(1f, 2f))
        val new = old.with(mapOf(sha(1) to null, sha(2) to 5f, sha(3) to 3f))
        assertEquals(mapOf(sha(2) to 5f, sha(3) to 3f), new.entries.associate { it.key to it.value })
        assertEquals(1f, old[sha(1)])
        assertEquals(2f, old[sha(2)])
        // Equal only to itself, so a StateFlow given new scores always tells the library.
        assertNotSame<Any>(old, new)
        assertFalse(old == ShaScores.of(hexBytes(sha(1)) + hexBytes(sha(2)), floatArrayOf(1f, 2f)))
    }

    @Test
    fun aDownloadsChangesSurviveSaving() {
        val fs = FakeFileSystem()
        val table = LocalTable.of(head, fs, "/local/aesthetic".toPath()) as ScoreTable
        for (n in 0 until 10_000) table.put(record(n, n.toFloat()))
        table.put(record(5, 50f))
        table.drop(sha(6))
        assertTrue(table.has(sha(5)))
        assertFalse(table.has(sha(6)))
        table.save(fs, "/local/aesthetic/table.bin".toPath())
        val scores = table.scores
        assertEquals(9_999, scores.size)
        assertEquals(50f, scores[sha(5)])
        assertNull(scores[sha(6)])
        assertSame(scores, table.scores)
        val loaded = LocalTable.load(fs, "/local/aesthetic/table.bin".toPath()) as ScoreTable
        assertEquals(9_999, loaded.size)
        assertEquals(50f, loaded.scores[sha(5)])
        assertEquals(9_999f, loaded.scores[sha(9_999)])
    }
}
