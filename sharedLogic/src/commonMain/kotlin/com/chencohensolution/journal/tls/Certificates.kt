package com.chencohensolution.journal.tls

import kotlin.io.encoding.Base64

private val PEM_CERTIFICATE = Regex("-----BEGIN CERTIFICATE-----([^-]+)-----END CERTIFICATE-----")

/** Decodes every certificate in a PEM bundle to DER bytes. */
internal fun decodePemCertificates(pem: String): List<ByteArray> =
    PEM_CERTIFICATE.findAll(pem)
        .map { match -> Base64.decode(match.groupValues[1].filterNot(Char::isWhitespace)) }
        .toList()

/** The certificate's subject common name (or its full subject when it has no CN). */
internal expect fun certificateCommonName(der: ByteArray): String
