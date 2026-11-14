package com.chencohensolution.journal.enrollment

import com.chencohensolution.journal.connection.ConnectionStatus
import com.chencohensolution.journal.connection.ObservationHandle
import com.apollographql.apollo.exception.ApolloNetworkException
import com.chencohensolution.journal.discovery.DhtClient
import com.chencohensolution.journal.discovery.KeptDiscovery
import com.chencohensolution.journal.discovery.ServerId
import com.chencohensolution.journal.discovery.ServerRecord
import com.chencohensolution.journal.discovery.answersAt
import com.chencohensolution.journal.discovery.firstToAnswer
import kotlin.time.Duration.Companion.milliseconds
import com.chencohensolution.journal.graphql.type.EnrollmentRejectedReason
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.createApolloClient
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.network.checkConnection
import com.chencohensolution.journal.network.graphQlEndpoint
import com.chencohensolution.journal.network.serverAddress
import com.chencohensolution.journal.network.useClient
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.tls.SERVER_CA_PEM
import com.chencohensolution.journal.server.askServerCa
import com.chencohensolution.journal.tls.decodePemCertificates
import io.ktor.http.Url
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/** Wizard screens, in order. */
sealed interface EnrollmentStep {
    data object Welcome : EnrollmentStep

    /** Server address and enrollment code. [checking] while the code is looked up. */
    data class EnterCode(val checking: Boolean = false, val error: EnrollmentError? = null) : EnrollmentStep

    /** The code is valid for [user]; [minutesLeft] until it expires. */
    data class Confirm(val user: String, val minutesLeft: Int) : EnrollmentStep

    data class Registering(val progress: RegistrationProgress) : EnrollmentStep

    /** [validUntil] is the certificate's expiry date (yyyy-mm-dd). */
    data class Enrolled(
        val user: String,
        val deviceName: String,
        val serverAddress: String,
        val keyProtection: KeyProtection,
        val validUntil: String,
    ) : EnrollmentStep

    data class Failed(val error: EnrollmentError) : EnrollmentStep
}

enum class RegistrationProgress {
    CREATING_KEY,
    SENDING_REQUEST,
    SAVING_CERTIFICATE,
    TESTING_CONNECTION,
}

sealed interface EnrollmentError {
    data object MissingServerAddress : EnrollmentError
    data object MissingCode : EnrollmentError

    /** Wrong, expired or already used code. */
    data object InvalidCode : EnrollmentError
    data object RateLimited : EnrollmentError

    /** The server could not use the certificate request; a bug rather than a user error. */
    data object RequestRejected : EnrollmentError

    /** Could not talk to the server (address, network, TLS). */
    data class Unreachable(val message: String) : EnrollmentError

    /** The device's key store could not create the key or store the certificate. */
    data class KeyStoreFailed(val message: String) : EnrollmentError

    /** The certificate was issued but signing in with it did not work. */
    data class ConnectionTestFailed(val message: String) : EnrollmentError
}

data class EnrollmentState(
    val serverAddress: String,
    val code: String,
    val deviceName: String,
    val step: EnrollmentStep,
    /**
     * The server's id when the enrollment link said who it is ("T6DNBAM-IJR6WLU-…"): its key is checked against it
     * instead of the CA and the host name. Null otherwise.
     */
    val serverId: String? = null,
)

/**
 * Shared logic of the device enrollment wizard: checks an enrollment code, creates a hardware-backed
 * key, trades a certificate request for a client certificate and hands the new identity to
 * [onEnrolled]. See docs/authentication.md in journal-server-go.
 */
@OptIn(ExperimentalTime::class)
class EnrollmentController internal constructor(
    private val settings: JournalSettings,
    private val clientFactory: MtlsHttpClientFactory,
    private val keyStore: DeviceKeyStore,
    defaultDeviceName: String,
    /**
     * The new identity, at the GraphQL endpoint it enrolled at, with who the server is when the link said (else null)
     * and the CA that signs its certificates when it told (`serverInfo.caCertificate`; null from an older server).
     */
    private val onEnrolled: (serverUrl: String, identityAlias: String, discovery: KeptDiscovery?, serverCa: String?) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val trustedCas: List<ByteArray> = decodePemCertificates(SERVER_CA_PEM),
    private val clock: Clock = Clock.System,
    /** The DHT lookup, when none of the link's addresses answers. */
    private val dhtLookup: suspend (key: String, fingerprint: String) -> ServerRecord? = { key, fingerprint ->
        DhtClient().lookup(key, fingerprint)
    },
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

    /** The link scanned or opened, when it said who the server is (its fingerprint): the connections pin its key. */
    private var pinned: EnrollmentLink? = null

    /**
     * What `serverDiscovery` was before enrollment put the link's in, so the mTLS clients pin it (they read it from the
     * settings); put back when the user leaves without enrolling. Null while nothing was put in.
     */
    private var discoveryBefore: SavedDiscovery? = null

    private class SavedDiscovery(val value: KeptDiscovery?)

    private val _state = MutableStateFlow(
        EnrollmentState(
            serverAddress = serverAddress(settings.serverUrl),
            code = "",
            deviceName = defaultDeviceName,
            step = EnrollmentStep.Welcome,
        )
    )
    val state: StateFlow<EnrollmentState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: EnrollmentState get() = _state.value

    fun start() {
        setStep(EnrollmentStep.EnterCode())
    }

    /** A whole enrollment link pasted into the address (or code) field opens it, as [openPasted] does. */
    fun updateServerAddress(address: String) {
        findEnrollmentLink(address)?.let { return openLink(it) }
        _state.update { it.copy(serverAddress = address, step = clearedCodeError(it.step)) }
    }

    fun updateCode(code: String) {
        findEnrollmentLink(code)?.let { return openLink(it) }
        _state.update { it.copy(code = cleanCode(code), step = clearedCodeError(it.step)) }
    }

    fun updateDeviceName(name: String) {
        _state.update { it.copy(deviceName = name) }
    }

    /** EnterCode → Confirm: looks the code up without using it. */
    fun checkCode() {
        val current = _state.value
        if (current.step !is EnrollmentStep.EnterCode || current.step.checking) return
        when {
            current.serverAddress.isBlank() -> return setStep(EnrollmentStep.EnterCode(error = EnrollmentError.MissingServerAddress))
            current.code.isBlank() -> return setStep(EnrollmentStep.EnterCode(error = EnrollmentError.MissingCode))
        }
        setStep(EnrollmentStep.EnterCode(checking = true))
        job = scope.launch {
            val next = try {
                val (address, preview) = reachServer(current.serverAddress) { previewEnrollment(it, current.code) }
                if (address != current.serverAddress) _state.update { it.copy(serverAddress = address) }
                EnrollmentStep.Confirm(preview.user, minutesUntil(preview.expiresAt))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                EnrollmentStep.EnterCode(error = e.toEnrollmentError())
            }
            setStep(next)
        }
    }

    /** Confirm → Registering → Enrolled or Failed. */
    fun register() {
        val current = _state.value
        if (current.step !is EnrollmentStep.Confirm) return
        var endpoint = graphQlEndpoint(current.serverAddress)
        val alias = IDENTITY_ALIAS_PREFIX + clock.now().toEpochMilliseconds()
        val deviceName = current.deviceName.trim()

        job = scope.launch {
            var keyCreated = false
            var succeeded = false
            try {
                setStep(EnrollmentStep.Registering(RegistrationProgress.CREATING_KEY))
                val key = try {
                    withContext(workDispatcher) { keyStore.generate(alias) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@launch setStep(EnrollmentStep.Failed(EnrollmentError.KeyStoreFailed(e.describe())))
                }
                keyCreated = true

                setStep(EnrollmentStep.Registering(RegistrationProgress.SENDING_REQUEST))
                val issued = try {
                    val csr = withContext(workDispatcher) {
                        CertificateRequest.build(deviceName, key.publicKeyInfo, key::sign)
                    }
                    val (address, issued) = reachServer(current.serverAddress) {
                        enrollDevice(it, current.code, deviceName, csr, key.protection)
                    }
                    endpoint = graphQlEndpoint(address)
                    issued
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@launch setStep(EnrollmentStep.Failed(e.toEnrollmentError()))
                }

                setStep(EnrollmentStep.Registering(RegistrationProgress.SAVING_CERTIFICATE))
                try {
                    withContext(workDispatcher) { keyStore.saveCertificate(alias, issued.certificateDer) }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    return@launch setStep(EnrollmentStep.Failed(EnrollmentError.KeyStoreFailed(e.describe())))
                }

                setStep(EnrollmentStep.Registering(RegistrationProgress.TESTING_CONNECTION))
                var serverCa: String? = null
                val test = try {
                    createApolloClient(clientFactory.create(alias, trustedCas), endpoint).useClient {
                        checkConnection(it).status.also { status ->
                            // Each server has its own CA: kept, so the server is trusted by it from now on.
                            if (status == ConnectionStatus.Connected) serverCa = askServerCa(it)
                        }
                    }
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    ConnectionStatus.Error(e.describe())
                }
                if (test != ConnectionStatus.Connected) {
                    return@launch setStep(EnrollmentStep.Failed(EnrollmentError.ConnectionTestFailed(test.describe())))
                }

                val previous = settings.identityAlias
                settings.certificateExpiresAt = runCatching { Instant.parse(issued.expiresAt).toEpochMilliseconds() }.getOrNull()
                onEnrolled(endpoint, alias, pinned?.let { discoveryOf(it, endpoint) }, serverCa)
                discoveryBefore = null
                succeeded = true
                if (previous != null && previous != alias && previous.startsWith(IDENTITY_ALIAS_PREFIX)) {
                    withContext(workDispatcher) { runCatching { keyStore.delete(previous) } }
                }
                setStep(
                    EnrollmentStep.Enrolled(
                        user = issued.user,
                        deviceName = issued.deviceName,
                        serverAddress = serverAddress(endpoint),
                        keyProtection = key.protection,
                        validUntil = issued.expiresAt.substringBefore('T'),
                    )
                )
            } finally {
                // The key is useless without the identity being in use; don't leave it behind.
                if (keyCreated && !succeeded) {
                    withContext(NonCancellable + workDispatcher) { runCatching { keyStore.delete(alias) } }
                }
            }
        }
    }

    /** Leaves the current step; cancels a lookup or registration in progress. */
    fun back() {
        job?.cancel()
        _state.update {
            when (it.step) {
                is EnrollmentStep.EnterCode -> it.copy(step = EnrollmentStep.Welcome).also { forgetPin() }
                is EnrollmentStep.Confirm, is EnrollmentStep.Registering -> it.copy(step = EnrollmentStep.EnterCode())
                is EnrollmentStep.Failed -> it.copy(code = "", step = EnrollmentStep.EnterCode())
                EnrollmentStep.Welcome, is EnrollmentStep.Enrolled -> it
            }
        }
    }

    /** Back to the start, e.g. to enroll again later. */
    /**
     * Fills in the code (and the server, when the link has one) from an enrollment link the server printed as a QR
     * code, and checks it: the wizard opens on Confirm, or on the code screen with what was wrong. Ignored while a
     * device is being registered.
     */
    fun openLink(link: EnrollmentLink) {
        if (_state.value.step is EnrollmentStep.Registering) return
        job?.cancel()
        forgetPin()
        pinned = link.takeIf { it.fingerprint != null }
        _state.update {
            it.copy(
                serverAddress = link.server ?: link.addresses.firstOrNull() ?: it.serverAddress,
                code = cleanCode(link.code),
                step = EnrollmentStep.EnterCode(),
                serverId = pinned?.fingerprint?.let(ServerId::format),
            )
        }
        checkCode()
    }

    /** What a QR code scan read: an enrollment link (see [openLink]), or else just the code. */
    fun openScanned(text: String) {
        parseEnrollmentLink(text)?.let(::openLink) ?: updateCode(text)
    }

    /**
     * What the Paste button read from the clipboard: an enrollment link anywhere in it (as a chat app sends it, with
     * words around it) opens it as a scan does, with the server's key pinned when the link has one; else a code in it
     * fills in the code. [PasteResult.NOTHING] when it has neither, and nothing changes.
     */
    fun openPasted(text: String): PasteResult {
        findEnrollmentLink(text)?.let {
            openLink(it)
            return PasteResult.LINK
        }
        if (_state.value.step !is EnrollmentStep.EnterCode) return PasteResult.NOTHING
        val code = PASTED_CODE.find(text)?.value ?: return PasteResult.NOTHING
        updateCode(code)
        return PasteResult.CODE
    }

    fun reset() {
        job?.cancel()
        forgetPin()
        _state.update { it.copy(code = "", serverAddress = serverAddress(settings.serverUrl), step = EnrollmentStep.Welcome) }
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (EnrollmentState) -> Unit): ObservationHandle {
        val observation = scope.launch { state.collect(onChange) }
        return ObservationHandle { observation.cancel() }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun <T> anonymousApollo(serverAddress: String, block: suspend (com.apollographql.apollo.ApolloClient) -> T): T {
        val endpoint = graphQlEndpoint(serverAddress)
        val httpClient = try {
            clientFactory.create(null, trustedCas)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            throw EnrollmentTransportException(e.describe())
        }
        return createApolloClient(httpClient, endpoint).useClient { block(it) }
    }

    /**
     * Runs [block] on the anonymous client at [address], pinning the link's key when it has one. When [address] doesn't
     * answer at all and the link said who the server is, the link's other addresses are tried (400 ms apart, the first
     * to answer wins), then the DHT under its key, and [block] runs where the server answered. Gives that address too.
     */
    private suspend fun <T> reachServer(address: String, block: suspend (com.apollographql.apollo.ApolloClient) -> T): Pair<String, T> {
        pinned?.let { pinFor(it, address) }
        try {
            return address to anonymousApollo(address, block)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            // No answer at all (Apollo's NoDataException around its network error), not a refusal or an HTTP error.
            val network = generateSequence<Throwable>(e) { it.cause?.takeIf { cause -> cause !== it } }.take(10).any { it is ApolloNetworkException }
            val link = pinned?.takeIf { network } ?: throw e
            val found = findElsewhere(link, KeptDiscovery.originOf(graphQlEndpoint(address))) ?: throw e
            pinFor(link, found)
            return found to anonymousApollo(found, block)
        }
    }

    private suspend fun findElsewhere(link: EnrollmentLink, failed: String?): String? {
        val fingerprint = link.fingerprint ?: return null
        val http = try {
            clientFactory.create(null, trustedCas)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return null
        }
        try {
            val probe: suspend (String) -> Boolean = { http.answersAt(it) }
            firstToAnswer(link.addresses.filter { it != failed }, 400.milliseconds, probe)?.let { return it }
            val key = link.key ?: return null
            val record = try {
                dhtLookup(key, fingerprint)
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                null
            } ?: return null
            val found = record.addresses.mapNotNull(KeptDiscovery::originOf).distinct().filter { it != failed }
            return firstToAnswer(found, 400.milliseconds, probe)
        } finally {
            http.close()
        }
    }

    /** Puts the link's server in the settings, as at [address], so the mTLS clients pin its key. */
    private fun pinFor(link: EnrollmentLink, address: String) {
        if (discoveryBefore == null) discoveryBefore = SavedDiscovery(settings.serverDiscovery)
        settings.serverDiscovery = discoveryOf(link, graphQlEndpoint(address))
    }

    /** Puts back what the settings had before [pinFor], when the user leaves without enrolling. */
    private fun forgetPin() {
        discoveryBefore?.let { settings.serverDiscovery = it.value }
        discoveryBefore = null
        pinned = null
        _state.update { it.copy(serverId = null) }
    }

    private fun discoveryOf(link: EnrollmentLink, endpoint: String): KeptDiscovery {
        val fingerprint = link.fingerprint!!
        return KeptDiscovery.merge(
            old = null,
            workingUrl = endpoint,
            fingerprint = fingerprint,
            id = ServerId.format(fingerprint).orEmpty(),
            key = link.key,
            addresses = link.addresses,
        )
    }

    private fun minutesUntil(timestamp: String): Int =
        runCatching { ((Instant.parse(timestamp) - clock.now()).inWholeSeconds + 59) / 60 }
            .getOrDefault(0L).toInt().coerceAtLeast(0)

    private fun setStep(step: EnrollmentStep) {
        _state.update { it.copy(step = step) }
    }

    private fun clearedCodeError(step: EnrollmentStep): EnrollmentStep =
        if (step is EnrollmentStep.EnterCode && step.error != null) step.copy(error = null) else step

    companion object {
        /** Aliases of keys created by enrollment, as opposed to identities imported by the user. */
        const val IDENTITY_ALIAS_PREFIX = "journal-device-"
    }
}

private const val CODE_LENGTH = 8
private val PASTED_CODE = Regex("""(?<![A-Za-z0-9])[A-Za-z0-9]{4}-?[A-Za-z0-9]{4}(?![A-Za-z0-9])""")

/**
 * Keeps what the user typed to letters, digits and dashes, at most [CODE_LENGTH] letters and digits.
 * Pasted text (e.g. a terminal line with "Code  K7QM-4XPD" or extra lines) is reduced to the first
 * code-shaped word in it. The server ignores case and dashes.
 */
/**
 * What an enrollment QR code holds (see `enroll-code` in journal-server-go docs/authentication.md). [server] is null when
 * it has none. Since Nov 7, 2026 ("Enrolling by id") also who the server is and where: its key's [fingerprint] (64 hex
 * digits; the app pins it from the first connection, so an address the certificate doesn't list works), its DHT [key]
 * and its [addresses]; null and empty from an older server.
 */
data class EnrollmentLink(
    val code: String,
    val server: String?,
    val fingerprint: String? = null,
    val key: String? = null,
    val addresses: List<String> = emptyList(),
)

/**
 * `journal://enroll?code=K7QM-4XPD&server=https%3A%2F%2Fhost%3A56398&fingerprint=…&key=…&address=…&address=…`; null
 * for any other link. A fingerprint or key that isn't 64 hex digits is left out, and addresses that aren't https.
 */
fun parseEnrollmentLink(link: String): EnrollmentLink? {
    val url = try {
        Url(link.trim())
    } catch (e: Exception) {
        return null
    }
    if (url.protocol.name != "journal" || url.host != "enroll") return null
    val code = url.parameters["code"]?.takeIf { it.isNotBlank() } ?: return null
    fun hex64(name: String) = url.parameters[name]?.trim()?.lowercase()?.takeIf { v -> v.length == 64 && v.all { it in "0123456789abcdef" } }
    return EnrollmentLink(
        code = code,
        server = url.parameters["server"]?.takeIf { it.isNotBlank() },
        fingerprint = hex64("fingerprint"),
        key = hex64("key"),
        addresses = url.parameters.getAll("address").orEmpty().mapNotNull(KeptDiscovery::originOf).distinct(),
    )
}

/** What [EnrollmentController.openPasted] found in the clipboard. */
enum class PasteResult { LINK, CODE, NOTHING }

private val LINK_IN_TEXT = Regex("""journal://enroll\?[^\s<>"']+""")

/** The first `journal://enroll?…` link in [text] that [parseEnrollmentLink] reads, without punctuation a sentence put after it. */
fun findEnrollmentLink(text: String): EnrollmentLink? =
    LINK_IN_TEXT.findAll(text).firstNotNullOfOrNull { parseEnrollmentLink(it.value.trimEnd('.', ',', ';', ')', ']', '!', '?')) }

internal fun cleanCode(input: String): String {
    val pasted = input.any(Char::isWhitespace) || input.count(Char::isLetterOrDigit) > CODE_LENGTH
    val candidate = if (pasted) PASTED_CODE.find(input)?.value ?: input else input
    var letters = 0
    return buildString {
        for (c in candidate.uppercase()) {
            when {
                c == '-' && letters in 1 until CODE_LENGTH -> append(c)
                c in 'A'..'Z' || c in '0'..'9' -> if (letters < CODE_LENGTH) append(c).also { letters++ }
            }
        }
    }
}

private fun Exception.toEnrollmentError(): EnrollmentError = when (this) {
    is EnrollmentRejectedException -> when (reason) {
        EnrollmentRejectedReason.INVALID_CODE -> EnrollmentError.InvalidCode
        EnrollmentRejectedReason.RATE_LIMITED -> EnrollmentError.RateLimited
        else -> EnrollmentError.RequestRejected
    }
    else -> EnrollmentError.Unreachable(describe())
}

private fun ConnectionStatus.describe(): String = when (this) {
    is ConnectionStatus.Failed -> "HTTP $httpCode"
    is ConnectionStatus.Error -> message
    else -> toString()
}
