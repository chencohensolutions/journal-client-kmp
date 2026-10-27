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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
import com.chencohensolution.journal.server.CamerasController
import com.chencohensolution.journal.server.CamerasState
import com.chencohensolution.journal.server.ServerLibrary
import java.text.NumberFormat

internal class CamerasActions(
    /** The switch turned on (marks it the user's at once) or off (asks first). */
    val setMine: (CameraModel, Boolean) -> Unit = { _, _ -> },
    /** "Move": marks the camera being asked about not the user's. */
    val confirm: () -> Unit = {},
    val cancel: () -> Unit = {},
    val retry: () -> Unit = {},
    val back: () -> Unit = {},
)

/**
 * Settings › Your cameras, opened: a [CamerasController] for this opening, and the camera whose "Mine" switch was
 * turned off and waits for its confirmation (kept by make and model across rotation). [onState] gets each state, for
 * Settings' row.
 */
@Composable
internal fun CamerasRoute(library: ServerLibrary, onState: (CamerasState) -> Unit, back: () -> Unit) {
    val controller = remember(library) { CamerasController(library) }
    DisposableEffect(controller) { onDispose { controller.close() } }
    LaunchedEffect(controller) { controller.load() }
    val state by controller.state.collectAsStateWithLifecycle()
    LaunchedEffect(state) { onState(state) }
    var asking by rememberSaveable { mutableStateOf<Pair<String, String>?>(null) }
    val confirming = asking?.let { (make, model) ->
        (state as? CamerasState.Ready)?.cameras?.firstOrNull { it.make == make && it.model == model }
    }
    CamerasScreen(
        state,
        confirming,
        CamerasActions(
            setMine = { camera, mine -> if (mine) controller.setMine(camera, true) else asking = camera.make to camera.model },
            confirm = {
                confirming?.let { controller.setMine(it, false) }
                asking = null
            },
            cancel = { asking = null },
            retry = controller::retry,
            back = back,
        ),
    )
}

/**
 * Settings › Library › Your cameras: one card of every camera model, the most photos first, each with its photos and
 * years ("Samsung SM-S931B · 1,240 · 2025–2026") and a "Mine" switch. Turning one off asks first ([confirming]: "312
 * photos will move to Sent to you"); while the server moves them the row says "Moving 312 photos…" with a bar. See
 * docs/screens/settings.md.
 */
@Composable
internal fun CamerasScreen(state: CamerasState, confirming: CameraModel?, actions: CamerasActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Text(
                stringResource(R.string.cameras_intro),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 12.dp),
            )
            when (val cameras = state) {
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
                        stringResource(R.string.cameras_unmarked),
                        color = LibraryColors.Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp),
                    )
                }
            }
        }
        TitleBar(stringResource(R.string.cameras_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
    confirming?.let { camera ->
        val photos = NumberFormat.getIntegerInstance().format(camera.files)
        AlertDialog(
            onDismissRequest = actions.cancel,
            title = { Text(pluralStringResource(R.plurals.cameras_confirm_title, camera.files, photos)) },
            text = { Text(stringResource(R.string.cameras_confirm_body, camera.name)) },
            confirmButton = { TextButton(onClick = actions.confirm) { Text(stringResource(R.string.cameras_move)) } },
            dismissButton = { TextButton(onClick = actions.cancel) { Text(stringResource(R.string.library_cancel)) } },
        )
    }
}

/** A camera: its name, "1,240 · 2025–2026", whether it is sent to you, and "Mine"; while moving, how many are left. */
@Composable
private fun CameraRow(camera: CameraModel, saving: Boolean, actions: CamerasActions) {
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
                Text(listOfNotNull(format.format(camera.files), years(camera)).joinToString(" · "), color = LibraryColors.Muted, fontSize = 13.sp)
                if (!camera.mine && camera.pending == 0) {
                    Text(stringResource(R.string.cameras_sent_to_you), color = LibraryColors.Muted, fontSize = 13.sp)
                }
            }
            Text(stringResource(R.string.cameras_mine), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
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
                pluralStringResource(R.plurals.cameras_moving, camera.pending, format.format(camera.pending)),
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
private fun years(camera: CameraModel): String? {
    val first = camera.firstYear ?: return null
    val last = camera.lastYear ?: first
    // Isolated left to right, so Hebrew doesn't turn "2021–2025" around.
    return if (first == last) "$first" else "⁦$first–$last⁩"
}
