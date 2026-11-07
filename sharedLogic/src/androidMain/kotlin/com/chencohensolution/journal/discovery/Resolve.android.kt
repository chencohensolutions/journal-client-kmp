package com.chencohensolution.journal.discovery

import java.net.Inet4Address
import java.net.InetAddress

internal actual fun resolveIpv4(host: String): String? =
    runCatching { InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }?.hostAddress }.getOrNull()
