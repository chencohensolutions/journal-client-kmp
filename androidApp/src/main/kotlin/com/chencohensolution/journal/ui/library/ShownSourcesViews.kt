package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import java.text.NumberFormat
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.settings.ShownSources

// Which sources the app shows, as Explore says it: the line under Places / Years / Map ("Camera · Other · WhatsApp
// hidden — Change") and the "Showing" sheet it opens, with the same switches as Settings › Show in the app.

/**
 * "Camera · Other · WhatsApp, Screenshots hidden" and "Change": the sources [shown] and those of [present] it hides.
 * Tapping it opens the "Showing" sheet.
 */
@Composable
internal fun ShowingLine(shown: Set<MediaSource>, present: Set<MediaSource>, onChange: () -> Unit, modifier: Modifier = Modifier) {
    val on = shown.sorted().map { sourceName(it) }
    val off = present.filter { it !in shown }.sorted().map { sourceName(it) }
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
    (shown + counts.filterValues { it > 0 }.keys).sorted().toSet()

/** Of the fixed sources and those [counts] has photos of, the ones the setting shows. */
internal fun ShownSources.shownAmong(counts: Map<MediaSource, Int>?): Set<MediaSource> =
    shownOf(MediaSource.choices(counts?.keys.orEmpty()))

/**
 * Explore's "Showing": a pill per source with how many photos of it are where Explore looks ([here]), the most first.
 * The same setting as Settings › Show in the app and the Moments filter bar.
 */
@Composable
internal fun ShowingSheet(shown: Set<MediaSource>, here: Map<MediaSource, Int>, onToggle: (MediaSource) -> Unit, onDismiss: () -> Unit) =
    ShowingPillsSheet(MediaSource.byCount(here, shown), here, { it in shown }, onToggle, onDismiss, whatsAppNote = explainsWhatsApp(shown, here))

/** Explore's note that hiding WhatsApp changes little on the map: when it has photos here, or is hidden. */
internal fun explainsWhatsApp(shown: Set<MediaSource>, here: Map<MediaSource, Int>) =
    (here[MediaSource.WHATSAPP] ?: 0) > 0 || MediaSource.WHATSAPP !in shown

/** The "Showing" sheet of the Moments filter bar's "+N" and of Explore: a pill per source in [sources], in that order. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShowingPillsSheet(
    sources: List<MediaSource>,
    counts: Map<MediaSource, Int>,
    isOn: (MediaSource) -> Boolean,
    onToggle: (MediaSource) -> Unit,
    onDismiss: () -> Unit,
    whatsAppNote: Boolean = false,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LibraryColors.Background,
    ) {
        ShowingSheetContent(sources, counts, isOn, onToggle, whatsAppNote)
    }
}

/** What the "Showing" sheet holds; drawn by itself in previews, where a sheet doesn't open. */
@Composable
internal fun ShowingSheetContent(
    sources: List<MediaSource>,
    counts: Map<MediaSource, Int>,
    isOn: (MediaSource) -> Boolean,
    onToggle: (MediaSource) -> Unit,
    whatsAppNote: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(bottom = 16.dp)) {
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
        SourcePills(sources, counts, isOn, onToggle)
        if (whatsAppNote) {
            Text(
                stringResource(R.string.explore_sheet_whatsapp),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp),
            )
        }
    }
}

/** A pill per source, "Petcube · 5" (its photos in [counts]), filled when [isOn]: Settings › Show in the app and Backup. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SourcePills(sources: List<MediaSource>, counts: Map<MediaSource, Int>, isOn: (MediaSource) -> Boolean, onToggle: (MediaSource) -> Unit) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        sources.forEach { source ->
            val count = counts[source] ?: 0
            Chip(
                if (count > 0) "${sourceName(source)} · ${NumberFormat.getIntegerInstance().format(count)}" else sourceName(source),
                isOn(source),
            ) { onToggle(source) }
        }
    }
}

/** "2,410 WhatsApp · 3 Screenshots", from counts per source. */
@Composable
internal fun sourceCountsText(counts: Map<MediaSource, Int>): String =
    counts.entries.map { (source, count) -> stringResource(R.string.explore_source_count, count, sourceName(source)) }.joinToString(" · ")
