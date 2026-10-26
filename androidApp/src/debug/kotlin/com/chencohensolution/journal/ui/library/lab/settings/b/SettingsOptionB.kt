package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R

internal class SettingsActionsB(
    val setRowsPerPage: (Int) -> Unit = {},
    val setLoadAhead: (Int) -> Unit = {},
    val reset: () -> Unit = {},
)

/**
 * Settings' last section, Advanced, on the first page itself: a slider for each value, saved when let go (as How
 * alike is), what each trades under it, and a reset once either is changed.
 */
@Composable
internal fun SettingsScreenB(state: SettingsOptionBState, actions: SettingsActionsB) {
    SectionTitle(stringResource(R.string.settings_b_advanced_title))
    SettingsCard {
        SliderRow(
            stringResource(R.string.settings_b_rows_title),
            stringResource(R.string.settings_b_rows_body),
            state.rowsPerPage,
            SettingsOptionBState.ROWS_PER_PAGE_RANGE,
            actions.setRowsPerPage,
        )
        RowDivider()
        SliderRow(
            stringResource(R.string.settings_b_ahead_title),
            stringResource(R.string.settings_b_ahead_body),
            state.loadAhead,
            SettingsOptionBState.LOAD_AHEAD_RANGE,
            actions.setLoadAhead,
        )
    }
    Text(
        stringResource(R.string.settings_b_note),
        color = LibraryColors.Muted,
        fontSize = 13.sp,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp),
    )
    if (!state.isDefault) {
        TextButton(onClick = actions.reset, modifier = Modifier.padding(start = 20.dp, top = 4.dp)) {
            Text(
                stringResource(R.string.settings_b_reset, SettingsOptionBState.DEFAULT_ROWS_PER_PAGE, SettingsOptionBState.DEFAULT_LOAD_AHEAD),
                color = LibraryColors.OnBackground,
            )
        }
    }
}

/** A title with its value at the end, a slider over [range] in its steps, and what it trades. */
@Composable
private fun SliderRow(title: String, body: String, value: Int, range: IntProgression, onSet: (Int) -> Unit) {
    var dragging by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text(dragging.toInt().toString(), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        Slider(
            value = dragging,
            onValueChange = { dragging = it },
            onValueChangeFinished = { onSet(dragging.toInt()) },
            valueRange = range.first.toFloat()..range.last.toFloat(),
            steps = (range.last - range.first) / range.step - 1,
            colors = SliderDefaults.colors(
                thumbColor = LibraryColors.OnBackground,
                activeTrackColor = LibraryColors.OnBackground,
                inactiveTrackColor = LibraryColors.Muted.copy(alpha = 0.3f),
                activeTickColor = Color.Transparent,
                inactiveTickColor = Color.Transparent,
            ),
        )
        Text(body, color = LibraryColors.Muted, fontSize = 13.sp)
    }
}
