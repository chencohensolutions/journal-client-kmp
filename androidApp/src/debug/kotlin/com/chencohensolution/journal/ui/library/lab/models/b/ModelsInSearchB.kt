package com.chencohensolution.journal.ui.library

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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.local.PhoneSearchState

/**
 * Option B: a "Models" card in Settings › Search, right above On device search (the real section below it), since
 * that is where the app already downloads what it keeps on the phone. Only on Wi-Fi is the section's own switch.
 */
@Composable
internal fun ModelsInSearchB(state: LabModelsState, phoneSearch: PhoneSearchState) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            SectionTitle(stringResource(R.string.models_b_title))
            Text(
                stringResource(R.string.models_b_intro),
                color = LibraryColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 8.dp),
            )
            if (state.offline) LabOfflineLine()
            SettingsCard {
                state.models.forEachIndexed { i, model ->
                    if (i > 0) RowDivider()
                    LabModelRow(model, state.offline)
                }
            }
            PhoneSearchSection(phoneSearch, PhoneSearchActions())
        }
        TitleBar(stringResource(R.string.settings_search_title), {}, Modifier.align(Alignment.TopCenter))
    }
}
