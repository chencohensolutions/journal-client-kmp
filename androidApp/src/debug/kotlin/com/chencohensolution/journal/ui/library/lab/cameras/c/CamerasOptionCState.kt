package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.server.CameraModel
import com.chencohensolution.journal.server.CamerasState

/**
 * Option c's state: the shared logic's [CamerasState] (CamerasController), and the camera "Sent to you" was chosen
 * for, whose question waits in the panel at the bottom; the screen's route would hold it.
 */
internal data class CamerasOptionCState(
    val cameras: CamerasState,
    /** Asked at the bottom: "312 photos will move to Sent to you", Move or Cancel. */
    val confirming: CameraModel? = null,
)

internal class CamerasOptionCActions(
    /** "Mine" (at once) or "Sent to you" (asks first). */
    val choose: (CameraModel, mine: Boolean) -> Unit = { _, _ -> },
    val confirm: () -> Unit = {},
    val cancel: () -> Unit = {},
    val retry: () -> Unit = {},
    val back: () -> Unit = {},
)
