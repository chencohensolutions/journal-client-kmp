package com.chencohensolution.journal.server

import com.apollographql.apollo.ApolloClient
import com.chencohensolution.journal.discovery.KeptDiscovery
import com.chencohensolution.journal.discovery.ServerFinder
import com.chencohensolution.journal.discovery.findServerOnNetworkErrors
import com.chencohensolution.journal.graphql.ServerCaQuery
import com.chencohensolution.journal.graphql.ServerDiscoveryQuery
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.createApolloClient
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.tls.decodePemCertificates
import kotlinx.coroutines.CancellationException
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The mTLS client for the server, made once per device identity, server address, pinned key and kept CA, and kept: previews,
 * originals, verify and GraphQL all go over it, so the phone holds one HTTP/2 connection to the server (one handshake,
 * with a signature in its secure hardware) instead of opening one per call. When the server stops answering, [finder]
 * looks for it at its other addresses and in the DHT (docs/plans/discovery.md, "Phones").
 */
internal class ServerHttp(
    private val settings: JournalSettings,
    private val clientFactory: MtlsHttpClientFactory,
    private val trustedCas: List<ByteArray>,
    private val finder: ServerFinder = ServerFinder(settings),
) {
    private class Held(val alias: String, val url: String, val trust: List<String?>, val http: HttpClient, val apollo: ApolloClient)

    private val mutex = Mutex()
    private var held: Held? = null

    /**
     * The client replaced last: closed only when the next one replaces it, since requests may still be running on it
     * (the request that found the server at a new address is retried on the client it started on).
     */
    private var retired: Held? = null

    private suspend fun held(): Held? = mutex.withLock {
        val alias = settings.identityAlias ?: return null
        val url = settings.serverUrl
        // What the mTLS clients trust the server by besides the app's CA: its pinned key and its own CA.
        val trust = listOf(settings.serverFingerprint, settings.serverCa)
        held?.takeIf { it.alias == alias && it.url == url && it.trust == trust }?.let { return it }
        retired?.apollo?.close() // closes its HttpClient too
        retired = held
        val http = clientFactory.create(alias, trustedCas).findServerOnNetworkErrors(finder)
        Held(alias, url, trust, http, createApolloClient(http, url)).also { held = it }
    }

    /** Null when the device isn't registered. */
    suspend fun client(): HttpClient? = held()?.http

    /** Runs [block] with the GraphQL client, as this device's identity; null when it isn't registered. */
    suspend fun <T> graphql(block: suspend (ApolloClient) -> T): T? = held()?.let { block(it.apollo) }

    /** [path] (and [query]) on the server's origin. */
    fun url(path: String, query: String? = null): String = URLBuilder(settings.serverUrl).apply {
        parameters.clear()
        fragment = ""
        encodedPath = path
    }.buildString() + (query?.let { "?$it" } ?: "")

    /**
     * Asks the server who it is and where (`serverInfo.discovery`) and keeps it. A server from before discovery
     * doesn't know the field: nothing changes then.
     */
    suspend fun refreshDiscovery() {
        val discovery = graphql { apollo ->
            apollo.query(ServerDiscoveryQuery()).execute().data?.serverInfo?.discovery
        } ?: return
        keepDiscovery(discovery.fingerprint, discovery.id, discovery.key, discovery.addresses)
    }

    /**
     * Asks the server for the CA that signs its certificates (`serverInfo.caCertificate`) and keeps it, so a device
     * enrolled before servers told it trusts its server by it too. A server from before it: nothing changes.
     */
    suspend fun refreshServerCa() {
        val ca = graphql { askServerCa(it) } ?: return
        if (ca != settings.serverCa) settings.serverCa = ca
    }

    /** Keeps what the server at `serverUrl` said of itself (see [KeptDiscovery.merge]). */
    fun keepDiscovery(fingerprint: String, id: String, key: String?, addresses: List<String>) {
        if (fingerprint.length != 64) return
        settings.serverDiscovery = KeptDiscovery.merge(settings.serverDiscovery, settings.serverUrl, fingerprint, id, key, addresses)
    }
}

/**
 * The CA that signs the server's certificates, PEM (`serverInfo.caCertificate`), asked on its own; null when the
 * server doesn't tell (one from before Nov 14, 2026) or what it tells isn't a certificate.
 */
internal suspend fun askServerCa(apollo: ApolloClient): String? = try {
    apollo.query(ServerCaQuery()).execute().data?.serverInfo?.caCertificate
        ?.takeIf { decodePemCertificates(it).isNotEmpty() }
} catch (e: CancellationException) {
    throw e
} catch (_: Exception) {
    null
}
