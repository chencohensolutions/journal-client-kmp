package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.server.CameraGroup
import com.chencohensolution.journal.server.CameraModel
import com.chencohensolution.journal.server.CamerasState
import kotlinx.coroutines.delay

// Made-up data for Settings › Your cameras in the UI lab (LabScreens' "cameras" screen, and Settings' row there).
// Fakes: the server's list (journal-server-go `cameras`) and its marks: the models, counts and years are made up, and
// setCamera isn't called. The models are tagged as the owner's files tag them (make and model as the server keeps them),
// so the app's names and merging show: "Galaxy S25", "SM-S931B" and a make-less "Galaxy S25" are one Samsung Galaxy S25,
// and "LGE" and "LG Electronics" "LG-E450" one LG Optimus L5 II. The move is timed here (seconds); on the server it takes
// a turn per file, and Moments, Show in the app and search get the moved photos with the next sync.

// The owner's cameras as the server lists them (counts close to his, years made up), one of another person's marked.
internal val labCameras = listOf(
    CameraModel("Sony", "H8324", 27298, 27298, listOf(2018, 2019, 2020), mine = true, marked = false, pending = 0),
    CameraModel("samsung", "SM-G781B", 19090, 19090, listOf(2020, 2021, 2022, 2023, 2024), mine = true, marked = true, pending = 0),
    CameraModel("Sony", "E5823", 12523, 12523, listOf(2015, 2016, 2017), mine = true, marked = false, pending = 0),
    CameraModel("samsung", "Galaxy S25", 8498, 0, listOf(2025, 2026), mine = true, marked = false, pending = 0),
    CameraModel("Sony", "D5503", 7409, 7409, listOf(2014, 2015), mine = true, marked = false, pending = 0),
    CameraModel("samsung", "Galaxy S25+", 3415, 0, listOf(2025, 2026), mine = true, marked = false, pending = 0),
    CameraModel("LGE", "LG-E450", 451, 451, listOf(2013, 2014), mine = true, marked = false, pending = 0),
    CameraModel("Canon", "Canon PowerShot SX10 IS", 71, 71, listOf(2009, 2010), mine = true, marked = false, pending = 0),
    CameraModel("NIKON CORPORATION", "NIKON D800", 70, 70, listOf(2016), mine = false, marked = true, pending = 0),
    CameraModel("samsung", "SM-S936B", 61, 0, listOf(2025), mine = true, marked = false, pending = 0),
    CameraModel("samsung", "SM-S931B", 43, 0, listOf(2025), mine = true, marked = false, pending = 0),
    CameraModel("HUAWEI", "SLA-L22", 33, 33, listOf(2017), mine = true, marked = false, pending = 0),
    CameraModel("LG Electronics", "LG-E450", 12, 12, listOf(2013), mine = true, marked = false, pending = 0),
    CameraModel("", "Galaxy S25", 1, 0, listOf(2025), mine = true, marked = false, pending = 0),
)

/** The LG's two tags, as one camera. */
private val lg = CamerasState.Ready(labCameras).cameras.first { it.name == "LG Optimus L5 II" }

private fun List<CameraModel>.with(camera: CameraGroup, change: (CameraModel) -> CameraModel) =
    map { if (it in camera.members) change(it) else it }

/** The list moving the LG's photos to Sent to you. */
internal val labCamerasMoving = labCameras.with(lg) { it.copy(mine = false, marked = true, pending = it.files) }

/**
 * The app's [CamerasScreen] with made-up cameras; a mark moves the camera's photos in 16 steps of [stepMillis], as the
 * server would report them.
 */
@Composable
internal fun CamerasLab(start: List<CameraModel>, confirmingAtStart: CameraGroup? = null, stepMillis: Long = 250) {
    var cameras by remember { mutableStateOf(start) }
    var confirming by remember { mutableStateOf(confirmingAtStart) }
    val moving = cameras.any { it.pending > 0 }
    LaunchedEffect(moving) {
        while (cameras.any { it.pending > 0 }) {
            delay(stepMillis)
            cameras = cameras.map { if (it.pending > 0) it.copy(pending = (it.pending - (it.files / 16).coerceAtLeast(1)).coerceAtLeast(0)) else it }
        }
    }
    fun mark(camera: CameraGroup, mine: Boolean) {
        val names = camera.members.map { it.make to it.model }
        cameras = cameras.map { if (it.make to it.model in names) it.copy(mine = mine, marked = true, pending = it.files) else it }
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

/** Asking before moving the LG's photos (both its tags). */
@Composable
internal fun CamerasLabConfirm() = CamerasLab(labCameras, confirmingAtStart = lg)

/** The LG's photos moving, slowly (about half a minute). */
@Composable
internal fun CamerasLabMoving() = CamerasLab(labCamerasMoving, stepMillis = 2000)
