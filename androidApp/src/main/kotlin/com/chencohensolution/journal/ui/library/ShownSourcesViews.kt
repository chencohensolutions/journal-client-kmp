package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.ShownSourcesPills

// Which sources the app shows, as Explore says it: the line under Places / Years / Map ("Camera · Other · WhatsApp
// hidden — Change") and the "Showing" sheet it opens, with the same pills as Settings › Show in the app.

/**
 * "Camera · Imported · WhatsApp, Screenshots hidden" and "Change": the sources [shown] and those of [present] it hides,
 * what was imported said once as "Imported". Tapping it opens the "Showing" sheet.
 */
@Composable
internal fun ShowingLine(shown: Set<MediaSource>, present: Set<MediaSource>, onChange: () -> Unit, modifier: Modifier = Modifier) {
    val importedShown = LocalShownSources.current.shown.importedShown
    val imported = stringResource(R.string.sources_imported)
    val anyImported = present.any { it.isImported }
    val on = shown.filterNot { it.isImported }.sorted().map { sourceName(it) } +
        listOfNotNull(imported.takeIf { importedShown && shown.any { it.isImported } })
    val off = present.filter { !it.isImported && it !in shown }.sorted().map { sourceName(it) } +
        listOfNotNull(imported.takeIf { anyImported && !importedShown })
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
    shownAmong(counts.orEmpty())

/** Explore's note that hiding WhatsApp changes little on the map: when it has photos here, or is hidden. */
internal fun explainsWhatsApp(shown: ShownSources, here: Map<MediaSource, Int>) =
    (here[MediaSource.WHATSAPP] ?: 0) > 0 || !shown.isShown(MediaSource.WHATSAPP)

/**
 * The "Showing" sheet of the Moments filter bar's "+N" and of Explore: Show in the app's pills over [counts] (photos per
 * source where it was opened), changed through [LocalShownSources]. With [whatsAppNote] (Explore), the line on
 * WhatsApp and the map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ShowingSheet(counts: Map<MediaSource, Int>, onDismiss: () -> Unit, whatsAppNote: Boolean = false) {
    val choice = LocalShownSources.current
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LibraryColors.Background,
    ) {
        ShowingSheetContent(rememberShownPills(counts), choice.actions, whatsAppNote && explainsWhatsApp(choice.shown, counts))
    }
}

/** What the "Showing" sheet holds; drawn by itself in previews, where a sheet doesn't open. */
@Composable
internal fun ShowingSheetContent(pills: ShownSourcesPills, actions: ShownSourcesActions, whatsAppNote: Boolean = false) {
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
        ShownSourcesPillsSection(pills, actions)
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

/** "2,410 WhatsApp · 3 Screenshots", from counts per source. */
@Composable
internal fun sourceCountsText(counts: Map<MediaSource, Int>): String =
    counts.entries.map { (source, count) -> stringResource(R.string.explore_source_count, count, sourceName(source)) }.joinToString(" · ")
