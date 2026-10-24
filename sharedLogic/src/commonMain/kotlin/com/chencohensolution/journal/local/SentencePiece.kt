package com.chencohensolution.journal.local

// SigLIP 2's tokenizer on the phone: SentencePiece's BPE model (Gemma's tokenizer.model), as the server reads it with
// github.com/eliben/go-sentencepiece, so a search's words become the same tokens here as there (checked against the
// server's answers in local-fixtures/tokens.json). Only what that model uses: BPE, user-defined pieces, byte fallback,
// and no normalization but spaces as "▁".

/** A SentencePiece model, read from its `tokenizer.model` (a ModelProto). */
internal class SentencePiece(model: ByteArray) {
    private class Piece(val text: String, val score: Float, val type: Int)

    private val pieces: List<Piece>
    private val normal = HashMap<String, Int>()
    private val reserved = HashMap<String, Int>()
    private val userDefined = HashSet<String>()
    private val byteIds = IntArray(256) { -1 }
    private val unknownId: Int
    private val byteFallback: Boolean
    private val maxPieceLength: Int
    private val maxUserDefinedLength: Int

    /** The ids of `<eos>` and `<pad>`, which the text model's input ends with; -1 when the model has none. */
    val eosId: Int
    val padId: Int

    init {
        val read = mutableListOf<Piece>()
        var fallback = false
        ProtoReader(model).forEachField { field, wire, reader ->
            when {
                field == 1 && wire == 2 -> read += reader.message { pieceReader ->
                    var text = ""
                    var score = 0f
                    var type = NORMAL
                    pieceReader.forEachField { f, w, r ->
                        when {
                            f == 1 && w == 2 -> text = r.string()
                            f == 2 && w == 5 -> score = r.float()
                            f == 3 && w == 0 -> type = r.varint().toInt()
                            else -> r.skip(w)
                        }
                    }
                    Piece(text, score, type)
                }
                field == 2 && wire == 2 -> reader.message { spec ->
                    spec.forEachField { f, w, r ->
                        if (f == 35 && w == 0) fallback = r.varint() != 0L else r.skip(w)
                    }
                }
                else -> reader.skip(wire)
            }
        }
        pieces = read
        byteFallback = fallback
        var unknown = -1
        var longest = 0
        var longestUser = 0
        read.forEachIndexed { id, piece ->
            when (piece.type) {
                NORMAL, USER_DEFINED, UNUSED -> {
                    normal[piece.text] = id
                    longest = maxOf(longest, piece.text.length)
                }
                else -> reserved[piece.text] = id
            }
            when (piece.type) {
                USER_DEFINED -> {
                    userDefined += piece.text
                    longestUser = maxOf(longestUser, piece.text.length)
                }
                UNKNOWN -> unknown = id
                BYTE -> hexByte(piece.text)?.let { byteIds[it] = id }
            }
        }
        require(unknown >= 0) { "tokenizer.model has no <unk>" }
        unknownId = unknown
        maxPieceLength = longest
        maxUserDefinedLength = longestUser
        eosId = controlId("<eos>")
        padId = controlId("<pad>")
    }

    private fun controlId(symbol: String): Int {
        val id = reserved[symbol] ?: normal[symbol] ?: return -1
        return if (pieces[id].type == CONTROL) id else -1
    }

    /** The token ids of [text], as SentencePiece's BPE encodes it. */
    fun encode(text: String): List<Int> {
        if (text.isEmpty()) return emptyList()
        val normalized = text.replace(" ", SEPARATOR)
        // Each symbol starts as one character (a code point) or a user-defined piece, then pairs are merged by score.
        val symbols = mutableListOf<String>()
        val noMerge = mutableListOf<Boolean>()
        var at = 0
        while (at < normalized.length) {
            val user = userDefinedAt(normalized, at)
            val length = user ?: if (normalized[at].isHighSurrogate() && at + 1 < normalized.length) 2 else 1
            symbols += normalized.substring(at, at + length)
            noMerge += user != null
            at += length
        }
        val n = symbols.size
        val prev = IntArray(n) { it - 1 }
        val next = IntArray(n) { if (it == n - 1) -1 else it + 1 }
        val queue = MergeQueue()

        fun suggest(left: Int, right: Int) {
            if (left == -1 || right == -1 || noMerge[left] || noMerge[right]) return
            val merged = symbols[left] + symbols[right]
            if (merged.length > maxPieceLength) return
            val id = normal[merged] ?: return
            queue.add(Candidate(left, right, merged.length, pieces[id].score))
        }

        for (i in 1 until n) suggest(i - 1, i)
        while (queue.isNotEmpty()) {
            val candidate = queue.popMax()
            val left = symbols[candidate.left]
            val right = symbols[candidate.right]
            // Out of date: one of its parts was merged with something else since.
            if (left.isEmpty() || right.isEmpty() || left.length + right.length != candidate.length) continue
            symbols[candidate.left] = left + right
            val after = next[candidate.right]
            next[candidate.left] = after
            if (after >= 0) prev[after] = candidate.left
            symbols[candidate.right] = ""
            suggest(prev[candidate.left], candidate.left)
            suggest(candidate.left, after)
        }

        val ids = mutableListOf<Int>()
        var i = 0
        while (i >= 0) {
            val symbol = symbols[i]
            val id = reserved[symbol] ?: normal[symbol] ?: unknownId
            if (id == unknownId && byteFallback) {
                symbol.encodeToByteArray().forEach { ids += byteIds[it.toInt() and 0xFF] }
            } else {
                ids += id
            }
            i = next[i]
        }
        return ids
    }

    /** The length of the longest user-defined piece starting at [at], or null. */
    private fun userDefinedAt(text: String, at: Int): Int? {
        if (userDefined.isEmpty()) return null
        for (length in minOf(maxUserDefinedLength, text.length - at) downTo 1) {
            if (text.regionMatches(at, text, at, length) && text.substring(at, at + length) in userDefined) return length
        }
        return null
    }

    private class Candidate(val left: Int, val right: Int, val length: Int, val score: Float)

    /** A max-heap of merge candidates: the highest score first, and of equal scores the leftmost. */
    private class MergeQueue {
        private val items = ArrayList<Candidate>()

        fun isNotEmpty() = items.isNotEmpty()

        private fun before(a: Candidate, b: Candidate) = a.score > b.score || (a.score == b.score && a.left < b.left)

        fun add(c: Candidate) {
            items += c
            var i = items.size - 1
            while (i > 0) {
                val parent = (i - 1) / 2
                if (!before(items[i], items[parent])) break
                items[i] = items[parent].also { items[parent] = items[i] }
                i = parent
            }
        }

        fun popMax(): Candidate {
            val top = items[0]
            val last = items.removeAt(items.size - 1)
            if (items.isNotEmpty()) {
                items[0] = last
                var i = 0
                while (true) {
                    val l = 2 * i + 1
                    val r = l + 1
                    var best = i
                    if (l < items.size && before(items[l], items[best])) best = l
                    if (r < items.size && before(items[r], items[best])) best = r
                    if (best == i) break
                    items[i] = items[best].also { items[best] = items[i] }
                    i = best
                }
            }
            return top
        }
    }

    private companion object {
        const val SEPARATOR = "▁"
        const val NORMAL = 1
        const val UNKNOWN = 2
        const val CONTROL = 3
        const val USER_DEFINED = 4
        const val UNUSED = 5
        const val BYTE = 6

        /** "<0x41>" → 0x41. */
        fun hexByte(piece: String): Int? =
            piece.removePrefix("<0x").removeSuffix(">").toIntOrNull(16)?.takeIf { it in 0..255 }
    }
}

/** Reads protobuf's wire format: just what [SentencePiece] needs. */
internal class ProtoReader(private val bytes: ByteArray, private var at: Int = 0, private val end: Int = bytes.size) {
    fun forEachField(block: (field: Int, wire: Int, reader: ProtoReader) -> Unit) {
        while (at < end) {
            val key = varint()
            block((key ushr 3).toInt(), (key and 7).toInt(), this)
        }
    }

    fun varint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            val b = bytes[at++].toInt()
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    fun float(): Float {
        val bits = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
        at += 4
        return Float.fromBits(bits)
    }

    fun string(): String {
        val length = varint().toInt()
        return bytes.decodeToString(at, at + length).also { at += length }
    }

    fun <T> message(block: (ProtoReader) -> T): T {
        val length = varint().toInt()
        return block(ProtoReader(bytes, at, at + length)).also { at += length }
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> at += 8
            2 -> {
                // Read before adding: `at += varint()` would add to the position from before the length.
                val length = varint().toInt()
                at += length
            }
            5 -> at += 4
            else -> error("Unsupported protobuf wire type $wire")
        }
    }
}

/**
 * [text] as SigLIP 2's text half reads it, as the server does (search.Model.Tokens): lowercased, encoded, cut to fit,
 * then `<eos>` and `<pad>` up to [TEXT_LENGTH].
 */
internal fun SentencePiece.textTokens(text: String): LongArray {
    val tokens = LongArray(TEXT_LENGTH) { padId.toLong() }
    val ids = encode(text.lowercase())
    val n = minOf(ids.size, TEXT_LENGTH - 1)
    for (i in 0 until n) tokens[i] = ids[i].toLong()
    tokens[n] = eosId.toLong()
    return tokens
}

/** SigLIP 2 reads text as 64 tokens. */
internal const val TEXT_LENGTH = 64
