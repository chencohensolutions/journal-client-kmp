package com.chencohensolution.journal.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.BufferedSink
import okio.BufferedSource
import okio.FileSystem
import okio.Path
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// The server's indexes as this phone keeps them (docs/screens/settings.md "On this phone"): each a table of what the
// server read in each file, by sha256, filled from the index's stream (LocalIndexApi) and saved in its own file. A
// cache: losing a file only means downloading that index again.

/** What a table's file starts with: the stream it came from, and the cursor to go on from. */
@Serializable
internal data class TableHead(
    val v: Int = 1,
    val index: String,
    val model: String,
    val format: String,
    val dim: Int,
    /** The cursor for the next look (the stream's last E frame); null: from the start. */
    val cursor: String? = null,
    /** When the table last caught up with the server (epoch milliseconds). */
    val updatedAt: Long = 0,
)

/** A table of an index: what [apply] makes of the stream's records, saved and loaded whole. */
internal sealed class LocalTable(var head: TableHead) {
    abstract val size: Int

    abstract fun has(sha256: String): Boolean

    /** A record frame: the record as the server's cache file keeps it (see LocalIndexApi). */
    abstract fun put(record: ByteArray)

    abstract fun drop(sha256: String)

    abstract fun clear()

    /** Makes room for [rows] files at once, so the table isn't grown (and copied) as a stream fills it. */
    open fun reserve(rows: Int) {}

    abstract fun writeRows(sink: BufferedSink)

    abstract fun readRows(source: BufferedSource)

    /** Saves the table to [path] (a temp file, then renamed). */
    fun save(fileSystem: FileSystem, path: Path) {
        fileSystem.createDirectories(path.parent!!)
        val temp = path.parent!! / (path.name + ".tmp")
        fileSystem.write(temp) {
            writeUtf8(json.encodeToString(TableHead.serializer(), head))
            writeByte('\n'.code)
            writeRows(this)
        }
        fileSystem.atomicMove(temp, path)
    }

    companion object {
        internal val json = Json { ignoreUnknownKeys = true }

        /** The table of [head]'s format: vectors (dim 1: scores), faces or text. */
        fun of(head: TableHead): LocalTable = when (head.format) {
            FORMAT_FACES -> FaceTable(head)
            FORMAT_JSONL -> TextTable(head)
            else -> if (head.dim == 1) ScoreTable(head) else VectorTable(head)
        }

        /** The table saved at [path]; null when there is none or it can't be read (it is downloaded again). */
        fun load(fileSystem: FileSystem, path: Path): LocalTable? = runCatching {
            if (!fileSystem.exists(path)) return null
            val size = fileSystem.metadataOrNull(path)?.size ?: 0
            fileSystem.read(path) {
                val line = readUtf8LineStrict()
                val head = json.decodeFromString(TableHead.serializer(), line)
                // A faces table from before the vectors were kept: downloaded again, from the start.
                if (head.format == FORMAT_FACES && head.v < FaceTable.VERSION) return null
                of(head).also { table ->
                    if (table is VectorTable && head.dim > 0) {
                        table.expectedRows = ((size - line.encodeToByteArray().size - 1) / (36 + head.dim)).toInt()
                    }
                    table.readRows(this)
                }
            }
        }.getOrNull()

        const val FORMAT_VECTORS = "vectors"
        const val FORMAT_FACES = "faces"
        const val FORMAT_JSONL = "jsonl"
    }
}

internal fun ByteArray.hex(from: Int = 0, to: Int = size): String {
    val chars = CharArray((to - from) * 2)
    for (i in from until to) {
        val b = this[i].toInt() and 0xFF
        chars[(i - from) * 2] = HEX[b ushr 4]
        chars[(i - from) * 2 + 1] = HEX[b and 0xF]
    }
    return chars.concatToString()
}

internal fun hexBytes(sha: String): ByteArray = ByteArray(sha.length / 2) { sha.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

private const val HEX = "0123456789abcdef"

internal fun ByteArray.floatAt(at: Int): Float = Float.fromBits(
    (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or
        ((this[at + 2].toInt() and 0xFF) shl 16) or ((this[at + 3].toInt() and 0xFF) shl 24),
)

/**
 * Vectors (text search's SigLIP 2, similar photos' DINOv2), kept as 8-bit numbers with a scale each: a quarter of
 * their size (73 MB for 95,000 photos of 768 numbers), and they rank photos as the full vectors do to within a
 * thousandth of a score. Rows freed by [drop] are reused.
 */
internal class VectorTable(head: TableHead) : LocalTable(head) {
    /** How many rows a file being read holds, when known ([LocalTable.load]). */
    internal var expectedRows: Int? = null

    private val dim get() = head.dim
    private val rowOf = HashMap<String, Int>()
    private var shas = ArrayList<String?>()
    private var values = ByteArray(0)
    private var scales = FloatArray(0)
    private val free = ArrayDeque<Int>()

    override val size: Int get() = rowOf.size

    override fun has(sha256: String) = sha256 in rowOf

    override fun put(record: ByteArray) {
        val sha = record.hex(0, 32)
        val vector = FloatArray(dim) { record.floatAt(32 + 4 * it) }
        set(sha, vector)
    }

    /** Keeps [vector] (made length 1) as [sha256]'s. */
    fun set(sha256: String, vector: FloatArray) {
        var norm = 0.0
        for (v in vector) norm += v.toDouble() * v
        val unit = if (norm > 0) (1 / sqrt(norm)).toFloat() else 1f
        var largest = 0f
        for (v in vector) largest = maxOf(largest, abs(v * unit))
        val scale = if (largest > 0) largest / 127f else 1f
        val row = rowOf[sha256] ?: free.removeFirstOrNull() ?: shas.size.also { grow(it + 1) }
        rowOf[sha256] = row
        if (row == shas.size) shas.add(sha256) else shas[row] = sha256
        scales[row] = scale
        for (i in 0 until dim) values[row * dim + i] = (vector[i] * unit / scale).roundToInt().coerceIn(-127, 127).toByte()
    }

    /**
     * Room for [rows]. Growing copies the numbers (the old and the new array at once), so it grows by an eighth: a
     * phone allows an app a few hundred MB, and the table is most of it. A stream reserves its size up front instead.
     */
    private fun grow(rows: Int) {
        if (rows * dim <= values.size) return
        resize(maxOf(rows, scales.size + scales.size / 8 + 64))
    }

    private fun resize(capacity: Int) {
        values = values.copyOf(capacity * dim)
        scales = scales.copyOf(capacity)
    }

    override fun reserve(rows: Int) {
        if (rows * dim > values.size) resize(rows)
    }

    override fun drop(sha256: String) {
        val row = rowOf.remove(sha256) ?: return
        shas[row] = null
        free.addLast(row)
    }

    override fun clear() {
        rowOf.clear()
        shas = ArrayList()
        values = ByteArray(0)
        scales = FloatArray(0)
        free.clear()
    }

    /** [sha256]'s vector, as kept; null when it has none. */
    fun vector(sha256: String): FloatArray? {
        val row = rowOf[sha256] ?: return null
        return FloatArray(dim) { values[row * dim + it] * scales[row] }
    }

    /** Every file's score against [query] (length 1): the dot product, as the server ranks them. */
    fun scores(query: FloatArray, keep: (String) -> Boolean): List<Pair<String, Float>> {
        val found = ArrayList<Pair<String, Float>>(rowOf.size)
        for (row in shas.indices) {
            val sha = shas[row] ?: continue
            if (!keep(sha)) continue
            var dot = 0f
            val base = row * dim
            for (i in 0 until dim) dot += values[base + i] * query[i]
            found += sha to dot * scales[row]
        }
        return found
    }

    override fun writeRows(sink: BufferedSink) {
        for (row in shas.indices) {
            val sha = shas[row] ?: continue
            sink.write(hexBytes(sha))
            sink.writeIntLe(scales[row].toBits())
            sink.write(values, row * dim, dim)
        }
    }

    override fun readRows(source: BufferedSource) {
        clear()
        while (!source.exhausted()) {
            val sha = source.readByteArray(32).hex()
            val scale = Float.fromBits(source.readIntLe())
            val row = shas.size
            // The file says how many rows it has: room for them at once (and a little to grow).
            if (row == 0) expectedRows?.let { reserve(it + it / 16) }
            grow(row + 1)
            shas.add(sha)
            rowOf[sha] = row
            scales[row] = scale
            source.readInto(values, row * dim, dim)
        }
    }
}

/** Scores (aesthetic: one number a file), kept as they come. */
internal class ScoreTable(head: TableHead) : LocalTable(head) {
    val scores = HashMap<String, Float>()

    override val size: Int get() = scores.size

    override fun has(sha256: String) = sha256 in scores

    override fun put(record: ByteArray) {
        scores[record.hex(0, 32)] = record.floatAt(32)
    }

    override fun drop(sha256: String) {
        scores.remove(sha256)
    }

    override fun clear() = scores.clear()

    override fun writeRows(sink: BufferedSink) {
        for ((sha, score) in scores) {
            sink.write(hexBytes(sha))
            sink.writeIntLe(score.toBits())
        }
    }

    override fun readRows(source: BufferedSource) {
        clear()
        while (!source.exhausted()) {
            val sha = source.readByteArray(32).hex()
            scores[sha] = Float.fromBits(source.readIntLe())
        }
    }
}

/**
 * Faces: where each face is in each file (x, y, w, h) and its vector (the server's SFace numbers, at full precision),
 * in the record's order, which a face id `<sha256>:<n>` counts. The five points are left out. Version 2 of the file;
 * version 1 kept no vectors, and is downloaded again.
 */
internal class FaceTable(head: TableHead) : LocalTable(head.copy(v = VERSION)) {
    val boxes = HashMap<String, FloatArray>()

    /** Each file's faces' vectors, one after another ([dim] numbers a face). */
    val vectors = HashMap<String, FloatArray>()

    private val dim get() = head.dim

    override val size: Int get() = boxes.size

    override fun has(sha256: String) = sha256 in boxes

    override fun put(record: ByteArray) {
        val sha = record.hex(0, 32)
        val n = (record[32].toInt() and 0xFF) or ((record[33].toInt() and 0xFF) shl 8)
        val per = 4 * (FACE_FIELDS + dim)
        boxes[sha] = FloatArray(n * 4) { i -> record.floatAt(34 + (i / 4) * per + 4 * (i % 4)) }
        vectors[sha] = FloatArray(n * dim) { i -> record.floatAt(34 + (i / dim) * per + 4 * (FACE_FIELDS + i % dim)) }
    }

    /** Face [n] of [sha256]'s box, or null. */
    fun box(sha256: String, n: Int): FloatArray? {
        val all = boxes[sha256] ?: return null
        if (n < 0 || 4 * n + 4 > all.size) return null
        return all.copyOfRange(4 * n, 4 * n + 4)
    }

    /** Face [n] of [sha256]'s vector, or null. */
    fun vector(sha256: String, n: Int): FloatArray? {
        val all = vectors[sha256] ?: return null
        if (n < 0 || dim == 0 || dim * n + dim > all.size) return null
        return all.copyOfRange(dim * n, dim * n + dim)
    }

    override fun drop(sha256: String) {
        boxes.remove(sha256)
        vectors.remove(sha256)
    }

    override fun clear() {
        boxes.clear()
        vectors.clear()
    }

    override fun writeRows(sink: BufferedSink) {
        for ((sha, all) in boxes) {
            val n = all.size / 4
            val numbers = vectors[sha]
            sink.write(hexBytes(sha))
            sink.writeShortLe(n)
            for (face in 0 until n) {
                for (i in 0 until 4) sink.writeIntLe(all[4 * face + i].toBits())
                for (i in 0 until dim) sink.writeIntLe((numbers?.getOrNull(dim * face + i) ?: 0f).toBits())
            }
        }
    }

    override fun readRows(source: BufferedSource) {
        clear()
        while (!source.exhausted()) {
            val sha = source.readByteArray(32).hex()
            val n = source.readShortLe().toInt() and 0xFFFF
            val box = FloatArray(n * 4)
            val numbers = FloatArray(n * dim)
            for (face in 0 until n) {
                for (i in 0 until 4) box[4 * face + i] = Float.fromBits(source.readIntLe())
                for (i in 0 until dim) numbers[dim * face + i] = Float.fromBits(source.readIntLe())
            }
            boxes[sha] = box
            vectors[sha] = numbers
        }
    }

    companion object {
        /** Box, score and five points, before the vector (the server's faces.faceFields). */
        private const val FACE_FIELDS = 15

        /** The file's version: 2 keeps the vectors. */
        const val VERSION = 2
    }
}

@Serializable
private data class TextRecord(val sha256: String, val lines: List<TextLine> = emptyList())

@Serializable
private data class TextLine(val text: String = "")

/** Text in photos: each file's lines, and its words (as [ocrWords] makes them) to find files by. */
internal class TextTable(head: TableHead) : LocalTable(head) {
    val lines = HashMap<String, List<String>>()

    override val size: Int get() = lines.size

    override fun has(sha256: String) = sha256 in lines

    override fun put(record: ByteArray) {
        val parsed = runCatching { json.decodeFromString(TextRecord.serializer(), record.decodeToString()) }.getOrNull() ?: return
        lines[parsed.sha256] = parsed.lines.map { it.text }
    }

    override fun drop(sha256: String) {
        lines.remove(sha256)
    }

    override fun clear() = lines.clear()

    override fun writeRows(sink: BufferedSink) {
        for ((sha, text) in lines) {
            sink.writeUtf8(json.encodeToString(TextRecord.serializer(), TextRecord(sha, text.map(::TextLine))))
            sink.writeByte('\n'.code)
        }
    }

    override fun readRows(source: BufferedSource) {
        clear()
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isNotBlank()) put(line.encodeToByteArray())
        }
    }
}

/** Reads exactly [length] bytes into [target] at [offset]. */
internal fun BufferedSource.readInto(target: ByteArray, offset: Int, length: Int) {
    var done = 0
    while (done < length) {
        val n = read(target, offset + done, length - done)
        if (n == -1) throw okio.EOFException()
        done += n
    }
}
