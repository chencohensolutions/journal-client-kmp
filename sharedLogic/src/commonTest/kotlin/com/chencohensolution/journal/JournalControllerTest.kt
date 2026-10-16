package com.chencohensolution.journal

import com.chencohensolution.journal.connection.ConnectionStatus
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.probeGraphQlServer
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.tls.SERVER_CA_PEM
import com.chencohensolution.journal.tls.certificateCommonName
import com.chencohensolution.journal.tls.decodePemCertificates
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

private class InMemoryStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private fun mockClient(status: HttpStatusCode) = HttpClient(MockEngine { respond("", status) })

/** Waits for the in-flight connection attempt to finish (the mock engine may complete on another thread). */
private suspend fun JournalController.finalStatus(): ConnectionStatus =
    state.first { it.status !is ConnectionStatus.Connecting }.status

class CertificatesTest {
    @Test
    fun bundledCaDecodesToOneCertificate() {
        val certificates = decodePemCertificates(SERVER_CA_PEM)
        assertEquals(1, certificates.size)
        assertEquals("journal-server-ca", certificateCommonName(certificates.single()))
    }
}

class GraphQlProbeTest {
    @Test
    fun successAndClientErrorsCountAsConnected() = runTest {
        for (status in listOf(HttpStatusCode.OK, HttpStatusCode.BadRequest, HttpStatusCode.NotFound)) {
            assertEquals(ConnectionStatus.Connected, probeGraphQlServer(mockClient(status), "https://server/query"))
        }
    }

    @Test
    fun otherStatusesFail() = runTest {
        assertEquals(
            ConnectionStatus.Failed(503),
            probeGraphQlServer(mockClient(HttpStatusCode.ServiceUnavailable), "https://server/query"),
        )
    }

    @Test
    fun transportErrorsAreReported() = runTest {
        val client = HttpClient(MockEngine { error("handshake failed") })
        assertEquals(ConnectionStatus.Error("handshake failed"), probeGraphQlServer(client, "https://server/query"))
    }

    @Test
    fun sendsTypenameQuery() = runTest {
        var body = ""
        val client = HttpClient(MockEngine { request ->
            body = request.body.toByteArray().decodeToString()
            respondError(HttpStatusCode.OK)
        })
        probeGraphQlServer(client, "https://server/query")
        assertEquals("""{"query":"{ __typename }"}""", body)
    }
}

class JournalControllerTest {
    private val store = InMemoryStore()
    private val requestedAliases = mutableListOf<String>()

    private fun TestScope.controller(status: HttpStatusCode = HttpStatusCode.OK) = JournalController(
        settings = JournalSettings(store),
        clientFactory = MtlsHttpClientFactory { alias, _ ->
            requestedAliases += alias
            mockClient(status)
        },
        dispatcher = StandardTestDispatcher(testScheduler),
        now = { "now" },
    )

    @Test
    fun startsWithDefaultsWhenNothingIsStored() = runTest {
        val state = controller().currentState
        assertEquals(JournalSettings.DEFAULT_SERVER_URL, state.serverUrl)
        assertEquals(null, state.identityAlias)
        assertEquals(ConnectionStatus.NotConnected, state.status)
    }

    @Test
    fun connectWithoutIdentityAsksForOne() = runTest {
        val controller = controller()
        controller.connect()
        assertEquals(ConnectionStatus.MissingIdentity, controller.currentState.status)
    }

    @Test
    fun connectWithBlankUrlAsksForOne() = runTest {
        val controller = controller()
        controller.selectIdentity("hen")
        controller.updateServerUrl("  ")
        controller.connect()
        assertEquals(ConnectionStatus.MissingServerUrl, controller.currentState.status)
    }

    @Test
    fun connectPersistsSettingsAndReportsResult() = runTest {
        val controller = controller()
        controller.selectIdentity("hen")
        controller.updateServerUrl(" https://10.0.2.2:8080/query ")
        controller.connect()

        val connecting = assertIs<ConnectionStatus.Connecting>(controller.currentState.status)
        assertEquals(listOf("journal-server-ca"), connecting.trustedCaNames)

        assertEquals(ConnectionStatus.Connected, controller.finalStatus())
        assertEquals(listOf("hen"), requestedAliases)

        val reloaded = controller().currentState
        assertEquals("https://10.0.2.2:8080/query", reloaded.serverUrl)
        assertEquals("hen", reloaded.identityAlias)
    }

    @Test
    fun clientCreationFailureIsReported() = runTest {
        val controller = JournalController(
            settings = JournalSettings(store),
            clientFactory = MtlsHttpClientFactory { _, _ -> error("Could not access system private key for hen") },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        controller.selectIdentity("hen")
        controller.connect()
        assertEquals(
            ConnectionStatus.Error("Could not access system private key for hen"),
            controller.finalStatus(),
        )
    }
}
