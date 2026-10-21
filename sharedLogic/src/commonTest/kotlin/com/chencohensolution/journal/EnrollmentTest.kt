package com.chencohensolution.journal

import com.chencohensolution.journal.connection.ConnectionStatus
import com.chencohensolution.journal.connection.JournalController
import com.chencohensolution.journal.enrollment.CertificateRequest
import com.chencohensolution.journal.enrollment.Der
import com.chencohensolution.journal.enrollment.DeviceKey
import com.chencohensolution.journal.enrollment.DeviceKeyStore
import com.chencohensolution.journal.enrollment.EnrollmentController
import com.chencohensolution.journal.enrollment.EnrollmentError
import com.chencohensolution.journal.enrollment.EnrollmentLink
import com.chencohensolution.journal.enrollment.EnrollmentStep
import com.chencohensolution.journal.enrollment.KeyProtection
import com.chencohensolution.journal.enrollment.cleanCode
import com.chencohensolution.journal.enrollment.parseEnrollmentLink
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.graphQlEndpoint
import com.chencohensolution.journal.network.serverAddress
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.settings.KeyValueStore
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
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

private fun ByteArray.hex() = joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

private fun hex(value: String) = value.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class DerTest {
    @Test
    fun encodesObjectIdentifiers() {
        assertEquals("06072a8648ce3d0201", Der.objectIdentifier("1.2.840.10045.2.1").hex())
        assertEquals("0603550403", Der.objectIdentifier("2.5.4.3").hex())
    }

    @Test
    fun encodesLongLengths() {
        assertEquals("0400", Der.tlv(0x04, ByteArray(0)).hex())
        assertEquals("047f", Der.tlv(0x04, ByteArray(0x7F)).hex().take(4))
        assertEquals("048180", Der.tlv(0x04, ByteArray(0x80)).hex().take(6))
        assertEquals("04820100", Der.tlv(0x04, ByteArray(0x100)).hex().take(8))
    }
}

class CertificateRequestTest {
    private val point = hex("04" + "11".repeat(32) + "22".repeat(32))

    @Test
    fun buildsPkcs10RequestSignedOverItsInfo() {
        var signed = ByteArray(0)
        val csr = CertificateRequest.build("Pixel", CertificateRequest.ecP256PublicKeyInfo(point)) {
            signed = it
            hex("3006020101020102") // stand-in DER signature
        }

        val publicKeyInfo = "3059301306072a8648ce3d020106082a8648ce3d030107034200" + point.hex()
        val info = "3072" + "020100" + "3010310e300c06035504030c05506978656c" + publicKeyInfo + "a000"
        assertEquals(info, signed.hex(), "the signature must cover exactly the CertificationRequestInfo")
        assertEquals("30818b" + info + "300a06082a8648ce3d040302" + "030900" + "3006020101020102", csr.hex())
    }
}

class CleanCodeTest {
    @Test
    fun keepsTypedCodes() {
        assertEquals("K7QM-4XPD", cleanCode("k7qm-4xpd"))
        assertEquals("K7QM", cleanCode("K7QM"))
        assertEquals("K7QM-", cleanCode("K7QM-"))
        assertEquals("K7QM4XPD", cleanCode("K7QM4XPD9"))
        assertEquals("K7QM-4XPD", cleanCode("K7QM-4XPD!"))
    }

    @Test
    fun extractsCodeFromPastedText() {
        val pasted = "5MHH-0JDB\n2026/10/17 13:51:27 http: TLS handshake error from 127.0.0.1:55470: remote error"
        assertEquals("5MHH-0JDB", cleanCode(pasted))
        assertEquals("K7QM-4XPD", cleanCode("  Code     K7QM-4XPD"))
        assertEquals("K7QM-4XPD", cleanCode(" k7qm-4xpd "))
    }
}

class EnrollmentLinkTest {
    @Test
    fun readsCodeAndServer() {
        assertEquals(
            EnrollmentLink("K7QM-4XPD", "https://journal.example.com:56398"),
            parseEnrollmentLink("journal://enroll?code=K7QM-4XPD&server=https%3A%2F%2Fjournal.example.com%3A56398"),
        )
        assertEquals(EnrollmentLink("K7QM-4XPD", null), parseEnrollmentLink("journal://enroll?code=K7QM-4XPD"))
    }

    @Test
    fun ignoresOtherLinks() {
        assertNull(parseEnrollmentLink("journal://enroll"))
        assertNull(parseEnrollmentLink("journal://other?code=K7QM-4XPD"))
        assertNull(parseEnrollmentLink("https://enroll?code=K7QM-4XPD"))
        assertNull(parseEnrollmentLink("not a link"))
    }
}

class EndpointTest {
    @Test
    fun addsSchemeAndGraphQlPath() {
        assertEquals("https://192.168.50.2:8080/query", graphQlEndpoint(" 192.168.50.2:8080/ "))
        assertEquals("https://journal.example/query", graphQlEndpoint("https://journal.example"))
        assertEquals("https://h:8080/custom", graphQlEndpoint("https://h:8080/custom"))
        assertEquals("https://192.168.50.2:8080", serverAddress("https://192.168.50.2:8080/query"))
    }
}

private class FakeKeyStore : DeviceKeyStore {
    val keys = mutableSetOf<String>()
    val certificates = mutableMapOf<String, ByteArray>()

    override fun generate(alias: String): DeviceKey {
        keys += alias
        return object : DeviceKey {
            override val protection = KeyProtection.TEE
            override val publicKeyInfo = CertificateRequest.ecP256PublicKeyInfo(byteArrayOf(4) + ByteArray(64))
            override fun sign(data: ByteArray) = hex("3006020101020102")
        }
    }

    override fun saveCertificate(alias: String, certificateDer: ByteArray) {
        check(alias in keys)
        certificates[alias] = certificateDer
    }

    override fun delete(alias: String) {
        keys -= alias
        certificates -= alias
    }
}

private class MemoryStore : KeyValueStore {
    val values = mutableMapOf<String, String>()
    override fun getString(key: String) = values[key]
    override fun putString(key: String, value: String?) {
        if (value == null) values.remove(key) else values[key] = value
    }
}

private val CERTIFICATE = byteArrayOf(0x30, 0x03, 0x02, 0x01, 0x05)

private const val PREVIEW_JSON =
    """{"data":{"enrollmentPreview":{"__typename":"EnrollmentPreview","user":"hen","expiresAt":"2026-10-17T14:32:00Z"}}}"""

private val ENROLLED_JSON =
    """{"data":{"enrollDevice":{"__typename":"EnrolledDevice","user":"hen","deviceName":"Pixel 9 Pro",""" +
        """"certificate":"${Base64.encode(CERTIFICATE)}","serial":"4fa107","expiresAt":"2027-10-17T14:24:00Z"}}}"""

private fun rejectedJson(field: String, reason: String) =
    """{"data":{"$field":{"__typename":"EnrollmentRejected","reason":"$reason"}}}"""

private const val IMAGES_OK = """{"data":{"images":[]}}"""

@OptIn(ExperimentalTime::class)
class EnrollmentControllerTest {
    private val store = MemoryStore()
    private val keyStore = FakeKeyStore()
    private val requestedAliases = mutableListOf<String?>()
    private val requests = mutableListOf<String>()
    private var enrolled: Pair<String, String>? = null

    /** Answers by operation name; [overrides] replaces the default answer of an operation. */
    private fun server(overrides: Map<String, String> = emptyMap(), imagesStatus: HttpStatusCode = HttpStatusCode.OK): MockRequestHandler = { request ->
        val body = request.body.toByteArray().decodeToString()
        requests += body
        val operation = Regex("\"operationName\":\"(\\w+)\"").find(body)!!.groupValues[1]
        val json = overrides[operation] ?: when (operation) {
            "EnrollmentPreview" -> PREVIEW_JSON
            "EnrollDevice" -> ENROLLED_JSON
            else -> IMAGES_OK
        }
        if (operation == "Images" && imagesStatus != HttpStatusCode.OK) {
            respond("unauthorized client certificate", imagesStatus)
        } else {
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
    }

    private fun TestScope.controller(handler: MockRequestHandler = server()): EnrollmentController {
        val dispatcher = StandardTestDispatcher(testScheduler)
        return EnrollmentController(
            settings = JournalSettings(store),
            clientFactory = MtlsHttpClientFactory { alias, _ ->
                requestedAliases += alias
                HttpClient(MockEngine(handler))
            },
            keyStore = keyStore,
            defaultDeviceName = "Pixel 9 Pro",
            onEnrolled = { url, alias -> enrolled = url to alias },
            dispatcher = dispatcher,
            workDispatcher = dispatcher,
            clock = object : Clock {
                override fun now() = Instant.parse("2026-10-17T14:24:00Z")
            },
        )
    }

    private suspend fun EnrollmentController.awaitStep(predicate: (EnrollmentStep) -> Boolean): EnrollmentStep =
        state.first { predicate(it.step) }.step

    private suspend fun EnrollmentController.reachConfirm() {
        start()
        updateServerAddress("192.168.50.2:8080")
        updateCode("k7qm-4xpd")
        checkCode()
        awaitStep { it is EnrollmentStep.Confirm }
    }

    @Test
    fun validCodeShowsAccountWithoutClientCertificate() = runTest {
        val controller = controller()
        controller.start()
        controller.updateServerAddress("192.168.50.2:8080")
        controller.updateCode("k7qm-4xpd")
        controller.checkCode()

        assertEquals(EnrollmentStep.EnterCode(checking = true), controller.currentState.step)
        assertEquals(EnrollmentStep.Confirm(user = "hen", minutesLeft = 8), controller.awaitStep { it is EnrollmentStep.Confirm })
        assertEquals(listOf<String?>(null), requestedAliases)
        assertContains(requests.single(), "\"code\":\"K7QM-4XPD\"")
    }

    @Test
    fun linkFillsInAndChecksTheCode() = runTest {
        val controller = controller()
        controller.openLink(EnrollmentLink("k7qm-4xpd", "https://192.168.50.2:8080"))

        assertEquals(EnrollmentStep.Confirm(user = "hen", minutesLeft = 8), controller.awaitStep { it is EnrollmentStep.Confirm })
        assertEquals("https://192.168.50.2:8080", controller.currentState.serverAddress)
        assertContains(requests.single(), "\"code\":\"K7QM-4XPD\"")
    }

    @Test
    fun scannedTextThatIsNotALinkIsTheCode() = runTest {
        val controller = controller()
        controller.start()
        controller.openScanned("k7qm-4xpd")

        assertEquals("K7QM-4XPD", controller.currentState.code)
        assertEquals(EnrollmentStep.EnterCode(), controller.currentState.step)
    }

    @Test
    fun rejectedCodeStaysOnCodeScreen() = runTest {
        val controller = controller(server(mapOf("EnrollmentPreview" to rejectedJson("enrollmentPreview", "INVALID_CODE"))))
        controller.start()
        controller.updateCode("AAAA-AAAA")
        controller.checkCode()

        val step = controller.awaitStep { it is EnrollmentStep.EnterCode && !it.checking }
        assertEquals(EnrollmentStep.EnterCode(error = EnrollmentError.InvalidCode), step)

        controller.updateCode("AAAA-AAAB")
        assertEquals(EnrollmentStep.EnterCode(), controller.currentState.step, "typing clears the error")
    }

    @Test
    fun missingFieldsAreReportedLocally() = runTest {
        val controller = controller()
        controller.start()
        controller.updateServerAddress(" ")
        controller.checkCode()
        assertEquals(EnrollmentStep.EnterCode(error = EnrollmentError.MissingServerAddress), controller.currentState.step)
        controller.updateServerAddress("h")
        controller.checkCode()
        assertEquals(EnrollmentStep.EnterCode(error = EnrollmentError.MissingCode), controller.currentState.step)
        assertTrue(requests.isEmpty())
    }

    @Test
    fun registrationCreatesKeyStoresCertificateAndSwitchesIdentity() = runTest {
        store.values["keychain_alias"] = "journal-device-1"
        keyStore.keys += "journal-device-1"
        val controller = controller()
        controller.reachConfirm()
        controller.register()

        val step = assertIs<EnrollmentStep.Enrolled>(controller.awaitStep { it is EnrollmentStep.Enrolled || it is EnrollmentStep.Failed })
        assertEquals(
            EnrollmentStep.Enrolled("hen", "Pixel 9 Pro", "https://192.168.50.2:8080", KeyProtection.TEE, "2027-10-17"),
            step,
        )
        val (url, alias) = enrolled!!
        assertEquals("https://192.168.50.2:8080/query", url)
        assertTrue(alias.startsWith(EnrollmentController.IDENTITY_ALIAS_PREFIX))
        assertTrue(CERTIFICATE.contentEquals(keyStore.certificates[alias]))
        assertEquals(setOf(alias), keyStore.keys, "the previous enrolled key is deleted")

        // Preview and enrollment without a certificate, then the connection test with the new identity.
        assertEquals(listOf(null, null, alias), requestedAliases)
        val enrollRequest = requests.single { "EnrollDevice" in it }
        assertContains(enrollRequest, "\"keyProtection\":\"TEE\"")
        assertContains(enrollRequest, "\"deviceName\":\"Pixel 9 Pro\"")
    }

    @Test
    fun rejectedEnrollmentDeletesTheKey() = runTest {
        val controller = controller(server(mapOf("EnrollDevice" to rejectedJson("enrollDevice", "INVALID_CODE"))))
        controller.reachConfirm()
        controller.register()

        assertEquals(EnrollmentStep.Failed(EnrollmentError.InvalidCode), controller.awaitStep { it is EnrollmentStep.Failed })
        assertTrue(keyStore.keys.isEmpty())
        assertNull(enrolled)

        controller.back()
        assertEquals("", controller.currentState.code)
        assertEquals(EnrollmentStep.EnterCode(), controller.currentState.step)
    }

    @Test
    fun failedConnectionTestDoesNotSwitchIdentity() = runTest {
        val controller = controller(server(imagesStatus = HttpStatusCode.Unauthorized))
        controller.reachConfirm()
        controller.register()

        assertEquals(
            EnrollmentStep.Failed(EnrollmentError.ConnectionTestFailed("HTTP 401")),
            controller.awaitStep { it is EnrollmentStep.Failed },
        )
        assertTrue(keyStore.keys.isEmpty())
        assertNull(enrolled)
    }

    @Test
    fun enrolledIdentityReplacesJournalSettings() = runTest {
        val journal = JournalController(
            settings = JournalSettings(store),
            clientFactory = MtlsHttpClientFactory { _, _ -> error("unused") },
            dispatcher = StandardTestDispatcher(testScheduler),
        )
        journal.useEnrolledIdentity("https://h:8080/query", "journal-device-2")
        assertEquals("https://h:8080/query", journal.currentState.serverUrl)
        assertEquals("journal-device-2", journal.currentState.identityAlias)
        assertEquals(ConnectionStatus.NotConnected, journal.currentState.status)
        assertEquals("journal-device-2", JournalSettings(store).identityAlias)
    }
}
