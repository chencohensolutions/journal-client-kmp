package com.chencohensolution.journal.backup

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.BackupStatus
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaLibrary
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.network.MtlsHttpClientFactory
import com.chencohensolution.journal.network.createApolloClient
import com.chencohensolution.journal.discovery.ServerFinder
import com.chencohensolution.journal.discovery.findServerOnNetworkErrors
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
import com.chencohensolution.journal.storage.ServerCheck
import kotlin.time.Clock
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** Why backup isn't running although it is on. */
enum class WaitReason {
    /** Connected to a Wi-Fi network that isn't on the list. */
    WIFI_NETWORK_NOT_ALLOWED,

    /** Networks are listed, but the network's name can't be read without location permission. */
    NEEDS_LOCATION,

    /** The device has no certificate, or the server refused it: register again. */
    NOT_REGISTERED,

    /** The app may not read the photo library, and no sound folder is backed up. */
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
    /** Sound folders the app could no longer read on the last run, by tree URI: picked again to back them up. */
    val lostFolders: Set<String> = emptySet(),
    /** Files in each sound folder, by tree URI, as of the last run or when it was added. */
    val soundCounts: Map<String, Int> = emptyMap(),
) {
    /** Where [item] is in the backup process. */
    fun status(item: MediaItem): BackupStatus {
        val running = (activity as? BackupActivity.Running)?.progress
        running?.uploading?.get(item.id)?.let { return BackupStatus.Uploading(it) }
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

    /**
     * Per kind of sound, the files of the sound folders backup takes that aren't on the server yet: each folder's files
     * as of its last run ([soundCounts]) less those the ledger holds as uploaded. For Show in the app's Sound lines.
     */
    fun soundsNotBackedUp(): Map<AudioKind, Int> {
        val folders = settings.soundFolders.filter { it.enabled }
        if (folders.isEmpty()) return emptyMap()
        val uploaded = entries.values.filter { it.uploaded }.groupingBy { SoundIds.treeOf(it.id) }.eachCount()
        return folders.groupBy { it.kind }
            .mapValues { (_, inKind) -> inKind.sumOf { ((soundCounts[it.treeUri] ?: 0) - (uploaded[it.treeUri] ?: 0)).coerceAtLeast(0) } }
            .filterValues { it > 0 }
    }
}

/** How often progress reaches the screens while bytes move. */
private val PROGRESS_EVERY = 500.milliseconds

/** Bytes per second over the last [WINDOW]: bytes sent, sampled as they go. */
internal class TransferRate(private val now: () -> TimeMark = TimeSource.Monotonic::markNow) {
    private val samples = ArrayDeque<Pair<TimeMark, Long>>()

    /** Adds the total sent so far; the rate, or null while there is too little to tell. */
    fun add(sent: Long): Long? {
        val mark = now()
        samples.addLast(mark to sent)
        while (samples.size > 2 && samples.first().first.elapsedNow() > WINDOW) samples.removeFirst()
        val (firstMark, firstSent) = samples.first()
        val seconds = firstMark.elapsedNow().inWholeMilliseconds / 1000.0
        if (seconds < MIN_SECONDS) return null
        return ((sent - firstSent) / seconds).toLong()
    }

    private companion object {
        val WINDOW = 5.seconds
        const val MIN_SECONDS = 0.5
    }
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
    /** How pieces travel; by default with the app's own client. */
    private val pieceSender: PieceSender? = null,
    /** The sound folders the user picked; null on a platform without them. */
    private val soundFolders: SoundFolders? = null,
    /** The server's check of a file, before a WAV kept as FLAC leaves the phone. */
    private val check: ServerCheck? = null,
    private val clock: Clock = Clock.System,
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
        // The folders' counts and whether they can still be read, for the pills, before the first run.
        scope.launch { runCatching { soundFiles(settings.backup.soundFolders) } }
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

    /**
     * Backs up [folder], which the user just picked (again, for a folder whose access was lost). Its files go on the
     * next run, which starts now.
     */
    fun addSoundFolder(folder: SoundFolder, look: FolderLook? = null) {
        changeSettings { current -> current.copy(soundFolders = current.soundFolders.filterNot { it.treeUri == folder.treeUri } + folder) }
        _state.update { state ->
            state.copy(
                lostFolders = state.lostFolders - folder.treeUri,
                soundCounts = look?.let { state.soundCounts + (folder.treeUri to it.files) } ?: state.soundCounts,
            )
        }
        backUpNow()
    }

    fun setSoundFolderEnabled(treeUri: String, enabled: Boolean) = changeSettings { current ->
        current.copy(soundFolders = current.soundFolders.map { if (it.treeUri == treeUri) it.copy(enabled = enabled) else it })
    }

    /** Stops backing up a folder and forgets it; the server keeps what was sent. */
    fun removeSoundFolder(treeUri: String) = changeSettings { current ->
        current.copy(soundFolders = current.soundFolders.filterNot { it.treeUri == treeUri })
    }

    fun setKeepOnPhone(keep: KeepOnPhone) = changeSettings { it.copy(keepOnPhone = keep) }
    fun setKeepJpegXlOnPhone(keep: Boolean) = changeSettings { it.copy(keepJpegXlOnPhone = keep) }

    /** What [folder] holds, for the add dialog; null when it can't be read. */
    suspend fun look(folder: SoundFolder): FolderLook? = soundFolders?.look(folder)

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

    /**
     * The ledger was changed elsewhere ("Free up space" found files changed on the phone or missing on the server): the
     * statuses follow, and a run starts to send those files again.
     */
    fun ledgerChanged() {
        scope.launch {
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
        val photos = library.access().let { it != PhotoAccess.DENIED && it != PhotoAccess.NOT_DETERMINED }
        val folders = current.soundFolders.filter { it.enabled }
        if (!photos && (folders.isEmpty() || soundFolders == null)) {
            reportWaiting(WaitReason.NO_PHOTO_ACCESS)
            return RunOutcome.Skipped
        }
        val url = settings.serverUrl
        try {
            val items = (if (photos) library.loadItems() else emptyList()) + soundFiles(folders)
            val queue = backupQueue(items, current).filterNot { ledger.isUploaded(it) }.map { it.id }.toSet()
            _state.update { it.copy(queued = queue) }
            val http = clientFactory.create(alias, trustedCas).findServerOnNetworkErrors(ServerFinder(settings))
            var done = 0
            val rate = TransferRate()
            var shownFile: String? = null
            var shownAt: TimeMark? = null
            val result = createApolloClient(http, url).useClient { apollo ->
                val packJpegs = settings.serverFeatures?.keepsJpegAsJxl == true
                BackupEngine(files, ledger, ServerBackupApi(apollo, url, pieceSender ?: KtorPieceSender(http)), locate, check, packJpegs).run(items, current) { counted ->
                    // Bytes are reported many times a second; the screens redraw from this state, so it changes at
                    // most every PROGRESS_EVERY, except when a file starts or finishes.
                    val newFile = counted.currentId != shownFile || counted.done != done
                    if (!newFile && shownAt != null && shownAt!!.elapsedNow() < PROGRESS_EVERY) return@run
                    shownFile = counted.currentId
                    shownAt = TimeSource.Monotonic.markNow()
                    val progress = counted.copy(bytesPerSecond = rate.add(counted.bytesSent))
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
            files.runEnded()
            reloadLedger()
        }
    }

    /** [runOnce] for Swift, which can't cancel a Kotlin suspend call: cancelling the handle stops the run. */
    fun launchRun(onDone: (RunOutcome?) -> Unit): ObservationHandle {
        val job = scope.launch {
            val outcome = try {
                runOnce()
            } catch (e: CancellationException) {
                null
            }
            onDone(outcome)
        }
        return ObservationHandle { job.cancel() }
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (BackupState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    fun close() {
        scope.cancel()
    }

    /**
     * The finished files of the backed-up sound folders; a folder the app can't read any more is marked lost (its pill
     * says so) and skipped.
     */
    private suspend fun soundFiles(folders: List<SoundFolder>): List<MediaItem> {
        val access = soundFolders ?: return emptyList()
        val lost = mutableSetOf<String>()
        val counts = mutableMapOf<String, Int>()
        val now = clock.now().toEpochMilliseconds()
        val items = folders.flatMap { folder ->
            val listed = access.list(folder)
            if (listed == null) lost += folder.treeUri else counts[folder.treeUri] = listed.size
            finished(listed.orEmpty(), now)
        }
        _state.update { it.copy(lostFolders = lost, soundCounts = it.soundCounts + counts) }
        return items
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
