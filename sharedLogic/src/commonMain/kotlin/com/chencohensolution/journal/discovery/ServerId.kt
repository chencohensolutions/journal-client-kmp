package com.chencohensolution.journal.discovery

import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString

/**
 * A server id as journal-server-go's `discovery/id.go` writes it (Syncthing's device id format): the fingerprint's
 * 32 bytes in base32 (52 letters), a Luhn mod 32 check letter after each 13, in 8 groups of 7
 * ("MFZWI3D-BONSGYC-…").
 */
internal object ServerId {
    private const val ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"

    /** The id of [fingerprint] (64 hex digits); null for one that isn't. */
    fun format(fingerprint: String): String? {
        val raw = runCatching { fingerprint.decodeHex() }.getOrNull()?.takeIf { it.size == 32 } ?: return null
        val plain = base32(raw.toByteArray())
        val checked = (0 until 4).joinToString("") { i -> plain.substring(i * 13, (i + 1) * 13).let { it + luhn32(it) } }
        return checked.chunked(7).joinToString("-")
    }

    /** The fingerprint (lower-case hex) of an id in any case, with dashes or spaces, or of a fingerprint; null if neither. */
    fun parse(text: String): String? {
        val s = text.trim().filter { it != '-' && it != ' ' }
        if (s.length == 64) runCatching { s.lowercase().decodeHex() }.getOrNull()?.let { return it.hex() }
        val upper = s.uppercase()
        if (upper.length != 56) return null
        val plain = StringBuilder()
        for (i in 0 until 4) {
            val part = upper.substring(i * 14, i * 14 + 13)
            if (part.any { it !in ALPHABET } || luhn32(part) != upper[i * 14 + 13]) return null
            plain.append(part)
        }
        return unbase32(plain.toString())?.takeIf { it.size == 32 }?.toByteString()?.hex()
    }

    private fun base32(bytes: ByteArray): String = buildString {
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) append(ALPHABET[(buffer shl (5 - bits)) and 31])
    }

    private fun unbase32(text: String): ByteArray? {
        val out = ArrayList<Byte>()
        var buffer = 0
        var bits = 0
        for (c in text) {
            val v = ALPHABET.indexOf(c).takeIf { it >= 0 } ?: return null
            buffer = (buffer shl 5) or v
            bits += 5
            if (bits >= 8) {
                out.add((buffer shr (bits - 8)).toByte())
                bits -= 8
            }
        }
        return out.toByteArray()
    }

    /** Syncthing's luhnBase32, as `luhn32` in id.go. */
    private fun luhn32(s: String): Char {
        val n = 32
        var factor = 1
        var sum = 0
        for (c in s) {
            val addend = factor * ALPHABET.indexOf(c)
            factor = if (factor == 2) 1 else 2
            sum += addend / n + addend % n
        }
        return ALPHABET[(n - sum % n) % n]
    }
}
