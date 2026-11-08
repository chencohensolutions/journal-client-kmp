package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R

/**
 * Option A: a page of its own, "Models on this phone", opened from a row in Settings' Search group ("Models · 1 of
 * 2 downloaded"): every model the app runs, each with how it stands and its button, Download all, Only on Wi-Fi, and
 * the space they take.
 */
@Composable
internal fun ModelsPageA(state: LabModelsState) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Text(
                stringResource(R.string.models_a_intro),
                color = LibraryColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 12.dp),
            )
            if (state.offline) LabOfflineLine()
            SettingsCard {
                state.models.forEachIndexed { i, model ->
                    if (i > 0) RowDivider()
                    LabModelRow(model, state.offline)
                }
            }
            if (state.missing.isNotEmpty()) {
                Button(
                    onClick = {},
                    enabled = !state.offline,
                    colors = ButtonDefaults.buttonColors(containerColor = LibraryColors.Inverse, contentColor = LibraryColors.OnInverse),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.models_a_download_all, labSize(state.missing.sumOf { it.bytes })))
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.models_wifi_only),
                    body = stringResource(R.string.models_wifi_only_body),
                    checked = state.wifiOnly,
                    onChange = {},
                )
            }
            Text(
                stringResource(R.string.models_space, labSize(state.keptBytes), labSize(state.freeBytes)),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp),
            )
        }
        TitleBar(stringResource(R.string.models_a_title), {}, Modifier.align(Alignment.TopCenter))
    }
}

/** Option A's entry: the row on Settings' first page, in the Search group, under Search. */
@Composable
internal fun ModelsRowA(state: LabModelsState) {
    val here = state.models.count { it.status == LabModelStatus.HERE }
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(Modifier.padding(top = insets.calculateTopPadding() + 64.dp)) {
            SectionTitle(stringResource(R.string.models_a_group))
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.settings_search_title), color = LibraryColors.OnBackground, fontSize = 16.sp)
                    Text(stringResource(R.string.models_a_search_row), color = LibraryColors.Muted, fontSize = 13.sp)
                }
                RowDivider()
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.models_a_title), color = LibraryColors.OnBackground, fontSize = 16.sp)
                    Text(
                        stringResource(R.string.models_a_row, here, state.models.size),
                        color = if (state.missing.isEmpty()) LibraryColors.Muted else LibraryColors.OnBackground,
                        fontSize = 13.sp,
                    )
                }
            }
        }
        TitleBar(stringResource(R.string.library_settings), {}, Modifier.align(Alignment.TopCenter))
    }
}
