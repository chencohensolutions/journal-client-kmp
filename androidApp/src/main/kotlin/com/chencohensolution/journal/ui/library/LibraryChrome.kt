package com.chencohensolution.journal.ui.library

import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.expandVertically
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.platform.LocalContext
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.backup.BackupProgress
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R

/** Shown only while the server can't be reached; nothing is shown when it can. */
@Composable
internal fun OfflinePill(modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(LibraryColors.PillScrim, RoundedCornerShape(50))
            .padding(start = 10.dp, end = 12.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.CloudOff, null, tint = LibraryColors.Warning, modifier = Modifier.size(15.dp))
        Text(stringResource(R.string.library_offline), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** "1,248 not backed up": the backup summary above the bottom bar. */
@Composable
internal fun BackupSummaryPill(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier
            .background(LibraryColors.OnBackground.copy(alpha = 0.12f), RoundedCornerShape(50))
            .height(36.dp)
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.CloudOff, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(16.dp))
        Text(text, color = LibraryColors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The floating segmented bar at the bottom of the library screens. */
@Composable
internal fun <T> SegmentedBar(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .shadow(12.dp, RoundedCornerShape(50))
            .background(LibraryColors.Raised.copy(alpha = 0.95f), RoundedCornerShape(50))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        options.forEach { (value, label) ->
            val isSelected = value == selected
            TextButton(
                onClick = { onSelect(value) },
                shape = RoundedCornerShape(50),
                colors = ButtonDefaults.textButtonColors(
                    containerColor = if (isSelected) LibraryColors.Inverse else Color.Transparent,
                    contentColor = if (isSelected) LibraryColors.OnInverse else LibraryColors.OnBackground,
                ),
                modifier = Modifier
                    .height(40.dp)
                    .semantics {
                        role = Role.Tab
                        this.selected = isSelected
                    },
            ) {
                Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The bottom controls: the backup summary (when something isn't backed up) above a [SegmentedBar]. The fade runs to
 * the bottom of the screen, under the system's gesture bar ([insets] keep the controls above it), so no unfaded strip
 * of photos shows there. While [summaryHidden] (scrolling down the home screen) the summary slides away.
 */
@Composable
internal fun BottomControls(
    summary: String?,
    modifier: Modifier = Modifier,
    insets: WindowInsets = WindowInsets.navigationBars,
    summaryHidden: Boolean = false,
    bar: @Composable () -> Unit,
) {
    Column(
        modifier.background(LibraryColors.BottomFade).windowInsetsPadding(insets).padding(top = 48.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (summary != null) {
            // It slides down into its own place as that closes, not over what is below it.
            AnimatedVisibility(
                !summaryHidden,
                enter = expandVertically(expandFrom = Alignment.Top) + slideInVertically { it },
                exit = shrinkVertically(shrinkTowards = Alignment.Top) + slideOutVertically { it },
            ) {
                BackupSummaryPill(summary)
            }
        }
        bar()
    }
}

/** A day label written over the first photo of the day. */
@Composable
internal fun DayLabelOverlay(text: String, modifier: Modifier = Modifier) {
    Box(modifier.background(LibraryColors.LabelFade).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 24.dp)) {
        Text(text, color = LibraryColors.PhotoText, fontSize = 15.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * The bottom pill's text: the running backup ("Backing up 3 of 120 · 42% · 5.1 MB/s"), else how many photos aren't
 * backed up.
 */
@Composable
internal fun backupSummary(library: LibraryState, running: BackupProgress?): String? = when {
    running != null && running.total > 0 -> backupProgressText(running)
    library.notBackedUpCount > 0 -> pluralStringResource(R.plurals.library_not_backed_up, library.notBackedUpCount, library.notBackedUpCount)
    else -> null
}

/** "Backing up 3 of 120 · 42% · 5.1 MB/s": the file, the whole run in percent, and the transfer rate once known. */
@Composable
internal fun backupProgressText(progress: BackupProgress): String {
    val context = LocalContext.current
    val parts = listOfNotNull(
        stringResource(R.string.library_backing_up, (progress.done + 1).coerceAtMost(progress.total), progress.total),
        "${(progress.fraction * 100).toInt()}%",
        progress.bytesPerSecond?.let { transferRate(context, it) },
    )
    return parts.joinToString(" · ")
}

/** "5.1 MB/s". */
internal fun transferRate(context: android.content.Context, bytesPerSecond: Long): String =
    context.getString(R.string.backup_rate, android.text.format.Formatter.formatShortFileSize(context, bytesPerSecond))
