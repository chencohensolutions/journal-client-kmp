@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

package com.chencohensolution.journal.discovery

import kotlinx.cinterop.toKString
import kotlinx.coroutines.runBlocking
import platform.posix.getenv
import kotlin.test.Test
import kotlin.test.assertNotNull

/**
 * [LiveDhtTest] on the iOS simulator (ktor-network on Kotlin/Native), run only by hand:
 *   SIMCTL_CHILD_LIVE_DHT_KEY=<key> SIMCTL_CHILD_LIVE_DHT_FINGERPRINT=<fingerprint> \
 *   SIMCTL_CHILD_LIVE_DHT_BOOTSTRAP=185.157.221.247:25401 ./gradlew :sharedLogic:iosSimulatorArm64Test
 * The test executable has no DNS in the simulator (an app has), so the bootstrap node may be given as an IP and port.
 */
class LiveDhtIosTest {
    @Test
    fun findsAPublishedServer() {
        val key = getenv("LIVE_DHT_KEY")?.toKString() ?: return
        val fingerprint = getenv("LIVE_DHT_FINGERPRINT")?.toKString() ?: return
        val bootstrap = getenv("LIVE_DHT_BOOTSTRAP")?.toKString()?.split(":")?.let { listOf(it[0] to it[1].toInt()) }
            ?: DhtClient.BOOTSTRAP
        val record = runBlocking { DhtClient(bootstrap = bootstrap, trace = ::println).lookup(key, fingerprint) }
        println("Live DHT on iOS: ${record?.addresses}")
        assertNotNull(record)
    }
}
