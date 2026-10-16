package com.chencohensolution.journal

import com.chencohensolution.journal.connection.ConnectionStatus
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.connection.JournalImage
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.createApolloClient
import com.chencohensolution.journal.network.fetchImages
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

private const val IMAGES_JSON = """
{"data":{"images":[
  {"id":"IMG 1.jpg","title":"IMG 1","url":"/images/IMG 1.jpg","description":null},
  {"id":"beach.png","title":"beach","url":"/images/beach.png","description":"Summer"}
]}}
"""

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
        createApolloClient(mockClient(handler), SERVER_URL).useClient { fetchImages(it, SERVER_URL) }

    @Test
    fun imagesAreMappedWithAbsoluteUrls() = runTest {
        val result = fetch(respondJson(IMAGES_JSON))
        assertEquals(ConnectionStatus.Connected, result.status)
        assertNull(result.imagesError)
        assertEquals(
            listOf(
                JournalImage("IMG 1.jpg", "IMG 1", "https://10.0.2.2:8080/images/IMG%201.jpg", null),
                JournalImage("beach.png", "beach", "https://10.0.2.2:8080/images/beach.png", "Summer"),
            ),
            result.images,
        )
    }

    @Test
    fun sendsImagesOperation() = runTest {
        var body = ""
        fetch { request ->
            body = request.body.toByteArray().decodeToString()
            respondJson(IMAGES_JSON)(request)
        }
        assertContains(body, "\"operationName\":\"Images\"")
    }

    @Test
    fun graphQlErrorStillMeansConnected() = runTest {
        val result = fetch(respondJson("""{"errors":[{"message":"failed to read image folder"}],"data":null}"""))
        assertEquals(ConnectionStatus.Connected, result.status)
        assertEquals("failed to read image folder", result.imagesError)
        assertEquals(emptyList(), result.images)
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
    private val requestedAliases = mutableListOf<String>()

    private fun TestScope.controller(handler: MockRequestHandler = respondJson(IMAGES_JSON)) = JournalController(
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
    fun connectLoadsImagesAndPersistsSettings() = runTest {
        val controller = controller()
        controller.selectIdentity("hen")
        controller.updateServerUrl(" $SERVER_URL ")
        controller.connect()

        val connecting = assertIs<ConnectionStatus.Connecting>(controller.currentState.status)
        assertEquals(listOf("journal-server-ca"), connecting.trustedCaNames)

        assertEquals(ConnectionStatus.Connected, controller.finalStatus())
        assertEquals(listOf("IMG 1", "beach"), controller.currentState.images.map { it.title })
        assertEquals(listOf("hen"), requestedAliases)

        val reloaded = controller().currentState
        assertEquals(SERVER_URL, reloaded.serverUrl)
        assertEquals("hen", reloaded.identityAlias)
    }

    @Test
    fun failedReconnectClearsImages() = runTest {
        var status = HttpStatusCode.OK
        val controller = controller { request ->
            if (status == HttpStatusCode.OK) respondJson(IMAGES_JSON)(request) else respond("", status)
        }
        controller.selectIdentity("hen")
        controller.connect()
        controller.finalStatus()
        assertEquals(2, controller.currentState.images.size)

        status = HttpStatusCode.Unauthorized
        controller.connect()
        assertEquals(ConnectionStatus.Failed(401), controller.finalStatus())
        assertEquals(emptyList(), controller.currentState.images)
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
