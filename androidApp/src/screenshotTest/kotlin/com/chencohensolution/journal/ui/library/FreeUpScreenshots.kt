package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.storage.FreeUpGroup
import com.chencohensolution.journal.storage.FreeUpPlan
import com.chencohensolution.journal.storage.FreeUpResult
import com.chencohensolution.journal.storage.FreeUpRule
import com.chencohensolution.journal.storage.FreeUpState
import com.chencohensolution.journal.storage.KeptReason

// Free up space: the one button (everything backed up), and More options open after a run. See docs/screens/settings.md.

private fun plan(): FreeUpPlan {
    val photo = MediaItem("1", MediaType.IMAGE, 1_552_000_000_000, LocalDay(2019, 3, 8), null, 4000, 3000, 14_200_000_000L / 1278, "IMG_1.jpg")
    val video = photo.copy(id = "2", type = MediaType.VIDEO, takenAtMillis = 1_760_000_000_000)
    return FreeUpPlan(List(1240) { FreeUpGroup(listOf(photo)) } + List(38) { FreeUpGroup(listOf(video)) })
}

private fun ui(state: FreeUpState) = StorageUi(previewBytes = 0, previewLimitGigabytes = 10, freeUp = state, canTrashWithoutAsking = false)

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun FreeUp() = Screen {
    FreeUpScreen(ui(FreeUpState(rule = FreeUpRule.Everything, plan = plan())), StorageActions(), back = {})
}

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=1100dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=1100dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun FreeUpOptions() = Screen {
    FreeUpScreen(
        ui(
            FreeUpState(
                rule = FreeUpRule.OlderThan(365),
                sources = setOf(MediaSource.CAMERA, MediaSource.WHATSAPP),
                plan = plan(),
                waitingCount = 120,
                waitingBytes = 3_200_000_000L,
                lastResult = FreeUpResult(removed = 0, removedBytes = 0, waiting = 120, kept = mapOf(KeptReason.CHANGED_ON_PHONE to 2)),
            ),
        ),
        StorageActions(),
        back = {},
    )
}
