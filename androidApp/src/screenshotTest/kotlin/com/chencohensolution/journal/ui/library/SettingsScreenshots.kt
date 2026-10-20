package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.server.PreviewFormat
import com.chencohensolution.journal.server.PreviewOptions
import com.chencohensolution.journal.server.PreviewQuality
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.settings.SimilarityThreshold
import com.chencohensolution.journal.storage.FreeUpGroup
import com.chencohensolution.journal.storage.FreeUpPlan
import com.chencohensolution.journal.storage.FreeUpResult
import com.chencohensolution.journal.storage.FreeUpRule
import com.chencohensolution.journal.storage.FreeUpState
import com.chencohensolution.journal.storage.KeptReason

// Settings: the Journal's grouping, and the "Look alike" threshold changed from its default. See docs/previews.md.

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun Settings() = Screen {
    SettingsScreen(SettingsState(similarity = SimilarityThreshold(percent = 72, defaultPercent = 85)), SettingsActions())
}

// Settings with backup off, then Storage on this phone: the previews' limit, and "Free up space" after a run whose
// checked files wait for the system prompt.

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=1500dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=1500dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SettingsStorage() = Screen {
    val photo = MediaItem("1", MediaType.IMAGE, 0, LocalDay(2025, 3, 1), null, 4000, 3000, 14_200_000_000L / 1278, "IMG_1.jpg")
    val video = photo.copy(id = "2", type = MediaType.VIDEO)
    val plan = FreeUpPlan(List(1240) { FreeUpGroup(listOf(photo)) } + List(38) { FreeUpGroup(listOf(video)) })
    SettingsScreen(
        settings = SettingsState(),
        actions = SettingsActions(),
        backup = BackupUi(BackupState(), waitingCount = 0, canCheckNetworkInBackground = false),
        storage = StorageUi(
            previewBytes = 2_300_000_000L,
            previewLimitGigabytes = 10,
            freeUp = FreeUpState(
                rule = FreeUpRule.OlderThan(365),
                plan = plan,
                waitingCount = 120,
                waitingBytes = 3_200_000_000L,
                lastResult = FreeUpResult(removed = 0, removedBytes = 0, waiting = 120, kept = mapOf(KeptReason.CHANGED_ON_PHONE to 2)),
            ),
            canTrashWithoutAsking = false,
            previewOptions = PreviewOptions(format = PreviewFormat.AVIF, quality = PreviewQuality.HIGH),
        ),
    )
}
