package com.chencohensolution.journal.server

import com.apollographql.apollo.ApolloClient
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.createApolloClient
import com.chencohensolution.journal.settings.JournalSettings
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The mTLS client for the server, made once per device identity and server address and kept: previews, originals,
 * verify and GraphQL all go over it, so the phone holds one HTTP/2 connection to the server (one handshake, with a
 * signature in its secure hardware) instead of opening one per call.
 */
internal class ServerHttp(
    private val settings: JournalSettings,
    private val clientFactory: MtlsHttpClientFactory,
    private val trustedCas: List<ByteArray>,
) {
    private class Held(val alias: String, val url: String, val http: HttpClient, val apollo: ApolloClient)

    private val mutex = Mutex()
    private var held: Held? = null

    private suspend fun held(): Held? = mutex.withLock {
        val alias = settings.identityAlias ?: return null
        val url = settings.serverUrl
        held?.takeIf { it.alias == alias && it.url == url }?.let { return it }
        held?.apollo?.close() // closes its HttpClient too
        val http = clientFactory.create(alias, trustedCas)
        Held(alias, url, http, createApolloClient(http, url)).also { held = it }
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
}
