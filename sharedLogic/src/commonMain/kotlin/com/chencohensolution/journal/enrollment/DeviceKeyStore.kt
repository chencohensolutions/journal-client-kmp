package com.chencohensolution.journal.enrollment

/** Where an enrolled device keeps its private key; reported to the server at enrollment. */
enum class KeyProtection {
    STRONGBOX,
    TEE,
    SECURE_ENCLAVE,
    SOFTWARE,
}

internal interface DeviceKey {
    val protection: KeyProtection

    /** DER SubjectPublicKeyInfo of the EC P-256 public key. */
    val publicKeyInfo: ByteArray

    /** SHA-256/ECDSA signature in DER form. */
    fun sign(data: ByteArray): ByteArray
}

/**
 * Non-exportable device keys for mTLS: Android Keystore (StrongBox when available) or the iOS
 * Keychain (Secure Enclave when available). The [alias] is also the identity alias the mTLS
 * client factory looks up. Calls may block.
 */
internal interface DeviceKeyStore {
    /** Creates a new EC P-256 key under [alias], replacing any key stored there. */
    fun generate(alias: String): DeviceKey

    /** Stores the certificate issued for the key under [alias], making it a usable identity. */
    fun saveCertificate(alias: String, certificateDer: ByteArray)

    fun delete(alias: String)

    /** When the certificate saved under [alias] expires (epoch milliseconds); null when it can't tell. */
    fun certificateNotAfter(alias: String): Long? = null
}
