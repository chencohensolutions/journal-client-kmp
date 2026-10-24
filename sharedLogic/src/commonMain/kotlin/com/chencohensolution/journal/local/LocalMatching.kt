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
internal fun ocrMatches(w: String, q: String): Boolean {
    if (w == q || (q.codePointCount() >= 3 && w.startsWith(q))) return true
    if (!w.endsWith(q)) return false
    val rest = w.dropLast(q.length)
    if (rest.isEmpty() || rest.codePointCount() > 2 || q.isEmpty() || !isHebrew(q[0])) return false
    return rest.all { it in HEBREW_PREFIXES }
}

private fun String.codePointCount(): Int = count { !it.isLowSurrogate() }

private fun isHebrew(c: Char) = c.code in 0x0590..0x05FF || c.code in 0xFB1D..0xFB4F

/**
 * The files whose text has every word of [query], those with the most matches first (then by sha256), of those [keep]
 * accepts: the server's ocr.Find over [lines] (by sha256). [words] is each file's words, made once by [wordIndex].
 */
internal fun ocrFind(words: Map<String, Map<String, Int>>, query: String, keep: (String) -> Boolean): List<String> {
    val tokens = ocrWords(query)
    if (tokens.isEmpty()) return emptyList()
    var counts: Map<String, Int>? = null
    for (token in tokens) {
        val these = HashMap<String, Int>()
        for ((w, shas) in words) {
            if (!ocrMatches(w, token)) continue
            for ((sha, n) in shas) {
                if (counts == null || (counts[sha] ?: 0) > 0) these[sha] = (these[sha] ?: 0) + n
            }
        }
        for ((sha, n) in these.toList()) these[sha] = n + (counts?.get(sha) ?: 0)
        counts = these
        if (counts.isEmpty()) return emptyList()
    }
    val found = counts!!.keys.filter(keep)
    return found.sortedWith(compareByDescending<String> { counts[it] }.thenBy { it })
}

/** Word → files having it → how often: what [ocrFind] looks up. */
internal fun wordIndex(lines: Map<String, List<String>>): Map<String, Map<String, Int>> {
    val index = HashMap<String, HashMap<String, Int>>()
    for ((sha, text) in lines) {
        for (line in text) for (w in ocrWords(line)) {
            val files = index.getOrPut(w) { HashMap() }
            files[sha] = (files[sha] ?: 0) + 1
        }
    }
    return index
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
