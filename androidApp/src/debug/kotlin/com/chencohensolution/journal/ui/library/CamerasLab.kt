package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.server.CameraModel
import com.chencohensolution.journal.server.CamerasState
import kotlinx.coroutines.delay

// Made-up data for Settings › Your cameras in the UI lab (LabScreens' "cameras" screen, and Settings' row there).
// Fakes: the server's list (journal-server-go `cameras`) and its marks: the models, counts and years are made up, and
// setCamera isn't called. The phones are named as their tags name them ("Samsung SM-S931B"). The move is timed here
// (seconds); on the server it takes a turn per file, and Moments, Show in the app and search get the moved photos with
// the next sync.

// The owner's library as the server could list it: two phones of his, an old phone, a camera, and two models of others.
internal val labCameras = listOf(
    CameraModel("samsung", "SM-G991B", 8930, 6100, listOf(2021, 2022, 2023, 2024, 2025), mine = true, marked = true, pending = 0),
    CameraModel("Apple", "iPhone 12", 2104, 2104, listOf(2020, 2021, 2022), mine = true, marked = false, pending = 0),
    CameraModel("samsung", "SM-S931B", 1240, 0, listOf(2025, 2026), mine = true, marked = false, pending = 0),
    CameraModel("Xiaomi", "Redmi Note 8", 486, 486, listOf(2019, 2020, 2021), mine = false, marked = true, pending = 0),
    CameraModel("Canon", "Canon EOS 80D", 312, 312, listOf(2014, 2015, 2016), mine = true, marked = false, pending = 0),
    CameraModel("HUAWEI", "ANE-LX1", 57, 57, listOf(2018), mine = true, marked = false, pending = 0),
)

private val canon = labCameras.first { it.model == "Canon EOS 80D" }

private fun List<CameraModel>.with(camera: CameraModel, change: (CameraModel) -> CameraModel) =
    map { if (it.make == camera.make && it.model == camera.model) change(it) else it }

/** The list moving the Canon's photos to Sent to you. */
internal val labCamerasMoving = labCameras.with(canon) { it.copy(mine = false, marked = true, pending = 312) }

/**
 * The app's [CamerasScreen] with made-up cameras; a mark moves the model's photos in 16 steps of [stepMillis], as the
 * server would report them.
 */
@Composable
internal fun CamerasLab(start: List<CameraModel>, confirmingAtStart: CameraModel? = null, stepMillis: Long = 250) {
    var cameras by remember { mutableStateOf(start) }
    var confirming by remember { mutableStateOf(confirmingAtStart) }
    val moving = cameras.any { it.pending > 0 }
    LaunchedEffect(moving) {
        while (cameras.any { it.pending > 0 }) {
            delay(stepMillis)
            cameras = cameras.map { if (it.pending > 0) it.copy(pending = (it.pending - (it.files / 16).coerceAtLeast(1)).coerceAtLeast(0)) else it }
        }
    }
    fun mark(camera: CameraModel, mine: Boolean) {
        cameras = cameras.with(camera) { it.copy(mine = mine, marked = true, pending = it.files) }
    }
    CamerasScreen(
        CamerasState.Ready(cameras),
        confirming,
        CamerasActions(
            setMine = { camera, mine -> if (mine) mark(camera, true) else confirming = camera },
            confirm = {
                confirming?.let { mark(it, false) }
                confirming = null
            },
            cancel = { confirming = null },
        ),
    )
}

/** Asking before moving the Canon's photos. */
@Composable
internal fun CamerasLabConfirm() = CamerasLab(labCameras, confirmingAtStart = canon)

/** The Canon's photos moving, slowly (about half a minute). */
@Composable
internal fun CamerasLabMoving() = CamerasLab(labCamerasMoving, stepMillis = 2000)
