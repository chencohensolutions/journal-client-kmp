package com.chencohensolution.journal.backup

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.createApolloClient
import com.chencohensolution.journal.network.describe
import com.chencohensolution.journal.network.useClient
import com.chencohensolution.journal.settings.JournalSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.cancellation.CancellationException

/** Why backup isn't running although it is on. */
enum class WaitReason {
    /** Connected to a Wi-Fi network that isn't on the list. */
    WIFI_NETWORK_NOT_ALLOWED,

    /** Networks are listed, but the network's name can't be read without location permission. */
    NEEDS_LOCATION,

    /** The device has no certificate, or the server refused it: register again. */
    NOT_REGISTERED,

    /** The app may not read the photo library. */
    NO_PHOTO_ACCESS,
}

sealed interface BackupActivity {
    /** Nothing to do, or waiting for the next check. */
    data object Idle : BackupActivity
    data class Running(val progress: BackupProgress) : BackupActivity
    data class Waiting(val reason: WaitReason) : BackupActivity

    /** The last run stopped on an error (offline, server down); it is tried again later. */
    data class Stopped(val message: String) : BackupActivity
}

data class BackupState(
    val settings: BackupSettings = BackupSettings(),
    val activity: BackupActivity = BackupActivity.Idle,
    /** The ledger's view of the photos read so far, by device id. */
    internal val entries: Map<String, LedgerEntry> = emptyMap(),
    /** Files the current run still has to send, by device id. */
    internal val queued: Set<String> = emptySet(),
) {
    /** Where [item] is in the backup process. */
    fun status(item: MediaItem): BackupStatus {
        val running = (activity as? BackupActivity.Running)?.progress
        if (running?.currentId == item.id) return BackupStatus.Uploading(running.currentFraction)
        val entry = entries[item.id]
        return when {
            entry != null && entry.uploaded && entry.matches(item) -> BackupStatus.BackedUp
            item.id in queued -> BackupStatus.Queued
            entry?.rejected != null && entry.matches(item) -> BackupStatus.Failed(entry.rejected)
            else -> BackupStatus.NotBackedUp
        }
    }

    /** The sources whose photos count as "not backed up"; everything when backup is off. */
    val countedSources: Set<MediaSource>? get() = settings.sources.takeIf { settings.enabled }
}

/** Outcome of [BackupController.runOnce], for the platform's scheduler. */
sealed interface RunOutcome {
    data class Done(val result: BackupRunResult) : RunOutcome

    /** Off, or nothing it can do now (see [BackupState.activity]); don't retry soon. */
    data object Skipped : RunOutcome

    /** Network or server trouble; retry later. */
    data class Retry(val message: String) : RunOutcome
}

/**
 * Backup of the device's photos to the server: the Backup settings, the state the screens show (per-photo status,
 * progress), and one run at a time of [BackupEngine], started by the platform's background scheduler.
 */
class BackupController internal constructor(
    private val settings: JournalSettings,
    private val ledger: UploadLedger,
    private val library: MediaLibrary,
    private val files: MediaFiles,
    private val clientFactory: MtlsHttpClientFactory,
    private val trustedCas: List<ByteArray>,
    private val locate: suspend (MediaItem) -> GeoPoint? = { null },
    /** Called when the settings change, to reschedule background runs. */
    private val onSettingsChanged: (BackupSettings) -> Unit = {},
    /** Asks the platform to run now (in the background, with its progress notification). */
    private val onBackUpNow: (BackupSettings) -> Unit = {},
    dispatcher: CoroutineDispatcher = Dispatchers.Main,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val runMutex = Mutex()

    private val _state = MutableStateFlow(BackupState(settings = settings.backup))
    val state: StateFlow<BackupState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift (generic StateFlow values are erased to `Any`). */
    val currentState: BackupState get() = _state.value

    init {
        scope.launch { reloadLedger() }
    }

    fun setEnabled(enabled: Boolean) {
        changeSettings { it.copy(enabled = enabled) }
        if (enabled) onBackUpNow(_state.value.settings)
    }

    fun toggleSource(source: MediaSource) = changeSettings {
        it.copy(sources = if (source in it.sources) it.sources - source else it.sources + source)
    }

    fun setWifiOnly(wifiOnly: Boolean) = changeSettings { it.copy(wifiOnly = wifiOnly) }

    fun setChargingOnly(chargingOnly: Boolean) = changeSettings { it.copy(chargingOnly = chargingOnly) }

    fun setOrder(order: UploadOrder) = changeSettings { it.copy(order = order) }

    /** Only these networks from now on; blank names are ignored. */
    fun addWifiNetwork(name: String) {
        val trimmed = name.trim()
        if (trimmed.isNotEmpty()) changeSettings { it.copy(wifiNetworks = it.wifiNetworks + trimmed) }
    }

    fun removeWifiNetwork(name: String) = changeSettings { it.copy(wifiNetworks = it.wifiNetworks - name) }

    /** "Back up now": runs in the background as soon as the conditions allow. */
    fun backUpNow() {
        if (_state.value.settings.enabled) onBackUpNow(_state.value.settings)
    }

    /**
     * The server answered that it doesn't have [item], though this device uploaded it (another server or data folder
     * since): it counts as not backed up again, and a run starts to send it.
     */
    fun uploadAgain(item: MediaItem) {
        scope.launch {
            ledger.entry(item.id)?.takeIf { it.uploaded }?.let { ledger.record(it.copy(uploaded = false)) }
            reloadLedger()
            backUpNow()
        }
    }

    /** Set by the platform when it can't run now; cleared by the next run. */
    fun reportWaiting(reason: WaitReason) {
        _state.update { it.copy(activity = BackupActivity.Waiting(reason)) }
    }

    /**
     * One run, called by the platform's background worker. Runs never overlap: a second call waits for the first and
     * then finds nothing left to do. [onProgress] gets each step, for the progress notification.
     */
    suspend fun runOnce(onProgress: (BackupProgress) -> Unit = {}): RunOutcome = runMutex.withLock {
        val current = settings.backup
        _state.update { it.copy(settings = current) }
        if (!current.enabled) {
            _state.update { it.copy(activity = BackupActivity.Idle) }
            return RunOutcome.Skipped
        }
        val alias = settings.identityAlias
        if (alias == null) {
            reportWaiting(WaitReason.NOT_REGISTERED)
            return RunOutcome.Skipped
        }
        if (library.access() == PhotoAccess.DENIED || library.access() == PhotoAccess.NOT_DETERMINED) {
            reportWaiting(WaitReason.NO_PHOTO_ACCESS)
            return RunOutcome.Skipped
        }
        val url = settings.serverUrl
        try {
            val items = library.loadItems()
            val queue = backupQueue(items, current).filterNot { ledger.isUploaded(it) }.map { it.id }.toSet()
            _state.update { it.copy(queued = queue) }
            val http = clientFactory.create(alias, trustedCas)
            var done = 0
            val result = createApolloClient(http, url).useClient { apollo ->
                BackupEngine(files, ledger, ServerBackupApi(apollo, http, url), locate).run(items, current) { progress ->
                    _state.update { it.copy(activity = BackupActivity.Running(progress)) }
                    // A file finished: show it as backed up without waiting for the whole run.
                    if (progress.done != done) {
                        done = progress.done
                        scope.launch { reloadLedger() }
                    }
                    onProgress(progress)
                }
            }
            _state.update { it.copy(activity = BackupActivity.Idle) }
            RunOutcome.Done(result)
        } catch (e: CancellationException) {
            _state.update { it.copy(activity = BackupActivity.Idle) }
            throw e
        } catch (e: BackupServerException) {
            if (e.unauthorized) reportWaiting(WaitReason.NOT_REGISTERED) else _state.update { it.copy(activity = BackupActivity.Stopped(e.message.orEmpty())) }
            if (e.unauthorized) RunOutcome.Skipped else RunOutcome.Retry(e.message.orEmpty())
        } catch (e: Exception) {
            _state.update { it.copy(activity = BackupActivity.Stopped(e.describe())) }
            RunOutcome.Retry(e.describe())
        } finally {
            _state.update { it.copy(queued = emptySet()) }
            reloadLedger()
        }
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (BackupState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() {
        scope.cancel()
    }

    private suspend fun reloadLedger() {
        val entries = ledger.all()
        _state.update { it.copy(entries = entries) }
    }

    private fun changeSettings(transform: (BackupSettings) -> BackupSettings) {
        val changed = transform(_state.value.settings)
        settings.backup = changed
        _state.update { it.copy(settings = changed) }
        onSettingsChanged(changed)
    }
}
