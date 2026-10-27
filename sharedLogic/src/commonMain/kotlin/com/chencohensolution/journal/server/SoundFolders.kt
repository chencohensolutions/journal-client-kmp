package com.chencohensolution.journal.server

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.graphql.fragment.SoundFolderFields
import com.chencohensolution.journal.library.FolderNames
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.chencohensolution.journal.graphql.type.SoundFolderMark as GraphqlMark

/** Where a sound folder's recordings go (Settings › Sound folders). */
enum class SoundFolderMark {
    /** Merged into Calls: its recordings' source is call. */
    CALL,

    /** Merged into Voice recordings: its recordings' source is recording. */
    RECORDING,

    /** Kept as its own folder ("folder:calls"): nothing merged. */
    NONE,
}

/**
 * One folder the user's sound recordings came from (Settings › Sound folders), as the server counts it: a phone's
 * "Call", an import's "Calls", "Voice Recorder". Folders that differ only by case are one, by their lower-case [id].
 * See journal-server-go docs/media.md, "Sound folders".
 */
data class ServerSoundFolder(
    /** "folder:calls", "folder:voice recorder". */
    val id: String,
    /** "Calls": as most of its files spell it. */
    val name: String,
    /** Its sound recordings, hidden or not; those already merged into calls or recordings count too. */
    val files: Int,
    /** Of [files], those the server's import placed; the rest came from phones. */
    val imported: Int,
    val mark: SoundFolderMark,
    /** Its files still being moved after the mark changed; 0 once all have. */
    val pending: Int,
) {
    /** The name to show: an app the name table knows as it spells it, else the server's ([FolderNames]). */
    val shownName: String get() = name.ifBlank { FolderNames.nameOf(id.removePrefix("folder:")) }
}

internal fun SoundFolderFields.toSoundFolder() = ServerSoundFolder(
    id = id,
    name = name,
    files = files,
    imported = imported,
    mark = when (`as`) {
        GraphqlMark.CALL -> SoundFolderMark.CALL
        GraphqlMark.RECORDING -> SoundFolderMark.RECORDING
        else -> SoundFolderMark.NONE
    },
    pending = pending,
)

internal fun SoundFolderMark.toInput(): GraphqlMark = when (this) {
    SoundFolderMark.CALL -> GraphqlMark.CALL
    SoundFolderMark.RECORDING -> GraphqlMark.RECORDING
    SoundFolderMark.NONE -> GraphqlMark.NONE
}

/** What Settings › Sound folders shows. */
sealed interface SoundFoldersState {
    /** Asking the server. */
    data object Loading : SoundFoldersState

    /** The server didn't answer; the screen offers to try again. */
    data object Unreachable : SoundFoldersState

    data class Ready(
        /** Every folder as the server listed it, most files first. */
        val folders: List<ServerSoundFolder>,
        /** A mark is on its way to the server; choices are ignored until it is back. */
        val saving: Boolean = false,
        /** The last mark didn't reach the server, and the choice went back. */
        val notChanged: Boolean = false,
    ) : SoundFoldersState {
        /** Files still being moved, of every folder: "Moving 953…". */
        val pending: Int get() = folders.sumOf { it.pending }
    }
}

/**
 * Settings › Sound folders: reads the folders sound recordings came from and merges one into Calls or Voice recordings,
 * or keeps it as its own folder, at once. While the server is still moving a folder's files
 * ([ServerSoundFolder.pending]) it asks again every [pollMillis]; once they have all moved, the library syncs, since
 * each moved file comes again in the change feed. One per opening of the screen, as [CamerasController].
 */
class SoundFoldersController internal constructor(
    private val library: ServerLibrary,
    dispatcher: CoroutineDispatcher,
    private val pollMillis: Long = POLL_MILLIS,
) {
    constructor(library: ServerLibrary) : this(library, Dispatchers.Main)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loading: Job? = null
    private var saving: Job? = null
    private var polling: Job? = null

    private val _state = MutableStateFlow<SoundFoldersState>(SoundFoldersState.Loading)
    val state: StateFlow<SoundFoldersState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: SoundFoldersState get() = _state.value

    /** Asks the server for the folders; does nothing while already asking or once loaded. */
    fun load() {
        if (loading?.isActive == true || _state.value is SoundFoldersState.Ready) return
        _state.value = SoundFoldersState.Loading
        loading = scope.launch {
            val folders = library.soundFolders()
            _state.value = if (folders == null) SoundFoldersState.Unreachable else SoundFoldersState.Ready(folders)
            followPending()
        }
    }

    /** After [SoundFoldersState.Unreachable]. */
    fun retry() = load()

    /**
     * Merges [folder] (matched by id) into [mark]'s kind, or keeps it as its own folder. The choice moves at once; if
     * the server doesn't take it, the state says "not changed" and shows what it was. Ignored while saving, and when
     * the folder is marked so already.
     */
    fun setMark(folder: ServerSoundFolder, mark: SoundFolderMark) {
        val before = _state.value as? SoundFoldersState.Ready ?: return
        if (before.saving) return
        val current = before.folders.firstOrNull { it.id == folder.id } ?: return
        if (current.mark == mark) return
        _state.value = before.copy(
            folders = before.folders.map { if (it.id == current.id) it.copy(mark = mark) else it },
            saving = true,
            notChanged = false,
        )
        saving = scope.launch {
            val saved = withContext(NonCancellable) { library.setSoundFolder(current.id, mark) }
            _state.value = SoundFoldersState.Ready(saved ?: before.folders, notChanged = saved == null)
            followPending()
        }
    }

    /** While files are still moving, asks again until none is; then the library syncs to get them. */
    private fun followPending() {
        val ready = _state.value as? SoundFoldersState.Ready ?: return
        if (ready.pending == 0 || polling?.isActive == true) return
        polling = scope.launch {
            while (true) {
                delay(pollMillis)
                val folders = library.soundFolders() ?: continue
                val now = _state.value as? SoundFoldersState.Ready ?: return@launch
                // A mark on its way keeps its choice; the next answer has it.
                if (!now.saving) _state.value = now.copy(folders = folders)
                if (folders.sumOf { it.pending } == 0) {
                    library.syncAgain()
                    return@launch
                }
            }
        }
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (SoundFoldersState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    /** The screen is gone: stops asking. Files the server moves meanwhile come with the library's next sync. */
    fun close() = scope.cancel()

    private companion object {
        const val POLL_MILLIS = 3_000L
    }
}
