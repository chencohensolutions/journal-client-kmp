package com.chencohensolution.journal.local

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.Buffer
import okio.BufferedSink
import okio.BufferedSource
import okio.FileHandle
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use
import kotlin.concurrent.Volatile
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
    open fun save(fileSystem: FileSystem, path: Path) {
        fileSystem.createDirectories(path.parent!!)
        val temp = path.parent!! / (path.name + ".tmp")
        fileSystem.write(temp) {
            writeUtf8(json.encodeToString(TableHead.serializer(), head))
            writeByte('\n'.code)
            writeRows(this)
        }
        fileSystem.atomicMove(temp, path)
    }

    /** What the table takes on the phone, saved at [path]. */
    open fun bytes(fileSystem: FileSystem, path: Path): Long = fileSystem.metadataOrNull(path)?.size ?: 0

    companion object {
        internal val json = Json { ignoreUnknownKeys = true }

        /**
         * The table of [head]'s format: vectors (dim 1: scores), faces or text; all but the scores kept in files in
         * [folder].
         */
        fun of(head: TableHead, fileSystem: FileSystem, folder: Path): LocalTable = when (head.format) {
            FORMAT_FACES -> FaceTable.create(head, fileSystem, folder)
            FORMAT_JSONL -> TextTable.create(head, fileSystem, folder)
            else -> if (head.dim == 1) ScoreTable(head) else VectorTable.create(head, fileSystem, folder)
        }

        /**
         * The table saved at [path] (or, for vectors, faces and text, in its folder); null when there is none or it can't
         * be read (it is downloaded again). Vectors, faces or text saved whole in [path] by an earlier version are moved
         * into their own file once.
         */
        fun load(fileSystem: FileSystem, path: Path): LocalTable? = runCatching {
            val folder = path.parent!!
            VectorTable.open(fileSystem, folder)?.let { return it }
            FaceTable.open(fileSystem, folder)?.let { return it }
            TextTable.open(fileSystem, folder)?.let { return it }
            if (!fileSystem.exists(path)) return null
            val table = fileSystem.read(path) {
                val head = json.decodeFromString(TableHead.serializer(), readUtf8LineStrict())
                // A faces table from before the vectors were kept: downloaded again, from the start.
                if (head.format == FORMAT_FACES && head.v < FaceTable.VERSION) return null
                of(head, fileSystem, folder).also { it.readRows(this) }
            }
            if (table is VectorTable || table is FaceTable || table is TextTable) {
                table.save(fileSystem, path)
                fileSystem.delete(path)
            }
            table
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
 * Vectors (text search's SigLIP 2, similar photos' DINOv2), kept as 8-bit numbers with a scale each (a quarter of their
 * size, and they rank photos as the full vectors do to within a thousandth of a score), in a file of their own that is
 * read, not held (plan B phase 7): in memory is only which row of the file is which file's, about 40 bytes a file (4 MB
 * for 95,000 photos, whose numbers take 73 MB on disk). A search reads the file in chunks and keeps the best ([top]).
 *
 * The file, `vectors-<n>.bin` in [folder], is rows of the sha256, the scale and the numbers. A later row of a file
 * replaces an earlier one, and a row with scale 0 says the file was dropped. What changes is appended, a few thousand
 * rows at a time ([flush]), and [save] writes the file again without the rows replaced once they are an eighth of it.
 * `vectors.json` beside it has the head and which file is current. The writer (a download, one at a time) and searches
 * share no lock: each append publishes a new [Rows], and a search reads the rows of the one it started with.
 */
internal class VectorTable private constructor(
    head: TableHead,
    private val fileSystem: FileSystem,
    private val folder: Path,
    rows: Rows,
) : LocalTable(head) {
    /** Which file's each row is, whether it is current, and where each file's latest row is. Never changed once made. */
    private class Rows(
        val file: Path,
        val count: Int,
        val shas: ByteArray,
        val current: BooleanArray,
        /** Open addressing: row + 1 of each file's latest row, by its sha256; 0 is empty. */
        val slots: IntArray,
        val live: Int,
    ) {
        /** The latest row of [sha], current or dropped; -1 when it has none. */
        fun find(sha: ByteArray): Int = findIn(slots, shas, sha)
    }

    @Volatile
    private var rows: Rows = rows

    /** Rows waiting to be appended: sha256, scale and numbers (null: dropped). */
    private class Pending(val sha: ByteArray, val scale: Float, val values: ByteArray?)

    private val pending = ArrayList<Pending>()

    private val dim get() = head.dim
    private val rowBytes get() = ROW_HEAD + dim

    /** The files kept, as of the last [flush]. */
    override val size: Int get() = rows.live

    override fun has(sha256: String): Boolean {
        pending.lastOrNull { it.sha.contentEquals(hexBytes(sha256)) }?.let { return it.values != null }
        val row = rows.find(hexBytes(sha256))
        return row >= 0 && rows.current[row]
    }

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
        val values = ByteArray(dim) { i -> (vector[i] * unit / scale).roundToInt().coerceIn(-127, 127).toByte() }
        add(Pending(hexBytes(sha256), scale, values))
    }

    override fun drop(sha256: String) {
        if (has(sha256)) add(Pending(hexBytes(sha256), 0f, null))
    }

    private fun add(row: Pending) {
        pending += row
        if (pending.size >= FLUSH_ROWS) flush()
    }

    /** Starts an empty file; the old one goes at the next [save]. */
    override fun clear() {
        pending.clear()
        rows = emptyRows(fileSystem, folder)
    }

    /**
     * Appends the rows waiting to the file, then publishes the rows with them. Called as a download goes and by [save];
     * a search running meanwhile reads the rows it started with.
     */
    fun flush() {
        if (pending.isEmpty()) return
        val old = rows
        fileSystem.createDirectories(folder)
        fileSystem.appendingSink(old.file).buffer().use { sink ->
            for (row in pending) {
                sink.write(row.sha)
                sink.writeIntLe(row.scale.toBits())
                if (row.values != null) sink.write(row.values) else sink.write(ByteArray(dim))
            }
        }
        val count = old.count + pending.size
        val shas = old.shas.copyOf(count * 32)
        val current = old.current.copyOf(count)
        var live = old.live
        val slots = if (count * 2 <= old.slots.size) {
            old.slots.copyOf()
        } else {
            IntArray(slotsFor(count)).also { slots -> for (r in 0 until old.count) if (old.current[r]) place(slots, shas, r) }
        }
        pending.forEachIndexed { i, row ->
            val r = old.count + i
            row.sha.copyInto(shas, r * 32)
            val before = findIn(slots, shas, row.sha)
            if (before >= 0 && current[before]) {
                current[before] = false
                live--
            }
            if (row.values != null) {
                current[r] = true
                live++
            }
            place(slots, shas, r)
        }
        pending.clear()
        rows = Rows(old.file, count, shas, current, slots, live)
    }

    /**
     * Saves the table in [path]'s folder (the path itself is the other tables'): the rows waiting appended, the file
     * written again without the rows replaced or dropped once they are an eighth of it, then the head, then the files
     * no longer current deleted.
     */
    override fun save(fileSystem: FileSystem, path: Path) {
        flush()
        val now = rows
        if (now.count - now.live > now.live / 8) compact(now)
        val file = rows.file
        val temp = folder / "$HEAD_FILE.tmp"
        fileSystem.createDirectories(folder)
        if (!fileSystem.exists(file)) fileSystem.write(file) {}
        fileSystem.write(temp) { writeUtf8(json.encodeToString(TableFiles.serializer(), TableFiles(file.name, head))) }
        fileSystem.atomicMove(temp, folder / HEAD_FILE)
        deleteOthers(fileSystem, folder, FILE_PREFIX, file)
    }

    /** The current rows of [from] in a new file. */
    private fun compact(from: Rows) {
        val target = nextFile(fileSystem, folder, FILE_PREFIX)
        val shas = ByteArray(from.live * 32)
        val current = BooleanArray(from.live) { true }
        val slots = IntArray(slotsFor(from.live))
        var count = 0
        fileSystem.write(target) {
            forEachChunk(from) { buffer, first, taken ->
                for (k in 0 until taken) {
                    if (!from.current[first + k]) continue
                    write(buffer, k * rowBytes, rowBytes)
                    buffer.copyInto(shas, count * 32, k * rowBytes, k * rowBytes + 32)
                    place(slots, shas, count)
                    count++
                }
            }
        }
        rows = Rows(target, count, shas, current, slots, count)
    }

    override fun bytes(fileSystem: FileSystem, path: Path): Long = fileSystem.metadataOrNull(rows.file)?.size ?: 0

    /** [sha256]'s vector, as kept; null when it has none (or it is still waiting to be appended). */
    fun vector(sha256: String): FloatArray? {
        val now = rows
        val row = now.find(hexBytes(sha256))
        if (row < 0 || !now.current[row]) return null
        val bytes = ByteArray(rowBytes)
        fileSystem.openReadOnly(now.file).use { it.readFully(row.toLong() * rowBytes, bytes, rowBytes) }
        val scale = bytes.floatAt(32)
        return FloatArray(dim) { bytes[ROW_HEAD + it] * scale }
    }

    /**
     * The [count] files whose vectors score best against [query] (length 1), best first, of those [keep] takes: the dot
     * product, as the server ranks them. The file is read in chunks; only the best so far are kept, in a small heap,
     * and [keep] is asked only of a file that would join them.
     */
    fun top(query: FloatArray, count: Int, keep: (String) -> Boolean): List<Pair<String, Float>> {
        val now = rows
        if (count <= 0 || now.live == 0) return emptyList()
        val heap = BestOf(count)
        forEachChunk(now) { buffer, first, taken ->
            for (k in 0 until taken) {
                if (!now.current[first + k]) continue
                val base = k * rowBytes
                var dot = 0f
                for (i in 0 until dim) dot += buffer[base + ROW_HEAD + i] * query[i]
                val score = dot * buffer.floatAt(base + 32)
                if (!heap.wouldTake(score)) continue
                val sha = buffer.hex(base, base + 32)
                if (keep(sha)) heap.add(sha, score)
            }
        }
        return heap.best()
    }

    /** Reads [of]'s file a chunk of rows at a time: [each] gets the bytes, the first row's number and how many rows. */
    private inline fun forEachChunk(of: Rows, each: (ByteArray, Int, Int) -> Unit) {
        if (of.count == 0) return
        val perChunk = maxOf(1, CHUNK_BYTES / rowBytes)
        val buffer = ByteArray(perChunk * rowBytes)
        fileSystem.openReadOnly(of.file).use { handle ->
            var first = 0
            while (first < of.count) {
                val taken = minOf(perChunk, of.count - first)
                handle.readFully(first.toLong() * rowBytes, buffer, taken * rowBytes)
                each(buffer, first, taken)
                first += taken
            }
        }
    }

    /** Every current row, as the file has it (sha256, scale, numbers). */
    override fun writeRows(sink: BufferedSink) {
        flush()
        val now = rows
        forEachChunk(now) { buffer, first, taken ->
            for (k in 0 until taken) if (now.current[first + k]) sink.write(buffer, k * rowBytes, rowBytes)
        }
    }

    /** Rows as [writeRows] writes them (an earlier version's whole table), added to this one. */
    override fun readRows(source: BufferedSource) {
        while (!source.exhausted()) {
            val sha = source.readByteArray(32)
            val scale = Float.fromBits(source.readIntLe())
            val values = source.readByteArray(dim.toLong())
            add(Pending(sha, scale, values))
        }
        flush()
    }

    /** The best [count] scores seen: a min-heap, its weakest at the root. */
    private class BestOf(private val count: Int) {
        private val shas = arrayOfNulls<String>(count)
        private val scores = FloatArray(count)
        private var size = 0

        fun wouldTake(score: Float) = size < count || score > scores[0]

        fun add(sha: String, score: Float) {
            if (size < count) {
                var i = size++
                shas[i] = sha
                scores[i] = score
                while (i > 0) {
                    val parent = (i - 1) / 2
                    if (scores[parent] <= scores[i]) break
                    swap(i, parent)
                    i = parent
                }
            } else {
                shas[0] = sha
                scores[0] = score
                var i = 0
                while (true) {
                    val left = 2 * i + 1
                    if (left >= size) break
                    val smaller = if (left + 1 < size && scores[left + 1] < scores[left]) left + 1 else left
                    if (scores[i] <= scores[smaller]) break
                    swap(i, smaller)
                    i = smaller
                }
            }
        }

        private fun swap(a: Int, b: Int) {
            val s = shas[a]; shas[a] = shas[b]; shas[b] = s
            val f = scores[a]; scores[a] = scores[b]; scores[b] = f
        }

        fun best(): List<Pair<String, Float>> = (0 until size).map { shas[it]!! to scores[it] }.sortedByDescending { it.second }
    }

    companion object {
        /** sha256 and scale, before the numbers. */
        private const val ROW_HEAD = 36

        /** Rows a download gathers before appending them. */
        private const val FLUSH_ROWS = 4096

        /** About what a search reads at a time. */
        private const val CHUNK_BYTES = 1 shl 20

        private const val HEAD_FILE = "vectors.json"
        private const val FILE_PREFIX = "vectors-"

        /** A new, empty table in [folder] (its file is made when rows come). */
        fun create(head: TableHead, fileSystem: FileSystem, folder: Path) = VectorTable(head, fileSystem, folder, emptyRows(fileSystem, folder))

        /**
         * The table saved in [folder]; null when there is none. Rows past what was saved last (a download cut off before
         * its end) are kept, as the stream only sends them again; half a row at the end is cut off.
         */
        fun open(fileSystem: FileSystem, folder: Path): VectorTable? {
            val headPath = folder / HEAD_FILE
            if (!fileSystem.exists(headPath)) return null
            val saved = json.decodeFromString(TableFiles.serializer(), fileSystem.read(headPath) { readUtf8() })
            val file = folder / saved.file
            val rowBytes = ROW_HEAD + saved.head.dim
            if (!fileSystem.exists(file)) return null
            val bytes = fileSystem.metadataOrNull(file)?.size ?: 0
            val whole = bytes / rowBytes
            if (bytes % rowBytes != 0L) fileSystem.openReadWrite(file).use { it.resize(whole * rowBytes) }
            val table = VectorTable(saved.head, fileSystem, folder, Rows(file, 0, ByteArray(0), BooleanArray(0), IntArray(slotsFor(0)), 0))
            table.index(file, whole.toInt())
            return table
        }

        private fun emptyRows(fileSystem: FileSystem, folder: Path) =
            Rows(nextFile(fileSystem, folder), 0, ByteArray(0), BooleanArray(0), IntArray(slotsFor(0)), 0)

        private fun nextFile(fileSystem: FileSystem, folder: Path) = nextFile(fileSystem, folder, FILE_PREFIX)

    }

    /** Reads the rows of [file] (sha256 and scale of each) into the table's rows. */
    private fun index(file: Path, count: Int) {
        val shas = ByteArray(count * 32)
        val current = BooleanArray(count)
        val slots = IntArray(slotsFor(count))
        var live = 0
        forEachChunk(Rows(file, count, shas, current, slots, 0)) { buffer, first, taken ->
            for (k in 0 until taken) {
                val r = first + k
                buffer.copyInto(shas, r * 32, k * rowBytes, k * rowBytes + 32)
                val before = findIn(slots, shas, buffer.copyOfRange(k * rowBytes, k * rowBytes + 32))
                if (before >= 0 && current[before]) {
                    current[before] = false
                    live--
                }
                if (buffer.floatAt(k * rowBytes + 32) != 0f) {
                    current[r] = true
                    live++
                }
                place(slots, shas, r)
            }
        }
        rows = Rows(file, count, shas, current, slots, live)
    }
}

/** `vectors.json`, `faces.json` or `text.json`: which file of the folder is current, and the table's head. */
@Serializable
private data class TableFiles(val file: String, val head: TableHead)

// The row index the tables on disk share: each row's sha256 in one array, and an open-addressing hash from a sha256 to
// its latest row.

private fun slotsFor(rows: Int): Int {
    var size = 64
    while (size < rows * 2 + 2) size *= 2
    return size
}

/** All 32 bytes mixed, so sha256s alike in part (made-up ones in tests) don't crowd one place. */
private fun hashOf(shas: ByteArray, at: Int): Int {
    // FNV-1a, then MurmurHash3's finish to spread the bits.
    var h = 0x811C9DC5.toInt()
    for (i in at until at + 32) h = (h xor (shas[i].toInt() and 0xFF)) * 0x01000193
    h = (h xor (h ushr 16)) * 0x85EBCA6B.toInt()
    h = (h xor (h ushr 13)) * 0xC2B2AE35.toInt()
    return h xor (h ushr 16)
}

private fun sameSha(shas: ByteArray, row: Int, sha: ByteArray, from: Int = 0): Boolean {
    for (i in 0 until 32) if (shas[row * 32 + i] != sha[from + i]) return false
    return true
}

/** The latest row of the sha256 at [from] of [sha]; -1 when it has none. */
private fun findIn(slots: IntArray, shas: ByteArray, sha: ByteArray, from: Int = 0): Int {
    val mask = slots.size - 1
    var i = hashOf(sha, from) and mask
    while (true) {
        val row = slots[i] - 1
        if (row < 0) return -1
        if (sameSha(shas, row, sha, from)) return row
        i = (i + 1) and mask
    }
}

/** Points [row]'s sha256 at [row] (its latest). */
private fun place(slots: IntArray, shas: ByteArray, row: Int) {
    val mask = slots.size - 1
    var i = hashOf(shas, row * 32) and mask
    while (true) {
        val there = slots[i] - 1
        if (there < 0 || sameSha(shas, there, shas, row * 32)) {
            slots[i] = row + 1
            return
        }
        i = (i + 1) and mask
    }
}

/** A file name in [folder] starting with [prefix] not used yet: one more than the highest. */
private fun nextFile(fileSystem: FileSystem, folder: Path, prefix: String): Path {
    val used = fileSystem.listOrNull(folder).orEmpty()
        .filter { it.name.startsWith(prefix) }
        .mapNotNull { it.name.removePrefix(prefix).removeSuffix(".bin").toIntOrNull() }
    return folder / "$prefix${(used.maxOrNull() ?: 0) + 1}.bin"
}

/** Deletes the files of [folder] starting with [prefix] other than [keep]. */
private fun deleteOthers(fileSystem: FileSystem, folder: Path, prefix: String, keep: Path) {
    for (other in fileSystem.list(folder)) {
        if (other.name != keep.name && other.name.startsWith(prefix) && other.name.endsWith(".bin")) fileSystem.delete(other)
    }
}

/** Reads exactly [length] bytes at [offset] of the file into [target]. */
private fun FileHandle.readFully(offset: Long, target: ByteArray, length: Int) {
    var done = 0
    while (done < length) {
        val n = read(offset + done, target, done, length - done)
        if (n == -1) throw okio.EOFException()
        done += n
    }
}

/**
 * Scores (aesthetic: one number a file). Kept as [ShaScores], which the library reads as it is (no copy): 36 bytes a
 * file instead of about 250 for a map of hex strings to boxed numbers. A download's changes gather in [pending] and
 * make a new [scores] every few thousand, and at the end.
 */
internal class ScoreTable(head: TableHead) : LocalTable(head) {
    var scores: ShaScores = ShaScores.EMPTY
        private set

    /** Changes not in [scores] yet: a score, or null for one dropped. */
    private val pending = HashMap<String, Float?>()

    override val size: Int get() = scores.size

    override fun has(sha256: String): Boolean = if (sha256 in pending) pending[sha256] != null else scores.containsKey(sha256)

    override fun put(record: ByteArray) = change(record.hex(0, 32), record.floatAt(32))

    override fun drop(sha256: String) = change(sha256, null)

    private fun change(sha256: String, score: Float?) {
        pending[sha256] = score
        if (pending.size >= FLUSH_CHANGES) flush()
    }

    /** Makes [scores] with the changes gathered. */
    fun flush() {
        if (pending.isEmpty()) return
        scores = scores.with(pending)
        pending.clear()
    }

    override fun clear() {
        pending.clear()
        scores = ShaScores.EMPTY
    }

    override fun writeRows(sink: BufferedSink) {
        flush()
        scores.forEachRow { sha, from, score ->
            sink.write(sha, from, 32)
            sink.writeIntLe(score.toBits())
        }
    }

    override fun readRows(source: BufferedSource) {
        clear()
        val shas = Buffer()
        val values = ArrayList<Float>()
        while (!source.exhausted()) {
            shas.write(source.readByteArray(32))
            values += Float.fromBits(source.readIntLe())
        }
        scores = ShaScores.of(shas.readByteArray(), values.toFloatArray())
    }

    private companion object {
        const val FLUSH_CHANGES = 4096
    }
}

/**
 * Numbers by sha256, compact and never changed once made: the sha256s as bytes, sorted, and a number each, looked up
 * by binary search. A read-only [Map] by hex sha256, so what reads scores needn't know. Two are equal only when they
 * are the same one (a [kotlinx.coroutines.flow.StateFlow] compares what it is given; comparing every entry would be
 * slow and is never needed).
 */
internal class ShaScores private constructor(private val shas: ByteArray, private val numbers: FloatArray) : AbstractMap<String, Float>() {
    override val size: Int get() = numbers.size

    override fun get(key: String): Float? {
        val at = indexOf(key)
        return if (at < 0) null else numbers[at]
    }

    override fun containsKey(key: String): Boolean = indexOf(key) >= 0

    override val entries: Set<Map.Entry<String, Float>>
        get() = (0 until size).mapTo(LinkedHashSet(size)) { i -> Entry(shas.hex(i * 32, i * 32 + 32), numbers[i]) }

    override fun equals(other: Any?): Boolean = this === other

    override fun hashCode(): Int = identity

    private val identity = kotlin.random.Random.nextInt()

    /** Each row: the bytes holding its sha256 (32 from the given place), and its number. */
    fun forEachRow(each: (ByteArray, Int, Float) -> Unit) {
        for (i in 0 until size) each(shas, i * 32, numbers[i])
    }

    /** These with [changes] made: a number, or null to leave a sha256 out. */
    fun with(changes: Map<String, Float?>): ShaScores {
        val changed = changes.keys.mapNotNullTo(HashSet()) { key -> parse(key)?.let(::Key) }
        val keptShas = Buffer()
        val keptValues = ArrayList<Float>(size + changes.size)
        for (i in 0 until size) {
            if (Key(shas.copyOfRange(i * 32, i * 32 + 32)) in changed) continue
            keptShas.write(shas, i * 32, 32)
            keptValues += numbers[i]
        }
        for ((key, value) in changes) {
            val bytes = parse(key) ?: continue
            if (value == null) continue
            keptShas.write(bytes)
            keptValues += value
        }
        return of(keptShas.readByteArray(), keptValues.toFloatArray())
    }

    private fun indexOf(key: String): Int {
        val sha = parse(key) ?: return -1
        var low = 0
        var high = size - 1
        while (low <= high) {
            val mid = (low + high) ushr 1
            val c = compare(shas, mid * 32, sha, 0)
            when {
                c < 0 -> low = mid + 1
                c > 0 -> high = mid - 1
                else -> return mid
            }
        }
        return -1
    }

    private class Entry(override val key: String, override val value: Float) : Map.Entry<String, Float>

    /** A sha256's bytes, compared by content. */
    private class Key(val bytes: ByteArray) {
        override fun equals(other: Any?) = other is Key && bytes.contentEquals(other.bytes)
        override fun hashCode() = bytes.contentHashCode()
    }

    companion object {
        val EMPTY = ShaScores(ByteArray(0), FloatArray(0))

        /** From [shas] (32 bytes each) and their [values], in any order; a sha256 given twice keeps its last number. */
        fun of(shas: ByteArray, values: FloatArray): ShaScores {
            val count = values.size
            val order = (0 until count).sortedWith { a, b -> compare(shas, a * 32, shas, b * 32).let { if (it != 0) it else b - a } }
            val sortedShas = ByteArray(count * 32)
            val sortedValues = FloatArray(count)
            var n = 0
            for (i in order) {
                // Sorted with the later of equal sha256s first: the one kept.
                if (n > 0 && compare(sortedShas, (n - 1) * 32, shas, i * 32) == 0) continue
                shas.copyInto(sortedShas, n * 32, i * 32, i * 32 + 32)
                sortedValues[n] = values[i]
                n++
            }
            return ShaScores(sortedShas.copyOf(n * 32), sortedValues.copyOf(n))
        }

        /** A hex sha256's bytes; null when it isn't one. */
        private fun parse(hex: String): ByteArray? {
            if (hex.length != 64) return null
            val bytes = ByteArray(32)
            for (i in 0 until 32) {
                val high = hex[2 * i].digitToIntOrNull(16) ?: return null
                val low = hex[2 * i + 1].digitToIntOrNull(16) ?: return null
                bytes[i] = (high * 16 + low).toByte()
            }
            return bytes
        }

        private fun compare(a: ByteArray, at: Int, b: ByteArray, bt: Int): Int {
            for (i in 0 until 32) {
                val x = a[at + i].toInt() and 0xFF
                val y = b[bt + i].toInt() and 0xFF
                if (x != y) return x - y
            }
            return 0
        }
    }
}

/**
 * Faces: where each face is in each file (x, y, w, h) and its vector (the server's SFace numbers, at full precision),
 * in the record's order, which a face id `<sha256>:<n>` counts. The score and the five points are left out.
 *
 * The vectors stay on disk, as the search vectors do (plan B phase 7): in `faces-<n>.bin` in [folder], one row a file:
 * its sha256, how many faces (0xFFFF says the file was dropped), their boxes, then their vectors. A later row of a file
 * replaces an earlier one. In memory are only the boxes (16 bytes a face, all in one array) and which row is which
 * file's: about 4 MB for 120,000 faces in 40,000 files, where holding the vectors took about 60 MB. [vector] reads one
 * face's numbers from the file. What changes is appended a few thousand rows at a time ([flush]), and [save] writes the
 * file again without the rows replaced once they are an eighth of it. `faces.json` beside it has the head and which
 * file is current. As with the vectors, the writer (a download) and the readers share no lock: each append publishes a
 * new [Rows], and a reader uses the one it started with.
 *
 * A `table.bin` from before (version 2, everything in it) is moved into these files once, without downloading; version
 * 1 kept no vectors and is downloaded again.
 */
internal class FaceTable private constructor(
    head: TableHead,
    private val fileSystem: FileSystem,
    private val folder: Path,
    rows: Rows,
) : LocalTable(head.copy(v = VERSION)) {
    /** Which file's each row is, its faces' boxes, and where each file's latest row is. Never changed once made. */
    private class Rows(
        val file: Path,
        val count: Int,
        val shas: ByteArray,
        /** How many faces the rows before each row have ([count] + 1 of them, the last every face's). */
        val firstFace: IntArray,
        /** The boxes of every row's faces, four numbers a face, in row order. */
        val boxes: FloatArray,
        val current: BooleanArray,
        /** Open addressing: row + 1 of each file's latest row, by its sha256; 0 is empty. */
        val slots: IntArray,
        val live: Int,
    ) {
        fun faces(row: Int) = firstFace[row + 1] - firstFace[row]

        /** [sha256]'s current row; -1 when it has none. */
        fun currentRow(sha256: String): Int {
            val sha = shaBytesOrNull(sha256) ?: return -1
            val row = findIn(slots, shas, sha)
            return if (row >= 0 && current[row]) row else -1
        }
    }

    /**
     * Rows being made: [from]'s (copied, so it stays as it was) with room for [rows] rows and [faces] faces in all, and
     * those [add]ed after them. It grows if more come.
     */
    private class Builder(val file: Path, from: Rows?, rows: Int, faces: Int) {
        var count = from?.count ?: 0
        var shas = (from?.shas ?: ByteArray(0)).copyOf(rows * 32)
        var firstFace = (from?.firstFace ?: IntArray(1)).copyOf(rows + 1)
        var boxes = (from?.boxes ?: FloatArray(0)).copyOf(faces * 4)
        var current = (from?.current ?: BooleanArray(0)).copyOf(rows)
        var slots = if (from != null && rows * 2 + 2 <= from.slots.size) from.slots.copyOf() else rehashed(slotsFor(rows))
        var live = from?.live ?: 0

        /** Slots of [size] pointing at the current rows. */
        fun rehashed(size: Int) = IntArray(size).also { for (r in 0 until count) if (current[r]) place(it, shas, r) }

        /** A row of [sha]'s with [faces] faces ([box] gives the boxes' numbers in order); [kept] false: it was dropped. */
        inline fun add(sha: ByteArray, faces: Int, kept: Boolean, box: (Int) -> Float) {
            if (count == current.size) {
                val rows = maxOf(64, count * 2)
                shas = shas.copyOf(rows * 32)
                firstFace = firstFace.copyOf(rows + 1)
                current = current.copyOf(rows)
            }
            if ((count + 1) * 2 + 2 > slots.size) slots = rehashed(slotsFor(current.size))
            val first = firstFace[count]
            if ((first + faces) * 4 > boxes.size) boxes = boxes.copyOf(maxOf((first + faces) * 4, boxes.size * 2))
            val r = count
            sha.copyInto(shas, r * 32, 0, 32)
            val before = findIn(slots, shas, sha)
            if (before >= 0 && current[before]) {
                current[before] = false
                live--
            }
            if (kept) {
                current[r] = true
                live++
            }
            for (i in 0 until faces * 4) boxes[first * 4 + i] = box(i)
            firstFace[r + 1] = first + faces
            place(slots, shas, r)
            count++
        }

        fun build(): Rows {
            val faces = firstFace[count]
            return Rows(
                file,
                count,
                if (shas.size == count * 32) shas else shas.copyOf(count * 32),
                if (firstFace.size == count + 1) firstFace else firstFace.copyOf(count + 1),
                if (boxes.size == faces * 4) boxes else boxes.copyOf(faces * 4),
                if (current.size == count) current else current.copyOf(count),
                slots,
                live,
            )
        }
    }

    @Volatile
    private var rows: Rows = rows

    /** Rows waiting to be appended: sha256, how many faces, then their boxes and their vectors (null: dropped). */
    private class Pending(val sha: ByteArray, val faces: Int, val data: ByteArray?)

    private val pending = ArrayList<Pending>()
    private var pendingFaces = 0

    private val dim get() = head.dim

    /** What a face takes in a row: its box and its vector. */
    private val faceBytes get() = 16 + 4 * dim

    /** The files kept, as of the last [flush]. */
    override val size: Int get() = rows.live

    override fun has(sha256: String): Boolean {
        val sha = hexBytes(sha256)
        pending.lastOrNull { it.sha.contentEquals(sha) }?.let { return it.data != null }
        val now = rows
        val row = findIn(now.slots, now.shas, sha)
        return row >= 0 && now.current[row]
    }

    override fun put(record: ByteArray) {
        val n = (record[32].toInt() and 0xFF) or ((record[33].toInt() and 0xFF) shl 8)
        val per = 4 * (FACE_FIELDS + dim)
        val data = ByteArray(n * faceBytes)
        for (face in 0 until n) {
            val at = 34 + face * per
            record.copyInto(data, 16 * face, at, at + 16)
            record.copyInto(data, 16 * n + 4 * dim * face, at + 4 * FACE_FIELDS, at + per)
        }
        add(Pending(record.copyOfRange(0, 32), n, data))
    }

    override fun drop(sha256: String) {
        if (has(sha256)) add(Pending(hexBytes(sha256), 0, null))
    }

    private fun add(row: Pending) {
        pending += row
        if (row.data != null) pendingFaces += row.faces
        if (pending.size >= FLUSH_ROWS || pendingFaces >= FLUSH_FACES) flush()
    }

    /** Starts an empty file; the old one goes at the next [save]. */
    override fun clear() {
        pending.clear()
        pendingFaces = 0
        rows = emptyRows(nextFile(fileSystem, folder, FILE_PREFIX))
    }

    /** Appends the rows waiting to the file, then publishes the rows with them. Called as a download goes and by [save]. */
    fun flush() {
        if (pending.isEmpty()) return
        val old = rows
        fileSystem.createDirectories(folder)
        fileSystem.appendingSink(old.file).buffer().use { sink ->
            for (row in pending) {
                sink.write(row.sha)
                sink.writeShortLe(if (row.data == null) DROPPED else row.faces)
                if (row.data != null) sink.write(row.data)
            }
        }
        val builder = Builder(old.file, old, old.count + pending.size, old.firstFace[old.count] + pendingFaces)
        for (row in pending) {
            val data = row.data
            if (data == null) builder.add(row.sha, 0, kept = false) { 0f } else builder.add(row.sha, row.faces, kept = true) { data.floatAt(4 * it) }
        }
        pending.clear()
        pendingFaces = 0
        rows = builder.build()
    }

    /**
     * Saves the table in [path]'s folder (the path itself is the other tables'): the rows waiting appended, the file
     * written again without the rows replaced or dropped once they are an eighth of it, then the head, then the files no
     * longer current deleted.
     */
    override fun save(fileSystem: FileSystem, path: Path) {
        flush()
        val now = rows
        if (now.count - now.live > now.live / 8) compact(now)
        val file = rows.file
        val temp = folder / "$HEAD_FILE.tmp"
        fileSystem.createDirectories(folder)
        if (!fileSystem.exists(file)) fileSystem.write(file) {}
        fileSystem.write(temp) { writeUtf8(json.encodeToString(TableFiles.serializer(), TableFiles(file.name, head))) }
        fileSystem.atomicMove(temp, folder / HEAD_FILE)
        deleteOthers(fileSystem, folder, FILE_PREFIX, file)
    }

    /** The current rows of [from] in a new file. */
    private fun compact(from: Rows) {
        val target = nextFile(fileSystem, folder, FILE_PREFIX)
        var faces = 0
        for (r in 0 until from.count) if (from.current[r]) faces += from.faces(r)
        val builder = Builder(target, null, from.live, faces)
        fileSystem.openReadOnly(from.file).use { handle ->
            handle.source().buffer().use { source ->
                fileSystem.write(target) {
                    for (r in 0 until from.count) {
                        val length = rowBytes(from, r)
                        if (!from.current[r]) {
                            source.skip(length)
                            continue
                        }
                        write(source, length)
                        val first = from.firstFace[r] * 4
                        builder.add(from.shas.copyOfRange(r * 32, r * 32 + 32), from.faces(r), kept = true) { from.boxes[first + it] }
                    }
                }
            }
        }
        rows = builder.build()
    }

    /** Where [row] starts in [of]'s file: every row has its head, and each face before it its box and vector. */
    private fun offsetOf(of: Rows, row: Int): Long = ROW_HEAD.toLong() * row + of.firstFace[row].toLong() * faceBytes

    private fun rowBytes(of: Rows, row: Int): Long = ROW_HEAD + of.faces(row).toLong() * faceBytes

    override fun bytes(fileSystem: FileSystem, path: Path): Long = fileSystem.metadataOrNull(rows.file)?.size ?: 0

    /** The boxes of [sha256]'s faces, four numbers a face; null when it has none kept (or it is still waiting to be appended). */
    fun boxes(sha256: String): FloatArray? {
        val now = rows
        val row = now.currentRow(sha256)
        if (row < 0) return null
        return now.boxes.copyOfRange(now.firstFace[row] * 4, now.firstFace[row + 1] * 4)
    }

    /** Face [n] of [sha256]'s box, or null. */
    fun box(sha256: String, n: Int): FloatArray? {
        val now = rows
        val row = now.currentRow(sha256)
        if (row < 0 || n < 0 || n >= now.faces(row)) return null
        val at = (now.firstFace[row] + n) * 4
        return now.boxes.copyOfRange(at, at + 4)
    }

    /** Face [n] of [sha256]'s vector, read from the file; null when there is none. */
    fun vector(sha256: String, n: Int): FloatArray? {
        val now = rows
        val row = now.currentRow(sha256)
        if (row < 0 || dim == 0) return null
        val faces = now.faces(row)
        if (n < 0 || n >= faces) return null
        val bytes = ByteArray(4 * dim)
        val at = offsetOf(now, row) + ROW_HEAD + 16L * faces + 4L * dim * n
        fileSystem.openReadOnly(now.file).use { it.readFully(at, bytes, bytes.size) }
        return FloatArray(dim) { bytes.floatAt(4 * it) }
    }

    /** Every current row as version 2's `table.bin` kept it: sha256, how many faces, and each face's box and vector. */
    override fun writeRows(sink: BufferedSink) {
        flush()
        val now = rows
        if (now.count == 0) return
        fileSystem.openReadOnly(now.file).use { handle ->
            for (r in 0 until now.count) {
                if (!now.current[r]) continue
                val faces = now.faces(r)
                val bytes = ByteArray(rowBytes(now, r).toInt())
                handle.readFully(offsetOf(now, r), bytes, bytes.size)
                sink.write(bytes, 0, ROW_HEAD)
                for (face in 0 until faces) {
                    sink.write(bytes, ROW_HEAD + 16 * face, 16)
                    sink.write(bytes, ROW_HEAD + 16 * faces + 4 * dim * face, 4 * dim)
                }
            }
        }
    }

    /** Rows as [writeRows] writes them (version 2's whole table), added to this one. */
    override fun readRows(source: BufferedSource) {
        while (!source.exhausted()) {
            val sha = source.readByteArray(32)
            val n = source.readShortLe().toInt() and 0xFFFF
            val data = ByteArray(n * faceBytes)
            for (face in 0 until n) {
                source.readInto(data, 16 * face, 16)
                source.readInto(data, 16 * n + 4 * dim * face, 4 * dim)
            }
            add(Pending(sha, n, data))
        }
        flush()
    }

    /**
     * Reads [file]'s rows (each sha256 and its faces' boxes; the vectors are skipped) into the table's rows. Returns how
     * many bytes of it are whole rows: half a row at the end (a download cut off while appending) is left out.
     */
    private fun index(file: Path): Long {
        val size = fileSystem.metadataOrNull(file)?.size ?: 0
        val builder = Builder(file, null, 1024, 1024)
        val sha = ByteArray(32)
        var box = FloatArray(64)
        var at = 0L
        fileSystem.openReadOnly(file).use { handle ->
            handle.source().buffer().use { source ->
                while (size - at >= ROW_HEAD) {
                    source.readInto(sha, 0, 32)
                    val n = source.readShortLe().toInt() and 0xFFFF
                    if (n == DROPPED) {
                        builder.add(sha, 0, kept = false) { 0f }
                        at += ROW_HEAD
                        continue
                    }
                    val length = n.toLong() * faceBytes
                    if (size - at - ROW_HEAD < length) break
                    if (box.size < 4 * n) box = FloatArray(4 * n)
                    for (i in 0 until 4 * n) box[i] = Float.fromBits(source.readIntLe())
                    source.skip(4L * dim * n)
                    builder.add(sha, n, kept = true) { box[it] }
                    at += ROW_HEAD + length
                }
            }
        }
        rows = builder.build()
        return at
    }

    companion object {
        /** Box, score and five points, before the vector (the server's faces.faceFields). */
        private const val FACE_FIELDS = 15

        /** The file's version: 2 keeps the vectors. */
        const val VERSION = 2

        /** sha256 and how many faces, before the faces. */
        private const val ROW_HEAD = 34

        /** The count of faces that says a file was dropped. */
        private const val DROPPED = 0xFFFF

        /** Rows, and faces, a download gathers before appending them (8,192 faces of 128 numbers: about 4 MB). */
        private const val FLUSH_ROWS = 4096
        private const val FLUSH_FACES = 8192

        private const val HEAD_FILE = "faces.json"
        private const val FILE_PREFIX = "faces-"

        /** A new, empty table in [folder] (its file is made when rows come). */
        fun create(head: TableHead, fileSystem: FileSystem, folder: Path) =
            FaceTable(head, fileSystem, folder, emptyRows(nextFile(fileSystem, folder, FILE_PREFIX)))

        /**
         * The table saved in [folder]; null when there is none. Rows past what was saved last (a download cut off before
         * its end) are kept, as the stream only sends them again; half a row at the end is cut off.
         */
        fun open(fileSystem: FileSystem, folder: Path): FaceTable? {
            val headPath = folder / HEAD_FILE
            if (!fileSystem.exists(headPath)) return null
            val saved = json.decodeFromString(TableFiles.serializer(), fileSystem.read(headPath) { readUtf8() })
            val file = folder / saved.file
            if (!fileSystem.exists(file)) return null
            val table = FaceTable(saved.head, fileSystem, folder, emptyRows(file))
            val whole = table.index(file)
            if (whole < (fileSystem.metadataOrNull(file)?.size ?: 0)) fileSystem.openReadWrite(file).use { it.resize(whole) }
            return table
        }

        private fun emptyRows(file: Path) = Rows(file, 0, ByteArray(0), IntArray(1), FloatArray(0), BooleanArray(0), IntArray(slotsFor(0)), 0)

        /** [sha256] as its 32 bytes; null when it isn't one (a face id written wrong). */
        private fun shaBytesOrNull(sha256: String): ByteArray? =
            if (sha256.length == 64 && sha256.all { it in '0'..'9' || it in 'a'..'f' }) hexBytes(sha256) else null
    }
}

@Serializable
private data class TextRecord(val sha256: String, val lines: List<TextLine> = emptyList())

@Serializable
private data class TextLine(val text: String = "")

/**
 * Text in photos: each file's lines, in a file of their own that is read, not held (plan B phase 7): in memory is only
 * which row of the file is which file's and where it starts, about 60 bytes a file (6 MB for 95,000 photos, whose lines
 * took 45 MB held whole). A photo's lines are read when asked for ([lines]); search reads the whole file once to make
 * its words ([TextWords]).
 *
 * The file, `text-<n>.bin` in [folder], is rows of the sha256, the length of what follows (-1: the file was dropped)
 * and the file's lines, each as its length and its UTF-8 bytes (a file read and found without text has none). A later
 * row of a file replaces an earlier one. As with [VectorTable], what changes is appended a few thousand rows at a time
 * ([flush]), [save] writes the file again without the rows replaced once they are an eighth of it, and `text.json`
 * beside it has the head and which file is current. Each append publishes a new [Rows], and a reader reads the rows
 * of the one it started with, so a download and a search share no lock.
 */
internal class TextTable private constructor(
    head: TableHead,
    private val fileSystem: FileSystem,
    private val folder: Path,
    rows: Rows,
) : LocalTable(head) {
    /** Which file's each row is, where it starts, whether it is current, and each file's latest row. Never changed once made. */
    private class Rows(
        val file: Path,
        val count: Int,
        val shas: ByteArray,
        /** Where each row starts in the file. */
        val starts: LongArray,
        /** Where the last row ends: the file's length. */
        val end: Long,
        val current: BooleanArray,
        /** Open addressing: row + 1 of each file's latest row, by its sha256; 0 is empty. */
        val slots: IntArray,
        val live: Int,
    ) {
        /** The latest row of [sha], current or dropped; -1 when it has none. */
        fun find(sha: ByteArray): Int = findIn(slots, shas, sha)

        /** The whole of row [r]: sha256, length and lines. */
        fun bytes(r: Int): Long = (if (r + 1 < count) starts[r + 1] else end) - starts[r]
    }

    @Volatile
    private var rows: Rows = rows

    /** Rows waiting to be appended: sha256 and lines as the file keeps them (null: dropped). */
    private class Pending(val sha: ByteArray, val lines: ByteArray?)

    private val pending = ArrayList<Pending>()

    /** The files kept, as of the last [flush]. */
    override val size: Int get() = rows.live

    override fun has(sha256: String): Boolean {
        val sha = shaBytes(sha256) ?: return false
        pending.lastOrNull { it.sha.contentEquals(sha) }?.let { return it.lines != null }
        val now = rows
        val row = now.find(sha)
        return row >= 0 && now.current[row]
    }

    /** A record as the server's cache keeps it: `{"sha256": …, "lines": [{"text": …}, …]}`. */
    override fun put(record: ByteArray) {
        val parsed = runCatching { json.decodeFromString(TextRecord.serializer(), record.decodeToString()) }.getOrNull() ?: return
        set(parsed.sha256, parsed.lines.map { it.text })
    }

    /** Keeps [lines] as [sha256]'s. */
    fun set(sha256: String, lines: List<String>) {
        val sha = shaBytes(sha256) ?: return
        add(Pending(sha, encodeLines(lines)))
    }

    override fun drop(sha256: String) {
        val sha = shaBytes(sha256) ?: return
        if (has(sha256)) add(Pending(sha, null))
    }

    private fun add(row: Pending) {
        pending += row
        if (pending.size >= FLUSH_ROWS) flush()
    }

    /** Starts an empty file; the old one goes at the next [save]. */
    override fun clear() {
        pending.clear()
        rows = emptyRows(fileSystem, folder)
    }

    /** Appends the rows waiting to the file, then publishes the rows with them. */
    fun flush() {
        if (pending.isEmpty()) return
        val old = rows
        fileSystem.createDirectories(folder)
        // Bytes past the rows known (an append cut off half way) would put every later row in the wrong place.
        val onDisk = fileSystem.metadataOrNull(old.file)?.size ?: 0
        if (onDisk > old.end) fileSystem.openReadWrite(old.file).use { it.resize(old.end) }
        val count = old.count + pending.size
        val shas = old.shas.copyOf(count * 32)
        val starts = old.starts.copyOf(count)
        val current = old.current.copyOf(count)
        var end = old.end
        fileSystem.appendingSink(old.file).buffer().use { sink ->
            pending.forEachIndexed { i, row ->
                starts[old.count + i] = end
                sink.write(row.sha)
                sink.writeIntLe(row.lines?.size ?: -1)
                if (row.lines != null) sink.write(row.lines)
                end += ROW_HEAD + (row.lines?.size ?: 0)
            }
        }
        var live = old.live
        val slots = if (count * 2 <= old.slots.size) {
            old.slots.copyOf()
        } else {
            IntArray(slotsFor(count)).also { slots -> for (r in 0 until old.count) if (old.current[r]) place(slots, shas, r) }
        }
        pending.forEachIndexed { i, row ->
            val r = old.count + i
            row.sha.copyInto(shas, r * 32)
            val before = findIn(slots, shas, row.sha)
            if (before >= 0 && current[before]) {
                current[before] = false
                live--
            }
            if (row.lines != null) {
                current[r] = true
                live++
            }
            place(slots, shas, r)
        }
        pending.clear()
        rows = Rows(old.file, count, shas, starts, end, current, slots, live)
    }

    /**
     * Saves the table in [path]'s folder (the path itself is the other tables'): the rows waiting appended, the file
     * written again without the rows replaced or dropped once they are an eighth of it, then the head, then the files
     * no longer current deleted.
     */
    override fun save(fileSystem: FileSystem, path: Path) {
        flush()
        val now = rows
        if (now.count - now.live > now.live / 8) compact(now)
        val file = rows.file
        val temp = folder / "$HEAD_FILE.tmp"
        fileSystem.createDirectories(folder)
        if (!fileSystem.exists(file)) fileSystem.write(file) {}
        fileSystem.write(temp) { writeUtf8(json.encodeToString(TableFiles.serializer(), TableFiles(file.name, head))) }
        fileSystem.atomicMove(temp, folder / HEAD_FILE)
        for (other in fileSystem.list(folder)) {
            if (other.name != file.name && other.name.startsWith(FILE_PREFIX) && other.name.endsWith(".bin")) fileSystem.delete(other)
        }
    }

    /** The current rows of [from] in a new file. */
    private fun compact(from: Rows) {
        val target = nextFile(fileSystem, folder, FILE_PREFIX)
        val shas = ByteArray(from.live * 32)
        val starts = LongArray(from.live)
        val current = BooleanArray(from.live) { true }
        val slots = IntArray(slotsFor(from.live))
        var count = 0
        var end = 0L
        fileSystem.write(target) {
            forEachRow(from) { _, buffer, length ->
                write(buffer, 0, length)
                buffer.copyInto(shas, count * 32, 0, 32)
                starts[count] = end
                place(slots, shas, count)
                end += length
                count++
            }
        }
        rows = Rows(target, count, shas, starts, end, current, slots, count)
    }

    override fun bytes(fileSystem: FileSystem, path: Path): Long = fileSystem.metadataOrNull(rows.file)?.size ?: 0

    /** [sha256]'s lines, read from the file; null when it has none (or it is still waiting to be appended). */
    fun lines(sha256: String): List<String>? {
        val sha = shaBytes(sha256) ?: return null
        val now = rows
        val row = now.find(sha)
        if (row < 0 || !now.current[row]) return null
        val length = (now.bytes(row) - ROW_HEAD).toInt()
        val bytes = ByteArray(length)
        fileSystem.openReadOnly(now.file).use { it.readFully(now.starts[row] + ROW_HEAD, bytes, length) }
        return decodeLines(bytes, 0, length)
    }

    /** Every file kept, as of the last [flush]: its sha256 (32 bytes, good only during the call) and its lines, in the file's order. */
    fun forEach(each: (sha: ByteArray, lines: List<String>) -> Unit) {
        val sha = ByteArray(32)
        forEachRow(rows) { _, buffer, length ->
            buffer.copyInto(sha, 0, 0, 32)
            each(sha, decodeLines(buffer, ROW_HEAD, length - ROW_HEAD))
        }
    }

    /** Reads [of]'s current rows in order, each whole into a buffer: [each] gets the row, the buffer and its length. */
    private inline fun forEachRow(of: Rows, each: (Int, ByteArray, Int) -> Unit) {
        if (of.count == 0) return
        var buffer = ByteArray(4096)
        fileSystem.read(of.file) {
            for (r in 0 until of.count) {
                val length = of.bytes(r)
                if (!of.current[r]) {
                    skip(length)
                    continue
                }
                if (length > buffer.size) buffer = ByteArray(maxOf(length.toInt(), buffer.size * 2))
                readInto(buffer, 0, length.toInt())
                each(r, buffer, length.toInt())
            }
        }
    }

    /** Every current row, as the server's records (the table.bin of an earlier version). */
    override fun writeRows(sink: BufferedSink) {
        flush()
        forEach { sha, lines ->
            sink.writeUtf8(json.encodeToString(TextRecord.serializer(), TextRecord(sha.hex(), lines.map(::TextLine))))
            sink.writeByte('\n'.code)
        }
    }

    /** Rows as [writeRows] writes them (an earlier version's whole table), added to this one. */
    override fun readRows(source: BufferedSource) {
        while (true) {
            val line = source.readUtf8Line() ?: break
            if (line.isNotBlank()) put(line.encodeToByteArray())
        }
        flush()
    }

    companion object {
        /** sha256 and length, before the lines. */
        private const val ROW_HEAD = 36

        /** Rows a download gathers before appending them. */
        private const val FLUSH_ROWS = 4096

        private const val HEAD_FILE = "text.json"
        private const val FILE_PREFIX = "text-"

        /** A new, empty table in [folder] (its file is made when rows come). */
        fun create(head: TableHead, fileSystem: FileSystem, folder: Path) = TextTable(head, fileSystem, folder, emptyRows(fileSystem, folder))

        private fun emptyRows(fileSystem: FileSystem, folder: Path) =
            Rows(nextFile(fileSystem, folder, FILE_PREFIX), 0, ByteArray(0), LongArray(0), 0, BooleanArray(0), IntArray(slotsFor(0)), 0)

        /**
         * The table saved in [folder]; null when there is none. Rows past what was saved last (a download cut off before
         * its end) are kept, as the stream only sends them again; half a row at the end is cut off.
         */
        fun open(fileSystem: FileSystem, folder: Path): TextTable? {
            val headPath = folder / HEAD_FILE
            if (!fileSystem.exists(headPath)) return null
            val saved = json.decodeFromString(TableFiles.serializer(), fileSystem.read(headPath) { readUtf8() })
            val file = folder / saved.file
            if (!fileSystem.exists(file)) return null
            val size = fileSystem.metadataOrNull(file)?.size ?: 0
            var shas = ByteArray(32 * 1024)
            var starts = LongArray(1024)
            var dropped = BooleanArray(1024)
            var count = 0
            var end = 0L
            fileSystem.read(file) {
                while (end + ROW_HEAD <= size) {
                    if (count == starts.size) {
                        shas = shas.copyOf(shas.size * 2)
                        starts = starts.copyOf(starts.size * 2)
                        dropped = dropped.copyOf(dropped.size * 2)
                    }
                    readInto(shas, count * 32, 32)
                    val length = readIntLe()
                    if (length < -1 || end + ROW_HEAD + maxOf(length, 0) > size) break
                    skip(maxOf(length, 0).toLong())
                    starts[count] = end
                    dropped[count] = length < 0
                    end += ROW_HEAD + maxOf(length, 0)
                    count++
                }
            }
            if (end < size) fileSystem.openReadWrite(file).use { it.resize(end) }
            val slots = IntArray(slotsFor(count))
            val current = BooleanArray(count)
            var live = 0
            for (r in 0 until count) {
                val before = findIn(slots, shas, shas, r * 32)
                if (before >= 0 && current[before]) {
                    current[before] = false
                    live--
                }
                if (!dropped[r]) {
                    current[r] = true
                    live++
                }
                place(slots, shas, r)
            }
            return TextTable(saved.head, fileSystem, folder, Rows(file, count, shas.copyOf(count * 32), starts.copyOf(count), end, current, slots, live))
        }

        /** Lines as the file keeps them: each its length in bytes, then its UTF-8. */
        private fun encodeLines(lines: List<String>): ByteArray {
            val buffer = okio.Buffer()
            for (line in lines) {
                val bytes = line.encodeToByteArray()
                buffer.writeIntLe(bytes.size)
                buffer.write(bytes)
            }
            return buffer.readByteArray()
        }

        private fun decodeLines(bytes: ByteArray, from: Int, length: Int): List<String> {
            if (length == 0) return emptyList()
            val lines = ArrayList<String>()
            var at = from
            while (at < from + length) {
                val n = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
                lines += bytes.decodeToString(at + 4, at + 4 + n)
                at += 4 + n
            }
            return lines
        }
    }
}

/** [sha] as 32 bytes; null when it isn't 64 hex digits. */
private fun shaBytes(sha: String): ByteArray? {
    if (sha.length != 64) return null
    return runCatching { hexBytes(sha) }.getOrNull()
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
