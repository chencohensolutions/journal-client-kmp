package com.chencohensolution.journal.discovery

import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The phones read back exactly what journal-server-go writes ([DhtFixtures], made by its own code). */
@OptIn(ExperimentalTime::class)
class DhtRecordTest {
    private fun hex(s: String) = s.decodeHex().toByteArray()

    @Test
    fun targetIsSha1OfKeyAndSalt() {
        assertEquals(DhtFixtures.TARGET, DhtRecord.target(DhtFixtures.KEY).toByteString().hex())
        assertEquals(DhtFixtures.SALT, DhtRecord.SALT)
    }

    @Test
    fun opensTheServersSeal() {
        val record = assertNotNull(DhtRecord.open(hex(DhtFixtures.SEALED), DhtFixtures.FINGERPRINT))
        assertEquals(DhtFixtures.ADDRESSES, record.addresses)
        assertEquals(Instant.parse(DhtFixtures.AT).toEpochMilliseconds(), record.at)
    }

    @Test
    fun anotherFingerprintOrAChangedByteDoesNotOpen() {
        assertNull(DhtRecord.open(hex(DhtFixtures.SEALED), "00" + DhtFixtures.FINGERPRINT.drop(2)))
        val changed = hex(DhtFixtures.SEALED).also { it[40] = (it[40].toInt() xor 0x10).toByte() }
        assertNull(DhtRecord.open(changed, DhtFixtures.FINGERPRINT))
        assertNull(DhtRecord.open(ByteArray(10), DhtFixtures.FINGERPRINT))
        assertNull(DhtRecord.open(hex(DhtFixtures.SEALED), "not hex"))
    }

    @Test
    fun readsTheWholeReply() {
        val reply = Bencode.decode(hex(DhtFixtures.REPLY)) as Map<*, *>
        assertEquals("r", (reply["y"] as ByteArray).decodeToString())
        val r = reply["r"] as Map<*, *>
        assertContentEquals(hex(DhtFixtures.KEY), r["k"] as ByteArray)
        assertEquals(DhtFixtures.SEQ, r["seq"])
        assertContentEquals(hex(DhtFixtures.SIG), r["sig"] as ByteArray)
        assertContentEquals(hex(DhtFixtures.SEALED), r["v"] as ByteArray)
        // Encoding it again gives the same bytes: keys sorted, lengths as the server wrote them.
        assertEquals(DhtFixtures.REPLY, Bencode.encode(reply).toByteString().hex())
    }

    @Test
    fun sealsAndOpensAgain() {
        val record = ServerRecord(listOf("https://198.51.100.4:56398"), Instant.parse("2026-11-07T20:00:00Z").toEpochMilliseconds())
        val sealed = DhtRecord.seal(record, DhtFixtures.FINGERPRINT, ByteArray(24) { it.toByte() })
        assertEquals(record, DhtRecord.open(sealed, DhtFixtures.FINGERPRINT))
    }

    @Test
    fun bencodeRoundTripsAndRefusesBadInput() {
        val value = mapOf("a" to listOf(1L, -2L, "x".encodeToByteArray()), "b" to mapOf<String, Any>())
        val encoded = Bencode.encode(value)
        assertEquals("d1:ali1ei-2e1:xe1:bdee", encoded.decodeToString())
        val back = Bencode.decode(encoded) as Map<*, *>
        assertEquals(listOf(1L, -2L), (back["a"] as List<*>).take(2))
        for (bad in listOf("", "i12", "5:abc", "d1:a", "x", "i1ei2e", "l".repeat(100))) {
            assertFailsWith<Bencode.FormatException>(bad) { Bencode.decode(bad.encodeToByteArray()) }
        }
    }
}
