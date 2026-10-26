package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R

internal class SettingsActionsA(
    val setRowsPerPage: (Int) -> Unit = {},
    val setLoadAhead: (Int) -> Unit = {},
    val reset: () -> Unit = {},
    val back: () -> Unit = {},
)

/** Settings' last row, under an Advanced title: "Loading Moments: 30 rows at a time · 40 ahead", opening [SettingsScreenA]. */
@Composable
internal fun AdvancedRowA(state: SettingsOptionAState, onOpen: () -> Unit) {
    SectionTitle(stringResource(R.string.settings_a_advanced_title))
    SettingsCard {
        PageRow(stringResource(R.string.settings_a_row_title), stringResource(R.string.settings_a_row_body, state.rowsPerPage, state.loadAhead), onOpen)
    }
}

/**
 * Settings › Advanced › Loading Moments: how Moments is read a page at a time. Two choices of chips, each with what it trades, and a
 * reset once either is changed.
 */
@Composable
internal fun SettingsScreenA(state: SettingsOptionAState, actions: SettingsActionsA) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Spacer(Modifier.height(20.dp))
            SettingsCard {
                Choice(
                    stringResource(R.string.settings_a_rows_title),
                    stringResource(R.string.settings_a_rows_body),
                    SettingsOptionAState.ROWS_PER_PAGE_OPTIONS,
                    state.rowsPerPage,
                    SettingsOptionAState.DEFAULT_ROWS_PER_PAGE,
                    actions.setRowsPerPage,
                )
                RowDivider()
                Choice(
                    stringResource(R.string.settings_a_ahead_title),
                    stringResource(R.string.settings_a_ahead_body),
                    SettingsOptionAState.LOAD_AHEAD_OPTIONS,
                    state.loadAhead,
                    SettingsOptionAState.DEFAULT_LOAD_AHEAD,
                    actions.setLoadAhead,
                )
            }
            Text(
                stringResource(R.string.settings_a_note),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp),
            )
            if (!state.isDefault) {
                TextButton(onClick = actions.reset, modifier = Modifier.padding(start = 20.dp, top = 4.dp)) {
                    Text(stringResource(R.string.settings_a_reset), color = LibraryColors.OnBackground)
                }
            }
        }
        TitleBar(stringResource(R.string.settings_a_row_title), actions.back, Modifier.align(Alignment.TopCenter))
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
                val label = if (n == default) stringResource(R.string.settings_a_default_chip, n) else n.toString()
                Chip(label, value == n) { onPick(n) }
            }
        }
    }
}
