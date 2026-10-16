package com.chencohensolution.journal.tls

import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate

internal fun parseX509Certificate(der: ByteArray): X509Certificate =
    CertificateFactory.getInstance("X.509").generateCertificate(der.inputStream()) as X509Certificate

internal actual fun certificateCommonName(der: ByteArray): String {
    val subject = parseX509Certificate(der).subjectX500Principal.name
    return if ("CN=" in subject) subject.substringAfter("CN=").substringBefore(',') else subject
}
