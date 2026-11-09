package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.settings.MomentsPaging

/** Settings' last section, Advanced: "Loading Moments · 30 rows at a time · 40 ahead", opening [LoadingMomentsPage]. */
@Composable
internal fun AdvancedSection(paging: MomentsPaging, onOpen: () -> Unit) {
    SectionTitle(stringResource(R.string.settings_advanced_title))
    SettingsCard {
        PageRow(stringResource(R.string.settings_loading_title), stringResource(R.string.settings_loading_row_body, paging.rowsPerPage, paging.loadAhead), onOpen)
    }
}

/**
 * Settings › Loading Moments (chosen by the owner from the UI lab, round ux/settings/2026-10-26): how Moments is read
 * a page at a time. Two choices of chips, each saying what more of it gives, the default's chip marked, and a reset
 * once either is changed.
 */
@Composable
internal fun LoadingMomentsPage(paging: MomentsPaging, actions: SettingsActions) {
    Spacer(Modifier.height(20.dp))
    SettingsCard {
        Choice(
            stringResource(R.string.settings_loading_rows_title),
            stringResource(R.string.settings_loading_rows_body),
            MomentsPaging.ROWS_PER_PAGE_OPTIONS,
            paging.rowsPerPage,
            MomentsPaging.DEFAULT_ROWS_PER_PAGE,
            actions.setMomentsRowsPerPage,
        )
        RowDivider()
        Choice(
            stringResource(R.string.settings_loading_ahead_title),
            stringResource(R.string.settings_loading_ahead_body),
            MomentsPaging.LOAD_AHEAD_OPTIONS,
            paging.loadAhead,
            MomentsPaging.DEFAULT_LOAD_AHEAD,
            actions.setMomentsLoadAhead,
        )
        RowDivider()
        Choice(
            stringResource(R.string.settings_viewer_ahead),
            stringResource(R.string.settings_viewer_ahead_body),
            MomentsPaging.VIEWER_AHEAD_OPTIONS,
            paging.viewerAhead,
            MomentsPaging.DEFAULT_VIEWER_AHEAD,
            actions.setViewerAhead,
        )
    }
    Text(
        stringResource(R.string.settings_loading_note),
        color = LibraryColors.Muted,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp),
    )
    if (!paging.isDefault) {
        TextButton(onClick = actions.resetMomentsPaging, modifier = Modifier.padding(start = 20.dp, top = 4.dp)) {
            Text(
                stringResource(R.string.settings_loading_reset, MomentsPaging.DEFAULT_ROWS_PER_PAGE, MomentsPaging.DEFAULT_LOAD_AHEAD),
                color = LibraryColors.OnBackground,
            )
        }
    }
}

/** A title, what it trades, and a chip per value; the default's chip says so. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(title: String, body: String, options: List<Int>, value: Int, default: Int, onPick: (Int) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Text(title, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp))
        Text(body, color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp))
        // Not OptionChips: these sit under a plain title, without its radio button's indent.
        FlowRow(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { n ->
                val label = if (n == default) stringResource(R.string.settings_loading_default_chip, n) else n.toString()
                Chip(label, value == n) { onPick(n) }
            }
        }
    }
}
