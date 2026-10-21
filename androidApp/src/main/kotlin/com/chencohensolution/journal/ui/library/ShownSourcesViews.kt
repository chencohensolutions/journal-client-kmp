package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaSource

// Which sources the app shows, as Explore says it: the line under Places / Years / Map ("Camera · Other · WhatsApp
// hidden — Change") and the "Showing" sheet it opens, with the same switches as Settings › Show in the app.

/**
 * "Camera · Other · WhatsApp, Screenshots hidden" and "Change": the sources [shown] and those of [present] it hides.
 * Tapping it opens the "Showing" sheet.
 */
@Composable
internal fun ShowingLine(shown: Set<MediaSource>, present: Set<MediaSource>, onChange: () -> Unit, modifier: Modifier = Modifier) {
    val on = MediaSource.entries.filter { it in shown }.map { sourceName(it) }
    val off = MediaSource.entries.filter { it in present && it !in shown }.map { sourceName(it) }
    val text = when {
        off.isEmpty() -> on.joinToString(" · ")
        on.isEmpty() -> stringResource(R.string.explore_all_hidden, off.joinToString(", "))
        else -> stringResource(R.string.explore_shown_hidden, on.joinToString(" · "), off.joinToString(", "))
    }
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LibraryColors.Raised)
            .clickable(role = Role.Button, onClick = onChange)
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(LibraryIcons.Eye, null, tint = LibraryColors.Muted, modifier = Modifier.size(16.dp))
        Text(text, color = LibraryColors.Muted, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(stringResource(R.string.explore_change), color = LibraryColors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}

/** The sources a library has photos of ([counts]), and those on even without any. */
internal fun presentSources(counts: Map<MediaSource, Int>, shown: Set<MediaSource>): Set<MediaSource> =
    MediaSource.entries.filter { it in shown || (counts[it] ?: 0) > 0 }.toSet()

/**
 * "Showing": a switch per source with how many photos of it are where Explore looks ([here]: "922 here", "—" for
 * none). The same setting as Settings › Show in the app and the Moments filter bar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShowingSheet(shown: Set<MediaSource>, here: Map<MediaSource, Int>, onToggle: (MediaSource) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LibraryColors.Background,
    ) {
        ShowingSheetContent(shown, here, onToggle)
    }
}

/** What the "Showing" sheet holds; drawn by itself in previews, where a sheet doesn't open. */
@Composable
internal fun ShowingSheetContent(shown: Set<MediaSource>, here: Map<MediaSource, Int>, onToggle: (MediaSource) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
        Text(
            stringResource(R.string.explore_sheet_title),
            color = LibraryColors.OnBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
        )
        Text(
            stringResource(R.string.explore_sheet_body),
            color = LibraryColors.Muted,
            fontSize = 13.sp,
            modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 2.dp, bottom = 12.dp),
        )
        SettingsCard {
            MediaSource.entries.forEach { source ->
                val count = here[source] ?: 0
                SwitchRow(
                    title = sourceName(source),
                    body = if (count > 0) stringResource(R.string.explore_sheet_here, count) else stringResource(R.string.explore_sheet_none),
                    checked = source in shown,
                    onChange = { onToggle(source) },
                )
            }
        }
        if ((here[MediaSource.WHATSAPP] ?: 0) > 0 || MediaSource.WHATSAPP !in shown) {
            Text(
                stringResource(R.string.explore_sheet_whatsapp),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp),
            )
        }
    }
}

/** "2,410 WhatsApp · 3 Screenshots", from counts per source. */
@Composable
internal fun sourceCountsText(counts: Map<MediaSource, Int>): String =
    counts.entries.map { (source, count) -> stringResource(R.string.explore_source_count, count, sourceName(source)) }.joinToString(" · ")
