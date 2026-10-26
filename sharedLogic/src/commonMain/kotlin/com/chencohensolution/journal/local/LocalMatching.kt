package com.chencohensolution.journal.local

// What the server decides by, done the same way on the phone (checked against local-fixtures): the words of text in
// photos and which match a search's words (journal-server-go ocr/service.go), and how search hits are grouped into
// sets and moments (search/group.go).

/**
 * [text]'s words as text in photos compares them: runs of letters and digits, lower case, without niqqud, and Hebrew's
 * final letters as the others (the server's ocr.Tokenize).
 */
internal fun ocrWords(text: String): List<String> {
    val words = mutableListOf<String>()
    val word = StringBuilder()
    fun end() {
        if (word.isNotEmpty()) words += word.toString()
        word.clear()
    }
    var i = 0
    while (i < text.length) {
        val cp = codePointAt(text, i)
        val chars = if (cp > 0xFFFF) 2 else 1
        if (isNiqqud(cp)) {
            // Part of a word, but not kept.
        } else if (isLetterOrDigit(cp)) {
            val lower = text.substring(i, i + chars).lowercase()
            for (c in lower) word.append(FINALS[c] ?: c)
        } else {
            end()
        }
        i += chars
    }
    end()
    return words
}

private val FINALS = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

private fun isNiqqud(cp: Int) = cp in 0x0591..0x05C7 && cp != 0x05BE && cp != 0x05C0 && cp != 0x05C3 && cp != 0x05C6

private fun codePointAt(text: String, i: Int): Int {
    val c = text[i]
    if (c.isHighSurrogate() && i + 1 < text.length && text[i + 1].isLowSurrogate()) {
        return 0x10000 + ((c.code - 0xD800) shl 10) + (text[i + 1].code - 0xDC00)
    }
    return c.code
}

private fun isLetterOrDigit(cp: Int): Boolean =
    if (cp <= 0xFFFF) cp.toChar().isLetterOrDigit() else true

/** The letters written onto a Hebrew word before it: the, and, in, as, to, from, that. */
private const val HEBREW_PREFIXES = "הובכלמש"

/**
 * Whether the word [w] (from a photo) answers the query word [q], as the server's ocr.Matches: the same word, a word it
 * begins (of three letters or more), or in Hebrew the word with up to two prefix letters before it.
 */
internal fun ocrMatches(w: String, q: String): Boolean = ocrMatches(w.toCharArray(), 0, w.length, q, q.codePointCount())

/** [ocrMatches] for the word in [letters] from [from] to [to]; [qPoints] is [q]'s code points, counted once. */
internal fun ocrMatches(letters: CharArray, from: Int, to: Int, q: String, qPoints: Int): Boolean {
    val length = to - from
    if (length < q.length) return false
    // The same word, or a word it begins.
    if (sameAt(letters, from, q) && (length == q.length || qPoints >= 3)) return true
    // The word after up to two Hebrew prefix letters.
    val rest = length - q.length
    if (rest == 0 || q.isEmpty() || !isHebrew(q[0]) || !sameAt(letters, to - q.length, q)) return false
    var restPoints = 0
    for (i in from until from + rest) {
        if (letters[i] !in HEBREW_PREFIXES) return false
        if (!letters[i].isLowSurrogate()) restPoints++
    }
    return restPoints <= 2
}

private fun sameAt(letters: CharArray, at: Int, q: String): Boolean {
    for (i in q.indices) if (letters[at + i] != q[i]) return false
    return true
}

private fun String.codePointCount(): Int = count { !it.isLowSurrogate() }

private fun isHebrew(c: Char) = c.code in 0x0590..0x05FF || c.code in 0xFB1D..0xFB4F

/**
 * The files whose text has every word of [query], those with the most matches first (then by sha256), of those [keep]
 * accepts: the server's ocr.Find. [words] is every file's words, made once by [wordIndex].
 */
internal fun ocrFind(words: TextWords, query: String, keep: (String) -> Boolean): List<String> = words.find(query, keep)

/** The words of [lines] (by sha256): what [ocrFind] looks up. */
internal fun wordIndex(lines: Map<String, List<String>>): TextWords {
    val builder = TextWords.Builder()
    for ((sha, text) in lines) builder.add(hexBytes(sha), text)
    return builder.build()
}

/** The words of the text kept on the phone, read from its file. */
internal fun wordIndex(table: TextTable): TextWords {
    val builder = TextWords.Builder()
    table.forEach { sha, lines -> builder.add(sha, lines) }
    return builder.build()
}

/**
 * Every word of the text in photos and the files having it, how often: what [ocrFind] looks up. Kept compact (plan B
 * phase 7): the words' letters one after another in one array, and each word's files as numbers into one array of
 * sha256s, a byte for how often (maps of strings took about 97 MB for 95,000 photos). Never changed once made.
 */
internal class TextWords private constructor(
    /** Each file's sha256, 32 bytes, by its number. */
    private val shas: ByteArray,
    private val files: Int,
    private val letters: CharArray,
    /** Where each word's letters start in [letters], and one more for where the last ends. */
    private val wordStarts: IntArray,
    /** Where each word's files start in [postingFiles], and one more for where the last ends. */
    private val postingStarts: IntArray,
    private val postingFiles: IntArray,
    /** How often the word is in the file; 255 means look in [manyTimes]. */
    private val postingCounts: ByteArray,
    private val manyTimes: Map<Int, Int>,
) {
    private val words get() = wordStarts.size - 1

    private fun timesAt(k: Int): Int {
        val n = postingCounts[k].toInt() and 0xFF
        return if (n == 255) manyTimes.getValue(k) else n
    }

    /** As the server's ocr.Find: each word of [query] must match a word of the file; the files' matches add up. */
    fun find(query: String, keep: (String) -> Boolean): List<String> {
        val tokens = ocrWords(query)
        if (tokens.isEmpty()) return emptyList()
        var counts: IntArray? = null
        for (token in tokens) {
            val points = token.codePointCount()
            val these = IntArray(files)
            var any = false
            for (w in 0 until words) {
                if (!ocrMatches(letters, wordStarts[w], wordStarts[w + 1], token, points)) continue
                for (k in postingStarts[w] until postingStarts[w + 1]) {
                    val f = postingFiles[k]
                    if (counts == null || counts[f] > 0) {
                        these[f] += timesAt(k)
                        any = true
                    }
                }
            }
            if (!any) return emptyList()
            if (counts != null) for (f in 0 until files) if (these[f] > 0) these[f] += counts[f]
            counts = these
        }
        val found = ArrayList<Pair<String, Int>>()
        for (f in 0 until files) {
            if (counts!![f] == 0) continue
            val sha = shas.hex(f * 32, f * 32 + 32)
            if (keep(sha)) found += sha to counts[f]
        }
        return found.sortedWith(compareByDescending<Pair<String, Int>> { it.second }.thenBy { it.first }).map { it.first }
    }

    /** Gathers files' words, then [build]s the index. The words are kept once each, without a string apiece. */
    class Builder {
        private var shas = ByteArray(32 * 256)
        private var files = 0
        private var letters = CharArray(1 shl 16)
        private var letterCount = 0
        private var wordStarts = IntArray(1024)
        private var wordHashes = IntArray(1024)
        private var words = 0

        /** Open addressing: word + 1 by its hash; 0 is empty. */
        private var slots = IntArray(2048)

        /** Each (word, file, how often), in the order added. */
        private var seenWords = IntArray(4096)
        private var seenFiles = IntArray(4096)
        private var seenTimes = IntArray(4096)
        private var seen = 0

        /** Adds a file's words; a file without words is left out. [sha] is copied. */
        fun add(sha: ByteArray, lines: List<String>) {
            if (lines.isEmpty()) return
            val times = HashMap<String, Int>()
            for (line in lines) for (w in ocrWords(line)) times[w] = (times[w] ?: 0) + 1
            if (times.isEmpty()) return
            if ((files + 1) * 32 > shas.size) shas = shas.copyOf(shas.size * 2)
            sha.copyInto(shas, files * 32, 0, 32)
            for ((w, n) in times) {
                if (seen == seenWords.size) {
                    seenWords = seenWords.copyOf(seen * 2)
                    seenFiles = seenFiles.copyOf(seen * 2)
                    seenTimes = seenTimes.copyOf(seen * 2)
                }
                seenWords[seen] = idOf(w)
                seenFiles[seen] = files
                seenTimes[seen] = n
                seen++
            }
            files++
        }

        private fun idOf(w: String): Int {
            val hash = w.hashCode()
            val mask = slots.size - 1
            var i = mix(hash) and mask
            while (true) {
                val id = slots[i] - 1
                if (id < 0) break
                if (wordHashes[id] == hash && wordStarts[id + 1] - wordStarts[id] == w.length && sameAt(letters, wordStarts[id], w)) return id
                i = (i + 1) and mask
            }
            // A new word.
            if (words + 2 > wordStarts.size) {
                wordStarts = wordStarts.copyOf(wordStarts.size * 2)
                wordHashes = wordHashes.copyOf(wordHashes.size * 2)
            }
            while (letterCount + w.length > letters.size) letters = letters.copyOf(letters.size * 2)
            for (c in w) letters[letterCount++] = c
            val id = words++
            wordHashes[id] = hash
            wordStarts[id + 1] = letterCount
            if (words * 2 > slots.size) {
                slots = IntArray(slots.size * 2)
                val bigger = slots.size - 1
                for (other in 0 until words) {
                    var j = mix(wordHashes[other]) and bigger
                    while (slots[j] != 0) j = (j + 1) and bigger
                    slots[j] = other + 1
                }
            } else {
                slots[i] = id + 1
            }
            return id
        }

        private fun mix(hash: Int): Int {
            val h = hash * 0x9E3779B9.toInt()
            return h xor (h ushr 16)
        }

        fun build(): TextWords {
            // Each word's files together, in the order the files were added.
            val postingStarts = IntArray(words + 1)
            for (k in 0 until seen) postingStarts[seenWords[k] + 1]++
            for (w in 0 until words) postingStarts[w + 1] += postingStarts[w]
            val next = postingStarts.copyOf(words)
            val postingFiles = IntArray(seen)
            val postingCounts = ByteArray(seen)
            val manyTimes = HashMap<Int, Int>()
            for (k in 0 until seen) {
                val at = next[seenWords[k]]++
                postingFiles[at] = seenFiles[k]
                val n = seenTimes[k]
                postingCounts[at] = minOf(n, 255).toByte()
                if (n >= 255) manyTimes[at] = n
            }
            return TextWords(
                shas.copyOf(files * 32),
                files,
                letters.copyOf(letterCount),
                wordStarts.copyOf(words + 1),
                postingStarts,
                postingFiles,
                postingCounts,
                manyTimes,
            )
        }
    }
}

/** How close a photo must be to a set's first to join it, and in time; how close sets must be to share a moment. */
internal const val SET_SIMILARITY = 0.93f
internal const val SET_WINDOW_MILLIS = 10 * 60_000L
internal const val MOMENT_WINDOW_MILLIS = 2 * 3_600_000L

/**
 * Numbers [hits] (best first) by set and by moment, as the server's search.Groups: a hit joins the first set whose first
 * hit was taken within 10 minutes of it and whose vector is at least 0.93 like its own; a set joins the first moment
 * whose first set was taken within 2 hours. [vector] and [takenAt] give a hit's (null: none, unknown).
 */
internal fun searchGroups(hits: List<String>, vector: (String) -> FloatArray?, takenAt: (String) -> Long?): Pair<List<Int>, List<Int>> {
    class Group(val at: Long?, val vector: FloatArray?, val moment: Int)
    val groups = mutableListOf<Group>()
    val momentStarts = mutableListOf<Long?>()
    val sets = IntArray(hits.size)
    val moments = IntArray(hits.size)
    hits.forEachIndexed { i, sha ->
        val at = takenAt(sha)
        val v = if (at != null) vector(sha) else null
        var set = -1
        if (v != null) {
            set = groups.indexOfFirst { g -> g.vector != null && g.at != null && kotlin.math.abs(at!! - g.at) <= SET_WINDOW_MILLIS && dot(v, g.vector) >= SET_SIMILARITY }
        }
        if (set < 0) {
            var moment = -1
            if (at != null) moment = momentStarts.indexOfFirst { start -> start != null && kotlin.math.abs(at - start) <= MOMENT_WINDOW_MILLIS }
            if (moment < 0) {
                moment = momentStarts.size
                momentStarts += at
            }
            set = groups.size
            groups += Group(at, v, moment)
        }
        sets[i] = set
        moments[i] = groups[set].moment
    }
    return sets.toList() to moments.toList()
}

internal fun dot(a: FloatArray, b: FloatArray): Float {
    var s = 0f
    for (i in a.indices) s += a[i] * b[i]
    return s
}
