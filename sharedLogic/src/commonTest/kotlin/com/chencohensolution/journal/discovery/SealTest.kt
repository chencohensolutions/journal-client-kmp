package com.chencohensolution.journal.discovery

import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The ciphers against RFC 8439, draft-irtf-cfrg-xchacha-03 and RFC 5869 test vectors. */
class SealTest {
    private fun hex(s: String) = s.filter { it.isLetterOrDigit() }.lowercase().decodeHex().toByteArray()
    private fun ByteArray.hex() = toByteString().hex()

    private val sunscreen = ("Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the " +
        "future, sunscreen would be it.").encodeToByteArray()

    @Test
    fun chaCha20MatchesRfc8439Section242() {
        val key = ByteArray(32) { it.toByte() }
        val nonce = hex("000000000000004a00000000")
        val cipher = Seal.chaCha20(key, 1, nonce, sunscreen)
        assertEquals(
            "6e2e359a2568f98041ba0728dd0d6981e97e7aec1d4360c20a27afccfd9fae0bf91b65c5524733ab8f593dabcd62b357" +
                "1639d624e65152ab8f530c359f0861d807ca0dbf500d6a6156a38e088a22b65e52bc514d16ccf806818ce91ab7793736" +
                "5af90bbf74a35be6b40b8eedf2785e42874d",
            cipher.hex(),
        )
        assertContentEquals(sunscreen, Seal.chaCha20(key, 1, nonce, cipher))
    }

    @Test
    fun poly1305MatchesRfc8439Section252() {
        val key = hex("85d6be7857556d337f4452fe42d506a80103808afb0db2fd4abff6af4149f51b")
        val tag = Seal.poly1305(key, "Cryptographic Forum Research Group".encodeToByteArray())
        assertEquals("a8061dc1305136c6c22b8baf0c0127a9", tag.hex())
    }

    /** RFC 8439 A.3, vectors 5 to 11: the edge cases of the reduction and of adding s. */
    @Test
    fun poly1305EdgeCasesFromRfc8439AppendixA3() {
        val zeros = "00".repeat(16)
        fun check(r: String, s: String, data: String, tag: String) =
            assertEquals(tag, Seal.poly1305(hex(r + s), hex(data)).hex(), "r=$r data=$data")
        check("02" + "00".repeat(15), zeros, "ff".repeat(16), "03" + "00".repeat(15))
        check("02" + "00".repeat(15), "ff".repeat(16), "02" + "00".repeat(15), "03" + "00".repeat(15))
        check("01" + "00".repeat(15), zeros, "ff".repeat(16) + "f0" + "ff".repeat(15) + "11" + "00".repeat(15), "05" + "00".repeat(15))
        check("01" + "00".repeat(15), zeros, "ff".repeat(16) + "fb" + "fe".repeat(15) + "01".repeat(16), zeros)
        check("02" + "00".repeat(15), zeros, "fd" + "ff".repeat(15), "fa" + "ff".repeat(15))
        val r10 = "0100000000000000" + "0400000000000000"
        val data11 = "e33594d7505e43b90000000000000000" + "3394d7505e4379cd0100000000000000" + "00".repeat(16)
        check(r10, zeros, data11 + "01" + "00".repeat(15), "14000000000000005500000000000000")
        check(r10, zeros, data11, "13" + "00".repeat(15))
    }

    @Test
    fun aeadMatchesRfc8439Section282() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("07000000" + "4041424344454647")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val sealed = Seal.sealChaCha20Poly1305(key, nonce, sunscreen, aad)
        assertEquals(
            "d31a8d34648e60db7b86afbc53ef7ec2a4aded51296e08fea9e2b5a736ee62d63dbea45e8ca9671282fafb69da92728b" +
                "1a71de0a9e060b2905d6a5b67ecd3b3692ddbd7f2d778b8c9803aee328091b58fab324e4fad675945585808b4831d7bc" +
                "3ff4def08e4b7a9de576d26586cec64b6116" + "1ae10b594f09e26a7e902ecbd0600691",
            sealed.hex(),
        )
        assertContentEquals(sunscreen, Seal.openChaCha20Poly1305(key, nonce, sealed, aad))
        sealed[3] = (sealed[3].toInt() xor 1).toByte()
        assertNull(Seal.openChaCha20Poly1305(key, nonce, sealed, aad))
    }

    @Test
    fun hChaCha20MatchesXChaChaDraftSection221() {
        val key = ByteArray(32) { it.toByte() }
        val subKey = Seal.hChaCha20(key, hex("000000090000004a0000000031415927"))
        assertEquals("82413b4227b27bfed30e42508a877d73a0f9e4d58a74a853c12ec41326d3ecdc", subKey.hex())
    }

    @Test
    fun xChaCha20Poly1305MatchesXChaChaDraftAppendixA31() {
        val key = hex("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f")
        val nonce = hex("404142434445464748494a4b4c4d4e4f5051525354555657")
        val aad = hex("50515253c0c1c2c3c4c5c6c7")
        val expected = "bd6d179d3e83d43b9576579493c0e939572a1700252bfaccbed2902c21396cbb" +
            "731c7f1b0b4aa6440bf3a82f4eda7e39ae64c6708c54c216cb96b72e1213b452" +
            "2f8c9ba40db5d945b11b69b982c1bb9e3f3fac2bc369488f76b2383565d3fff9" +
            "21f9664c97637da9768812f615c68b13b52e" + "c0875924c1c7987947deafd8780acf49"
        assertEquals(expected, Seal.sealXChaCha20Poly1305(key, nonce, sunscreen, aad).hex())
        assertContentEquals(sunscreen, Seal.openXChaCha20Poly1305(key, nonce, hex(expected), aad))
        assertNull(Seal.openXChaCha20Poly1305(key, nonce, hex(expected), hex("50515253c0c1c2c3c4c5c6c8")))
    }

    @Test
    fun hkdfMatchesRfc5869() {
        val ikm = ByteArray(22) { 0x0b }
        assertEquals(
            "3cb25f25faacd57a90434f64d0362f2a2d2d0a90cf1a5a4c5db02d56ecc4c5bf34007208d5b887185865",
            Seal.hkdfSha256(ikm, hex("000102030405060708090a0b0c"), hex("f0f1f2f3f4f5f6f7f8f9"), 42).hex(),
        )
        // A.3: no salt, no info.
        assertEquals(
            "8da4e775a563c18f715f802a063c5a31b8a11f5c5ee1879ec3454e5f3c738d2d9d201395faa4b61a96c8",
            Seal.hkdfSha256(ikm, ByteArray(0), ByteArray(0), 42).hex(),
        )
    }
}
