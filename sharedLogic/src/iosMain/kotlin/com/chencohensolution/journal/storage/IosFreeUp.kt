package com.chencohensolution.journal.storage

import com.chencohensolution.journal.backup.MediaFiles
import com.chencohensolution.journal.connection.ObservationHandle
import com.chencohensolution.journal.library.MediaItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.coroutines.cancellation.CancellationException

/**
 * iOS can't delete photos without asking: PhotoKit always shows its own prompt, and the photos go to Recently Deleted
 * (30 days). So every checked file waits, and the app deletes them all in one request when the user taps Move to
 * trash (`PhotoRemoval.swift`, the only place allowed to; see PhotoSafetyTest).
 */
internal object IosPhotoTrash : PhotoTrash {
    override fun canTrashWithoutAsking(): Boolean = false

    override suspend fun trash(items: List<MediaItem>): Set<String> = emptySet()
}

/**
 * "Free up space" as Swift runs it: in the app, since only the user can confirm the deletion. Each call answers on
 * the main thread and returns a handle that cancels it.
 */
class IosFreeUp internal constructor(
    val controller: FreeUpController,
    /** Where photos are copied out of PhotoKit to hash them; emptied when a run ends. */
    private val files: MediaFiles,
    /** Deletes sound files of the picked folders the server holds, once the user agreed (FreeUpController.confirmSounds). */
    private val sounds: SoundRemoval? = null,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** One run ([FreeUpController.runOnce]); [onDone] when it ended, stopped or was cancelled. */
    fun launchRun(onDone: () -> Unit): ObservationHandle {
        val job = scope.launch {
            try {
                controller.runOnce(IosPhotoTrash, sounds)
            } catch (_: CancellationException) {
                // Stopped: what was checked so far waits for the user.
            } catch (_: Exception) {
                // The library couldn't be read: nothing was checked.
            } finally {
                files.runEnded()
            }
        }
        job.invokeOnCompletion { scope.launch { onDone() } }
        return ObservationHandle { job.cancel() }
    }

    /** [FreeUpController.waitingItems]: the checked files, as they are in the library now (empty when cancelled). */
    fun waitingItems(onResult: (List<MediaItem>) -> Unit): ObservationHandle {
        var result = emptyList<MediaItem>()
        val job = scope.launch { result = runCatching { controller.waitingItems() }.getOrDefault(emptyList()) }
        job.invokeOnCompletion { scope.launch { onResult(result) } }
        return ObservationHandle { job.cancel() }
    }
}
