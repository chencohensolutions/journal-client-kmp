package com.chencohensolution.journal.enrollment

/**
 * Builds PKCS#10 certificate requests for EC P-256 keys. Shared by both platforms because iOS has
 * no API for it; only the signature comes from the platform key.
 */
internal object CertificateRequest {
    private const val OID_EC_PUBLIC_KEY = "1.2.840.10045.2.1"
    private const val OID_PRIME256V1 = "1.2.840.10045.3.1.7"
    private const val OID_ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"
    private const val OID_COMMON_NAME = "2.5.4.3"

    /** SubjectPublicKeyInfo for an uncompressed P-256 point (`04 || X || Y`, as iOS exports it). */
    fun ecP256PublicKeyInfo(uncompressedPoint: ByteArray): ByteArray {
        require(uncompressedPoint.size == 65 && uncompressedPoint[0] == 0x04.toByte()) {
            "expected an uncompressed P-256 point"
        }
        return Der.sequence(
            Der.sequence(Der.objectIdentifier(OID_EC_PUBLIC_KEY), Der.objectIdentifier(OID_PRIME256V1)),
            Der.bitString(uncompressedPoint),
        )
    }

    /**
     * [signSha256WithEcdsa] signs its input with SHA-256/ECDSA and returns the DER signature.
     * The server ignores [commonName]: the certificate's subject comes from the enrollment code.
     */
    fun build(commonName: String, publicKeyInfo: ByteArray, signSha256WithEcdsa: (ByteArray) -> ByteArray): ByteArray {
        val info = Der.sequence(
            Der.integer(0),
            Der.sequence(Der.set(Der.sequence(Der.objectIdentifier(OID_COMMON_NAME), Der.utf8String(commonName)))),
            publicKeyInfo,
            Der.contextSpecific(0), // no attributes
        )
        return Der.sequence(
            info,
            Der.sequence(Der.objectIdentifier(OID_ECDSA_WITH_SHA256)),
            Der.bitString(signSha256WithEcdsa(info)),
        )
    }
}
