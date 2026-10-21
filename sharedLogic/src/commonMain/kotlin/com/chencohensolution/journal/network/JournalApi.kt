package com.chencohensolution.journal.network

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.ktor.http.KtorHttpEngine
import com.chencohensolution.journal.connection.ConnectionStatus
import com.chencohensolution.journal.graphql.StorageUsageQuery
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath

/**
 * Builds an [HttpClient] that trusts [trustedCas] (DER) in addition to the platform roots and
 * presents the client identity named [identityAlias] during the TLS handshake. Without an alias
 * no client certificate is sent, which the server accepts only for enrollment.
 */
internal fun interface MtlsHttpClientFactory {
    suspend fun create(identityAlias: String?, trustedCas: List<ByteArray>): HttpClient
}

private const val GRAPHQL_PATH = "/query"

/** The GraphQL endpoint for what the user typed: `https://` is assumed and `/query` added when there is no path. */
internal fun graphQlEndpoint(serverAddress: String): String {
    val trimmed = serverAddress.trim().trimEnd('/')
    val withScheme = if ("://" in trimmed) trimmed else "https://$trimmed"
    return URLBuilder(withScheme).apply {
        if (encodedPath.isEmpty() || encodedPath == "/") encodedPath = GRAPHQL_PATH
    }.buildString()
}

/** The address to show the user for a stored endpoint; the inverse of [graphQlEndpoint]. */
internal fun serverAddress(endpoint: String): String = endpoint.removeSuffix(GRAPHQL_PATH)

/** Apollo on top of the platform mTLS client. Closing the ApolloClient also closes [httpClient]. */
internal fun createApolloClient(httpClient: HttpClient, serverUrl: String): ApolloClient =
    ApolloClient.Builder()
        .serverUrl(serverUrl)
        .httpEngine(KtorHttpEngine(httpClient))
        .build()

/** `ApolloClient` is okio-Closeable, which is not `AutoCloseable` on Kotlin/Native. */
internal inline fun <T> ApolloClient.useClient(block: (ApolloClient) -> T): T =
    try {
        block(this)
    } finally {
        close()
    }

/** What a connection check found: the server answered ([ConnectionStatus.Connected]), or why not. */
internal data class ConnectionResult(
    val status: ConnectionStatus,
    /** A GraphQL error from the server while the connection itself worked. */
    val serverError: String? = null,
)

/**
 * Checks the connection with the smallest authenticated query, `storageUsage`. Any GraphQL response means the mTLS
 * handshake and authentication succeeded; HTTP errors (e.g. 401 for an unknown certificate) and transport errors
 * do not.
 */
internal suspend fun checkConnection(apollo: ApolloClient): ConnectionResult {
    val response = apollo.query(StorageUsageQuery()).execute()
    return when (val exception = response.exception) {
        null -> ConnectionResult(ConnectionStatus.Connected, serverError = response.errors?.firstOrNull()?.message)
        is ApolloHttpException -> ConnectionResult(ConnectionStatus.Failed(exception.statusCode))
        else -> ConnectionResult(ConnectionStatus.Error(exception.describe()))
    }
}

/**
 * The innermost cause's message: Apollo wraps failures ("No data was found" → "Failed to execute GraphQL http network
 * request" → the TLS or socket error), and only the last one says what went wrong.
 */
internal fun Throwable.describe(): String {
    val causes = generateSequence(this) { it.cause?.takeIf { cause -> cause !== it } }.take(10).toList()
    return causes.lastOrNull { it.message != null }?.message ?: this::class.simpleName ?: "Unknown error"
}
