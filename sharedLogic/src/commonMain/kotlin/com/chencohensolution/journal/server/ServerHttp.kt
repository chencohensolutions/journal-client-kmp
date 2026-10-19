package com.chencohensolution.journal.server

import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.settings.JournalSettings
import io.ktor.client.HttpClient
import io.ktor.http.URLBuilder
import io.ktor.http.encodedPath
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** The mTLS client for the server's plain HTTP files (previews, originals), made once per device identity. */
internal class ServerHttp(
    private val settings: JournalSettings,
    private val clientFactory: MtlsHttpClientFactory,
    private val trustedCas: List<ByteArray>,
) {
    private val mutex = Mutex()
    private var client: Pair<String, HttpClient>? = null

    /** Null when the device isn't registered. */
    suspend fun client(): HttpClient? = mutex.withLock {
        val alias = settings.identityAlias ?: return null
        client?.takeIf { it.first == alias }?.let { return it.second }
        client?.second?.close()
        clientFactory.create(alias, trustedCas).also { client = alias to it }
    }

    /** [path] (and [query]) on the server's origin. */
    fun url(path: String, query: String? = null): String = URLBuilder(settings.serverUrl).apply {
        parameters.clear()
        fragment = ""
        encodedPath = path
    }.buildString() + (query?.let { "?$it" } ?: "")
}
