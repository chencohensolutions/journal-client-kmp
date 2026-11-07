package com.chencohensolution.journal.swap

// The passphrase of an encrypted copy on a friend's server (docs/plans/swap-from-phones.md, "Keeping the passphrase").
// The server makes the user's copy key from it (Argon2id) and never keeps it; the phone suggests it, shows it once, has
// two of its words typed back, then saves it to the platform's synced keychain. A forgotten passphrase is a lost copy.

/** A passphrase the phone suggested: its words, and which two the user types back. */
data class SuggestedPassphrase(
    val words: List<String>,
    /** Two positions in [words] (0-based, in order) the user types back before the copy starts. */
    val check: List<Int>,
) {
    /** The words with single spaces, as the server and the keychain get it. */
    val text: String get() = words.joinToString(" ")

    /** Whether [typed] (one answer per position in [check], in order) are those words, in any case and spacing. */
    fun typedBack(typed: List<String>): Boolean =
        typed.size == check.size && check.indices.all { normalize(typed[it]) == words[check[it]] }

    /** For Swift, which can't build a List easily: the two answers. */
    fun typedBack(first: String, second: String): Boolean = typedBack(listOf(first, second))
}

object Passphrase {
    /** Six words of the EFF large list: about 77 bits, which the Argon2id key on the server makes slow to guess. */
    const val WORDS = 6

    /** How many words are typed back. */
    const val CHECKED = 2

    /** [WORDS] random words from the bundled list, and [CHECKED] random positions to type back. */
    fun suggest(): SuggestedPassphrase = suggest(::secureRandomBelow)

    internal fun suggest(below: (Int) -> Int): SuggestedPassphrase {
        val list = SWAP_WORDS
        val words = List(WORDS) { list[below(list.size)] }
        val positions = (0 until WORDS).toMutableList()
        val check = List(CHECKED) { positions.removeAt(below(positions.size)) }.sorted()
        return SuggestedPassphrase(words, check)
    }

    /**
     * Why [passphrase] is too weak for a new copy key, as the server checks it (journal-server-go copies/derive.go
     * `WeakPassphrase`): it needs 24 characters, or 4 words or more and 20; null when strong enough.
     */
    fun weakness(passphrase: String): PassphraseWeakness? {
        val p = passphrase.trim()
        val words = p.split(Regex("[\\s-]+")).count { it.isNotEmpty() }
        val length = p.codePointCount()
        return if (length >= 24 || (length >= 20 && words >= 4)) null else PassphraseWeakness(length, words)
    }

    /** The list's word count, for tests and the recovery sheet's note. */
    val wordListSize: Int get() = SWAP_WORDS.size
}

/** A passphrase that is [characters] long in [words] words: too short (see [Passphrase.weakness]). */
data class PassphraseWeakness(val characters: Int, val words: Int)

internal fun normalize(word: String) = word.trim().lowercase()

private fun String.codePointCount(): Int {
    var n = 0
    var i = 0
    while (i < length) {
        if (this[i].isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate()) i++
        i++
        n++
    }
    return n
}

/** A uniform random number in 0 until [bound], from the secure source, without modulo bias. */
internal fun secureRandomBelow(bound: Int): Int {
    require(bound > 0)
    val limit = (0x1_0000_0000L / bound) * bound
    while (true) {
        val b = secureRandomBytes(4)
        val v = ((b[0].toLong() and 0xff) shl 24) or ((b[1].toLong() and 0xff) shl 16) or
            ((b[2].toLong() and 0xff) shl 8) or (b[3].toLong() and 0xff)
        if (v < limit) return (v % bound).toInt()
    }
}
