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
    /** "Canon EOS 80D", "Samsung Galaxy S25": what the screens call it ([cameraName]). */
    val name: String get() = cameraName(make, model) ?: model

    /** The first and last year, or null without any. */
    val firstYear: Int? get() = years.minOrNull()
    val lastYear: Int? get() = years.maxOrNull()
}

/**
 * A camera's name from its tags, as the app shows it (the server keeps the tags as they are). A model the app knows
 * ([CameraNames.models], matched ignoring case) has its whole name ("SM-S931B" and "Galaxy S25": "Samsung Galaxy S25";
 * "Galaxy S25+" stays another camera). Else the make, spelled as the app spells it ("samsung" "Samsung", "LGE" "LG",
 * "NIKON CORPORATION" "Nikon"), and the model, without the make it repeats ("NIKON D800": "Nikon D800"); a model with
 * no make is shown alone ("iPhone 15 Pro"). Null when both are empty.
 */
fun cameraName(make: String?, model: String?): String? {
    val m = make?.trim().orEmpty()
    val name = model?.trim().orEmpty()
    CameraNames.models[name.lowercase()]?.let { return it }
    if (m.isEmpty()) return name.ifEmpty { null }
    val maker = CameraNames.make(m)
    if (name.isEmpty()) return maker
    for (repeated in listOf(m, maker)) {
        if (!name.startsWith(repeated, ignoreCase = true)) continue
        val rest = name.substring(repeated.length)
        // "Canon EOS 80D": the make, then the rest; "LG-K420" names its make already, joined, and stays as tagged.
        return when {
            rest.isEmpty() -> maker
            rest.startsWith(" ") -> maker + rest
            else -> name
        }
    }
    return "$maker $name"
}

/**
 * One row of Settings › Your cameras: every model the app gives the same [name] ("Galaxy S25", "SM-S931B" and a
 * "Galaxy S25" without a make are all "Samsung Galaxy S25"), as one camera. The server knows only the [members].
 */
data class CameraGroup(
    /** "Samsung Galaxy S25". */
    val name: String,
    /** Its models as the server listed them, most files first; marking the camera marks each. */
    val members: List<CameraModel>,
) {
    /** Its files, of every member. */
    val files: Int get() = members.sumOf { it.files }

    /** Of [files], those the server's import placed. */
    val imported: Int get() = members.sumOf { it.imported }

    /** The years any member's files were taken in, oldest first. */
    val years: List<Int> get() = members.flatMap { it.years }.distinct().sorted()

    /** The user's when every member is. */
    val mine: Boolean get() = members.all { it.mine }

    /** Every member was marked by the user. */
    val marked: Boolean get() = members.all { it.marked }

    /** Files still being moved, of every member. */
    val pending: Int get() = members.sumOf { it.pending }

    val firstYear: Int? get() = years.minOrNull()
    val lastYear: Int? get() = years.maxOrNull()

    companion object {
        /** One group per name, the most files first. */
        fun of(models: List<CameraModel>): List<CameraGroup> =
            models.groupBy { it.name }
                .map { (name, members) -> CameraGroup(name, members.sortedByDescending { it.files }) }
                .sortedByDescending { it.files }
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
        /** Every model as the server listed it (make and model exactly as tagged). */
        val models: List<CameraModel>,
        /** A mark is on its way to the server; switches are ignored until it is back. */
        val saving: Boolean = false,
        /** The last mark didn't reach the server, and the switch went back. */
        val notChanged: Boolean = false,
    ) : CamerasState {
        /** The rows: one per camera name, merging the models that share it, most files first. */
        val cameras: List<CameraGroup> = CameraGroup.of(models)

        /** Files still being moved, of every model: "Moving 312 photos…". */
        val pending: Int get() = models.sumOf { it.pending }
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
     * Marks [camera] (matched by name) as the user's or not: one setCamera per member, with its make and model exactly
     * as the server gave them. The switch moves at once; if the server doesn't take every member, the state says "not
     * changed" and shows what the server last answered. Ignored while saving, and when it is marked so already.
     */
    fun setMine(camera: CameraGroup, mine: Boolean) {
        val before = _state.value as? CamerasState.Ready ?: return
        if (before.saving) return
        val current = before.cameras.firstOrNull { it.name == camera.name } ?: return
        if (current.mine == mine && current.marked) return
        _state.value = before.copy(
            models = before.models.map { if (it in current.members) it.copy(mine = mine, marked = true) else it },
            saving = true,
            notChanged = false,
        )
        saving = scope.launch {
            var latest: List<CameraModel>? = null
            var failed = false
            withContext(NonCancellable) {
                for (member in current.members) {
                    val saved = library.setCamera(member, mine)
                    if (saved == null) failed = true else latest = saved
                }
            }
            _state.value = CamerasState.Ready(latest ?: before.models, notChanged = failed)
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
                if (!now.saving) _state.value = now.copy(models = cameras)
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

    private companion object {
        const val POLL_MILLIS = 3_000L
    }
}
