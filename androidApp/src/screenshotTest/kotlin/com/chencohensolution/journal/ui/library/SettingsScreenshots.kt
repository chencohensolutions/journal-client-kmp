package com.chencohensolution.journal.ui.library

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.android.tools.screenshot.PreviewTest
import com.chencohensolution.journal.backup.BackupSettings
import com.chencohensolution.journal.backup.BackupState
import com.chencohensolution.journal.library.AudioKind
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.settings.KindsStatus
import com.chencohensolution.journal.server.StorageUsage
import com.chencohensolution.journal.server.SyncProgress
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.TimelineGrouping
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

// Settings' first page: the groups, with backup on and waiting, previews kept, and the library syncing after a change in
// Hidden collections. See docs/previews.md.

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=1300dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=1300dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun Settings() = Screen {
    SettingsScreen(
        settings = SettingsState(similarity = SimilarityThreshold(percent = 72, defaultPercent = 85)),
        actions = SettingsActions(),
        kinds = kinds,
        backup = BackupUi(BackupState(BackupSettings(enabled = true)), waitingCount = 79, canCheckNetworkInBackground = true, serverUsage = serverUsage),
        storage = storage,
        syncProgress = SyncProgress(done = 38_000, total = 95_412),
    )
}

// Settings › Show in the app: each kind with its total and what isn't backed up, WhatsApp hidden on all devices.

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=1000dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=1000dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SettingsShown() = Screen {
    SettingsScreen(SettingsState(), SettingsActions(), page = SettingsPage.SHOWN, kinds = kinds)
}

// Settings › Moments: rows by breaks, with the break's length under it, and "How alike" changed from its default.

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=844dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SettingsMoments() = Screen {
    SettingsScreen(
        SettingsState(grouping = TimelineGrouping(GroupingMode.BY_GAP, 12), similarity = SimilarityThreshold(percent = 72, defaultPercent = 85)),
        SettingsActions(),
        page = SettingsPage.MOMENTS,
    )
}

// Settings › Backup: on, on a listed Wi-Fi network without "all the time" location.

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=1500dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=1500dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SettingsBackup() = Screen {
    val backup = BackupSettings(enabled = true, sources = setOf(MediaSource.CAMERA, MediaSource.WHATSAPP), wifiNetworks = setOf("Home"))
    BackupSettingsScreen(
        BackupUi(BackupState(backup), waitingCount = 79, canCheckNetworkInBackground = false, serverUsage = serverUsage, counts = kinds.kinds.associate { it.source to it.files } + (MediaSource.WHATSAPP to 18_458)),
        BackupActions(),
        back = {},
    )
}

// Settings › Storage on this phone: what is kept and its limit, then the previews' format and quality.

@PreviewTest
@Preview(name = "light", device = "spec:width=390dp,height=1300dp,dpi=420", showBackground = true)
@Preview(name = "dark", device = "spec:width=390dp,height=1300dp,dpi=420", showBackground = true, uiMode = UI_MODE_NIGHT_YES)
@Composable
fun SettingsStorage() = Screen {
    SettingsScreen(SettingsState(), SettingsActions(), page = SettingsPage.STORAGE, storage = storage)
}

private val kinds = KindsStatus(
    listOf(
        KindsStatus.Kind(MediaSource.CAMERA, 48_210, 79),
        KindsStatus.Kind(MediaSource.SCREENSHOTS, 2_388, 0),
        KindsStatus.Kind(MediaSource.OTHER, 3_104, 0),
    ),
    listOf(KindsStatus.SoundKind(AudioKind.CALL, 6_196, 0)),
    hiddenEverywhere = 1,
)

private val serverUsage = StorageUsage(photos = 48_210, videos = 1_204, bytes = 214_300_000_000)

/** Previews kept, and "Free up space" after a run whose checked files wait for the system prompt. */
private val storage: StorageUi = run {
    val photo = MediaItem("1", MediaType.IMAGE, 0, LocalDay(2025, 3, 1), null, 4000, 3000, 14_200_000_000L / 1278, "IMG_1.jpg")
    val video = photo.copy(id = "2", type = MediaType.VIDEO)
    val plan = FreeUpPlan(List(1240) { FreeUpGroup(listOf(photo)) } + List(38) { FreeUpGroup(listOf(video)) })
    StorageUi(
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
    )
}
