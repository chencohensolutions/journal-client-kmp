package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.settings.SimilarityThreshold
import com.chencohensolution.journal.settings.TimelineGrouping

internal class SettingsActions(
    val setGroupingMode: (GroupingMode) -> Unit = {},
    val setGapHours: (Int) -> Unit = {},
    val setSimilarityPercent: (Int) -> Unit = {},
    val resetSimilarity: () -> Unit = {},
    val back: () -> Unit = {},
)

/** The user's choices, kept on this device. See docs/screens/settings.md. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(settings: SettingsState, actions: SettingsActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val grouping = settings.grouping

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_grouping_title))
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .background(LibraryColors.Raised, RoundedCornerShape(14.dp))
                    .selectableGroup(),
            ) {
                ModeOption(
                    title = stringResource(R.string.settings_grouping_by_day),
                    body = stringResource(R.string.settings_grouping_by_day_body),
                    selected = grouping.mode == GroupingMode.BY_DAY,
                    onSelect = { actions.setGroupingMode(GroupingMode.BY_DAY) },
                )
                ModeOption(
                    title = stringResource(R.string.settings_grouping_by_gap),
                    body = stringResource(R.string.settings_grouping_by_gap_body),
                    selected = grouping.mode == GroupingMode.BY_GAP,
                    onSelect = { actions.setGroupingMode(GroupingMode.BY_GAP) },
                )
            }
            if (grouping.mode == GroupingMode.BY_GAP) {
                SectionTitle(stringResource(R.string.settings_gap_title))
                FlowRow(
                    Modifier.padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TimelineGrouping.GAP_HOURS_OPTIONS.forEach { hours ->
                        FilterChip(
                            selected = grouping.gapHours == hours,
                            onClick = { actions.setGapHours(hours) },
                            label = { Text(pluralStringResource(R.plurals.settings_gap_hours, hours, hours)) },
                            colors = FilterChipDefaults.filterChipColors(
                                labelColor = LibraryColors.OnBackground,
                                selectedContainerColor = LibraryColors.Inverse,
                                selectedLabelColor = LibraryColors.OnInverse,
                            ),
                        )
                    }
                }
            }
            SectionTitle(stringResource(R.string.settings_similarity_title))
            SimilarityCard(settings.similarity, actions)
        }
        TitleBar(stringResource(R.string.library_settings), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

/** How alike photos must be for a "Look alike" suggestion: a slider in percent, saved when let go. */
@Composable
private fun SimilarityCard(similarity: SimilarityThreshold, actions: SettingsActions) {
    var dragging by remember(similarity.percent) { mutableFloatStateOf(similarity.percent.toFloat()) }
    val range = SimilarityThreshold.RANGE
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(LibraryColors.Raised, RoundedCornerShape(14.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                stringResource(R.string.settings_similarity_label),
                color = LibraryColors.OnBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text("${dragging.toInt()}%", color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { actions.setSimilarityPercent(dragging.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = range.last - range.first - 1,
            colors = SliderDefaults.colors(
                thumbColor = LibraryColors.OnBackground,
                activeTrackColor = LibraryColors.OnBackground,
                inactiveTrackColor = LibraryColors.Muted.copy(alpha = 0.3f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        Text(stringResource(R.string.settings_similarity_body), color = LibraryColors.Muted, fontSize = 13.sp)
        if (!similarity.isDefault) {
            TextButton(onClick = actions.resetSimilarity, modifier = Modifier.padding(top = 4.dp)) {
                Text(stringResource(R.string.settings_similarity_reset, similarity.defaultPercent), color = LibraryColors.OnBackground)
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        color = LibraryColors.Muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
private fun ModeOption(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton)
            .padding(start = 4.dp, end = 16.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(
            selected = selected,
            onClick = null,
            colors = RadioButtonDefaults.colors(selectedColor = LibraryColors.OnBackground, unselectedColor = LibraryColors.Muted),
            modifier = Modifier.padding(12.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(title, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = LibraryColors.Muted, fontSize = 13.sp)
        }
    }
}
