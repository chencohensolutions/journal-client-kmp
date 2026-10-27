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

// The owner's library as the server could list it: two phones of his, an old phone, a camera, and two models of others.
private val labCameras = listOf(
    CameraModel("samsung", "Galaxy S21", 8930, 6100, listOf(2021, 2022, 2023, 2024, 2025), mine = true, marked = true, pending = 0),
    CameraModel("Apple", "iPhone 12", 2104, 2104, listOf(2020, 2021, 2022), mine = true, marked = false, pending = 0),
    CameraModel("samsung", "Galaxy S25", 1240, 0, listOf(2025, 2026), mine = true, marked = false, pending = 0),
    CameraModel("Xiaomi", "Redmi Note 8", 486, 486, listOf(2019, 2020, 2021), mine = false, marked = true, pending = 0),
    CameraModel("Canon", "Canon EOS 80D", 312, 312, listOf(2014, 2015, 2016), mine = true, marked = false, pending = 0),
    CameraModel("HUAWEI", "P20 lite", 57, 57, listOf(2018), mine = true, marked = false, pending = 0),
)

private fun List<CameraModel>.with(camera: CameraModel, change: (CameraModel) -> CameraModel) =
    map { if (it.make == camera.make && it.model == camera.model) change(it) else it }

/** The page with made-up cameras; a mark moves the model's photos in 16 steps of [stepMillis], as the server would report. */
@Composable
private fun Live(start: CamerasOptionAState, stepMillis: Long = 250) {
    var cameras by remember { mutableStateOf((start.cameras as CamerasState.Ready).cameras) }
    var confirming by remember { mutableStateOf(start.confirming) }
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
    CamerasScreenA(
        CamerasOptionAState(CamerasState.Ready(cameras), confirming),
        CamerasOptionAActions(
            setMine = { camera, mine -> if (mine) mark(camera, true) else confirming = camera },
            confirm = {
                confirming?.let { mark(it, false) }
                confirming = null
            },
            cancel = { confirming = null },
        ),
    )
}

private val canon = labCameras.first { it.model == "Canon EOS 80D" }

// Fakes: the server's list (journal-server-go `cameras`) and its marks: the models, counts and years are made up, and
// setCamera isn't called. The phones' names ("Samsung Galaxy S25") need a table of model numbers that isn't built: the
// tags say "SM-S931B", which the app shows as "Samsung SM-S931B" (cameraName). The move is timed here (seconds); on the
// server it takes a turn per file, and Moments, Show in the app and search get the moved photos with the next sync.
// Not reachable from Settings: the "Your cameras" row under Library isn't drawn.
internal val camerasOptionA = LabOption("a", "One list with Mine switches", "Every camera in one card; turning one off asks first") {
    Live(CamerasOptionAState(CamerasState.Ready(labCameras)))
}

// Fakes: as option a; opened on the question for the Canon.
internal val camerasOptionAConfirm = LabOption("a-confirm", "One list: asking before moving", "\"312 photos will move to Sent to you\"") {
    Live(CamerasOptionAState(CamerasState.Ready(labCameras), confirming = canon))
}

// Fakes: as option a; opened while the Canon's photos are moving, slowly (about half a minute).
internal val camerasOptionAMoving = LabOption("a-moving", "One list: moving", "\"Moving 312 photos…\" on the row") {
    Live(CamerasOptionAState(CamerasState.Ready(labCameras.with(canon) { it.copy(mine = false, marked = true, pending = 312) })), stepMillis = 2000)
}
