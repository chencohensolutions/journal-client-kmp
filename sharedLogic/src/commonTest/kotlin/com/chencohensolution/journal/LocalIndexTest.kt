package com.chencohensolution.journal

import com.chencohensolution.journal.local.FaceTable
import com.chencohensolution.journal.local.LocalTable
import com.chencohensolution.journal.local.ScoreTable
import com.chencohensolution.journal.local.StreamHead
import com.chencohensolution.journal.local.StreamSink
import com.chencohensolution.journal.local.TableHead
import com.chencohensolution.journal.local.TextTable
import com.chencohensolution.journal.local.VectorTable
import com.chencohensolution.journal.local.hexBytes
import com.chencohensolution.journal.local.readStream
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import okio.Buffer
import okio.Path.Companion.toPath
import okio.buffer
import okio.use
import okio.fakefilesystem.FakeFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LocalIndexTest {
    private fun sha(c: Char) = c.toString().repeat(64)

    private fun floats(vararg v: Float): ByteArray {
        val b = Buffer()
        v.forEach { b.writeIntLe(it.toBits()) }
        return b.readByteArray()
    }

    private fun vectorRecord(c: Char, vararg v: Float) = hexBytes(sha(c)) + floats(*v)

    private fun frame(kind: Char, payload: ByteArray): ByteArray {
        val b = Buffer().writeByte(kind.code).writeIntLe(payload.size).write(payload)
        return b.readByteArray()
    }

    @Test
    fun readsAStreamFrameByFrame() = runTest {
        val bytes = Buffer()
            .writeUtf8("""{"v":1,"index":"search","model":"m","format":"vectors","dim":2,"reset":true}""" + "\n")
            .write(frame('R', vectorRecord('a', 1f, 0f)))
            .write(frame('C', "half".encodeToByteArray()))
            .write(frame('D', hexBytes(sha('b'))))
            .write(frame('E', "next".encodeToByteArray()))
            .readByteArray()
        val seen = mutableListOf<String>()
        val cursor = readStream(ByteReadChannel(bytes), object : StreamSink {
            override fun head(head: StreamHead) { seen += "head ${head.index} ${head.dim} ${head.reset}" }
            override fun record(record: ByteArray) { seen += "record ${record.size}" }
            override fun drop(sha256: String) { seen += "drop ${sha256.take(4)}" }
            override fun checkpoint(cursor: String) { seen += "checkpoint $cursor" }
        })
        assertEquals("next", cursor)
        assertEquals(listOf("head search 2 true", "record 40", "checkpoint half", "drop bbbb"), seen)
    }

    @Test
    fun aStreamWithoutItsEndBroke() = runTest {
        val bytes = Buffer().writeUtf8("""{"index":"search"}""" + "\n").write(frame('R', vectorRecord('a', 1f, 0f))).readByteArray()
        val result = runCatching {
            readStream(ByteReadChannel(bytes), object : StreamSink {
                override fun head(head: StreamHead) {}
                override fun record(record: ByteArray) {}
                override fun drop(sha256: String) {}
                override fun checkpoint(cursor: String) {}
            })
        }
        assertTrue(result.isFailure)
    }

    private val vectorsHead = TableHead(index = "search", model = "m", format = "vectors", dim = 2)
    private val searchFolder = "/local/search".toPath()
    private val searchTable = searchFolder / "table.bin"

    @Test
    fun vectorsRankAsTheirDotProductsAndSurviveSaving() {
        val fs = FakeFileSystem()
        val table = VectorTable.create(vectorsHead, fs, searchFolder)
        table.put(vectorRecord('a', 1f, 0f))
        table.put(vectorRecord('b', 0f, 1f))
        table.put(vectorRecord('c', 0.6f, 0.8f))
        table.drop(sha('b'))
        table.flush()
        assertEquals(listOf(sha('a'), sha('c')), table.top(floatArrayOf(1f, 0f), 10) { true }.map { it.first })
        assertEquals(listOf(sha('a')), table.top(floatArrayOf(1f, 0f), 1) { true }.map { it.first })
        assertEquals(listOf(sha('c')), table.top(floatArrayOf(1f, 0f), 1) { it != sha('a') }.map { it.first })
        table.save(fs, searchTable)
        val loaded = LocalTable.load(fs, searchTable) as VectorTable
        assertEquals(2, loaded.size)
        val c = assertNotNull(loaded.vector(sha('c')))
        assertEquals(0.6f, c[0], 0.01f)
        assertEquals(0.8f, c[1], 0.01f)
        assertNull(loaded.vector(sha('b')))
        // Only the numbers' file (written again without the dropped row) and the head: no table.bin.
        assertEquals(setOf("vectors.json", "vectors-2.bin"), fs.list(searchFolder).map { it.name }.toSet())
    }

    /** Many rows, replaced and dropped as downloads go: the best are those of a plain dot product over what is kept. */
    @Test
    fun theBestOfManyRowsMatchAPlainRankingThroughAppendsAndCompaction() {
        val fs = FakeFileSystem()
        val random = kotlin.random.Random(7)
        val dim = 8
        val head = TableHead(index = "search", model = "m", format = "vectors", dim = dim)
        var table = VectorTable.create(head, fs, searchFolder)
        val kept = HashMap<String, FloatArray>()
        fun shaOf(n: Int) = n.toString(16).padStart(64, '0')
        fun unit(v: FloatArray): FloatArray {
            val norm = kotlin.math.sqrt(v.sumOf { it.toDouble() * it }).toFloat()
            return FloatArray(v.size) { v[it] / norm }
        }
        repeat(3) { round ->
            for (n in 0 until 6000) {
                if (random.nextInt(3) == 0 && round > 0) {
                    table.drop(shaOf(n))
                    kept.remove(shaOf(n))
                } else if (round == 0 || random.nextBoolean()) {
                    val v = FloatArray(dim) { random.nextFloat() - 0.5f }
                    table.set(shaOf(n), v)
                    kept[shaOf(n)] = unit(v)
                }
            }
            table.save(fs, searchTable)
            table = LocalTable.load(fs, searchTable) as VectorTable
            assertEquals(kept.size, table.size, "round $round")
        }
        // Compaction left one file of only the rows kept.
        val files = fs.list(searchFolder).filter { it.name.endsWith(".bin") }
        assertEquals(1, files.size)
        val query = unit(FloatArray(dim) { random.nextFloat() - 0.5f })
        val expected = kept.entries.map { (sha, v) -> sha to v.indices.sumOf { (v[it] * query[it]).toDouble() } }
            .sortedByDescending { it.second }.take(20).map { it.first }
        val found = table.top(query, 20) { true }.map { it.first }
        // 8-bit numbers can swap two nearly equal scores; the sets agree all but at the edge.
        assertTrue((found intersect expected.toSet()).size >= 18, "found $found, expected $expected")
        assertEquals(expected.first(), found.first())
    }

    /** A search while a download appends reads whole rows: the ones it started with. */
    @Test
    fun aSearchReadsTheRowsItStartedWith() {
        // As on a phone: one handle appends while another reads (the fake refuses it by default).
        val fs = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val table = VectorTable.create(vectorsHead, fs, searchFolder)
        table.put(vectorRecord('a', 1f, 0f))
        table.flush()
        val seen = mutableListOf<String>()
        table.top(floatArrayOf(1f, 0f), 5) { sha ->
            // The download goes on meanwhile.
            table.put(vectorRecord('b', 1f, 0f))
            table.flush()
            seen += sha
            true
        }
        assertEquals(listOf(sha('a')), seen)
        assertEquals(2, table.top(floatArrayOf(1f, 0f), 5) { true }.size)
    }

    /** A download cut off after appending rows: they are kept; half a row at the end is cut off. */
    @Test
    fun rowsPastTheLastSaveAreKeptAndHalfARowIsCutOff() {
        val fs = FakeFileSystem()
        val table = VectorTable.create(vectorsHead, fs, searchFolder)
        table.put(vectorRecord('a', 1f, 0f))
        table.save(fs, searchTable)
        table.put(vectorRecord('b', 0f, 1f))
        table.flush()
        fs.appendingSink(searchFolder / "vectors-1.bin").buffer().use { it.write(ByteArray(10)) }
        val loaded = LocalTable.load(fs, searchTable) as VectorTable
        assertEquals(2, loaded.size)
        assertNotNull(loaded.vector(sha('b')))
        loaded.put(vectorRecord('c', 0.6f, 0.8f))
        loaded.save(fs, searchTable)
        assertEquals(0.8f, assertNotNull((LocalTable.load(fs, searchTable) as VectorTable).vector(sha('c')))[1], 0.01f)
    }

    /** A table an earlier version saved whole in table.bin is moved into the numbers' file once, without downloading. */
    @Test
    fun anEarlierWholeTableIsMovedIntoItsOwnFile() {
        val fs = FakeFileSystem()
        fs.createDirectories(searchFolder)
        fs.write(searchTable) {
            writeUtf8("""{"v":1,"index":"search","model":"m","format":"vectors","dim":2,"cursor":"c9"}""" + "\n")
            for ((c, values) in listOf('a' to byteArrayOf(127, 0), 'b' to byteArrayOf(0, 127))) {
                write(hexBytes(sha(c)))
                writeIntLe((1f / 127f).toBits())
                write(values)
            }
        }
        val table = LocalTable.load(fs, searchTable) as VectorTable
        assertEquals("c9", table.head.cursor)
        assertEquals(listOf(sha('b'), sha('a')), table.top(floatArrayOf(0f, 1f), 2) { true }.map { it.first })
        assertTrue(!fs.exists(searchTable))
        assertEquals(2, (LocalTable.load(fs, searchTable) as VectorTable).size)
    }

    @Test
    fun scoresFacesAndTextAreKept() {
        val fs = FakeFileSystem()
        val scores = LocalTable.of(TableHead(index = "aesthetic", model = "m", format = "vectors", dim = 1), fs, "/".toPath()) as ScoreTable
        scores.put(vectorRecord('a', 6.5f))
        scores.save(fs, "/s.bin".toPath())
        assertEquals(6.5f, (LocalTable.load(fs, "/s.bin".toPath()) as ScoreTable).scores[sha('a')])

        val faces = LocalTable.of(TableHead(index = "faces", model = "m", format = "faces", dim = 1), fs, "/".toPath()) as FaceTable
        // Two faces: box, score and points (15 numbers) and a vector of 1.
        val face1 = floats(0.1f, 0.2f, 0.3f, 0.4f, *FloatArray(11), 0.5f)
        val face2 = floats(0.5f, 0.6f, 0.1f, 0.1f, *FloatArray(11), 0.5f)
        faces.put(hexBytes(sha('a')) + byteArrayOf(2, 0) + face1 + face2)
        faces.save(fs, "/f.bin".toPath())
        val loadedFaces = LocalTable.load(fs, "/f.bin".toPath()) as FaceTable
        val box = assertNotNull(loadedFaces.box(sha('a'), 1))
        assertEquals(listOf(0.5f, 0.6f, 0.1f, 0.1f), box.toList())
        // The vectors are kept whole.
        assertEquals(listOf(0.5f), loadedFaces.vector(sha('a'), 1)?.toList())
        // A table from before the vectors were kept isn't read: it is downloaded again.
        fs.write("/old.bin".toPath()) { writeUtf8("""{"v":1,"index":"faces","model":"m","format":"faces","dim":1}""" + "\n") }
        assertNull(LocalTable.load(fs, "/old.bin".toPath()))

        val text = LocalTable.of(TableHead(index = "ocr", model = "m", format = "jsonl", dim = 0), fs, "/".toPath()) as TextTable
        text.put("""{"sha256":"${sha('a')}","lines":[{"text":"קבלה","box":[0,0,1,1],"score":0.9}]}""".encodeToByteArray())
        text.save(fs, "/t.bin".toPath())
        assertEquals(listOf("קבלה"), (LocalTable.load(fs, "/t.bin".toPath()) as TextTable).lines[sha('a')])
    }
}
