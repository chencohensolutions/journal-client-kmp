package com.chencohensolution.journal.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.chencohensolution.journal.R

/**
 * Fakes:
 * - The two values are kept only while the lab is open: JournalSettings has neither yet.
 * - Moments doesn't read them: FeedPager.PAGE and LOAD_AHEAD_ROWS stay 30 and 40.
 * - Settings' first page is a copy of the app's (without Backup and storage, as the lab's "current" has it),
 *   scrolled to its end, where the new section is.
 */
internal val settingsOptionC = LabOption("c", "c: Presets", "An Advanced row opens three presets and Custom") {
    var paging by remember { mutableStateOf(SettingsOptionCState()) }
    var open by remember { mutableStateOf(false) }
    if (open) {
        BackHandler { open = false }
        SettingsScreenC(
            paging,
            SettingsActionsC(
                pick = { paging = SettingsOptionCState(it.rowsPerPage, it.loadAhead) },
                pickCustom = { paging = paging.copy(custom = true) },
                setRowsPerPage = { paging = paging.copy(rowsPerPage = it) },
                setLoadAhead = { paging = paging.copy(loadAhead = it) },
                back = { open = false },
            ),
        )
    } else {
        LabMainPage { AdvancedRowC(paging) { open = true } }
    }
}

/** Settings' first page as the app draws it, scrolled to the end, with [end] after Search. */
@Composable
private fun LabMainPage(end: @Composable () -> Unit) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val scroll = rememberScrollState()
    LaunchedEffect(scroll.maxValue) { scroll.scrollTo(scroll.maxValue) }
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(scroll)
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_library_title))
            SettingsCard {
                PageRow(stringResource(R.string.settings_shown_title), stringResource(R.string.settings_shown_summary, "Camera, Other, Petcube +2")) {}
                RowDivider()
                PageRow(
                    stringResource(R.string.settings_moments_title),
                    stringResource(R.string.feed_place_time, stringResource(R.string.settings_grouping_by_day), stringResource(R.string.settings_moments_similar_summary, 60)),
                ) {}
                RowDivider()
                SwitchRow(stringResource(R.string.settings_on_phone_badge), stringResource(R.string.settings_on_phone_badge_body), false) {}
            }
            SectionTitle(stringResource(R.string.settings_privacy_title))
            SettingsCard { PageRow(stringResource(R.string.settings_hidden_title), stringResource(R.string.settings_hidden_row_body)) {} }
            Column(Modifier.padding(top = 20.dp)) {
                SettingsCard { SearchSettingsRow(100, null, null) {} }
            }
            end()
        }
        TitleBar(stringResource(R.string.library_settings), {}, Modifier.align(Alignment.TopCenter))
    }
}
