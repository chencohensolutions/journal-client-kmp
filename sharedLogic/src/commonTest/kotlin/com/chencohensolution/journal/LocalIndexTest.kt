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

    private val facesHead = TableHead(index = "faces", model = "m", format = "faces", dim = 2)
    private val facesFolder = "/local/faces".toPath()
    private val facesTable = facesFolder / "table.bin"

    /** A file's record in the faces stream: each face's box, its score and five points (left 0), then its vector. */
    private fun faceRecord(sha: String, faces: List<Pair<FloatArray, FloatArray>>): ByteArray {
        var bytes = hexBytes(sha) + byteArrayOf(faces.size.toByte(), (faces.size shr 8).toByte())
        for ((box, vector) in faces) bytes += floats(*box, *FloatArray(11), *vector)
        return bytes
    }

    private fun face(x: Float, v0: Float, v1: Float) = floatArrayOf(x, 0.2f, 0.3f, 0.4f) to floatArrayOf(v0, v1)

    /** Faces' boxes are held and their vectors read from the file; a file dropped or sent again replaces its row. */
    @Test
    fun facesSurviveSavingDroppingAndReplacing() {
        val fs = FakeFileSystem()
        fs.createDirectories(facesFolder)
        fs.write(facesFolder / "people.json") { writeUtf8("{}") }
        val table = LocalTable.of(facesHead, fs, facesFolder) as FaceTable
        table.put(faceRecord(sha('a'), listOf(face(0.1f, 1f, 2f), face(0.5f, 3f, 4f))))
        table.put(faceRecord(sha('b'), listOf(face(0.7f, 5f, 6f))))
        table.put(faceRecord(sha('c'), emptyList()))
        // Waiting to be appended: not read yet.
        assertNull(table.box(sha('a'), 0))
        table.save(fs, facesTable)
        assertEquals(listOf(0.5f, 0.2f, 0.3f, 0.4f), table.box(sha('a'), 1)?.toList())

        val loaded = LocalTable.load(fs, facesTable) as FaceTable
        assertEquals(3, loaded.size)
        assertEquals(listOf(0.5f, 0.2f, 0.3f, 0.4f), loaded.box(sha('a'), 1)?.toList())
        assertEquals(listOf(3f, 4f), loaded.vector(sha('a'), 1)?.toList())
        assertEquals(listOf(1f, 2f), loaded.vector(sha('a'), 0)?.toList())
        assertEquals(listOf(5f, 6f), loaded.vector(sha('b'), 0)?.toList())
        assertEquals(8, loaded.boxes(sha('a'))?.size)
        // A file read without faces is kept, with none.
        assertTrue(loaded.has(sha('c')))
        assertEquals(0, loaded.boxes(sha('c'))?.size)
        assertNull(loaded.box(sha('a'), 2))
        assertNull(loaded.vector(sha('a'), -1))
        assertNull(loaded.box("not a sha", 0))

        loaded.drop(sha('b'))
        loaded.put(faceRecord(sha('a'), listOf(face(0.9f, 7f, 8f))))
        loaded.save(fs, facesTable)
        // Three of five rows replaced or dropped: the file was written again, without them.
        assertTrue(!fs.exists(facesFolder / "faces-1.bin"))
        assertEquals(34L + 24 + 34, fs.metadata(facesFolder / "faces-2.bin").size)
        assertTrue(fs.exists(facesFolder / "people.json"))

        val again = LocalTable.load(fs, facesTable) as FaceTable
        assertEquals(2, again.size)
        assertNull(again.box(sha('b'), 0))
        assertNull(again.box(sha('a'), 1))
        assertEquals(listOf(0.9f, 0.2f, 0.3f, 0.4f), again.box(sha('a'), 0)?.toList())
        assertEquals(listOf(7f, 8f), again.vector(sha('a'), 0)?.toList())
    }

    /** Thousands of files through appends, drops, replacements and a rewrite read as a plain map of them says. */
    @Test
    fun manyFacesReadAsTheyWereSent() {
        val fs = FakeFileSystem()
        val table = LocalTable.of(facesHead, fs, facesFolder) as FaceTable
        fun shaOf(n: Int) = n.toString(16).padStart(64, '0')
        val expected = HashMap<String, List<Pair<FloatArray, FloatArray>>>()
        fun send(n: Int, round: Int) {
            val faces = (0 until (n + round) % 5).map { face(n.toFloat(), round.toFloat(), it.toFloat()) }
            table.put(faceRecord(shaOf(n), faces))
            expected[shaOf(n)] = faces
        }
        for (n in 0 until 6000) send(n, 0)
        table.save(fs, facesTable)
        for (n in 0 until 6000 step 7) {
            table.drop(shaOf(n))
            expected.remove(shaOf(n))
        }
        for (n in 0 until 6000 step 3) if (shaOf(n) in expected) send(n, 1)
        table.save(fs, facesTable)

        val loaded = LocalTable.load(fs, facesTable) as FaceTable
        assertEquals(expected.size, loaded.size)
        for (n in 0 until 6000) {
            val faces = expected[shaOf(n)]
            if (faces == null) {
                assertNull(loaded.boxes(shaOf(n)), "file $n")
                continue
            }
            assertEquals(faces.size * 4, loaded.boxes(shaOf(n))?.size, "file $n")
            faces.forEachIndexed { i, (box, vector) ->
                assertEquals(box.toList(), loaded.box(shaOf(n), i)?.toList(), "file $n face $i")
                assertEquals(vector.toList(), loaded.vector(shaOf(n), i)?.toList(), "file $n face $i")
            }
        }
    }

    /** A face read while a download appends reads the rows published before; the rows waiting come with the next flush. */
    @Test
    fun aFaceIsReadWhileADownloadAppends() {
        // As on a phone: one handle appends while another reads (the fake refuses it by default).
        val fs = FakeFileSystem().apply { allowReadsWhileWriting = true }
        val table = FaceTable.create(facesHead, fs, facesFolder)
        table.put(faceRecord(sha('a'), listOf(face(0.1f, 1f, 2f))))
        table.flush()
        table.put(faceRecord(sha('b'), listOf(face(0.2f, 3f, 4f))))
        // The file open for appending (as a flush has it) while a face is read.
        fs.appendingSink(facesFolder / "faces-1.bin").use {
            assertEquals(listOf(1f, 2f), table.vector(sha('a'), 0)?.toList())
            assertNull(table.vector(sha('b'), 0))
        }
        table.flush()
        assertEquals(listOf(3f, 4f), table.vector(sha('b'), 0)?.toList())
        assertEquals(listOf(1f, 2f), table.vector(sha('a'), 0)?.toList())
    }

    /** A download cut off after appending faces: they are kept; half a row at the end is cut off. */
    @Test
    fun facesPastTheLastSaveAreKeptAndHalfARowIsCutOff() {
        val fs = FakeFileSystem()
        val table = FaceTable.create(facesHead, fs, facesFolder)
        table.put(faceRecord(sha('a'), listOf(face(0.1f, 1f, 2f))))
        table.save(fs, facesTable)
        table.put(faceRecord(sha('b'), listOf(face(0.2f, 3f, 4f))))
        table.flush()
        // The head of a row of one face, and only part of the face.
        fs.appendingSink(facesFolder / "faces-1.bin").buffer().use { it.write(hexBytes(sha('e')) + byteArrayOf(1, 0) + ByteArray(5)) }
        val loaded = LocalTable.load(fs, facesTable) as FaceTable
        assertEquals(2, loaded.size)
        assertTrue(!loaded.has(sha('e')))
        assertEquals(2L * (34 + 24), fs.metadata(facesFolder / "faces-1.bin").size)
        assertEquals(listOf(3f, 4f), loaded.vector(sha('b'), 0)?.toList())
        loaded.put(faceRecord(sha('c'), listOf(face(0.3f, 5f, 6f))))
        loaded.save(fs, facesTable)
        assertEquals(listOf(5f, 6f), (LocalTable.load(fs, facesTable) as FaceTable).vector(sha('c'), 0)?.toList())
    }

    /** A faces table version 2 saved whole in table.bin is moved into the faces' own files once, without downloading. */
    @Test
    fun anEarlierWholeFacesTableIsMovedIntoItsOwnFiles() {
        val fs = FakeFileSystem()
        fs.createDirectories(facesFolder)
        val rows = Buffer()
        rows.write(hexBytes(sha('a'))).writeShortLe(2)
        rows.write(floats(0.1f, 0.2f, 0.3f, 0.4f, 1f, 2f)).write(floats(0.5f, 0.6f, 0.7f, 0.8f, 3f, 4f))
        rows.write(hexBytes(sha('b'))).writeShortLe(0)
        val old = rows.readByteArray()
        fs.write(facesTable) {
            writeUtf8("""{"v":2,"index":"faces","model":"m","format":"faces","dim":2,"cursor":"c9"}""" + "\n")
            write(old)
        }
        val table = LocalTable.load(fs, facesTable) as FaceTable
        assertEquals("c9", table.head.cursor)
        assertEquals(2, table.size)
        assertEquals(listOf(0.5f, 0.6f, 0.7f, 0.8f), table.box(sha('a'), 1)?.toList())
        assertEquals(listOf(3f, 4f), table.vector(sha('a'), 1)?.toList())
        assertTrue(!fs.exists(facesTable))
        assertTrue(fs.exists(facesFolder / "faces.json"))
        // What it writes as a whole table is what it was given.
        val written = Buffer()
        table.writeRows(written)
        assertEquals(old.toList(), written.readByteArray().toList())
        assertEquals(2, (LocalTable.load(fs, facesTable) as FaceTable).size)
    }

    @Test
    fun scoresFacesAndTextAreKept() {
        val fs = FakeFileSystem()
        val scores = LocalTable.of(TableHead(index = "aesthetic", model = "m", format = "vectors", dim = 1), fs, "/".toPath()) as ScoreTable
        scores.put(vectorRecord('a', 6.5f))
        scores.save(fs, "/s.bin".toPath())
        assertEquals(6.5f, (LocalTable.load(fs, "/s.bin".toPath()) as ScoreTable).scores[sha('a')])

        val faces = LocalTable.of(TableHead(index = "faces", model = "m", format = "faces", dim = 1), fs, facesFolder) as FaceTable
        // Two faces: box, score and points (15 numbers) and a vector of 1.
        val face1 = floats(0.1f, 0.2f, 0.3f, 0.4f, *FloatArray(11), 0.5f)
        val face2 = floats(0.5f, 0.6f, 0.1f, 0.1f, *FloatArray(11), 0.5f)
        faces.put(hexBytes(sha('a')) + byteArrayOf(2, 0) + face1 + face2)
        faces.save(fs, facesTable)
        val loadedFaces = LocalTable.load(fs, facesTable) as FaceTable
        val box = assertNotNull(loadedFaces.box(sha('a'), 1))
        assertEquals(listOf(0.5f, 0.6f, 0.1f, 0.1f), box.toList())
        // The vectors are kept whole.
        assertEquals(listOf(0.5f), loadedFaces.vector(sha('a'), 1)?.toList())
        // A table from before the vectors were kept isn't read: it is downloaded again.
        fs.createDirectories("/old".toPath())
        fs.write("/old/table.bin".toPath()) { writeUtf8("""{"v":1,"index":"faces","model":"m","format":"faces","dim":1}""" + "\n") }
        assertNull(LocalTable.load(fs, "/old/table.bin".toPath()))

        val text = LocalTable.of(TableHead(index = "ocr", model = "m", format = "jsonl", dim = 0), fs, "/".toPath()) as TextTable
        text.put("""{"sha256":"${sha('a')}","lines":[{"text":"קבלה","box":[0,0,1,1],"score":0.9}]}""".encodeToByteArray())
        text.save(fs, "/t.bin".toPath())
        assertEquals(listOf("קבלה"), (LocalTable.load(fs, "/t.bin".toPath()) as TextTable).lines[sha('a')])
    }
}
