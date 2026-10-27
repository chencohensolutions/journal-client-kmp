package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.server.HiddenCollections
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.ShownSourcesPills
import java.text.NumberFormat

// Show in the app's pills (option b2 of the UI lab, Oct 27, 2026), in Settings › Show in the app and in the "Showing"
// sheets of Moments and Explore: the phones' sources, one "Imported" with its kinds under it while on, and what Private
// settings hides on every device said in place, never switched here. State: ShownSourcesPills (shared logic).

/** What Show in the app's pills can do, wherever they are drawn. */
internal class ShownSourcesActions(
    /** A source on or off, phones' or one of Imported's kinds (the same as the Moments filter bar). */
    val toggleSource: (MediaSource) -> Unit = {},
    /** Imported, all of it, on or off. */
    val toggleImported: () -> Unit = {},
    /** Private settings, behind the phone's lock. */
    val openPrivate: () -> Unit = {},
)

/**
 * Show in the app as every screen changes it, provided once by the routing table: the setting, its actions, and what
 * Private settings hides on every device (null until it was opened in this run).
 */
internal class ShownSourcesChoice(
    val shown: ShownSources = ShownSources(),
    val actions: ShownSourcesActions = ShownSourcesActions(),
    val hiddenEverywhere: HiddenCollections? = null,
)

internal val LocalShownSources = compositionLocalOf { ShownSourcesChoice() }

/** The pills of [counts] (photos per source, shown or not) as the setting and Private settings have them now. */
@Composable
internal fun rememberShownPills(counts: Map<MediaSource, Int>): ShownSourcesPills {
    val choice = LocalShownSources.current
    return remember(choice.shown, choice.hiddenEverywhere, counts) { ShownSourcesPills.of(choice.shown, counts, choice.hiddenEverywhere) }
}

/** What Private settings hides on every device, as [library] last read it; null until it is opened. */
@Composable
internal fun hiddenEverywhereOf(library: com.chencohensolution.journal.server.ServerLibrary?): State<HiddenCollections?> =
    library?.hiddenEverywhere?.collectAsState() ?: remember { mutableStateOf(null) }

/**
 * The pills, the kinds under Imported while it is on, the line on Other, and what Private settings hides (or, before it
 * was opened, what it can hide), with "Private settings ›".
 */
@Composable
internal fun ShownSourcesPillsSection(pills: ShownSourcesPills, actions: ShownSourcesActions) {
    Pills(pills, actions)
    ImportedKinds(pills, actions)
    OtherLine(pills)
    HiddenEverywhereLine(pills, actions.openPrivate)
}

/** The phone's sources, the most photos first, then "Imported" with its box icon and the count of what it has. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Pills(pills: ShownSourcesPills, actions: ShownSourcesActions) {
    FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        pills.fromPhones.forEach { pill ->
            SourcePill(pill, small = false, onToggle = { actions.toggleSource(pill.source) }, openPrivate = actions.openPrivate)
        }
        if (pills.hasImported) {
            FilterBarChip(
                stringResource(R.string.sources_imported),
                FeedIcons.Imported,
                count(pills.importedCount),
                on = pills.importedShown,
                keepIcon = true,
                onClick = actions.toggleImported,
            )
        }
    }
}

/** While Imported is on: "Of what was imported", its kinds as smaller pills in a tinted box. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ImportedKinds(pills: ShownSourcesPills, actions: ShownSourcesActions) {
    if (!pills.importedShown || !pills.hasImported) return
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .background(LibraryColors.Raised, RoundedCornerShape(14.dp))
            .padding(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(FeedIcons.Imported, null, tint = LibraryColors.Muted, modifier = Modifier.size(14.dp))
            Text(stringResource(R.string.sources_of_imported), color = LibraryColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        FlowRow(
            Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            pills.importedKinds.forEach { pill ->
                SourcePill(pill, small = true, onToggle = { actions.toggleSource(pill.source) }, openPrivate = actions.openPrivate)
            }
        }
    }
}

/**
 * A source's pill: locked and dimmed when Private settings hides it whole (tapping opens Private settings), with a
 * lock after its count when it hides part of it, else the plain pill.
 */
@Composable
private fun SourcePill(pill: ShownSourcesPills.Pill, small: Boolean, onToggle: () -> Unit, openPrivate: () -> Unit) {
    when {
        pill.allHiddenEverywhere -> Box(Modifier.alpha(0.5f)) {
            FilterBarChip(sourceName(pill.source), LibraryIcons.Lock, null, on = false, small = small, onClick = openPrivate)
        }
        else -> FilterBarChip(
            sourceName(pill.source),
            pill.source.icon,
            count(pill.count),
            on = pill.shown,
            small = small,
            locked = pill.hiddenEverywhere > 0,
            onClick = onToggle,
        )
    }
}

/** "Other: files whose name and folder say nothing…", while Other is among the pills (the owner asked, Oct 27, 2026). */
@Composable
private fun OtherLine(pills: ShownSourcesPills) {
    if (pills.fromPhones.none { it.source == MediaSource.OTHER } && pills.importedKinds.none { it.source.kind == MediaSource.OTHER }) return
    Row(Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp)) {
        Text(
            stringResource(R.string.sources_other_about, stringResource(R.string.feed_source_other)),
            color = LibraryColors.Muted,
            fontSize = 13.sp,
        )
    }
}

/**
 * Once Private settings was opened: "🔒 Hidden on all your devices: WhatsApp, 1,640 more Screenshots. Not counted
 * here." Before, or when it hides none of these sources, what it can hide. Either way "Private settings ›" opens it.
 */
@Composable
private fun HiddenEverywhereLine(pills: ShownSourcesPills, openPrivate: () -> Unit) {
    val whole = pills.hiddenWhole
    val part = pills.hiddenInPart
    val text = if (!pills.privateKnown || (whole.isEmpty() && part.isEmpty())) {
        stringResource(R.string.sources_private_about)
    } else {
        val names = whole.map { sourceName(it) } +
            part.map { (source, n) -> stringResource(R.string.sources_more, stringResource(R.string.explore_source_count, n, sourceName(source))) }
        stringResource(R.string.sources_everywhere, names.joinToString(", "))
    }
    Row(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 12.dp)
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = openPrivate)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Icon(LibraryIcons.Lock, null, tint = LibraryColors.Muted, modifier = Modifier.padding(top = 2.dp).size(15.dp))
        Column(Modifier.weight(1f)) {
            Text(text, color = LibraryColors.Muted, fontSize = 13.sp)
            Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.settings_hidden_title), color = LibraryColors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Icon(LibraryIcons.ChevronRight, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(16.dp))
            }
        }
    }
}

private fun count(n: Int) = NumberFormat.getIntegerInstance().format(n)
