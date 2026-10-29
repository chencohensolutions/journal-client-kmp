package com.chencohensolution.journal.enrollment

import com.chencohensolution.journal.connection.ConnectionStatus
import com.chencohensolution.journal.connection.ObservationHandle
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
    private val onEnrolled: (serverUrl: String, identityAlias: String) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val trustedCas: List<ByteArray> = decodePemCertificates(SERVER_CA_PEM),
    private val clock: Clock = Clock.System,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var job: Job? = null

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

    fun updateServerAddress(address: String) {
        _state.update { it.copy(serverAddress = address, step = clearedCodeError(it.step)) }
    }

    fun updateCode(code: String) {
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
                val preview = anonymousApollo(current.serverAddress) { previewEnrollment(it, current.code) }
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
        val endpoint = graphQlEndpoint(current.serverAddress)
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
                    anonymousApollo(current.serverAddress) { enrollDevice(it, current.code, deviceName, csr, key.protection) }
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
                val test = try {
                    createApolloClient(clientFactory.create(alias, trustedCas), endpoint).useClient { checkConnection(it) }.status
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                    ConnectionStatus.Error(e.describe())
                }
                if (test != ConnectionStatus.Connected) {
                    return@launch setStep(EnrollmentStep.Failed(EnrollmentError.ConnectionTestFailed(test.describe())))
                }

                val previous = settings.identityAlias
                settings.certificateExpiresAt = runCatching { Instant.parse(issued.expiresAt).toEpochMilliseconds() }.getOrNull()
                onEnrolled(endpoint, alias)
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
                is EnrollmentStep.EnterCode -> it.copy(step = EnrollmentStep.Welcome)
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
        _state.update {
            it.copy(serverAddress = link.server ?: it.serverAddress, code = cleanCode(link.code), step = EnrollmentStep.EnterCode())
        }
        checkCode()
    }

    /** What a QR code scan read: an enrollment link (see [openLink]), or else just the code. */
    fun openScanned(text: String) {
        parseEnrollmentLink(text)?.let(::openLink) ?: updateCode(text)
    }

    fun reset() {
        job?.cancel()
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
/** What an enrollment QR code holds (see `enroll-code` in journal-server-go). [server] is null when it has none. */
data class EnrollmentLink(val code: String, val server: String?)

/** `journal://enroll?code=K7QM-4XPD&server=https%3A%2F%2Fhost%3A56398`; null for any other link. */
fun parseEnrollmentLink(link: String): EnrollmentLink? {
    val url = try {
        Url(link.trim())
    } catch (e: Exception) {
        return null
    }
    if (url.protocol.name != "journal" || url.host != "enroll") return null
    val code = url.parameters["code"]?.takeIf { it.isNotBlank() } ?: return null
    return EnrollmentLink(code, url.parameters["server"]?.takeIf { it.isNotBlank() })
}

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
