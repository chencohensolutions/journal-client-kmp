package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.ui.library.SettingsOptionCState.Preset

internal class SettingsActionsC(
    val pick: (Preset) -> Unit = {},
    val pickCustom: () -> Unit = {},
    val setRowsPerPage: (Int) -> Unit = {},
    val setLoadAhead: (Int) -> Unit = {},
    val back: () -> Unit = {},
)

/** Settings' last row, under an Advanced title: "Loading Moments: Balanced", opening [SettingsScreenC]. */
@Composable
internal fun AdvancedRowC(state: SettingsOptionCState, onOpen: () -> Unit) {
    SectionTitle(stringResource(R.string.settings_c_advanced_title))
    SettingsCard {
        val body = state.preset?.let { presetName(it) } ?: stringResource(R.string.settings_c_custom_summary, state.rowsPerPage, state.loadAhead)
        PageRow(stringResource(R.string.settings_c_row_title), body, onOpen)
    }
}

/**
 * Settings › Advanced › Loading Moments: three presets that say what they are for, each with its two numbers, and
 * Custom, which shows a − / + for each value.
 */
@Composable
internal fun SettingsScreenC(state: SettingsOptionCState, actions: SettingsActionsC) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            SectionTitle(stringResource(R.string.settings_c_how_title))
            Column(
                Modifier
                    .padding(horizontal = 16.dp)
                    .fillMaxWidth()
                    .background(LibraryColors.Raised, RoundedCornerShape(14.dp))
                    .selectableGroup(),
            ) {
                Preset.entries.forEach { preset ->
                    ModeOption(
                        title = presetName(preset),
                        body = presetBody(preset),
                        selected = state.preset == preset,
                        onSelect = { actions.pick(preset) },
                    )
                }
                ModeOption(
                    title = stringResource(R.string.settings_c_custom),
                    body = stringResource(R.string.settings_c_custom_body),
                    selected = state.preset == null,
                    onSelect = actions.pickCustom,
                )
                if (state.preset == null) {
                    Stepper(stringResource(R.string.settings_c_rows_title), state.rowsPerPage, SettingsOptionCState.ROWS_PER_PAGE_STEPS, actions.setRowsPerPage)
                    Stepper(stringResource(R.string.settings_c_ahead_title), state.loadAhead, SettingsOptionCState.LOAD_AHEAD_STEPS, actions.setLoadAhead)
                }
            }
            Text(
                stringResource(R.string.settings_c_note),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp),
            )
        }
        TitleBar(stringResource(R.string.settings_c_row_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun presetName(preset: Preset): String = stringResource(
    when (preset) {
        Preset.LIGHT -> R.string.settings_c_light
        Preset.BALANCED -> R.string.settings_c_balanced
        Preset.SMOOTH -> R.string.settings_c_smooth
    },
)

/** What the preset is for, then its numbers: "Fewest photos in memory · 15 rows at a time, 20 ahead". */
@Composable
private fun presetBody(preset: Preset): String {
    val what = stringResource(
        when (preset) {
            Preset.LIGHT -> R.string.settings_c_light_body
            Preset.BALANCED -> R.string.settings_c_balanced_body
            Preset.SMOOTH -> R.string.settings_c_smooth_body
        },
    )
    return stringResource(R.string.feed_place_time, what, stringResource(R.string.settings_c_numbers, preset.rowsPerPage, preset.loadAhead))
}

/** A value under Custom, indented like a radio button's text, with − and + moving through [steps]. */
@Composable
private fun Stepper(title: String, value: Int, steps: List<Int>, onSet: (Int) -> Unit) {
    val at = steps.indexOf(value).takeIf { it >= 0 } ?: steps.indexOfFirst { it >= value }.coerceAtLeast(0)
    Row(Modifier.fillMaxWidth().padding(start = 52.dp, end = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = LibraryColors.OnBackground, fontSize = 15.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { onSet(steps[at - 1]) }, enabled = at > 0) { Text("−", fontSize = 20.sp, color = if (at > 0) LibraryColors.OnBackground else LibraryColors.Muted) }
        Text(value.toString(), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, modifier = Modifier.widthIn(min = 36.dp))
        TextButton(onClick = { onSet(steps[at + 1]) }, enabled = at < steps.lastIndex) {
            Text("+", fontSize = 20.sp, color = if (at < steps.lastIndex) LibraryColors.OnBackground else LibraryColors.Muted)
        }
    }
}
