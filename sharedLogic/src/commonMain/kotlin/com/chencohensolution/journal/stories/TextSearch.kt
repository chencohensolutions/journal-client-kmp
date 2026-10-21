package com.chencohensolution.journal.stories

// Title and tag search: a substring, or one typo for longer queries.

/**
 * Whether [text] matches a search: [query] appears anywhere in it ignoring case, or, for queries of four letters or
 * more, a word of [text] is one typo away from it.
 */
internal fun matches(text: String, query: String): Boolean {
    val q = query.trim().lowercase()
    if (q.isEmpty()) return true
    val t = text.lowercase()
    if (q in t) return true
    return q.length >= 4 && t.split(Regex("\\W+")).any { word -> withinOneEdit(word, q) || withinOneEdit(word.take(q.length), q) }
}

private fun withinOneEdit(a: String, b: String): Boolean {
    if (kotlin.math.abs(a.length - b.length) > 1) return false
    var i = 0
    var j = 0
    var edits = 0
    while (i < a.length && j < b.length) {
        if (a[i] == b[j]) {
            i++; j++
            continue
        }
        if (++edits > 1) return false
        when {
            a.length > b.length -> i++
            a.length < b.length -> j++
            else -> { i++; j++ }
        }
    }
    return edits + (a.length - i) + (b.length - j) <= 1
}
