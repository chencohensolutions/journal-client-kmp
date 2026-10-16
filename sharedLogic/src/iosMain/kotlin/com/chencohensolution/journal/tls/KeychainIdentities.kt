@file:OptIn(ExperimentalForeignApi::class)

package com.chencohensolution.journal.tls

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayGetCount
import platform.CoreFoundation.CFArrayGetValueAtIndex
import platform.CoreFoundation.CFArrayRefVar
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.Foundation.CFBridgingRelease
import platform.Foundation.NSData
import platform.Security.SecCertificateRefVar
import platform.Security.SecIdentityCopyCertificate
import platform.Security.SecIdentityRef
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecPKCS12Import
import platform.Security.errSecAuthFailed
import platform.Security.errSecSuccess
import platform.Security.kSecAttrLabel
import platform.Security.kSecClass
import platform.Security.kSecClassIdentity
import platform.Security.kSecImportExportPassphrase
import platform.Security.kSecImportItemIdentity
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnRef
import platform.Security.kSecValueRef

sealed interface IdentityImportResult {
    /** [label] is the certificate's subject (e.g. its CN) and is what gets stored as the selected identity. */
    data class Success(val label: String) : IdentityImportResult
    data class Failure(val message: String) : IdentityImportResult
}

/**
 * iOS has no system-wide picker for client certificates, so the user imports a PKCS#12 bundle
 * (`<user>.p12` from the server's generate-user-cert script) into the app's own Keychain.
 */
internal object KeychainIdentities {

    fun importPkcs12(data: NSData, password: String): IdentityImportResult = memScoped {
        val items = alloc<CFArrayRefVar>()
        val status = withCFBridge(data) { pkcs12 ->
            withCFBridge(password) { passphrase ->
                withCFDictionary(kSecImportExportPassphrase to passphrase) { options ->
                    SecPKCS12Import(pkcs12?.reinterpret(), options, items.ptr)
                }
            }
        }
        if (status == errSecAuthFailed) return IdentityImportResult.Failure("Wrong password for the certificate bundle")
        if (status != errSecSuccess) return IdentityImportResult.Failure("Could not read the certificate bundle (OSStatus $status)")

        val array = items.value
        try {
            if (array == null || CFArrayGetCount(array) < 1) {
                return IdentityImportResult.Failure("The certificate bundle contains no identity")
            }
            val firstItem = CFArrayGetValueAtIndex(array, 0)
            val identity: SecIdentityRef = CFDictionaryGetValue(firstItem?.reinterpret(), kSecImportItemIdentity)
                ?.reinterpret()
                ?: return IdentityImportResult.Failure("The certificate bundle contains no private key")

            val label = identityLabel(identity)
                ?: return IdentityImportResult.Failure("Could not read the certificate subject")
            store(identity, label)
        } finally {
            if (array != null) CFRelease(array)
        }
    }

    /** Returns the stored identity as a GC-managed object, or null when none is stored under [label]. */
    fun find(label: String): Any? = memScoped {
        val result = alloc<CFTypeRefVar>()
        val status = withCFBridge(label) { labelRef ->
            withCFDictionary(
                kSecClass to kSecClassIdentity,
                kSecAttrLabel to labelRef,
                kSecReturnRef to kCFBooleanTrue,
                kSecMatchLimit to kSecMatchLimitOne,
            ) { query -> SecItemCopyMatching(query, result.ptr) }
        }
        if (status == errSecSuccess) CFBridgingRelease(result.value) else null
    }

    private fun store(identity: SecIdentityRef, label: String): IdentityImportResult = withCFBridge(label) { labelRef ->
        // Replace an earlier import of the same certificate subject.
        withCFDictionary(kSecClass to kSecClassIdentity, kSecAttrLabel to labelRef) { SecItemDelete(it) }
        val status = withCFDictionary(kSecValueRef to identity, kSecAttrLabel to labelRef) { SecItemAdd(it, null) }
        if (status == errSecSuccess) {
            IdentityImportResult.Success(label)
        } else {
            IdentityImportResult.Failure("Could not save the identity to the Keychain (OSStatus $status)")
        }
    }

    private fun identityLabel(identity: SecIdentityRef): String? = memScoped {
        val certificate = alloc<SecCertificateRefVar>()
        if (SecIdentityCopyCertificate(identity, certificate.ptr) != errSecSuccess) return null
        try {
            subjectSummary(certificate.value)
        } finally {
            CFRelease(certificate.value)
        }
    }
}
