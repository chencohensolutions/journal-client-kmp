package com.chencohensolution.journal.discovery

import com.chencohensolution.journal.network.graphQlEndpoint
import com.chencohensolution.journal.settings.JournalSettings
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpSend
import io.ktor.client.plugins.plugin
import io.ktor.client.request.get
import io.ktor.http.URLProtocol
import io.ktor.http.Url
import io.ktor.http.content.OutgoingContent
import io.ktor.util.AttributeKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Finds the server again when it stops answering at its address (docs/plans/discovery.md, "Phones" 3): tries each
 * kept address (the next started [stagger] after the one before; the first to answer wins), then looks it up in the
 * DHT, at most every [dhtEvery]. The address that works becomes `serverUrl` and goes first in the kept list. Only
 * once a fingerprint is kept: the mTLS clients then pin the server's key, so an address that answers is the server,
 * whatever it is.
 */
internal class ServerFinder(
    private val settings: JournalSettings,
    private val dht: suspend (key: String, fingerprint: String) -> ServerRecord? = { key, fingerprint ->
        DhtClient().lookup(key, fingerprint)
    },
    private val stagger: Duration = 400.milliseconds,
    private val dhtEvery: Duration = 2.minutes,
    /** After a round of the kept addresses found nothing, how long before the next failure tries them again. */
    private val addressesEvery: Duration = 10.seconds,
    /** What the app's clients share (one server, one finder); its own in tests. */
    private val shared: Shared = Shared.process,
) {
    class Shared {
        internal val mutex = Mutex()
        internal var lastRound: TimeSource.Monotonic.ValueTimeMark? = null
        internal var lastDht: TimeSource.Monotonic.ValueTimeMark? = null

        companion object {
            val process = Shared()
        }
    }

    /**
     * A request to [failed] (`https://host:port`) failed on the network: the origin that answers now (already made
     * `serverUrl`), or null when none does. [probe] says whether the server answers at an origin.
     */
    suspend fun find(failed: String, probe: suspend (origin: String) -> Boolean): String? = shared.mutex.withLock {
        val kept = settings.serverDiscovery ?: return null
        // Another request found it while this one waited.
        KeptDiscovery.originOf(settings.serverUrl)?.takeIf { it != failed }?.let { return it }
        if (shared.lastRound?.let { it.elapsedNow() < addressesEvery } == true) return null
        shared.lastRound = TimeSource.Monotonic.markNow()

        race(kept.addresses.filter { it != failed }, probe)?.let { return use(it) }

        val key = kept.key ?: return null
        if (shared.lastDht?.let { it.elapsedNow() < dhtEvery } == true) return null
        shared.lastDht = TimeSource.Monotonic.markNow()
        val record = try {
            dht(key, kept.fingerprint)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        } ?: return null
        settings.serverDiscovery = kept.withFound(record.addresses)
        race(record.addresses.mapNotNull(KeptDiscovery::originOf).distinct().filter { it != failed }, probe)?.let { use(it) }
    }

    private fun use(origin: String): String {
        settings.serverUrl = graphQlEndpoint(origin)
        settings.serverDiscovery?.let { settings.serverDiscovery = it.working(origin) }
        return origin
    }

    /** The first of [origins] to answer, each started [stagger] after the one before; null when none does. */
    private suspend fun race(origins: List<String>, probe: suspend (String) -> Boolean): String? {
        if (origins.isEmpty()) return null
        return coroutineScope {
            val winner = CompletableDeferred<String?>()
            val tries = origins.mapIndexed { i, origin ->
                launch {
                    delay(stagger * i)
                    val answered = try {
                        // On its own job: a failed request may fail the job it runs in, which mustn't stop the others.
                        supervisorScope { async { probe(origin) }.await() }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        false
                    }
                    if (answered) winner.complete(origin)
                }
            }
            launch {
                tries.joinAll()
                winner.complete(null)
            }
            winner.await().also { coroutineContext.cancelChildren() }
        }
    }

    companion object {
        /** How long an address may take to answer a probe. */
        val PROBE_TIMEOUT = 6.seconds
    }
}

/** Marks a request the fallback leaves alone: the finder's own probes. */
private val NoFallback = AttributeKey<Unit>("journal.noFallback")

/**
 * Makes this client find the server again (with [finder]) when a request to the kept server fails on the network, and
 * send the request once more to where it answers now. Only requests to an https origin, and only when the body can be
 * sent again (GraphQL and plain GETs; a streamed upload piece fails, and the next goes to the new address).
 */
internal fun HttpClient.findServerOnNetworkErrors(finder: ServerFinder): HttpClient = apply {
    val client = this
    plugin(HttpSend).intercept { request ->
        if (request.attributes.contains(NoFallback)) return@intercept execute(request)
        val origin = KeptDiscovery.originOf(request.url.buildString()) ?: return@intercept execute(request)
        try {
            execute(request)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // On a job of its own: the failed request's job is done, and what runs on it is cancelled at once.
            val moved = withContext(Job()) { finder.find(origin) { candidate -> client.answersAt(candidate) } } ?: throw e
            if (moved == origin || !request.body.canSendAgain()) throw e
            val to = Url(moved)
            request.url.protocol = URLProtocol.HTTPS
            request.url.host = to.host
            request.url.port = to.port
            execute(request)
        }
    }
}

private fun Any.canSendAgain(): Boolean =
    this is OutgoingContent.ByteArrayContent || this is OutgoingContent.NoContent

/** Whether the server answers at [origin] at all: any HTTP answer means the TLS handshake (and the pin) passed. */
private suspend fun HttpClient.answersAt(origin: String): Boolean = withTimeout(ServerFinder.PROBE_TIMEOUT) {
    get("$origin/query") { attributes.put(NoFallback, Unit) }
    true
}
