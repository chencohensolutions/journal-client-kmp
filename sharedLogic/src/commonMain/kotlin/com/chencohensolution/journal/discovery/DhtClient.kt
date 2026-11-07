package com.chencohensolution.journal.discovery

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.BoundDatagramSocket
import io.ktor.network.sockets.Datagram
import io.ktor.network.sockets.InetSocketAddress
import io.ktor.network.sockets.aSocket
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.readByteArray
import okio.ByteString.Companion.decodeHex
import kotlin.random.Random
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/** A UDP packet to or from a DHT node. */
internal class DhtPacket(val host: String, val port: Int, val data: ByteArray)

/** Sends and receives UDP packets for [DhtClient]: ktor-network on the phones ([KtorDhtTransport]), a fake in tests. */
internal interface DhtTransport {
    suspend fun send(packet: DhtPacket)
    suspend fun receive(): DhtPacket
    fun close()
}

/**
 * Looks a server up in the BitTorrent DHT (BEP 5 KRPC over UDP, BEP 44 `get`): walks from the bootstrap nodes toward
 * SHA-1(key + salt), up to [parallel] queries in flight, and opens every record found with the server's fingerprint,
 * keeping the one sealed last. Stops after [timeLimit], or once the [closest] nodes nearest the target have answered.
 */
internal class DhtClient(
    private val transport: suspend () -> DhtTransport = { KtorDhtTransport.open() },
    private val bootstrap: List<Pair<String, Int>> = BOOTSTRAP,
    private val timeLimit: Duration = 15.seconds,
    private val queryTimeout: Duration = 2.seconds,
    private val parallel: Int = 8,
    private val closest: Int = 8,
    private val random: Random = Random.Default,
) {
    private enum class Asked { NOT_YET, WAITING, ANSWERED, FAILED }

    private class Node(val host: String, val port: Int, var id: ByteArray?) {
        var asked = Asked.NOT_YET
        var deadline: TimeSource.Monotonic.ValueTimeMark? = null
    }

    /** The newest record of the server with DHT key [keyHex] that opens with [fingerprintHex]; null when none was found. */
    suspend fun lookup(keyHex: String, fingerprintHex: String): ServerRecord? {
        val key = keyHex.decodeHex().toByteArray()
        val target = DhtRecord.target(keyHex)
        val ownId = random.nextBytes(20)
        val nodes = LinkedHashMap<String, Node>()
        bootstrap.forEach { (host, port) -> nodes["$host:$port"] = Node(host, port, id = null) }
        val waiting = HashMap<String, Node>() // by transaction id (hex)
        var best: ServerRecord? = null
        val started = TimeSource.Monotonic.markNow()

        fun distanceOrder(a: Node, b: Node): Int {
            val x = a.id; val y = b.id
            if (x == null || y == null) return if (x == null && y == null) 0 else if (x == null) -1 else 1 // bootstrap first
            for (i in 0 until 20) {
                val dx = (x[i].toInt() xor target[i].toInt()) and 0xff
                val dy = (y[i].toInt() xor target[i].toInt()) and 0xff
                if (dx != dy) return dx - dy
            }
            return 0
        }

        fun done(): Boolean {
            val live = nodes.values.filter { it.asked != Asked.FAILED }.sortedWith(::distanceOrder)
            if (live.none { it.asked == Asked.NOT_YET || it.asked == Asked.WAITING }) return true
            val nearest = live.filter { it.id != null }.take(closest)
            return nearest.size >= closest && nearest.all { it.asked == Asked.ANSWERED }
        }

        val socket = transport()
        try {
            while (started.elapsedNow() < timeLimit && !done()) {
                // Queries that took too long have failed.
                waiting.entries.removeAll { (_, node) ->
                    (node.deadline!!.hasPassedNow()).also { if (it) node.asked = Asked.FAILED }
                }
                // Ask the nearest not asked yet, up to [parallel] at once.
                val next = nodes.values.filter { it.asked == Asked.NOT_YET }.sortedWith(::distanceOrder)
                    .take((parallel - waiting.size).coerceAtLeast(0))
                for (node in next) {
                    val tid = random.nextBytes(2)
                    val query = Bencode.encode(
                        mapOf("t" to tid, "y" to "q", "q" to "get", "a" to mapOf("id" to ownId, "target" to target)),
                    )
                    node.asked = Asked.WAITING
                    node.deadline = TimeSource.Monotonic.markNow() + queryTimeout
                    try {
                        socket.send(DhtPacket(node.host, node.port, query))
                        waiting[tid.toHexString()] = node
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        node.asked = Asked.FAILED // a bootstrap name that doesn't resolve, no network
                    }
                }
                if (waiting.isEmpty()) continue
                val untilDeadline = waiting.values.minOf { -it.deadline!!.elapsedNow() }
                val untilEnd = timeLimit - started.elapsedNow()
                val packet = withTimeoutOrNull(minOf(untilDeadline, untilEnd).coerceAtLeast(Duration.ZERO)) {
                    try {
                        socket.receive()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        null
                    }
                } ?: continue
                val reply = runCatching { Bencode.decode(packet.data) as? Map<*, *> }.getOrNull() ?: continue
                val tid = (reply["t"] as? ByteArray)?.toHexString() ?: continue
                val node = waiting.remove(tid) ?: continue
                if ((reply["y"] as? ByteArray)?.decodeToString() != "r") {
                    node.asked = Asked.FAILED
                    continue
                }
                val r = reply["r"] as? Map<*, *> ?: run { node.asked = Asked.FAILED; continue }
                node.asked = Asked.ANSWERED
                (r["id"] as? ByteArray)?.takeIf { it.size == 20 }?.let { node.id = it }
                (r["nodes"] as? ByteArray)?.let { compact ->
                    for (i in 0 until compact.size / 26) {
                        val at = i * 26
                        val id = compact.copyOfRange(at, at + 20)
                        val host = (0 until 4).joinToString(".") { (compact[at + 20 + it].toInt() and 0xff).toString() }
                        val port = ((compact[at + 24].toInt() and 0xff) shl 8) or (compact[at + 25].toInt() and 0xff)
                        if (port == 0) continue
                        nodes.getOrPut("$host:$port") { Node(host, port, id) }
                    }
                }
                recordIn(r, key, fingerprintHex)?.let { found ->
                    // The newest by its sealed time: seq isn't checked without the signature.
                    if (found.at > (best?.at ?: Long.MIN_VALUE)) best = found
                }
            }
        } finally {
            socket.close()
        }
        return best
    }

    private fun recordIn(r: Map<*, *>, key: ByteArray, fingerprintHex: String): ServerRecord? {
        val value = r["v"] as? ByteArray ?: return null
        val k = r["k"] as? ByteArray ?: return null
        if (r["seq"] !is Long || r["sig"] !is ByteArray || !k.contentEquals(key)) return null
        return DhtRecord.open(value, fingerprintHex)
    }

    companion object {
        val BOOTSTRAP = listOf(
            "router.bittorrent.com" to 6881,
            "dht.transmissionbt.com" to 6881,
            "router.utorrent.com" to 6881,
            "dht.libtorrent.org" to 25401,
        )
    }
}

private fun ByteArray.toHexString(): String = joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }

/** UDP with ktor-network, on Android and iOS. */
internal class KtorDhtTransport private constructor(
    private val selector: SelectorManager,
    private val socket: BoundDatagramSocket,
) : DhtTransport {
    override suspend fun send(packet: DhtPacket) {
        socket.send(Datagram(Buffer().apply { write(packet.data) }, InetSocketAddress(packet.host, packet.port)))
    }

    override suspend fun receive(): DhtPacket {
        val datagram = socket.receive()
        val from = datagram.address as? InetSocketAddress
        return DhtPacket(from?.hostname ?: "", from?.port ?: 0, datagram.packet.readByteArray())
    }

    override fun close() {
        socket.close()
        selector.close()
    }

    companion object {
        suspend fun open(): KtorDhtTransport {
            val selector = SelectorManager(Dispatchers.Default)
            return try {
                KtorDhtTransport(selector, aSocket(selector).udp().bind())
            } catch (e: Throwable) {
                selector.close()
                throw e
            }
        }
    }
}
