package com.chencohensolution.journal.stories

/** A run of [text] in an earlier version, [changed] when the version after it no longer has it. */
data class DiffPart(val text: String, val changed: Boolean)

/**
 * [earlier] split into runs by what [later] kept of it, word by word (spaces and line breaks stay with the word before
 * them), for History to highlight what changed. Long texts are compared by lines only, to stay quick.
 */
fun changedParts(earlier: String, later: String): List<DiffPart> {
    val byWord = earlier.length + later.length <= MAX_WORD_DIFF
    val old = tokens(earlier, byWord)
    val new = tokens(later, byWord)
    // Longest common subsequence, by suffix lengths.
    val common = Array(old.size + 1) { IntArray(new.size + 1) }
    for (i in old.indices.reversed()) for (j in new.indices.reversed()) {
        common[i][j] = if (old[i].trim() == new[j].trim()) common[i + 1][j + 1] + 1 else maxOf(common[i + 1][j], common[i][j + 1])
    }
    val parts = mutableListOf<DiffPart>()
    var i = 0
    var j = 0
    while (i < old.size) {
        val kept = j < new.size && old[i].trim() == new[j].trim()
        when {
            kept -> { add(parts, old[i], changed = false); i++; j++ }
            j < new.size && common[i][j + 1] > common[i + 1][j] -> j++
            else -> { add(parts, old[i], changed = true); i++ }
        }
    }
    return parts
}

private const val MAX_WORD_DIFF = 8_000

private fun tokens(text: String, byWord: Boolean): List<String> =
    (if (byWord) Regex("""\S+\s*""") else Regex(""".*(\n|$)""")).findAll(text).map { it.value }.filter { it.isNotEmpty() }.toList()

private fun add(parts: MutableList<DiffPart>, text: String, changed: Boolean) {
    val last = parts.lastOrNull()
    if (last != null && last.changed == changed) parts[parts.size - 1] = last.copy(text = last.text + text) else parts += DiffPart(text, changed)
}
