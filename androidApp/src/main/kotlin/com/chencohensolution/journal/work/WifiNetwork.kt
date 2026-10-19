package com.chencohensolution.journal.work

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * The name of the Wi-Fi network the device is on, or null when it isn't on Wi-Fi or the name can't be read. Android
 * gives it only to apps with location permission (in the background: "Allow all the time").
 */
suspend fun currentWifiName(context: Context): String? {
    if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null
    val connectivity = context.getSystemService(ConnectivityManager::class.java)
    var callback: ConnectivityManager.NetworkCallback? = null
    return try {
        withTimeoutOrNull(3_000) {
            suspendCancellableCoroutine { continuation ->
                callback = object : ConnectivityManager.NetworkCallback(FLAG_INCLUDE_LOCATION_INFO) {
                    override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                        val name = (capabilities.transportInfo as? WifiInfo)?.ssid?.removeSurrounding("\"")
                            ?.takeIf { it.isNotEmpty() && it != WifiManager.UNKNOWN_SSID } ?: return
                        if (continuation.isActive) continuation.resume(name)
                    }
                }.also {
                    connectivity.registerNetworkCallback(NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(), it)
                }
            }
        }
    } finally {
        callback?.let(connectivity::unregisterNetworkCallback)
    }
}
