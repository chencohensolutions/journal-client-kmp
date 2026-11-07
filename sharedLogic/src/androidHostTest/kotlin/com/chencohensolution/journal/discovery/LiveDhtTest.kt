package com.chencohensolution.journal.discovery

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A lookup in the real BitTorrent DHT, run only by hand: publish a server's record (`journal-server discovery`, or the
 * Go `discovery.Node.Publish`), then
 *   LIVE_DHT_KEY=<key> LIVE_DHT_FINGERPRINT=<fingerprint> ./gradlew :sharedLogic:testAndroidHostTest --tests '*LiveDhtTest*'
 * Without the two variables it passes without looking anything up.
 */
class LiveDhtTest {
    @Test
    fun findsAPublishedServer() {
        val key = System.getenv("LIVE_DHT_KEY") ?: return
        val fingerprint = System.getenv("LIVE_DHT_FINGERPRINT") ?: return
        val started = System.nanoTime()
        val record = runBlocking { DhtClient(trace = ::println).lookup(key, fingerprint) }
        println("Live DHT: ${record?.addresses} sealed at ${record?.at} in ${(System.nanoTime() - started) / 1_000_000} ms")
        assertNotNull(record)
        assertTrue(record.addresses.isNotEmpty())
    }
}
