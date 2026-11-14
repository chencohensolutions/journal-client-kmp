package com.chencohensolution.journal.discovery

import com.chencohensolution.journal.network.KeptServerTrustManager
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
        val trust = KeptServerTrustManager(emptyList(), ServerPin.fingerprintOfSpki(server.publicKey.encoded))
        trust.checkServerTrusted(arrayOf(server), "RSA")
        assertFailsWith<CertificateException> { trust.checkServerTrusted(arrayOf(other), "RSA") }
        assertFailsWith<CertificateException> { trust.checkServerTrusted(emptyArray(), "RSA") }
        assertFailsWith<CertificateException> { trust.checkClientTrusted(arrayOf(server), "RSA") }
    }

    /**
     * A server with its own CA (journal-server-go docs/install.md, "A new server, and the CA"): once the phone keeps
     * the CA, any server certificate it signed is taken, whatever its key or names, so a certificate made again keeps
     * working; a device's certificate from the same CA (clientAuth), or another CA's server, is not. The certificates
     * below are made the way journal-server setup makes them (EC keys here), valid until 2125.
     */
    @Test
    fun theKeptCaTakesItsServerCertificates() {
        fun cert(pem: String) = x509(decodePemCertificates(pem).single())
        val ca = cert(SERVER_CA)
        val trust = KeptServerTrustManager(listOf(ca), fingerprint = null)
        trust.checkServerTrusted(arrayOf(cert(SERVER), ca), "ECDHE_ECDSA")
        trust.checkServerTrusted(arrayOf(cert(SERVER_MADE_AGAIN)), "ECDHE_ECDSA")
        assertFailsWith<CertificateException> { trust.checkServerTrusted(arrayOf(cert(DEVICE_OF_SERVER_CA)), "ECDHE_ECDSA") }
        assertFailsWith<CertificateException> {
            trust.checkServerTrusted(arrayOf(cert(SERVER_OF_OTHER_CA), cert(OTHER_CA)), "ECDHE_ECDSA")
        }

        // The pinned key still works beside the CA: a server whose certificate is from elsewhere but has that key.
        val stranger = cert(SERVER_OF_OTHER_CA)
        val both = KeptServerTrustManager(listOf(ca), ServerPin.fingerprintOfSpki(stranger.publicKey.encoded))
        both.checkServerTrusted(arrayOf(stranger), "ECDHE_ECDSA")
        both.checkServerTrusted(arrayOf(cert(SERVER)), "ECDHE_ECDSA")
    }
}

private const val SERVER_CA = """
-----BEGIN CERTIFICATE-----
MIIBajCCARGgAwIBAgIBAjAKBggqhkjOPQQDAjAcMRowGAYDVQQDExFqb3VybmFs
LXNlcnZlci1jYTAgFw0yNjAxMDEwMDAwMDBaGA8yMTI1MDEwMTAwMDAwMFowHDEa
MBgGA1UEAxMRam91cm5hbC1zZXJ2ZXItY2EwWTATBgcqhkjOPQIBBggqhkjOPQMB
BwNCAAREQ3Fjo4sm9CENq7HsWKJtbV8aR9A+ubBEM26sjkRppIlB9Hx1ovUlvvkT
KbS5kLdwg3CEn77SqQ2FLeJMu+91o0IwQDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0T
AQH/BAUwAwEB/zAdBgNVHQ4EFgQUeu+CcCpQsv73c6opeE1MdkBowb0wCgYIKoZI
zj0EAwIDRwAwRAIgYcO30PeOIY0W+k3jii5E9SHDQULcR07TFoe1MgInkjACIFf7
ydMSN2Oa0wDkYGMv6+PyOnB8YaJ+o9BphX+6iXGE
-----END CERTIFICATE-----
"""

private const val SERVER = """
-----BEGIN CERTIFICATE-----
MIIBdzCCAR2gAwIBAgIBAzAKBggqhkjOPQQDAjAcMRowGAYDVQQDExFqb3VybmFs
LXNlcnZlci1jYTAgFw0yNjAxMDEwMDAwMDBaGA8yMTI1MDEwMTAwMDAwMFowETEP
MA0GA1UEAxMGc2VydmVyMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEEwzVx9Am
KWfxmY4ECX/4Smghdlw5oiAaoKSoHOgl5G5Br9K7RGZgYWCu6GymXWSLUA2SnXVc
ceLTtJwe/xcpfqNZMFcwDgYDVR0PAQH/BAQDAgeAMBMGA1UdJQQMMAoGCCsGAQUF
BwMBMB8GA1UdIwQYMBaAFHrvgnAqULL+93OqKXhNTHZAaMG9MA8GA1UdEQQIMAaH
BMCoMgIwCgYIKoZIzj0EAwIDSAAwRQIgANujJDNMZJPSzPt4M6LnmPnZNa0t6/Ke
5wTnvqH2I4YCIQD46cJF4ZppYAKEXkmtXH82hyPoVHa9ChNvu2l5ZGtb3Q==
-----END CERTIFICATE-----
"""

private const val SERVER_MADE_AGAIN = """
-----BEGIN CERTIFICATE-----
MIIBeDCCAR2gAwIBAgIBBDAKBggqhkjOPQQDAjAcMRowGAYDVQQDExFqb3VybmFs
LXNlcnZlci1jYTAgFw0yNjAxMDEwMDAwMDBaGA8yMTI1MDEwMTAwMDAwMFowETEP
MA0GA1UEAxMGc2VydmVyMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEOAnfZmgo
KMt0lZOzqHDlg8hAXvPp4+6qC9SJBfMgezsba8A82T6sKPXi+A0+l7mzR1w/Y/R6
IyvcvjRG5a7bCqNZMFcwDgYDVR0PAQH/BAQDAgeAMBMGA1UdJQQMMAoGCCsGAQUF
BwMBMB8GA1UdIwQYMBaAFHrvgnAqULL+93OqKXhNTHZAaMG9MA8GA1UdEQQIMAaH
BMCoMgIwCgYIKoZIzj0EAwIDSQAwRgIhAN0p4FuPDMPU41gtFzWFWSaAhwJDl+dA
md0UwGrfvJrIAiEA0CekBGMLE2x3adKmGmodQU20CB0sEpu1SJMuU6nJrNY=
-----END CERTIFICATE-----
"""

private const val DEVICE_OF_SERVER_CA = """
-----BEGIN CERTIFICATE-----
MIIBdjCCAR2gAwIBAgIBBTAKBggqhkjOPQQDAjAcMRowGAYDVQQDExFqb3VybmFs
LXNlcnZlci1jYTAgFw0yNjAxMDEwMDAwMDBaGA8yMTI1MDEwMTAwMDAwMFowETEP
MA0GA1UEAxMGc2VydmVyMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEFkRyBIFP
BlK2EnUHrKclA/ohOJVJAOyambc1C1bfSFKfnT8QxxfJOzMG8JnYTD4h+IsJjZeV
VZ2kWZJS7Ze2gaNZMFcwDgYDVR0PAQH/BAQDAgeAMBMGA1UdJQQMMAoGCCsGAQUF
BwMCMB8GA1UdIwQYMBaAFHrvgnAqULL+93OqKXhNTHZAaMG9MA8GA1UdEQQIMAaH
BMCoMgIwCgYIKoZIzj0EAwIDRwAwRAIgB+tzfPCUi98wcO+L4ees/FjmjylH2Y3s
956AfSJ4aFMCICob+n9lmrYWh+h9OokyG+HW59FDkTiLes+piHw3x21N
-----END CERTIFICATE-----
"""

private const val OTHER_CA = """
-----BEGIN CERTIFICATE-----
MIIBazCCARGgAwIBAgIBBjAKBggqhkjOPQQDAjAcMRowGAYDVQQDExFqb3VybmFs
LXNlcnZlci1jYTAgFw0yNjAxMDEwMDAwMDBaGA8yMTI1MDEwMTAwMDAwMFowHDEa
MBgGA1UEAxMRam91cm5hbC1zZXJ2ZXItY2EwWTATBgcqhkjOPQIBBggqhkjOPQMB
BwNCAASAHcmSBHJftiAj2j9YRktvOHF0bkTUsG0gngvNDqYs/0qecdLReoyjcef0
97SNw9HHFJF6UUTky/tC24/MRrGPo0IwQDAOBgNVHQ8BAf8EBAMCAQYwDwYDVR0T
AQH/BAUwAwEB/zAdBgNVHQ4EFgQUAkIgYargbT8v4b5/RnZJG6bA6PIwCgYIKoZI
zj0EAwIDSAAwRQIgXmKD15clvUsER9NzvD+agTXtco7XxMLjR1aqB4b1ljwCIQCO
cMTwg0V+k6EwNEEWu1K5YffRKFQRFeY3UVBf8vsphQ==
-----END CERTIFICATE-----
"""

private const val SERVER_OF_OTHER_CA = """
-----BEGIN CERTIFICATE-----
MIIBdzCCAR2gAwIBAgIBBzAKBggqhkjOPQQDAjAcMRowGAYDVQQDExFqb3VybmFs
LXNlcnZlci1jYTAgFw0yNjAxMDEwMDAwMDBaGA8yMTI1MDEwMTAwMDAwMFowETEP
MA0GA1UEAxMGc2VydmVyMFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEuLne8Tqb
ZLatb0ia/h2RIg22HL51oLk6lgJbWz/qxybJ41pVzu3Gvr9sqQSu3UkcDDlmBi91
h+YqxkbAZMhsN6NZMFcwDgYDVR0PAQH/BAQDAgeAMBMGA1UdJQQMMAoGCCsGAQUF
BwMBMB8GA1UdIwQYMBaAFAJCIGGq4G0/L+G+f0Z2SRumwOjyMA8GA1UdEQQIMAaH
BMCoMgIwCgYIKoZIzj0EAwIDSAAwRQIgELbwq9UU6JhDU2CotINO3Q3yxkCcXJK4
Bn05z0QmgR8CIQDfSTHqRCLoaaRh/slyEZ1g/HfuIxkKW88A98O2ub2LkA==
-----END CERTIFICATE-----
"""

