package com.chencohensolution.journal.discovery

import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * The ciphers that open a server's DHT record, in pure Kotlin (no CryptoKit from Kotlin/Native): ChaCha20 and
 * Poly1305 (RFC 8439), HChaCha20 and XChaCha20-Poly1305 (draft-irtf-cfrg-xchacha), and HKDF-SHA256 (RFC 5869) over
 * okio's HMAC. Speed doesn't matter here: a record is a few hundred bytes, opened a few times an hour at most.
 */
internal object Seal {
    const val KEY_SIZE = 32
    const val NONCE_SIZE = 24
    const val TAG_SIZE = 16

    /** XChaCha20-Poly1305: [sealed] is the ciphertext with its tag at the end. Null when the tag doesn't match. */
    fun openXChaCha20Poly1305(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        require(key.size == KEY_SIZE && nonce.size == NONCE_SIZE)
        if (sealed.size < TAG_SIZE) return null
        val (subKey, chachaNonce) = xNonce(key, nonce)
        return openChaCha20Poly1305(subKey, chachaNonce, sealed, aad)
    }

    /** The other way, for tests (the server seals; the phones only open). */
    fun sealXChaCha20Poly1305(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        require(key.size == KEY_SIZE && nonce.size == NONCE_SIZE)
        val (subKey, chachaNonce) = xNonce(key, nonce)
        return sealChaCha20Poly1305(subKey, chachaNonce, plain, aad)
    }

    private fun xNonce(key: ByteArray, nonce: ByteArray): Pair<ByteArray, ByteArray> {
        val subKey = hChaCha20(key, nonce.copyOfRange(0, 16))
        val chachaNonce = ByteArray(12).also { nonce.copyInto(it, destinationOffset = 4, startIndex = 16, endIndex = 24) }
        return subKey to chachaNonce
    }

    /** AEAD_CHACHA20_POLY1305 (RFC 8439 2.8), opening. */
    fun openChaCha20Poly1305(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray): ByteArray? {
        val cipher = sealed.copyOfRange(0, sealed.size - TAG_SIZE)
        val tag = sealed.copyOfRange(sealed.size - TAG_SIZE, sealed.size)
        val polyKey = chaCha20Block(key, 0, nonce).copyOfRange(0, 32)
        if (!constantTimeEquals(poly1305(polyKey, aeadMacData(aad, cipher)), tag)) return null
        return chaCha20(key, 1, nonce, cipher)
    }

    /** AEAD_CHACHA20_POLY1305 (RFC 8439 2.8), sealing: ciphertext then tag. */
    fun sealChaCha20Poly1305(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray): ByteArray {
        val polyKey = chaCha20Block(key, 0, nonce).copyOfRange(0, 32)
        val cipher = chaCha20(key, 1, nonce, plain)
        return cipher + poly1305(polyKey, aeadMacData(aad, cipher))
    }

    private fun aeadMacData(aad: ByteArray, cipher: ByteArray): ByteArray {
        fun pad(n: Int) = ByteArray((16 - n % 16) % 16)
        return aad + pad(aad.size) + cipher + pad(cipher.size) + le64(aad.size.toLong()) + le64(cipher.size.toLong())
    }

    /** ChaCha20 (RFC 8439 2.4): [data] XOR the key stream from block [counter]. */
    fun chaCha20(key: ByteArray, counter: Int, nonce: ByteArray, data: ByteArray): ByteArray {
        val out = ByteArray(data.size)
        var block = counter
        var offset = 0
        while (offset < data.size) {
            val stream = chaCha20Block(key, block++, nonce)
            val n = minOf(64, data.size - offset)
            for (i in 0 until n) out[offset + i] = (data[offset + i].toInt() xor stream[i].toInt()).toByte()
            offset += n
        }
        return out
    }

    /** The ChaCha20 block function (RFC 8439 2.3): 64 bytes of key stream. */
    fun chaCha20Block(key: ByteArray, counter: Int, nonce: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 12)
        val initial = IntArray(16)
        setup(initial, key)
        initial[12] = counter
        for (i in 0 until 3) initial[13 + i] = le32(nonce, 4 * i)
        val state = initial.copyOf()
        rounds(state)
        val out = ByteArray(64)
        for (i in 0 until 16) putLe32(out, 4 * i, state[i] + initial[i])
        return out
    }

    /** HChaCha20 (draft-irtf-cfrg-xchacha 2.2): a subkey from [key] and a 16-byte [nonce]. */
    fun hChaCha20(key: ByteArray, nonce: ByteArray): ByteArray {
        require(key.size == 32 && nonce.size == 16)
        val state = IntArray(16)
        setup(state, key)
        for (i in 0 until 4) state[12 + i] = le32(nonce, 4 * i)
        rounds(state)
        val out = ByteArray(32)
        for (i in 0 until 4) putLe32(out, 4 * i, state[i])
        for (i in 0 until 4) putLe32(out, 16 + 4 * i, state[12 + i])
        return out
    }

    private fun setup(state: IntArray, key: ByteArray) {
        state[0] = 0x61707865
        state[1] = 0x3320646e
        state[2] = 0x79622d32
        state[3] = 0x6b206574
        for (i in 0 until 8) state[4 + i] = le32(key, 4 * i)
    }

    private fun rounds(s: IntArray) {
        repeat(10) {
            quarter(s, 0, 4, 8, 12); quarter(s, 1, 5, 9, 13); quarter(s, 2, 6, 10, 14); quarter(s, 3, 7, 11, 15)
            quarter(s, 0, 5, 10, 15); quarter(s, 1, 6, 11, 12); quarter(s, 2, 7, 8, 13); quarter(s, 3, 4, 9, 14)
        }
    }

    private fun quarter(s: IntArray, a: Int, b: Int, c: Int, d: Int) {
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(16)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(12)
        s[a] += s[b]; s[d] = (s[d] xor s[a]).rotateLeft(8)
        s[c] += s[d]; s[b] = (s[b] xor s[c]).rotateLeft(7)
    }

    /** Poly1305 (RFC 8439 2.5) with a 32-byte one-time [key]: 26-bit limbs in Longs ("donna-32"). */
    fun poly1305(key: ByteArray, message: ByteArray): ByteArray {
        require(key.size == 32)
        val mask = 0x3ffffffL
        val t0 = u32(key, 0); val t1 = u32(key, 4); val t2 = u32(key, 8); val t3 = u32(key, 12)
        val r0 = t0 and 0x3ffffffL
        val r1 = ((t0 ushr 26) or (t1 shl 6)) and 0x3ffff03L
        val r2 = ((t1 ushr 20) or (t2 shl 12)) and 0x3ffc0ffL
        val r3 = ((t2 ushr 14) or (t3 shl 18)) and 0x3f03fffL
        val r4 = (t3 ushr 8) and 0x00fffffL
        val s1 = r1 * 5; val s2 = r2 * 5; val s3 = r3 * 5; val s4 = r4 * 5
        var h0 = 0L; var h1 = 0L; var h2 = 0L; var h3 = 0L; var h4 = 0L

        var offset = 0
        val block = ByteArray(16)
        while (offset < message.size) {
            val n = minOf(16, message.size - offset)
            block.fill(0)
            message.copyInto(block, 0, offset, offset + n)
            val hibit = if (n == 16) 1L shl 24 else 0L.also { block[n] = 1 }
            val m0 = u32(block, 0); val m1 = u32(block, 4); val m2 = u32(block, 8); val m3 = u32(block, 12)
            h0 += m0 and mask
            h1 += ((m0 ushr 26) or (m1 shl 6)) and mask
            h2 += ((m1 ushr 20) or (m2 shl 12)) and mask
            h3 += ((m2 ushr 14) or (m3 shl 18)) and mask
            h4 += (m3 ushr 8) or hibit

            val d0 = h0 * r0 + h1 * s4 + h2 * s3 + h3 * s2 + h4 * s1
            var d1 = h0 * r1 + h1 * r0 + h2 * s4 + h3 * s3 + h4 * s2
            var d2 = h0 * r2 + h1 * r1 + h2 * r0 + h3 * s4 + h4 * s3
            var d3 = h0 * r3 + h1 * r2 + h2 * r1 + h3 * r0 + h4 * s4
            var d4 = h0 * r4 + h1 * r3 + h2 * r2 + h3 * r1 + h4 * r0
            var c = d0 ushr 26; h0 = d0 and mask
            d1 += c; c = d1 ushr 26; h1 = d1 and mask
            d2 += c; c = d2 ushr 26; h2 = d2 and mask
            d3 += c; c = d3 ushr 26; h3 = d3 and mask
            d4 += c; c = d4 ushr 26; h4 = d4 and mask
            h0 += c * 5; c = h0 ushr 26; h0 = h0 and mask
            h1 += c
            offset += n
        }

        var c = h1 ushr 26; h1 = h1 and mask
        h2 += c; c = h2 ushr 26; h2 = h2 and mask
        h3 += c; c = h3 ushr 26; h3 = h3 and mask
        h4 += c; c = h4 ushr 26; h4 = h4 and mask
        h0 += c * 5; c = h0 ushr 26; h0 = h0 and mask
        h1 += c

        // h - p, where p = 2^130 - 5: use it when it isn't negative.
        var g0 = h0 + 5; c = g0 ushr 26; g0 = g0 and mask
        var g1 = h1 + c; c = g1 ushr 26; g1 = g1 and mask
        var g2 = h2 + c; c = g2 ushr 26; g2 = g2 and mask
        var g3 = h3 + c; c = g3 ushr 26; g3 = g3 and mask
        val g4 = h4 + c - (1L shl 26)
        if (g4 >= 0) {
            h0 = g0; h1 = g1; h2 = g2; h3 = g3; h4 = g4
        }

        val w0 = (h0 or (h1 shl 26)) and 0xffffffffL
        val w1 = ((h1 ushr 6) or (h2 shl 20)) and 0xffffffffL
        val w2 = ((h2 ushr 12) or (h3 shl 14)) and 0xffffffffL
        val w3 = ((h3 ushr 18) or (h4 shl 8)) and 0xffffffffL

        val out = ByteArray(16)
        var f = w0 + u32(key, 16); putLe32(out, 0, f.toInt())
        f = w1 + u32(key, 20) + (f ushr 32); putLe32(out, 4, f.toInt())
        f = w2 + u32(key, 24) + (f ushr 32); putLe32(out, 8, f.toInt())
        f = w3 + u32(key, 28) + (f ushr 32); putLe32(out, 12, f.toInt())
        return out
    }

    /** HKDF-SHA256 (RFC 5869); an empty [salt] is a salt of 32 zeros, as the RFC says. */
    fun hkdfSha256(secret: ByteArray, salt: ByteArray, info: ByteArray, length: Int): ByteArray {
        require(length in 0..255 * 32)
        val prk = secret.toByteString().hmacSha256((if (salt.isEmpty()) ByteArray(32) else salt).toByteString())
        val out = ByteArray(length)
        var previous = ByteString.EMPTY
        var written = 0
        var counter = 1
        while (written < length) {
            previous = (previous.toByteArray() + info + byteArrayOf(counter.toByte())).toByteString().hmacSha256(prk)
            val n = minOf(previous.size, length - written)
            previous.toByteArray().copyInto(out, written, 0, n)
            written += n
            counter++
        }
        return out
    }

    private fun constantTimeEquals(a: ByteArray, b: ByteArray): Boolean {
        if (a.size != b.size) return false
        var diff = 0
        for (i in a.indices) diff = diff or (a[i].toInt() xor b[i].toInt())
        return diff == 0
    }

    private fun le32(b: ByteArray, i: Int): Int =
        (b[i].toInt() and 0xff) or ((b[i + 1].toInt() and 0xff) shl 8) or
            ((b[i + 2].toInt() and 0xff) shl 16) or ((b[i + 3].toInt() and 0xff) shl 24)

    private fun u32(b: ByteArray, i: Int): Long = le32(b, i).toLong() and 0xffffffffL

    private fun putLe32(b: ByteArray, i: Int, v: Int) {
        b[i] = v.toByte(); b[i + 1] = (v ushr 8).toByte(); b[i + 2] = (v ushr 16).toByte(); b[i + 3] = (v ushr 24).toByte()
    }

    private fun le64(v: Long) = ByteArray(8) { (v ushr (8 * it)).toByte() }
}
