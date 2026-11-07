package com.chencohensolution.journal.discovery

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Where a server said it can be reached, from its DHT record; [at] is when it sealed it (epoch milliseconds). */
data class ServerRecord(val addresses: List<String>, val at: Long)

/**
 * A server's record in the DHT (journal-server-go `discovery/record.go`): a BEP 44 mutable item under the server's
 * ed25519 key with salt [SALT], whose value is a 24-byte nonce then the XChaCha20-Poly1305 seal of
 * `{"v": 1, "a": [addresses], "t": "<RFC 3339>"}`, with the salt as additional data. The seal's key is made from the
 * server's fingerprint, so only who knows the server can open it, and a record that opens came from someone who knows
 * it (DHT nodes don't): the phones open it instead of checking the ed25519 signature.
 */
internal object DhtRecord {
    const val SALT = "journal-server/v1"
    private val saltBytes = SALT.encodeToByteArray()
    private val json = Json { ignoreUnknownKeys = true }

    /** The 20-byte DHT target of [keyHex] (BEP 44): SHA-1(key + salt). */
    fun target(keyHex: String): ByteArray = (keyHex.decodeHex().toByteArray() + saltBytes).toByteString().sha1().toByteArray()

    /** The seal's key: HKDF-SHA256 of the fingerprint's 32 bytes, no salt, info "journal dht seal v1". */
    fun sealKey(fingerprintHex: String): ByteArray {
        val raw = fingerprintHex.decodeHex().toByteArray()
        require(raw.size == 32) { "not a fingerprint" }
        return Seal.hkdfSha256(raw, ByteArray(0), "journal dht seal v1".encodeToByteArray(), Seal.KEY_SIZE)
    }

    /** The record in [sealed], or null when it doesn't open with [fingerprintHex] or isn't one. */
    @OptIn(ExperimentalTime::class)
    fun open(sealed: ByteArray, fingerprintHex: String): ServerRecord? {
        if (sealed.size < Seal.NONCE_SIZE + Seal.TAG_SIZE) return null
        val key = runCatching { sealKey(fingerprintHex) }.getOrNull() ?: return null
        val plain = Seal.openXChaCha20Poly1305(
            key, sealed.copyOfRange(0, Seal.NONCE_SIZE), sealed.copyOfRange(Seal.NONCE_SIZE, sealed.size), saltBytes,
        ) ?: return null
        val record = runCatching { json.decodeFromString(Plain.serializer(), plain.decodeToString()) }.getOrNull() ?: return null
        if (record.v != 1) return null
        val at = runCatching { Instant.parse(record.t).toEpochMilliseconds() }.getOrNull() ?: return null
        return ServerRecord(record.a.filter { it.startsWith("https://") }, at)
    }

    /** Seals [record] as a server does, for tests. */
    @OptIn(ExperimentalTime::class)
    fun seal(record: ServerRecord, fingerprintHex: String, nonce: ByteArray): ByteArray {
        val plain = json.encodeToString(Plain.serializer(), Plain(1, record.addresses, Instant.fromEpochMilliseconds(record.at).toString()))
        return nonce + Seal.sealXChaCha20Poly1305(sealKey(fingerprintHex), nonce, plain.encodeToByteArray(), saltBytes)
    }

    @Serializable
    private class Plain(val v: Int, @SerialName("a") val a: List<String> = emptyList(), val t: String = "")
}
