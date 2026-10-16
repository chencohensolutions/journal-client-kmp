@file:OptIn(ExperimentalForeignApi::class)

package com.chencohensolution.journal.tls

import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Security.SecCertificateCopySubjectSummary
import platform.Security.SecCertificateCreateWithData
import platform.Security.SecCertificateRef

/** Runs [block] with a CF dictionary built from [entries], releasing it afterwards. */
internal inline fun <T> withCFDictionary(
    vararg entries: Pair<COpaquePointer?, COpaquePointer?>,
    block: (CFMutableDictionaryRef?) -> T,
): T {
    val dictionary = CFDictionaryCreateMutable(
        null,
        entries.size.convert(),
        kCFTypeDictionaryKeyCallBacks.ptr,
        kCFTypeDictionaryValueCallBacks.ptr,
    )
    try {
        entries.forEach { (key, value) -> CFDictionaryAddValue(dictionary, key, value) }
        return block(dictionary)
    } finally {
        CFRelease(dictionary)
    }
}

/** Runs [block] with a retained CF bridge of a Foundation-compatible object (String, NSData, ...). */
internal inline fun <T> withCFBridge(value: Any, block: (COpaquePointer?) -> T): T {
    val ref = CFBridgingRetain(value)
    try {
        return block(ref)
    } finally {
        CFRelease(ref)
    }
}

/** Creates a SecCertificate from DER bytes as a GC-managed object (bridge back with [CFBridgingRetain]). */
internal fun secCertificateObject(der: ByteArray): Any {
    val data = der.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), der.size.convert()) }
    try {
        val certificate = SecCertificateCreateWithData(null, data)
            ?: error("Invalid DER certificate")
        return CFBridgingRelease(certificate)!!
    } finally {
        CFRelease(data)
    }
}

internal fun subjectSummary(certificate: SecCertificateRef?): String? {
    val summary: CFStringRef = SecCertificateCopySubjectSummary(certificate) ?: return null
    return CFBridgingRelease(summary) as String?
}

internal actual fun certificateCommonName(der: ByteArray): String {
    val certificate = secCertificateObject(der)
    return withCFBridge(certificate) { subjectSummary(it?.reinterpret()) } ?: "Unknown CA"
}
