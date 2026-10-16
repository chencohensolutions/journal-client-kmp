package com.chencohensolution.journal.network

import com.chencohensolution.journal.connection.ConnectionStatus
import io.ktor.client.HttpClient
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * Builds an [HttpClient] that trusts [trustedCas] (DER) in addition to the platform roots and
 * presents the client identity named [identityAlias] during the TLS handshake.
 */
internal fun interface MtlsHttpClientFactory {
    suspend fun create(identityAlias: String, trustedCas: List<ByteArray>): HttpClient
}

private const val PROBE_QUERY = """{"query":"{ __typename }"}"""

/**
 * Sends a trivial GraphQL query to check that the mTLS handshake succeeds and the server answers.
 * 400/404 still count as connected: the TLS session was accepted, only the request was not.
 */
internal suspend fun probeGraphQlServer(client: HttpClient, url: String): ConnectionStatus =
    try {
        val response = client.post(url) {
            contentType(ContentType.Application.Json)
            setBody(PROBE_QUERY)
        }
        val status = response.status
        if (status.isSuccess() || status == HttpStatusCode.BadRequest || status == HttpStatusCode.NotFound) {
            ConnectionStatus.Connected
        } else {
            ConnectionStatus.Failed(status.value)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        ConnectionStatus.Error(e.describe())
    }

internal fun Throwable.describe(): String = message ?: this::class.simpleName ?: "Unknown error"
