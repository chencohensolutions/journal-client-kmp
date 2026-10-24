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

    @Test
    fun vectorsRankAsTheirDotProductsAndSurviveSaving() {
        val table = VectorTable(TableHead(index = "search", model = "m", format = "vectors", dim = 2))
        table.put(vectorRecord('a', 1f, 0f))
        table.put(vectorRecord('b', 0f, 1f))
        table.put(vectorRecord('c', 0.6f, 0.8f))
        table.drop(sha('b'))
        val ranked = table.scores(floatArrayOf(1f, 0f)) { true }.sortedByDescending { it.second }.map { it.first }
        assertEquals(listOf(sha('a'), sha('c')), ranked)
        val fs = FakeFileSystem()
        table.save(fs, "/local/search/table.bin".toPath())
        val loaded = LocalTable.load(fs, "/local/search/table.bin".toPath()) as VectorTable
        assertEquals(2, loaded.size)
        val c = assertNotNull(loaded.vector(sha('c')))
        assertEquals(0.6f, c[0], 0.01f)
        assertEquals(0.8f, c[1], 0.01f)
        assertNull(loaded.vector(sha('b')))
    }

    @Test
    fun scoresFacesAndTextAreKept() {
        val fs = FakeFileSystem()
        val scores = LocalTable.of(TableHead(index = "aesthetic", model = "m", format = "vectors", dim = 1)) as ScoreTable
        scores.put(vectorRecord('a', 6.5f))
        scores.save(fs, "/s.bin".toPath())
        assertEquals(6.5f, (LocalTable.load(fs, "/s.bin".toPath()) as ScoreTable).scores[sha('a')])

        val faces = LocalTable.of(TableHead(index = "faces", model = "m", format = "faces", dim = 1)) as FaceTable
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

        val text = LocalTable.of(TableHead(index = "ocr", model = "m", format = "jsonl", dim = 0)) as TextTable
        text.put("""{"sha256":"${sha('a')}","lines":[{"text":"קבלה","box":[0,0,1,1],"score":0.9}]}""".encodeToByteArray())
        text.save(fs, "/t.bin".toPath())
        assertEquals(listOf("קבלה"), (LocalTable.load(fs, "/t.bin".toPath()) as TextTable).lines[sha('a')])
    }
}
