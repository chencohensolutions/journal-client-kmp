package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.server.CameraModel
import com.chencohensolution.journal.server.CamerasState
import java.text.NumberFormat

/**
 * Settings › Library › Your cameras as option a: one card of every camera model, the most photos first, each with its
 * photos and years ("Galaxy S25 · 1,240 · 2025–2026") and a "Mine" switch. Turning one off asks first ("312 photos
 * will move to Sent to you"); while the server moves them the row says "Moving 312 photos…" with a bar.
 */
@Composable
internal fun CamerasScreenA(state: CamerasOptionAState, actions: CamerasOptionAActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Text(
                stringResource(R.string.cameras_a_intro),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 12.dp),
            )
            when (val cameras = state.cameras) {
                CamerasState.Loading -> Text(
                    stringResource(R.string.hidden_loading),
                    color = LibraryColors.Muted,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 32.dp),
                )
                CamerasState.Unreachable -> Column(Modifier.padding(horizontal = 32.dp)) {
                    Text(stringResource(R.string.hidden_unreachable), color = LibraryColors.OnBackground, fontSize = 16.sp)
                    TextButton(onClick = actions.retry) { Text(stringResource(R.string.hidden_retry), color = LibraryColors.OnBackground) }
                }
                is CamerasState.Ready -> {
                    if (cameras.notChanged) {
                        Text(
                            stringResource(R.string.hidden_not_changed),
                            color = LibraryColors.Warning,
                            fontSize = 13.sp,
                            modifier = Modifier.padding(horizontal = 32.dp, vertical = 4.dp),
                        )
                    }
                    SettingsCard {
                        cameras.cameras.forEachIndexed { i, camera ->
                            if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                            CameraRow(camera, cameras.saving, actions)
                        }
                    }
                    Text(
                        stringResource(R.string.cameras_a_unmarked),
                        color = LibraryColors.Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp),
                    )
                }
            }
        }
        TitleBar(stringResource(R.string.cameras_a_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
    state.confirming?.let { camera ->
        val photos = NumberFormat.getIntegerInstance().format(camera.files)
        AlertDialog(
            onDismissRequest = actions.cancel,
            title = { Text(pluralStringResource(R.plurals.cameras_a_confirm_title, camera.files, photos)) },
            text = { Text(stringResource(R.string.cameras_a_confirm_body, camera.name)) },
            confirmButton = { TextButton(onClick = actions.confirm) { Text(stringResource(R.string.cameras_a_move)) } },
            dismissButton = { TextButton(onClick = actions.cancel) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
}

/** A camera: its name, "1,240 · 2025–2026", whether it is sent to you, and "Mine"; while moving, how many are left. */
@Composable
private fun CameraRow(camera: CameraModel, saving: Boolean, actions: CamerasOptionAActions) {
    val format = NumberFormat.getIntegerInstance()
    Column(
        Modifier
            .fillMaxWidth()
            .toggleable(camera.mine, enabled = !saving, role = Role.Switch) { actions.setMine(camera, it) }
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 12.dp)) {
                Text(camera.name, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(listOfNotNull(format.format(camera.files), yearsA(camera)).joinToString(" · "), color = LibraryColors.Muted, fontSize = 13.sp)
                if (!camera.mine && camera.pending == 0) {
                    Text(stringResource(R.string.cameras_a_sent_to_you), color = LibraryColors.Muted, fontSize = 13.sp)
                }
            }
            Text(stringResource(R.string.cameras_a_mine), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
            Switch(
                checked = camera.mine,
                onCheckedChange = null,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = LibraryColors.OnInverse,
                    checkedTrackColor = LibraryColors.Inverse,
                    uncheckedThumbColor = LibraryColors.Muted,
                    uncheckedTrackColor = LibraryColors.Background,
                    uncheckedBorderColor = LibraryColors.Muted,
                ),
            )
        }
        if (camera.pending > 0) {
            Text(
                pluralStringResource(R.plurals.cameras_a_moving, camera.pending, format.format(camera.pending)),
                color = LibraryColors.OnBackground,
                fontSize = 13.sp,
                modifier = Modifier.padding(top = 8.dp),
            )
            LinearProgressIndicator(
                progress = { 1f - camera.pending.toFloat() / camera.files.coerceAtLeast(1) },
                color = LibraryColors.OnBackground,
                trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
            )
        }
    }
}

/** "2025–2026", "2014"; null without years. */
private fun yearsA(camera: CameraModel): String? {
    val first = camera.firstYear ?: return null
    val last = camera.lastYear ?: first
    // Isolated left to right, so Hebrew doesn't turn "2021–2025" around.
    return if (first == last) "$first" else "⁦$first–$last⁩"
}
