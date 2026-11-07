package com.chencohensolution.journal.discovery

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import okio.ByteString.Companion.decodeHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** The lookup walk over a made-up DHT: bootstrap, nodes nearer the target, records old and new, nodes that don't answer. */
@OptIn(ExperimentalTime::class)
class DhtClientTest {
    private val key = DhtFixtures.KEY.decodeHex().toByteArray()
    private val target = DhtRecord.target(DhtFixtures.KEY)

    /** An id [distance] away from the target in its last byte (0: the target itself). */
    private fun idNear(distance: Int, firstByteFlip: Int = 0) = target.copyOf().also {
        it[0] = (it[0].toInt() xor firstByteFlip).toByte()
        it[19] = (it[19].toInt() xor distance).toByte()
    }

    private class FakeNode(val host: String, val port: Int, val id: ByteArray)

    private fun compact(vararg nodes: FakeNode): ByteArray = nodes.fold(ByteArray(0)) { acc, n ->
        acc + n.id + n.host.split(".").map { it.toInt().toByte() }.toByteArray() +
            byteArrayOf((n.port shr 8).toByte(), n.port.toByte())
    }

    private fun record(addresses: List<String>, at: String): Map<String, Any> = mapOf(
        "k" to key,
        "seq" to 1L,
        "sig" to ByteArray(64),
        "v" to DhtRecord.seal(ServerRecord(addresses, Instant.parse(at).toEpochMilliseconds()), DhtFixtures.FINGERPRINT, ByteArray(24) { 7 }),
    )

    /** UDP to [answers] ("host:port" → the `r` of its reply, or null for no answer); "bad.example" fails to send. */
    private class FakeNetwork(val answers: Map<String, (Map<*, *>) -> Map<String, Any>?>) : DhtTransport {
        val asked = mutableListOf<String>()
        private val inbox = Channel<DhtPacket>(Channel.UNLIMITED)

        override suspend fun send(packet: DhtPacket) {
            if (packet.host == "bad.example") error("unknown host")
            val where = "${packet.host}:${packet.port}"
            asked += where
            val query = Bencode.decode(packet.data) as Map<*, *>
            check((query["q"] as ByteArray).decodeToString() == "get")
            val r = answers[where]?.invoke(query["a"] as Map<*, *>) ?: return
            inbox.send(DhtPacket(packet.host, packet.port, Bencode.encode(mapOf("t" to query["t"]!!, "y" to "r", "r" to r))))
        }

        override suspend fun receive(): DhtPacket = inbox.receive()
        override fun close() = Unit
    }

    private fun client(network: FakeNetwork, bootstrap: List<Pair<String, Int>>) = DhtClient(
        transport = { network },
        bootstrap = bootstrap,
        timeLimit = 5.seconds,
        queryTimeout = 300.milliseconds,
    )

    @Test
    fun walksTowardTheTargetAndKeepsTheNewestRecord() = runTest {
        val far = FakeNode("10.0.0.1", 6881, idNear(0, firstByteFlip = 0x80))
        val near = FakeNode("10.0.0.2", 6881, idNear(0x40))
        val nearer = FakeNode("10.0.0.3", 6881, idNear(0x02))
        val silent = FakeNode("10.0.0.4", 6881, idNear(0x01))
        val old = listOf("https://203.0.113.1:56398")
        val new = listOf("https://203.0.113.9:56398", "https://192.168.1.20:56398")
        val network = FakeNetwork(
            mapOf(
                "boot.example:6881" to { a ->
                    assertTrue(target.contentEquals(a["target"] as ByteArray))
                    mapOf("id" to idNear(0x7f, 0x01), "nodes" to compact(far, near))
                },
                "10.0.0.1:6881" to { _ -> mapOf("id" to far.id) },
                "10.0.0.2:6881" to { _ -> mapOf("id" to near.id, "nodes" to compact(nearer, silent)) + record(old, "2026-11-07T10:00:00Z") },
                "10.0.0.3:6881" to { _ -> mapOf("id" to nearer.id) + record(new, "2026-11-07T19:00:00Z") },
            ),
        )
        val found = withContext(Dispatchers.Default) {
            client(network, listOf("bad.example" to 6881, "boot.example" to 6881)).lookup(DhtFixtures.KEY, DhtFixtures.FINGERPRINT)
        }
        assertEquals(new, found?.addresses)
        assertTrue("10.0.0.4:6881" in network.asked)
    }

    @Test
    fun theServersOwnReplyIsRead() = runTest {
        val network = FakeNetwork(
            mapOf(
                "boot.example:6881" to { _ ->
                    (Bencode.decode(DhtFixtures.REPLY.decodeHex().toByteArray()) as Map<*, *>)["r"]
                        .let { @Suppress("UNCHECKED_CAST") (it as Map<String, Any>) }
                },
            ),
        )
        val found = withContext(Dispatchers.Default) {
            client(network, listOf("boot.example" to 6881)).lookup(DhtFixtures.KEY, DhtFixtures.FINGERPRINT)
        }
        assertEquals(DhtFixtures.ADDRESSES, found?.addresses)
    }

    @Test
    fun recordsThatDontOpenOrUnderAnotherKeyAreLeftOut() = runTest {
        val other = record(listOf("https://203.0.113.66:1"), "2026-11-08T00:00:00Z") + ("k" to ByteArray(32))
        val forged = record(listOf("https://203.0.113.66:2"), "2026-11-08T00:00:00Z") + ("v" to ByteArray(80))
        val network = FakeNetwork(
            mapOf(
                "boot.example:6881" to { _ -> mapOf("id" to idNear(9), "nodes" to compact(FakeNode("10.0.0.5", 1, idNear(3)))) + other },
                "10.0.0.5:1" to { _ -> mapOf("id" to idNear(3)) + forged },
            ),
        )
        val found = withContext(Dispatchers.Default) {
            client(network, listOf("boot.example" to 6881)).lookup(DhtFixtures.KEY, DhtFixtures.FINGERPRINT)
        }
        assertNull(found)
    }

    @Test
    fun noAnswerAtAllEndsQuickly() = runTest {
        val network = FakeNetwork(emptyMap())
        val found = withContext(Dispatchers.Default) {
            client(network, listOf("boot.example" to 6881, "bad.example" to 1)).lookup(DhtFixtures.KEY, DhtFixtures.FINGERPRINT)
        }
        assertNull(found)
        assertEquals(listOf("boot.example:6881"), network.asked)
    }
}
