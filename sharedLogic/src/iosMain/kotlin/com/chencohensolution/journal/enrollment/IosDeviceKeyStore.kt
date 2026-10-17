@file:OptIn(ExperimentalForeignApi::class)

package com.chencohensolution.journal.enrollment

import com.chencohensolution.journal.tls.secCertificateObject
import com.chencohensolution.journal.tls.toByteArray
import com.chencohensolution.journal.tls.toNSData
import com.chencohensolution.journal.tls.withCFBridge
import com.chencohensolution.journal.tls.withCFDictionary
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFErrorCopyDescription
import platform.CoreFoundation.CFErrorRefVar
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Foundation.NSNumber
import platform.Security.SecAccessControlCreateWithFlags
import platform.Security.SecItemAdd
import platform.Security.SecItemDelete
import platform.Security.SecKeyCopyExternalRepresentation
import platform.Security.SecKeyCopyPublicKey
import platform.Security.SecKeyCreateRandomKey
import platform.Security.SecKeyCreateSignature
import platform.Security.errSecSuccess
import platform.Security.kSecAccessControlPrivateKeyUsage
import platform.Security.kSecAttrAccessControl
import platform.Security.kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
import platform.Security.kSecAttrApplicationTag
import platform.Security.kSecAttrIsPermanent
import platform.Security.kSecAttrKeySizeInBits
import platform.Security.kSecAttrKeyType
import platform.Security.kSecAttrKeyTypeECSECPrimeRandom
import platform.Security.kSecAttrLabel
import platform.Security.kSecAttrTokenID
import platform.Security.kSecAttrTokenIDSecureEnclave
import platform.Security.kSecClass
import platform.Security.kSecClassCertificate
import platform.Security.kSecClassKey
import platform.Security.kSecKeyAlgorithmECDSASignatureMessageX962SHA256
import platform.Security.kSecPrivateKeyAttrs
import platform.Security.kSecValueRef

/**
 * Keys in the app Keychain. Real devices create them in the Secure Enclave; the Simulator has no
 * Secure Enclave, so it falls back to an ordinary (software) Keychain key.
 *
 * Both the key and the certificate carry the alias as their label, and the Keychain pairs them into
 * the identity that KeychainIdentities.find(alias) returns.
 */
internal class IosDeviceKeyStore : DeviceKeyStore {

    override fun generate(alias: String): DeviceKey {
        delete(alias)
        val secureEnclaveKey = runCatching { createKey(alias, secureEnclave = true) }
        val key = secureEnclaveKey.getOrNull()
            ?: createKey(alias, secureEnclave = false)
        val protection = if (secureEnclaveKey.isSuccess) KeyProtection.SECURE_ENCLAVE else KeyProtection.SOFTWARE
        return IosDeviceKey(key, protection)
    }

    override fun saveCertificate(alias: String, certificateDer: ByteArray) {
        val certificate = secCertificateObject(certificateDer)
        withCFBridge(alias) { label ->
            withCFDictionary(kSecClass to kSecClassCertificate, kSecAttrLabel to label) { SecItemDelete(it) }
            withCFBridge(certificate) { certificateRef ->
                val status = withCFDictionary(
                    kSecClass to kSecClassCertificate,
                    kSecValueRef to certificateRef,
                    kSecAttrLabel to label,
                ) { SecItemAdd(it, null) }
                check(status == errSecSuccess) { "Could not save the certificate to the Keychain (OSStatus $status)" }
            }
        }
    }

    override fun delete(alias: String) {
        withCFBridge(applicationTag(alias)) { tag ->
            withCFDictionary(kSecClass to kSecClassKey, kSecAttrApplicationTag to tag) { SecItemDelete(it) }
        }
        withCFBridge(alias) { label ->
            withCFDictionary(kSecClass to kSecClassCertificate, kSecAttrLabel to label) { SecItemDelete(it) }
        }
    }

    /** Returns the new private key as a GC-managed object. */
    private fun createKey(alias: String, secureEnclave: Boolean): Any = memScoped {
        val error = alloc<CFErrorRefVar>()
        val flags = if (secureEnclave) kSecAccessControlPrivateKeyUsage else 0uL
        val access = SecAccessControlCreateWithFlags(null, kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly, flags, error.ptr)
            ?: throwCFError(error.value, "Could not create the key's access control")
        try {
            withCFBridge(alias) { label ->
                withCFBridge(applicationTag(alias)) { tag ->
                    withCFBridge(NSNumber(int = 256)) { keySize ->
                        withCFDictionary(
                            kSecAttrIsPermanent to kCFBooleanTrue,
                            kSecAttrLabel to label,
                            kSecAttrApplicationTag to tag,
                            kSecAttrAccessControl to access,
                        ) { privateKeyAttributes ->
                            val attributes = mutableListOf<Pair<COpaquePointer?, COpaquePointer?>>(
                                kSecAttrKeyType to kSecAttrKeyTypeECSECPrimeRandom,
                                kSecAttrKeySizeInBits to keySize,
                                kSecPrivateKeyAttrs to privateKeyAttributes,
                            )
                            if (secureEnclave) attributes += kSecAttrTokenID to kSecAttrTokenIDSecureEnclave
                            val key = withCFDictionary(*attributes.toTypedArray()) { SecKeyCreateRandomKey(it, error.ptr) }
                                ?: throwCFError(error.value, "Could not create the device key")
                            CFBridgingRelease(key)!!
                        }
                    }
                }
            }
        } finally {
            CFRelease(access)
        }
    }

    private fun applicationTag(alias: String): NSData =
        "com.chencohensolution.journal.device.$alias".encodeToByteArray().toNSData()
}

private class IosDeviceKey(private val privateKey: Any, override val protection: KeyProtection) : DeviceKey {

    override val publicKeyInfo: ByteArray
        get() = memScoped {
            val error = alloc<CFErrorRefVar>()
            withCFBridge(privateKey) { keyRef ->
                val publicKey = SecKeyCopyPublicKey(keyRef?.reinterpret()) ?: error("The device key has no public key")
                try {
                    // X9.63 uncompressed point: 04 || X || Y
                    val point = SecKeyCopyExternalRepresentation(publicKey, error.ptr)
                        ?: throwCFError(error.value, "Could not export the public key")
                    CertificateRequest.ecP256PublicKeyInfo((CFBridgingRelease(point) as NSData).toByteArray())
                } finally {
                    CFRelease(publicKey)
                }
            }
        }

    override fun sign(data: ByteArray): ByteArray = memScoped {
        val error = alloc<CFErrorRefVar>()
        withCFBridge(privateKey) { keyRef ->
            withCFBridge(data.toNSData()) { dataRef ->
                val signature = SecKeyCreateSignature(
                    keyRef?.reinterpret(),
                    kSecKeyAlgorithmECDSASignatureMessageX962SHA256,
                    dataRef?.reinterpret(),
                    error.ptr,
                ) ?: throwCFError(error.value, "Could not sign with the device key")
                (CFBridgingRelease(signature) as NSData).toByteArray()
            }
        }
    }
}

private fun throwCFError(error: platform.CoreFoundation.CFErrorRef?, message: String): Nothing {
    val detail = error?.let {
        val description = CFErrorCopyDescription(it)
        CFRelease(it)
        CFBridgingRelease(description) as String?
    }
    throw IllegalStateException(if (detail != null) "$message: $detail" else message)
}
