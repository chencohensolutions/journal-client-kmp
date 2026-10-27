package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.server.CameraModel
import com.chencohensolution.journal.server.CamerasState

/**
 * Option b's state: the shared logic's [CamerasState] (CamerasController), and the camera whose "Not mine" was tapped,
 * opened in place with its question, which the screen's route would hold.
 */
internal data class CamerasOptionBState(
    val cameras: CamerasState,
    /** Asked in its row: "312 photos will move to Sent to you", Move or Cancel. */
    val confirming: CameraModel? = null,
)

internal class CamerasOptionBActions(
    /** "Not mine": opens the question in the row. */
    val askNotMine: (CameraModel) -> Unit = {},
    /** "Mine" on a camera under Sent to you: marks it the user's at once. */
    val markMine: (CameraModel) -> Unit = {},
    val confirm: () -> Unit = {},
    val cancel: () -> Unit = {},
    val retry: () -> Unit = {},
    val back: () -> Unit = {},
)
