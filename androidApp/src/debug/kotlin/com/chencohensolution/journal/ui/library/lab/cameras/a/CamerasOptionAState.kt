package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.server.CameraModel
import com.chencohensolution.journal.server.CamerasState

/**
 * Option a's state: the shared logic's [CamerasState] (CamerasController), and the camera whose "Mine" switch was
 * turned off and waits for its confirmation, which the screen's route would hold.
 */
internal data class CamerasOptionAState(
    val cameras: CamerasState,
    /** Asked "312 photos will move to Sent to you": Move or Cancel. */
    val confirming: CameraModel? = null,
)

internal class CamerasOptionAActions(
    /** The switch turned on (marks it the user's at once) or off (asks first). */
    val setMine: (CameraModel, Boolean) -> Unit = { _, _ -> },
    /** "Move": marks [CamerasOptionAState.confirming] not the user's. */
    val confirm: () -> Unit = {},
    val cancel: () -> Unit = {},
    val retry: () -> Unit = {},
    val back: () -> Unit = {},
)
