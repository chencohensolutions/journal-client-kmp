package com.chencohensolution.journal.server

import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.graphql.fragment.CameraModelFields
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

/**
 * One camera model in the user's library (Settings › Your cameras), as the server counts it: the photos and videos it
 * took, the years they were taken in, and the user's mark. A model marked not [mine] has its files kept apart as "Sent
 * to you" ([com.chencohensolution.journal.library.MediaSource.RECEIVED]); one nobody marked counts as the user's. See
 * journal-server-go docs/media.md, "Cameras".
 */
data class CameraModel(
    /** "samsung", "Canon": as most of its files spell it. */
    val make: String,
    /** "SM-S931B", "Canon EOS 80D". */
    val model: String,
    /** Its files, hidden or not. */
    val files: Int,
    /** Of [files], those the server's import placed. */
    val imported: Int,
    /** The years its files were taken in, oldest first. */
    val years: List<Int>,
    /** The user's: marked so, or not marked at all. */
    val mine: Boolean,
    /** The user marked it; false while it is [mine] only because nobody marked it. */
    val marked: Boolean,
    /** Its files still being moved after the mark changed (to Sent to you, or back); 0 once all have. */
    val pending: Int,
) {
    /** "Canon EOS 80D", "Samsung SM-S931B": what the screens call it. */
    val name: String get() = cameraName(make, model) ?: model

    /** The first and last year, or null without any. */
    val firstYear: Int? get() = years.minOrNull()
    val lastYear: Int? get() = years.maxOrNull()
}

/**
 * A camera's name from its tags: the model alone when it names the make already ("Canon" "Canon EOS 80D"), else the
 * make, with a capital, and the model ("samsung" "SM-S931B": "Samsung SM-S931B"). Null when both are empty.
 */
fun cameraName(make: String?, model: String?): String? {
    val m = make?.trim().orEmpty()
    val name = model?.trim().orEmpty()
    return when {
        name.isEmpty() && m.isEmpty() -> null
        name.isEmpty() -> m.replaceFirstChar { it.uppercaseChar() }
        m.isEmpty() || name.startsWith(m, ignoreCase = true) -> name
        else -> m.replaceFirstChar { it.uppercaseChar() } + " " + name
    }
}

internal fun CameraModelFields.toCameraModel() = CameraModel(make, model, files, imported, years, mine, marked, pending)

/** What Settings › Your cameras shows. */
sealed interface CamerasState {
    /** Asking the server. */
    data object Loading : CamerasState

    /** The server didn't answer; the screen offers to try again. */
    data object Unreachable : CamerasState

    data class Ready(
        /** Every model, most files first. */
        val cameras: List<CameraModel>,
        /** A mark is on its way to the server; switches are ignored until it is back. */
        val saving: Boolean = false,
        /** The last mark didn't reach the server, and the switch went back. */
        val notChanged: Boolean = false,
    ) : CamerasState {
        /** Files still being moved, of every model: "Moving 312 photos…". */
        val pending: Int get() = cameras.sumOf { it.pending }
    }
}

/**
 * Settings › Your cameras: reads the camera models from the server and marks one as the user's or not at once. While
 * the server is still moving a model's files ([CameraModel.pending]) it asks again every [pollMillis]; once they have
 * all moved, the library syncs, since each moved file comes again in the change feed. One per opening of the screen.
 */
class CamerasController internal constructor(
    private val library: ServerLibrary,
    dispatcher: CoroutineDispatcher,
    private val pollMillis: Long = POLL_MILLIS,
) {
    constructor(library: ServerLibrary) : this(library, Dispatchers.Main)

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private var loading: Job? = null
    private var saving: Job? = null
    private var polling: Job? = null

    private val _state = MutableStateFlow<CamerasState>(CamerasState.Loading)
    val state: StateFlow<CamerasState> = _state.asStateFlow()

    /** Same as `state.value`, typed for Swift. */
    val currentState: CamerasState get() = _state.value

    /** Asks the server for the models; does nothing while already asking or once loaded. */
    fun load() {
        if (loading?.isActive == true || _state.value is CamerasState.Ready) return
        _state.value = CamerasState.Loading
        loading = scope.launch {
            val cameras = library.cameras()
            _state.value = if (cameras == null) CamerasState.Unreachable else CamerasState.Ready(cameras)
            followPending()
        }
    }

    /** After [CamerasState.Unreachable]. */
    fun retry() = load()

    /**
     * Marks [camera] (matched by make and model) as the user's or not. The switch moves at once; if the server doesn't
     * take it, it goes back and the state says "not changed". Ignored while saving, and when it is marked so already.
     */
    fun setMine(camera: CameraModel, mine: Boolean) {
        val before = _state.value as? CamerasState.Ready ?: return
        if (before.saving) return
        val current = before.cameras.firstOrNull { it.same(camera) } ?: return
        if (current.mine == mine && current.marked) return
        _state.value = before.copy(
            cameras = before.cameras.map { if (it.same(camera)) it.copy(mine = mine, marked = true) else it },
            saving = true,
            notChanged = false,
        )
        saving = scope.launch {
            val saved = withContext(NonCancellable) { library.setCamera(current, mine) }
            _state.value = CamerasState.Ready(saved ?: before.cameras, notChanged = saved == null)
            followPending()
        }
    }

    /** While files are still moving, asks again until none is; then the library syncs to get them. */
    private fun followPending() {
        val ready = _state.value as? CamerasState.Ready ?: return
        if (ready.pending == 0 || polling?.isActive == true) return
        polling = scope.launch {
            while (true) {
                delay(pollMillis)
                val cameras = library.cameras() ?: continue
                val now = _state.value as? CamerasState.Ready ?: return@launch
                // A mark on its way keeps its switch; the next answer has it.
                if (!now.saving) _state.value = now.copy(cameras = cameras)
                if (cameras.sumOf { it.pending } == 0) {
                    library.syncAgain()
                    return@launch
                }
            }
        }
    }

    /** Callback-style observation for Swift; Android collects [state] directly. */
    fun observe(onChange: (CamerasState) -> Unit): ObservationHandle {
        val job = scope.launch { state.collect(onChange) }
        return ObservationHandle { job.cancel() }
    }

    /** The screen is gone: stops asking. Files the server moves meanwhile come with the library's next sync. */
    fun close() = scope.cancel()

    private fun CameraModel.same(other: CameraModel) =
        make.equals(other.make, ignoreCase = true) && model.equals(other.model, ignoreCase = true)

    private companion object {
        const val POLL_MILLIS = 3_000L
    }
}
