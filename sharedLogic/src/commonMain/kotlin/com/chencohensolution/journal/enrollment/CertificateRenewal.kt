package com.chencohensolution.journal.enrollment

import com.apollographql.apollo.ApolloClient
import com.chencohensolution.journal.connection.ConnectionStatus
import com.chencohensolution.journal.graphql.RenewCertificateMutation
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.checkConnection
import com.chencohensolution.journal.network.createApolloClient
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.network.useClient
import com.chencohensolution.journal.server.ServerHttp
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.tls.SERVER_CA_PEM
import com.chencohensolution.journal.tls.decodePemCertificates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import com.chencohensolution.journal.graphql.type.KeyProtection as GraphQlKeyProtection

// A device certificate lasts a year (journal-server-go docs/authentication.md). In its last 30 days the app renews
// it: a new key, a certificate for it asked with the current one (renewCertificate), a connection test with it, and
// then the switch, as enrollment switches. The server refuses the old certificate once the new one is used, so a
// renewal that fails part way leaves the device as it was; it is tried again at the next start.

/** How a renewal went. */
sealed interface RenewalResult {
    /** More than [RENEW_BEFORE_DAYS] days left, or the end isn't known. */
    data object NotDue : RenewalResult

    /** The device uses a new certificate, valid until [validUntilMillis]. */
    data class Renewed(val validUntilMillis: Long) : RenewalResult

    /** It couldn't be renewed now (offline, the server refused); the old certificate goes on. */
    data class Failed(val message: String) : RenewalResult
}

/** The server's part of a renewal: the new certificate, asked as the device's current identity. */
internal fun interface RenewApi {
    suspend fun renew(csrDer: ByteArray, protection: KeyProtection): IssuedCertificate
}

/** A renewal as the device's current identity, over the app's one client. */
internal class HttpRenewApi(private val http: ServerHttp) : RenewApi {
    override suspend fun renew(csrDer: ByteArray, protection: KeyProtection): IssuedCertificate =
        http.graphql { renewCertificate(it, csrDer, protection) } ?: throw EnrollmentTransportException("This device isn't registered")
}

internal suspend fun renewCertificate(apollo: ApolloClient, csrDer: ByteArray, protection: KeyProtection): IssuedCertificate {
    val response = apollo.mutation(RenewCertificateMutation(Base64.encode(csrDer), GraphQlKeyProtection.safeValueOf(protection.name))).execute()
    response.exception?.let { throw EnrollmentTransportException(it.describe()) }
    response.errors?.firstOrNull()?.let { throw EnrollmentTransportException(it.message) }
    val result = response.data?.renewCertificate ?: throw EnrollmentTransportException("Empty response")
    result.onEnrollmentRejected?.let { throw EnrollmentRejectedException(it.reason) }
    val renewed = result.onEnrolledDevice ?: throw EnrollmentTransportException("Unexpected answer ${result.__typename}")
    return IssuedCertificate(renewed.user, renewed.deviceName, Base64.decode(renewed.certificate), renewed.expiresAt)
}

/** Days before its end that a certificate is renewed. */
const val RENEW_BEFORE_DAYS = 30

@OptIn(ExperimentalTime::class)
class CertificateRenewal internal constructor(
    private val settings: JournalSettings,
    private val keyStore: DeviceKeyStore,
    private val api: RenewApi,
    /** Tests the new identity before the switch: true when the server answers with it. */
    private val test: suspend (alias: String) -> Boolean,
    /** Switches the app to the renewed identity (JournalController). */
    private val onRenewed: (alias: String) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val workDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: Clock = Clock.System,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutex = Mutex()

    /** Renews in the background when due; [onDone] gets how it went. */
    fun renewIfDue(onDone: (RenewalResult) -> Unit = {}) {
        scope.launch { onDone(renewNow()) }
    }

    /** When the certificate in use expires (epoch milliseconds); null when not known. */
    fun expiresAt(): Long? {
        settings.certificateExpiresAt?.let { return it }
        val alias = settings.identityAlias ?: return null
        return keyStore.certificateNotAfter(alias)?.also { settings.certificateExpiresAt = it }
    }

    internal suspend fun renewNow(): RenewalResult = mutex.withLock {
        val previous = settings.identityAlias ?: return RenewalResult.NotDue
        val end = withContext(workDispatcher) { expiresAt() } ?: return RenewalResult.NotDue
        if (end - clock.now().toEpochMilliseconds() > RENEW_BEFORE_DAYS.days.inWholeMilliseconds) return RenewalResult.NotDue

        val alias = EnrollmentController.IDENTITY_ALIAS_PREFIX + clock.now().toEpochMilliseconds()
        var succeeded = false
        try {
            val key = withContext(workDispatcher) { keyStore.generate(alias) }
            val csr = withContext(workDispatcher) { CertificateRequest.build("device", key.publicKeyInfo, key::sign) }
            val issued = api.renew(csr, key.protection)
            withContext(workDispatcher) { keyStore.saveCertificate(alias, issued.certificateDer) }
            if (!test(alias)) return RenewalResult.Failed("The renewed certificate didn't connect")
            val validUntil = Instant.parse(issued.expiresAt).toEpochMilliseconds()
            settings.certificateExpiresAt = validUntil
            onRenewed(alias)
            succeeded = true
            if (previous != alias && previous.startsWith(EnrollmentController.IDENTITY_ALIAS_PREFIX)) {
                withContext(workDispatcher) { runCatching { keyStore.delete(previous) } }
            }
            return RenewalResult.Renewed(validUntil)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return RenewalResult.Failed(e.message ?: e.toString())
        } finally {
            if (!succeeded) withContext(NonCancellable + workDispatcher) { runCatching { keyStore.delete(alias) } }
        }
    }

    fun close() = scope.cancel()

    companion object {
        /** Tests an identity the way enrollment does: the connection check, over a client of its own. */
        internal fun connectionTest(
            settings: JournalSettings,
            clientFactory: MtlsHttpClientFactory,
            trustedCas: List<ByteArray> = decodePemCertificates(SERVER_CA_PEM),
        ): suspend (String) -> Boolean = { alias ->
            runCatching {
                createApolloClient(clientFactory.create(alias, trustedCas), settings.serverUrl)
                    .useClient { checkConnection(it) }.status == ConnectionStatus.Connected
            }.getOrDefault(false)
        }
    }
}
