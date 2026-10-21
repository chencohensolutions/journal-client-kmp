package com.chencohensolution.journal

import com.chencohensolution.journal.connection.ConnectionStatus
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.createApolloClient
import com.chencohensolution.journal.network.checkConnection
import com.chencohensolution.journal.network.useClient
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
import com.chencohensolution.journal.tls.SERVER_CA_PEM
import com.chencohensolution.journal.tls.certificateCommonName
import com.chencohensolution.journal.tls.decodePemCertificates
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

private const val SERVER_URL = "https://10.0.2.2:8080/query"

private const val USAGE_JSON = """{"data":{"storageUsage":{"photos":2,"videos":0,"bytes":1234}}}"""

private class InMemoryStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private fun respondJson(json: String): MockRequestHandler = {
    respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
}

private fun mockClient(handler: MockRequestHandler) = HttpClient(MockEngine(handler))

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

class JournalApiTest {
    private suspend fun fetch(handler: MockRequestHandler) =
        createApolloClient(mockClient(handler), SERVER_URL).useClient { checkConnection(it) }

    @Test
    fun answerMeansConnected() = runTest {
        val result = fetch(respondJson(USAGE_JSON))
        assertEquals(ConnectionStatus.Connected, result.status)
        assertNull(result.serverError)
    }

    @Test
    fun sendsStorageUsageOperation() = runTest {
        var body = ""
        fetch { request ->
            body = request.body.toByteArray().decodeToString()
            respondJson(USAGE_JSON)(request)
        }
        assertContains(body, "\"operationName\":\"StorageUsage\"")
    }

    @Test
    fun graphQlErrorStillMeansConnected() = runTest {
        val result = fetch(respondJson("""{"errors":[{"message":"no data_folder is configured for hen"}],"data":null}"""))
        assertEquals(ConnectionStatus.Connected, result.status)
        assertEquals("no data_folder is configured for hen", result.serverError)
    }

    @Test
    fun rejectedCertificateIsHttpFailure() = runTest {
        val result = fetch { respond("unauthorized client certificate", HttpStatusCode.Unauthorized) }
        assertEquals(ConnectionStatus.Failed(401), result.status)
    }

    @Test
    fun transportErrorsAreReported() = runTest {
        val result = fetch { error("handshake failed") }
        assertEquals(ConnectionStatus.Error("handshake failed"), result.status)
    }
}

class JournalControllerTest {
    private val store = InMemoryStore()
    private val requestedAliases = mutableListOf<String?>()

    private fun TestScope.controller(handler: MockRequestHandler = respondJson(USAGE_JSON)) = JournalController(
        settings = JournalSettings(store),
        clientFactory = MtlsHttpClientFactory { alias, _ ->
            requestedAliases += alias
            mockClient(handler)
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
    fun connectChecksTheServerAndPersistsSettings() = runTest {
        val controller = controller()
        controller.selectIdentity("hen")
        controller.updateServerUrl(" $SERVER_URL ")
        controller.connect()

        val connecting = assertIs<ConnectionStatus.Connecting>(controller.currentState.status)
        assertEquals(listOf("journal-server-ca"), connecting.trustedCaNames)

        assertEquals(ConnectionStatus.Connected, controller.finalStatus())
        assertNull(controller.currentState.serverError)
        assertEquals(listOf<String?>("hen"), requestedAliases)

        val reloaded = controller().currentState
        assertEquals(SERVER_URL, reloaded.serverUrl)
        assertEquals("hen", reloaded.identityAlias)
    }

    @Test
    fun failedReconnectIsReported() = runTest {
        var status = HttpStatusCode.OK
        val controller = controller { request ->
            if (status == HttpStatusCode.OK) respondJson(USAGE_JSON)(request) else respond("", status)
        }
        controller.selectIdentity("hen")
        controller.connect()
        assertEquals(ConnectionStatus.Connected, controller.finalStatus())

        status = HttpStatusCode.Unauthorized
        controller.connect()
        assertEquals(ConnectionStatus.Failed(401), controller.finalStatus())
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
