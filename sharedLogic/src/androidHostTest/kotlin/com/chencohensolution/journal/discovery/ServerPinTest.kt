package com.chencohensolution.journal.discovery

import com.chencohensolution.journal.network.PinnedTrustManager
import com.chencohensolution.journal.tls.SERVER_CA_PEM
import com.chencohensolution.journal.tls.ServerPin
import com.chencohensolution.journal.tls.decodePemCertificates
import okhttp3.tls.HeldCertificate
import java.io.ByteArrayInputStream
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pin is the sha256 of the certificate's SubjectPublicKeyInfo, as journal-server-go's `peers.Fingerprint`: the
 * common DER reader (iOS) and the JVM's own encoding (Android) agree on RSA (the servers' keys) and EC certificates.
 */
class ServerPinTest {
    private fun x509(der: ByteArray) =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate

    private val certificates: List<X509Certificate> = decodePemCertificates(SERVER_CA_PEM).map(::x509) + listOf(
        HeldCertificate.Builder().rsa2048().commonName("server").addSubjectAlternativeName("journal.example.com").build().certificate,
        HeldCertificate.Builder().ecdsa256().commonName("server").build().certificate,
    )

    @Test
    fun theDerReaderFindsTheKeyTheJvmSees() {
        for (certificate in certificates) {
            val fingerprint = ServerPin.fingerprintOfSpki(certificate.publicKey.encoded)
            assertEquals(fingerprint, ServerPin.spkiOf(certificate.encoded)?.let(ServerPin::fingerprintOfSpki))
            assertTrue(ServerPin.matches(certificate.encoded, fingerprint.uppercase()))
            assertFalse(ServerPin.matches(certificate.encoded, "00".repeat(32)))
        }
        assertNull(ServerPin.spkiOf(ByteArray(3)))
        assertNull(ServerPin.spkiOf(certificates[0].encoded.copyOf(100)))
    }

    @Test
    fun thePinnedTrustManagerTakesOnlyThePinnedKey() {
        val server = HeldCertificate.Builder().rsa2048().commonName("server").build().certificate
        val other = HeldCertificate.Builder().rsa2048().commonName("server").build().certificate
        val trust = PinnedTrustManager(ServerPin.fingerprintOfSpki(server.publicKey.encoded))
        trust.checkServerTrusted(arrayOf(server), "RSA")
        assertFailsWith<CertificateException> { trust.checkServerTrusted(arrayOf(other), "RSA") }
        assertFailsWith<CertificateException> { trust.checkServerTrusted(emptyArray(), "RSA") }
        assertFailsWith<CertificateException> { trust.checkClientTrusted(arrayOf(server), "RSA") }
    }
}
