package com.chencohensolution.journal.ui.library

import android.text.format.Formatter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.PreviewFormat
import com.chencohensolution.journal.server.PreviewOptions
import com.chencohensolution.journal.server.PreviewQuality
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.storage.FreeUpLine
import com.chencohensolution.journal.storage.FreeUpSummary
import com.chencohensolution.journal.storage.FreeUpRule
import com.chencohensolution.journal.storage.FreeUpState
import com.chencohensolution.journal.storage.KeptReason

// Settings › Storage on this phone: the space previews may take, and how they look; and the lines of Free up space.

/** What the Storage section shows. */
internal data class StorageUi(
    /** Bytes the previews of server photos take up here; null while counting. */
    val previewBytes: Long?,
    val previewLimitGigabytes: Int,
    val freeUp: FreeUpState,
    /** "Media management" access: files go to the trash without a prompt each time. */
    val canTrashWithoutAsking: Boolean,
    /** Previews from the server (format, quality), or originals. */
    val previewOptions: PreviewOptions = PreviewOptions(),
)

internal class StorageActions(
    val setPreviewsEnabled: (Boolean) -> Unit = {},
    val setPreviewFormat: (PreviewFormat) -> Unit = {},
    val setPreviewQuality: (PreviewQuality) -> Unit = {},
    val setFirstImageFormat: (PreviewFormat) -> Unit = {},
    val setFirstImageWidth: (Int) -> Unit = {},
    val setPreviewLimit: (Int) -> Unit = {},
    val clearPreviews: () -> Unit = {},
    val setRule: (FreeUpRule) -> Unit = {},
    /** Starts "Free up space" in the background. */
    val freeUp: () -> Unit = {},
    /** Opens the system page for "Media management" access, then starts "Free up space". */
    val allowTrashWithoutAsking: () -> Unit = {},
    /** One system prompt for the checked files that wait. */
    val moveWaitingToTrash: () -> Unit = {},
    /** Lets a source's photos leave the phone, or keeps them. */
    val toggleSource: (MediaSource) -> Unit = {},
    /** Opens the Free up space screen. */
    val openFreeUp: () -> Unit = {},
)

/**
 * Settings › Storage on this phone: the space photos from the server may take here (with Clear), then how they look:
 * previews in a format and quality, or the originals, and the quick first image of a photo only on the server, shown at
 * once and while a list moves fast, before its sharp preview (docs/design.md): its width in percent of the screen's,
 * or none, and its format.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StorageSection(ui: StorageUi, actions: StorageActions) {
    val context = LocalContext.current
    val options = ui.previewOptions
    SectionTitle(stringResource(R.string.settings_storage_kept))
    SettingsCard {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(stringResource(R.string.settings_storage_used), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(
                ui.previewBytes?.let {
                    stringResource(
                        R.string.settings_storage_previews_used,
                        Formatter.formatShortFileSize(context, it),
                        stringResource(R.string.settings_storage_gigabytes, ui.previewLimitGigabytes),
                    )
                } ?: stringResource(R.string.settings_storage_counting),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            FlowRow(Modifier.padding(top = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SettingsState.PREVIEW_CACHE_GB_OPTIONS.forEach { gigabytes ->
                    Chip(stringResource(R.string.settings_storage_gigabytes, gigabytes), ui.previewLimitGigabytes == gigabytes) { actions.setPreviewLimit(gigabytes) }
                }
            }
            TextButton(onClick = actions.clearPreviews) { Text(stringResource(R.string.settings_storage_clear_previews), color = LibraryColors.OnBackground) }
        }
    }

    SectionTitle(stringResource(R.string.settings_image_quality_title))
    SettingsCard {
        SwitchRow(
            title = stringResource(R.string.settings_storage_previews),
            body = stringResource(if (options.enabled) R.string.settings_storage_previews_on else R.string.settings_storage_previews_off),
            checked = options.enabled,
            onChange = actions.setPreviewsEnabled,
        )
        if (options.enabled) {
            Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, bottom = 12.dp)) {
                Text(stringResource(R.string.settings_storage_format), color = LibraryColors.OnBackground, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                FlowRow(Modifier.padding(top = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewFormat.entries.forEach { format ->
                        Chip(formatName(format), options.format == format) { actions.setPreviewFormat(format) }
                    }
                }
                Text(formatHint(options.format), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                Text(
                    stringResource(R.string.settings_storage_quality),
                    color = LibraryColors.OnBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                FlowRow(Modifier.padding(top = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewQuality.entries.forEach { quality ->
                        Chip(qualityName(quality), options.quality == quality) { actions.setPreviewQuality(quality) }
                    }
                }
            }
        }
        HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)) {
            Text(stringResource(R.string.settings_first_image), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(stringResource(R.string.settings_first_image_body), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            Text(
                stringResource(R.string.settings_first_image_size),
                color = LibraryColors.OnBackground,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(top = 12.dp),
            )
            FlowRow(Modifier.padding(top = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PreviewOptions.FIRST_WIDTH_OPTIONS.forEach { percent ->
                    val label = if (percent == 0) stringResource(R.string.settings_first_image_off) else stringResource(R.string.settings_first_image_percent, percent)
                    Chip(label, options.firstWidthPercent == percent) { actions.setFirstImageWidth(percent) }
                }
            }
            if (options.firstWidthPercent > 0) {
                Text(
                    stringResource(R.string.settings_storage_format),
                    color = LibraryColors.OnBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 12.dp),
                )
                FlowRow(Modifier.padding(top = 4.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PreviewFormat.entries.forEach { format ->
                        Chip(formatName(format), options.firstFormat == format) { actions.setFirstImageFormat(format) }
                    }
                }
                Text(formatHint(options.firstFormat), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            }
        }
    }
}

/** Storage on this phone's row in Settings: "1.8 GB of 10 GB · WebP, High", or "· Originals" with previews off. */
@Composable
internal fun storageSummary(ui: StorageUi): String {
    val limit = stringResource(R.string.settings_storage_gigabytes, ui.previewLimitGigabytes)
    val used = ui.previewBytes?.let { stringResource(R.string.settings_storage_summary, Formatter.formatShortFileSize(LocalContext.current, it), limit) }
        ?: stringResource(R.string.settings_storage_counting)
    val options = ui.previewOptions
    val images = if (options.enabled) "${formatName(options.format)}, ${qualityName(options.quality)}" else stringResource(R.string.settings_storage_originals)
    return stringResource(R.string.feed_place_time, used, images)
}

internal const val DEFAULT_KEEP_GIGABYTES = 10

/** The Settings row ([FreeUpState.summary]): what Free up space would free, or what it is doing. */
@Composable
internal fun freeUpRowText(state: FreeUpState): String {
    val context = LocalContext.current
    return when (val summary = state.summary) {
        is FreeUpSummary.Checking -> stringResource(R.string.settings_free_up_running, summary.number, summary.total)
        is FreeUpSummary.Waiting -> pluralStringResource(
            R.plurals.settings_free_up_waiting, summary.count, summary.count, Formatter.formatShortFileSize(context, summary.bytes),
        )
        FreeUpSummary.Counting -> stringResource(R.string.settings_storage_counting)
        FreeUpSummary.Nothing -> stringResource(R.string.settings_free_up_row_none)
        is FreeUpSummary.CanFree -> stringResource(R.string.settings_free_up_row_amount, Formatter.formatShortFileSize(context, summary.bytes))
    }
}

@Composable
private fun formatName(format: PreviewFormat): String = stringResource(
    when (format) {
        PreviewFormat.WEBP -> R.string.settings_storage_format_webp
        PreviewFormat.HEIF -> R.string.settings_storage_format_heif
        PreviewFormat.AVIF -> R.string.settings_storage_format_avif
        PreviewFormat.JPEG_XL -> R.string.settings_storage_format_jxl
        PreviewFormat.JPEG -> R.string.settings_storage_format_jpeg
    },
)

/** What a format is good for. */
@Composable
private fun formatHint(format: PreviewFormat): String = stringResource(
    when (format) {
        PreviewFormat.WEBP -> R.string.settings_storage_format_webp_hint
        PreviewFormat.HEIF -> R.string.settings_storage_format_heif_hint
        PreviewFormat.AVIF -> R.string.settings_storage_format_avif_hint
        PreviewFormat.JPEG_XL -> R.string.settings_storage_format_jxl_hint
        PreviewFormat.JPEG -> R.string.settings_storage_format_jpeg_hint
    },
)

@Composable
private fun qualityName(quality: PreviewQuality): String = stringResource(
    when (quality) {
        PreviewQuality.LOW -> R.string.settings_storage_quality_low
        PreviewQuality.MEDIUM -> R.string.settings_storage_quality_medium
        PreviewQuality.HIGH -> R.string.settings_storage_quality_high
        PreviewQuality.MAX -> R.string.settings_storage_quality_max
    },
)

/** "3 months", "1 year". */
@Composable
internal fun ageText(days: Int): String =
    if (days % 365 == 0) {
        pluralStringResource(R.plurals.settings_free_up_years, days / 365, days / 365)
    } else {
        pluralStringResource(R.plurals.settings_free_up_months, days / 30, days / 30)
    }

/** "1,240 photos, 38 videos". */
@Composable
internal fun photosAndVideos(photos: Int, videos: Int): String = listOfNotNull(
    pluralStringResource(R.plurals.settings_backup_server_photos, photos, photos).takeIf { photos > 0 },
    pluralStringResource(R.plurals.settings_backup_server_videos, videos, videos).takeIf { videos > 0 },
).joinToString(", ")

/** [FreeUpState.statusLines] as text: the run going on, why it stopped, or what the last one did; and what waits. */
@Composable
internal fun freeUpStatus(state: FreeUpState): String? {
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    val lines = state.statusLines.map { line ->
        when (line) {
            is FreeUpLine.Checking -> stringResource(R.string.settings_free_up_running, line.number, line.total) + (line.fileName?.let { " · $it" } ?: "")
            is FreeUpLine.Stopped -> stringResource(R.string.settings_free_up_stopped, line.message)
            is FreeUpLine.Removed -> pluralStringResource(R.plurals.settings_free_up_removed, line.count, line.count, size(line.bytes))
            is FreeUpLine.Kept -> pluralStringResource(
                when (line.reason) {
                    KeptReason.CHANGED_ON_PHONE -> R.plurals.settings_free_up_kept_changed
                    KeptReason.MISSING_ON_SERVER -> R.plurals.settings_free_up_kept_missing
                    KeptReason.DAMAGED_ON_SERVER -> R.plurals.settings_free_up_kept_damaged
                },
                line.count,
                line.count,
            )
            is FreeUpLine.Waiting -> pluralStringResource(R.plurals.settings_free_up_waiting, line.count, line.count, size(line.bytes))
        }
    }
    return lines.takeIf { it.isNotEmpty() }?.joinToString("\n")
}
