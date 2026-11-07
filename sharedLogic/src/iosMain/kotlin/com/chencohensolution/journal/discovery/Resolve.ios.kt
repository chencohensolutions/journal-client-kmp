@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.chencohensolution.journal.discovery

import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.value
import platform.posix.AF_INET
import platform.posix.SOCK_DGRAM
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.sockaddr_in

/** getaddrinfo, IPv4 only. */
internal actual fun resolveIpv4(host: String): String? = memScoped {
    val hints = alloc<addrinfo>().apply {
        ai_family = AF_INET
        ai_socktype = SOCK_DGRAM
    }
    val result = allocPointerTo<addrinfo>()
    if (getaddrinfo(host, null, hints.ptr, result.ptr) != 0) return null
    try {
        val address = result.value?.pointed?.ai_addr?.reinterpret<sockaddr_in>()?.pointed ?: return null
        // s_addr is in network order: its first byte in memory is the first number.
        val n = address.sin_addr.s_addr.toLong()
        (0 until 4).joinToString(".") { ((n shr (8 * it)) and 0xff).toString() }
    } finally {
        freeaddrinfo(result.value)
    }
}
