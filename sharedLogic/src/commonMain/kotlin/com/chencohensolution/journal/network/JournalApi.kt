package com.chencohensolution.journal.network

import com.apollographql.apollo.ApolloClient
import com.apollographql.apollo.exception.ApolloHttpException
import com.apollographql.ktor.http.KtorHttpEngine
import com.chencohensolution.journal.connection.ConnectionStatus
import com.chencohensolution.journal.connection.JournalImage
import com.chencohensolution.journal.graphql.ImagesQuery
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.appendPathSegments
import io.ktor.http.encodedPath

/**
 * Builds an [HttpClient] that trusts [trustedCas] (DER) in addition to the platform roots and
 * presents the client identity named [identityAlias] during the TLS handshake.
 */
internal fun interface MtlsHttpClientFactory {
    suspend fun create(identityAlias: String, trustedCas: List<ByteArray>): HttpClient
}

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

internal data class ImagesResult(
    val status: ConnectionStatus,
    val images: List<JournalImage> = emptyList(),
    /** GraphQL error from the server (e.g. unreadable image folder) while the connection itself worked. */
    val imagesError: String? = null,
)

/**
 * Runs the Images query. Any GraphQL response means the mTLS handshake and authentication
 * succeeded; HTTP errors (e.g. 401 for an unknown certificate) and transport errors do not.
 */
internal suspend fun fetchImages(apollo: ApolloClient, serverUrl: String): ImagesResult {
    val response = apollo.query(ImagesQuery()).execute()
    return when (val exception = response.exception) {
        null -> ImagesResult(
            status = ConnectionStatus.Connected,
            images = response.data?.images.orEmpty().map { it.toJournalImage(serverUrl) },
            imagesError = response.errors?.firstOrNull()?.message,
        )
        is ApolloHttpException -> ImagesResult(ConnectionStatus.Failed(exception.statusCode))
        else -> ImagesResult(ConnectionStatus.Error(exception.describe()))
    }
}

private fun ImagesQuery.Image.toJournalImage(serverUrl: String) = JournalImage(
    id = id,
    title = title,
    url = resolveServerPath(serverUrl, url),
    description = description,
)

/** The server returns unencoded paths relative to its origin (`/images/<file name>`). */
private fun resolveServerPath(serverUrl: String, relativePath: String): String =
    URLBuilder(serverUrl).apply {
        parameters.clear()
        fragment = ""
        encodedPath = ""
        appendPathSegments(relativePath.split('/').filter { it.isNotEmpty() })
    }.buildString()

internal fun Throwable.describe(): String = message ?: this::class.simpleName ?: "Unknown error"
