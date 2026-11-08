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
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R

/**
 * Option C: each model beside what it is for. The photo model is a card in Settings › Moments, under "Sets of similar
 * photos" (its How alike slider above it, drawn here as it is); search's model stays where it is today, with Search
 * by words in On device search. Nothing new on Settings' first page.
 */
@Composable
internal fun PhotoModelInMomentsC(state: LabModelsState) {
    val photos = state.models.first { it.kind == LabModelKind.PHOTOS }
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_similarity_title))
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(16.dp)) {
                    Text(stringResource(R.string.settings_similarity_label), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = 60f,
                        onValueChange = {},
                        valueRange = 50f..99f,
                        colors = SliderDefaults.colors(
                            thumbColor = LibraryColors.OnBackground,
                            activeTrackColor = LibraryColors.OnBackground,
                            inactiveTrackColor = LibraryColors.Muted.copy(alpha = 0.3f),
                            activeTickColor = Color.Transparent,
                            inactiveTickColor = Color.Transparent,
                        ),
                    )
                    Text(stringResource(R.string.settings_similarity_body), color = LibraryColors.Muted, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(12.dp))
            if (state.offline) LabOfflineLine()
            SettingsCard { LabModelRow(photos, state.offline) }
            Text(
                stringResource(
                    if (photos.status == LabModelStatus.HERE) R.string.models_c_note_here else R.string.models_c_note_missing,
                ),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
            )
        }
        TitleBar(stringResource(R.string.settings_moments_title), {}, Modifier.align(Alignment.TopCenter))
    }
}
