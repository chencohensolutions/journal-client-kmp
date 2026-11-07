package com.chencohensolution.journal.tls

import okio.ByteString.Companion.toByteString

/**
 * The server's key pin (docs/plans/discovery.md, "Phones"): the sha256 of its certificate's SubjectPublicKeyInfo in
 * hex, as journal-server-go's `peers.Fingerprint` makes it. Once the app keeps it (`serverInfo.discovery`), the mTLS
 * clients accept a server whose certificate has that key, whatever its host name or IP, instead of checking the CA
 * and the name.
 */
internal object ServerPin {
    /** Whether the certificate [certificateDer] carries the key pinned by [fingerprintHex]. */
    fun matches(certificateDer: ByteArray, fingerprintHex: String): Boolean =
        spkiOf(certificateDer)?.let { fingerprintOfSpki(it).equals(fingerprintHex, ignoreCase = true) } ?: false

    fun fingerprintOfSpki(spki: ByteArray): String = spki.toByteString().sha256().hex()

    /**
     * The SubjectPublicKeyInfo (tag, length and content) of an X.509 certificate in DER; null when it isn't one.
     * Certificate ::= SEQUENCE { tbsCertificate SEQUENCE { [0] version OPTIONAL, serialNumber, signature, issuer,
     * validity, subject, subjectPublicKeyInfo, ... }, ... }
     */
    fun spkiOf(certificateDer: ByteArray): ByteArray? = runCatching {
        val certificate = Tlv.read(certificateDer, 0)
        require(certificate.tag == 0x30)
        val tbs = Tlv.read(certificateDer, certificate.contentStart)
        require(tbs.tag == 0x30)
        var at = tbs.contentStart
        var element = Tlv.read(certificateDer, at)
        if (element.tag == 0xA0) {
            at = element.end
            element = Tlv.read(certificateDer, at)
        }
        // serialNumber, signature, issuer, validity, subject
        repeat(5) {
            at = element.end
            element = Tlv.read(certificateDer, at)
        }
        require(element.tag == 0x30 && element.end <= tbs.end)
        certificateDer.copyOfRange(element.start, element.end)
    }.getOrNull()

    private class Tlv(val tag: Int, val start: Int, val contentStart: Int, val end: Int) {
        companion object {
            fun read(b: ByteArray, at: Int): Tlv {
                val tag = b[at].toInt() and 0xff
                var pos = at + 1
                val first = b[pos++].toInt() and 0xff
                val length = if (first < 0x80) {
                    first
                } else {
                    val n = first and 0x7f
                    require(n in 1..4)
                    var l = 0
                    repeat(n) { l = (l shl 8) or (b[pos++].toInt() and 0xff) }
                    l
                }
                require(length >= 0 && pos + length <= b.size)
                return Tlv(tag, at, pos, pos + length)
            }
        }
    }
}
