package com.chencohensolution.journal.connection

import com.chencohensolution.journal.network.ConnectionResult
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.createApolloClient
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.network.checkConnection
import com.chencohensolution.journal.network.useClient
import com.chencohensolution.journal.settings.JournalSettings
import com.chencohensolution.journal.tls.SERVER_CA_PEM
import com.chencohensolution.journal.tls.certificateCommonName
import com.chencohensolution.journal.tls.decodePemCertificates
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

sealed interface ConnectionStatus {
    data object NotConnected : ConnectionStatus
    data object MissingServerUrl : ConnectionStatus
    data object MissingIdentity : ConnectionStatus
    data class Connecting(val startedAt: String, val trustedCaNames: List<String>) : ConnectionStatus
    data object Connected : ConnectionStatus
    data class Failed(val httpCode: Int) : ConnectionStatus
    data class Error(val message: String) : ConnectionStatus
}

/** What the library screens show about the server: nothing, an "Offline" pill, or a register-again banner. */
enum class ServerHealth {
    /** Connected, or still checking: show nothing. */
    OK,

    /** Network error, timeout or server error: show the quiet "Offline" pill. */
    OFFLINE,

    /** The server refused this device's certificate (401), or the device has no identity: needs registering again. */
    REJECTED,
}

data class JournalState(
    val serverUrl: String,
    val identityAlias: String?,
    val status: ConnectionStatus,
    /** A GraphQL error the server answered the connection check with, while the connection itself worked. */
    val serverError: String? = null,
) {
    val health: ServerHealth = when (status) {
        ConnectionStatus.NotConnected, is ConnectionStatus.Connecting, ConnectionStatus.Connected -> ServerHealth.OK
        is ConnectionStatus.Failed -> if (status.httpCode == 401) ServerHealth.REJECTED else ServerHealth.OFFLINE
        is ConnectionStatus.Error -> ServerHealth.OFFLINE
        ConnectionStatus.MissingServerUrl, ConnectionStatus.MissingIdentity -> ServerHealth.REJECTED
    }
}

/** Handle returned by [JournalController.observe]; call [cancel] to stop receiving updates. */
fun interface ObservationHandle {
    fun cancel()
}

/**
 * Shared screen logic: holds the server URL and selected identity, and checks the connection over mTLS (a small
 * authenticated query). Platform UIs render [state] and call the action methods; they never touch networking.
 */
class JournalController internal constructor(
    private val settings: JournalSettings,
    private val clientFactory: MtlsHttpClientFactory,
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
    private val trustedCas: List<ByteArray> = decodePemCertificates(SERVER_CA_PEM),
    private val now: () -> String = ::currentTimestamp,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var connectJob: Job? = null

    private val _state = MutableStateFlow(
        JournalState(
            serverUrl = settings.serverUrl,
            identityAlias = settings.identityAlias,
            status = ConnectionStatus.NotConnected,
        )
    )
    val state: StateFlow<JournalState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: JournalState get() = _state.value

    fun updateServerUrl(url: String) {
        _state.update { it.copy(serverUrl = url) }
    }

    fun selectIdentity(alias: String) {
        settings.identityAlias = alias
        _state.update { it.copy(identityAlias = alias) }
    }

    /** Switches to an identity created by enrollment; called by EnrollmentController. */
    internal fun useEnrolledIdentity(serverUrl: String, alias: String) {
        connectJob?.cancel()
        settings.serverUrl = serverUrl
        settings.identityAlias = alias
        _state.update {
            JournalState(serverUrl = serverUrl, identityAlias = alias, status = ConnectionStatus.NotConnected)
        }
    }

    fun connect() {
        val url = _state.value.serverUrl.trim()
        if (url.isEmpty()) {
            setStatus(ConnectionStatus.MissingServerUrl)
            return
        }
        settings.serverUrl = url

        val alias = _state.value.identityAlias
        if (alias == null) {
            setStatus(ConnectionStatus.MissingIdentity)
            return
        }

        connectJob?.cancel()
        setStatus(ConnectionStatus.Connecting(now(), trustedCas.map(::certificateCommonName)))
        connectJob = scope.launch {
            val result = try {
                createApolloClient(clientFactory.create(alias, trustedCas), url).useClient { checkConnection(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                ConnectionResult(ConnectionStatus.Error(e.describe()))
            }
            _state.update { it.copy(status = result.status, serverError = result.serverError) }
        }
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (JournalState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() {
        scope.cancel()
    }

    private fun setStatus(status: ConnectionStatus) {
        _state.update { it.copy(status = status) }
    }
}

@OptIn(ExperimentalTime::class)
private fun currentTimestamp(): String = Clock.System.now().toString()
