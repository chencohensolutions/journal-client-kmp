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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.server.CameraModel
import com.chencohensolution.journal.server.CamerasState
import java.text.NumberFormat

/**
 * Settings › Library › Your cameras as option c: the cameras in the order you used them, newest first, each a card with
 * its photos, a strip of the library's years with its own filled in (which shows which phone was yours when), and
 * "Mine" / "Sent to you" to choose between. Choosing Sent to you asks in a panel at the bottom ("312 photos will move to
 * Sent to you"); while the server moves them the card says "Moving 312 photos…".
 */
@Composable
internal fun CamerasScreenC(state: CamerasOptionCState, actions: CamerasOptionCActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                stringResource(R.string.cameras_c_intro),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 4.dp),
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
                    val years = cameras.cameras.flatMap { it.years }
                    val span = (years.minOrNull() ?: 0)..(years.maxOrNull() ?: 0)
                    cameras.cameras
                        .sortedWith(compareByDescending<CameraModel> { it.lastYear ?: 0 }.thenByDescending { it.files })
                        .forEach { camera -> CameraCard(camera, span, cameras.saving, actions) }
                }
            }
        }
        TitleBar(stringResource(R.string.cameras_c_title), actions.back, Modifier.align(Alignment.TopCenter))
        state.confirming?.let { camera ->
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)).clickable(onClick = actions.cancel))
            ConfirmPanel(camera, actions, Modifier.align(Alignment.BottomCenter))
        }
    }
}

/** A camera's card: name and photos, the years strip, and Mine / Sent to you (or how many are still moving). */
@Composable
private fun CameraCard(camera: CameraModel, span: IntRange, saving: Boolean, actions: CamerasOptionCActions) {
    val format = NumberFormat.getIntegerInstance()
    Column(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(LibraryColors.Raised, RoundedCornerShape(16.dp))
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(FeedIcons.Camera, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
            Text(
                camera.name,
                color = LibraryColors.OnBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
            )
            Text(format.format(camera.files), color = LibraryColors.Muted, fontSize = 14.sp)
        }
        YearStrip(camera, span)
        if (camera.pending > 0) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(color = LibraryColors.OnBackground, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                Text(
                    pluralStringResource(R.plurals.cameras_c_moving, camera.pending, format.format(camera.pending)),
                    color = LibraryColors.OnBackground,
                    fontSize = 13.sp,
                )
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Choice(stringResource(R.string.cameras_c_mine), camera.mine, enabled = !saving) { if (!camera.mine) actions.choose(camera, true) }
                Choice(stringResource(R.string.feed_source_received), !camera.mine, enabled = !saving) { if (camera.mine) actions.choose(camera, false) }
            }
        }
    }
}

/** The library's years, one cell each, those [camera] has photos of filled; the first and last year under it. */
@Composable
private fun YearStrip(camera: CameraModel, span: IntRange) {
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            span.forEach { year ->
                val on = year in camera.years
                Box(
                    Modifier
                        .weight(1f)
                        .height(8.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(if (on) LibraryColors.OnBackground.copy(alpha = if (camera.mine) 0.85f else 0.4f) else LibraryColors.Muted.copy(alpha = 0.18f)),
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            Text("${span.first}", color = LibraryColors.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f))
            val first = camera.firstYear
            val last = camera.lastYear
            if (first != null && last != null) {
                Text(if (first == last) "$first" else "⁦$first–$last⁩", color = LibraryColors.OnBackground, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            }
            Text("${span.last}", color = LibraryColors.Muted, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End)
        }
    }
}

/** One of Mine / Sent to you: filled while chosen. */
@Composable
private fun Choice(label: String, chosen: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (chosen) Modifier.background(LibraryColors.Inverse) else Modifier.border(1.dp, LibraryColors.Muted.copy(alpha = 0.45f), RoundedCornerShape(50)),
            )
            .clickable(enabled = enabled, role = Role.RadioButton, onClick = onClick)
            .semantics { selected = chosen }
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (chosen) Icon(LibraryIcons.Check, null, tint = LibraryColors.OnInverse, modifier = Modifier.size(14.dp))
        Text(label, color = if (chosen) LibraryColors.OnInverse else LibraryColors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** "312 photos will move to Sent to you", what that means, and Move / Cancel, at the bottom of the page. */
@Composable
private fun ConfirmPanel(camera: CameraModel, actions: CamerasOptionCActions, modifier: Modifier) {
    val photos = NumberFormat.getIntegerInstance().format(camera.files)
    Column(
        modifier
            .fillMaxWidth()
            .background(LibraryColors.Raised, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .navigationBarsPadding()
            .padding(start = 24.dp, end = 16.dp, top = 22.dp, bottom = 12.dp),
    ) {
        Text(camera.name, color = LibraryColors.Muted, fontSize = 13.sp)
        Text(
            pluralStringResource(R.plurals.cameras_c_confirm, camera.files, photos),
            color = LibraryColors.OnBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = 2.dp, end = 8.dp),
        )
        Text(stringResource(R.string.cameras_c_confirm_body), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp, end = 8.dp))
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = actions.cancel) { Text(stringResource(R.string.library_cancel), color = LibraryColors.Muted) }
            TextButton(onClick = actions.confirm) { Text(stringResource(R.string.cameras_c_move), color = LibraryColors.OnBackground, fontWeight = FontWeight.Bold) }
        }
    }
}
