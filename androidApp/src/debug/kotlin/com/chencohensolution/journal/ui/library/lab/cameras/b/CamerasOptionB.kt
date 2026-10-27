package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 * Settings › Library › Your cameras as option b: two groups, "Yours" and "Sent to you", each camera with its photos
 * and years and one button that moves it to the other group. "Not mine" opens its question in the row ("312 photos
 * will move to Sent to you", Move / Cancel); while the server moves them, a band at the top says "Moving 312 photos…"
 * and the camera waits in its new group with a spinner.
 */
@Composable
internal fun CamerasScreenB(state: CamerasOptionBState, actions: CamerasOptionBActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
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
                    if (cameras.pending > 0) MovingBand(cameras)
                    val (mine, others) = cameras.cameras.partition { it.mine }
                    SectionTitle(stringResource(R.string.cameras_b_yours))
                    Group(mine, state.confirming, cameras.saving, actions, emptyText = R.string.cameras_b_yours_none)
                    Text(
                        stringResource(R.string.cameras_b_yours_body),
                        color = LibraryColors.Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
                    )
                    SectionTitle(stringResource(R.string.feed_source_received))
                    Group(others, state.confirming, cameras.saving, actions, emptyText = R.string.cameras_b_others_none)
                    Text(
                        stringResource(R.string.cameras_b_others_body),
                        color = LibraryColors.Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
                    )
                }
            }
        }
        TitleBar(stringResource(R.string.cameras_b_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

/** "Moving 312 photos…" over a bar, while the server moves any camera's photos. */
@Composable
private fun MovingBand(cameras: CamerasState.Ready) {
    val files = cameras.cameras.filter { it.pending > 0 }.sumOf { it.files }
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, top = 8.dp)
            .fillMaxWidth()
            .background(LibraryColors.Raised, RoundedCornerShape(14.dp))
            .padding(14.dp),
    ) {
        Text(
            pluralStringResource(R.plurals.cameras_b_moving, cameras.pending, NumberFormat.getIntegerInstance().format(cameras.pending)),
            color = LibraryColors.OnBackground,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(stringResource(R.string.cameras_b_moving_body), color = LibraryColors.Muted, fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { 1f - cameras.pending.toFloat() / files.coerceAtLeast(1) },
            color = LibraryColors.OnBackground,
            trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(4.dp),
        )
    }
}

@Composable
private fun Group(cameras: List<CameraModel>, confirming: CameraModel?, saving: Boolean, actions: CamerasOptionBActions, emptyText: Int) {
    SettingsCard {
        if (cameras.isEmpty()) {
            Text(stringResource(emptyText), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(16.dp))
        }
        cameras.forEachIndexed { i, camera ->
            if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
            val asking = confirming != null && confirming.make == camera.make && confirming.model == camera.model
            CameraRow(camera, asking, saving, actions)
        }
    }
}

/** A camera: name, "1,240 photos · 2025–2026", and its button; asking, the question and Move / Cancel under it. */
@Composable
private fun CameraRow(camera: CameraModel, asking: Boolean, saving: Boolean, actions: CamerasOptionBActions) {
    val format = NumberFormat.getIntegerInstance()
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(FeedIcons.Camera, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
            Column(Modifier.weight(1f).padding(start = 12.dp, end = 8.dp)) {
                Text(camera.name, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                val photos = pluralStringResource(R.plurals.cameras_b_photos, camera.files, format.format(camera.files))
                Text(listOfNotNull(photos, yearsB(camera)).joinToString(" · "), color = LibraryColors.Muted, fontSize = 13.sp)
            }
            when {
                camera.pending > 0 -> CircularProgressIndicator(color = LibraryColors.Muted, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                asking -> Unit
                else -> OutlineButton(stringResource(if (camera.mine) R.string.cameras_b_not_mine else R.string.cameras_b_mine), enabled = !saving) {
                    if (camera.mine) actions.askNotMine(camera) else actions.markMine(camera)
                }
            }
        }
        if (asking) {
            Column(
                Modifier
                    .padding(top = 12.dp)
                    .fillMaxWidth()
                    .background(LibraryColors.Background, RoundedCornerShape(12.dp))
                    .padding(start = 14.dp, end = 6.dp, top = 12.dp, bottom = 4.dp),
            ) {
                Text(
                    pluralStringResource(R.plurals.cameras_b_confirm, camera.files, format.format(camera.files)),
                    color = LibraryColors.OnBackground,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(stringResource(R.string.cameras_b_confirm_body), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = actions.cancel) { Text(stringResource(R.string.library_cancel), color = LibraryColors.Muted) }
                    TextButton(onClick = actions.confirm) { Text(stringResource(R.string.cameras_b_move), color = LibraryColors.OnBackground, fontWeight = FontWeight.Bold) }
                }
            }
        }
    }
}

@Composable
private fun OutlineButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    Text(
        label,
        color = if (enabled) LibraryColors.OnBackground else LibraryColors.Muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, LibraryColors.Muted.copy(alpha = 0.45f), RoundedCornerShape(50))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
}

/** "2025–2026", "2014"; null without years. */
private fun yearsB(camera: CameraModel): String? {
    val first = camera.firstYear ?: return null
    val last = camera.lastYear ?: first
    // Isolated left to right, so Hebrew doesn't turn "2021–2025" around.
    return if (first == last) "$first" else "⁦$first–$last⁩"
}
