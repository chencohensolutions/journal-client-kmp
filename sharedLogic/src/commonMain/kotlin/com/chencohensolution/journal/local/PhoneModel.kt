package com.chencohensolution.journal.local

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okio.FileSystem
import okio.Path

/**
 * Where the phone gets the model files it runs by itself, not as part of an index (journal-server-go `phoneModels`,
 * served at `/models/phone/{name}` with Range): MediaPipe's photo embedder on Android.
 */
internal interface PhoneModelApi {
    /** The files the server offers; empty when it doesn't have them (or is still hashing them after a start). */
    suspend fun phoneModels(): List<ModelFileInfo>

    /** The same download as [LocalIndexApi.model]: from what [target] has (Range), sha256 checked. */
    suspend fun model(file: ModelFileInfo, fileSystem: FileSystem, target: Path, progress: (Long) -> Unit): Boolean
}

/** How a [PhoneModel] stands: Settings › Models on this phone shows it ([PhoneModels]). */
data class PhoneModelState(
    /** Downloaded whole and checked: the app uses it. */
    val here: Boolean = false,
    /** Its size: the file's when here, else the server's; null until the server was asked. */
    val bytes: Long? = null,
    /** While downloading: the bytes there so far. */
    val downloaded: Long? = null,
    /** False when the server answered without it (it doesn't have it yet); null until asked. */
    val offered: Boolean? = null,
    /** The last download broke or didn't match its sha256: Try again. */
    val failed: Boolean = false,
    /** The server couldn't be reached the last time it was asked. */
    val unreachable: Boolean = false,
    /** A download is due but waits for Wi-Fi (Only on Wi-Fi). */
    val waitingForWifi: Boolean = false,
) {
    val downloading: Boolean get() = downloaded != null
}

/**
 * One model file the phone downloads from the server instead of carrying it in the app: on first use ([fetch], from
 * the code that runs it) or from Settings ([download]). Kept as `<directory>/<name>`, with `<name>.ok` (its sha256)
 * beside it once downloaded whole and checked. A broken download goes on from where it broke next time; a file that
 * doesn't match its sha256 is deleted and downloaded again. One download at a time, shared by whoever asks; with
 * [wifiOnly] (On device search's Only on Wi-Fi, one setting for every download) it waits for Wi-Fi unless forced.
 *
 * [builtIn], when the app knows the file itself (its pinned source and sha256), is downloaded from that source even
 * when the server doesn't list it or can't be reached, and gives the server's entry its source when the server has
 * the same file without one.
 */
class PhoneModel internal constructor(
    private val name: String,
    private val api: PhoneModelApi,
    private val fileSystem: FileSystem,
    private val directory: Path,
    /** Only on Wi-Fi (the same setting On device search uses). */
    private val wifiOnly: () -> Boolean = { false },
    /** Whether the phone is on Wi-Fi (or another network that costs nothing). */
    private val onWifi: () -> Boolean = { true },
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val builtIn: ModelFileInfo? = null,
) {
    private val file = directory / name
    private val ok = directory / "$name.ok"
    private val lock = Mutex()
    private var running: Deferred<Path?>? = null

    private val _state = MutableStateFlow(
        if (path() != null) PhoneModelState(here = true, bytes = fileSystem.metadataOrNull(file)?.size, offered = true) else PhoneModelState(),
    )

    /** Whether it is here, its size, a download's progress. */
    val state: StateFlow<PhoneModelState> = _state.asStateFlow()

    /** The model's file when it is here whole; reads nothing from the server. */
    internal fun path(): Path? = if (fileSystem.exists(ok) && fileSystem.exists(file)) file else null

    /** Asks the server whether it has the model, and its size (Settings opened), when it isn't here. */
    fun check() {
        if (_state.value.here || _state.value.downloading) return
        scope.launch {
            val info = try {
                offer(api.phoneModels())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (builtIn == null) {
                    _state.update { it.copy(unreachable = true) }
                    return@launch
                }
                builtIn
            }
            _state.update { if (it.here || it.downloading) it else it.copy(offered = info != null, bytes = info?.bytes ?: it.bytes, unreachable = false) }
        }
    }

    /** Settings' Download ([force]: Download now, whatever the network). */
    fun download(force: Boolean = false) {
        scope.launch { fetch(force) }
    }

    /** Pause: stops the download; what came is kept, and the next one goes on from there. */
    fun pause() {
        running?.cancel()
    }

    /** Delete: the file goes; the app reads photos by dHash alone until it is downloaded again. */
    fun delete() {
        scope.launch {
            running?.cancel()
            lock.withLock {
                fileSystem.delete(ok)
                fileSystem.delete(file)
                _state.update { PhoneModelState(bytes = it.bytes, offered = it.offered) }
            }
        }
    }

    /**
     * The model's file, downloaded first if it isn't here. Null when it waits for Wi-Fi, the server doesn't offer it,
     * can't be reached or the download broke: the next call tries again. Callers asking at once share one download.
     */
    internal suspend fun fetch(force: Boolean = false): Path? {
        path()?.let { return it }
        if (!force && wifiOnly() && !onWifi()) {
            _state.update { it.copy(waitingForWifi = true) }
            return null
        }
        val download = lock.withLock {
            running?.takeIf { it.isActive } ?: scope.async { downloadNow() }.also { running = it }
        }
        return try {
            download.await()
        } catch (e: CancellationException) {
            // Paused (the download was cancelled), or the caller went away.
            null
        }
    }

    /**
     * What to download: the server's entry (with [builtIn]'s source when it is the same file and the server knows none),
     * else [builtIn]; null when neither has it.
     */
    private fun offer(offered: List<ModelFileInfo>): ModelFileInfo? {
        val served = offered.firstOrNull { it.name == name } ?: return builtIn
        val known = builtIn ?: return served
        return if (served.source == null && served.sha256.equals(known.sha256, ignoreCase = true)) served.copy(source = known.source) else served
    }

    private suspend fun downloadNow(): Path? = lock.withLock {
        path()?.let { return it }
        _state.update { it.copy(waitingForWifi = false, failed = false) }
        try {
            val info = try {
                offer(api.phoneModels())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (builtIn == null) {
                    _state.update { it.copy(unreachable = true, failed = true) }
                    return null
                }
                builtIn
            }
            if (info == null) {
                _state.update { it.copy(offered = false, unreachable = false) }
                return null
            }
            val have = withContext(Dispatchers.IO) { fileSystem.metadataOrNull(file)?.size } ?: 0
            _state.update { it.copy(offered = true, unreachable = false, bytes = info.bytes, downloaded = have) }
            val whole = withContext(Dispatchers.IO) {
                api.model(info, fileSystem, file) { got -> _state.update { it.copy(downloaded = got) } }
            }
            if (!whole) {
                _state.update { it.copy(downloaded = null, failed = true) }
                return null
            }
            withContext(Dispatchers.IO) { fileSystem.write(ok) { writeUtf8(info.sha256) } }
            _state.value = PhoneModelState(here = true, bytes = info.bytes, offered = true)
            file
        } catch (e: CancellationException) {
            _state.update { it.copy(downloaded = null) }
            throw e
        } catch (e: Exception) {
            _state.update { it.copy(downloaded = null, failed = true) }
            null
        }
    }
}
