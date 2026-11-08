package com.chencohensolution.journal.ui.library

import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.local.PhoneModelKind
import com.chencohensolution.journal.local.PhoneModelRow
import com.chencohensolution.journal.local.PhoneModelStatus
import com.chencohensolution.journal.local.PhoneModelsState

internal class ModelsActions(
    /** Download ([force]: Download now, Try again). */
    val download: (PhoneModelKind, force: Boolean) -> Unit = { _, _ -> },
    val pause: (PhoneModelKind) -> Unit = {},
    val delete: (PhoneModelKind) -> Unit = {},
    val downloadAll: () -> Unit = {},
    val setWifiOnly: (Boolean) -> Unit = {},
    val back: () -> Unit = {},
)

/**
 * Settings › Models on this phone (chosen Nov 8, 2026 from the UI lab's option A, docs/screens/mockups/models/): every
 * model the app runs here, each with how it stands and its button, Download all, Only on Wi-Fi (On device search's
 * setting) and the space they take. See docs/screens/settings.md.
 */
@Composable
internal fun ModelsScreen(state: PhoneModelsState, actions: ModelsActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Text(
                stringResource(R.string.models_intro),
                color = LibraryColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp, bottom = 12.dp),
            )
            if (state.offline) {
                Text(
                    stringResource(R.string.models_offline),
                    color = LibraryColors.Warning,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 8.dp),
                )
            }
            SettingsCard {
                state.models.forEachIndexed { i, model ->
                    if (i > 0) RowDivider()
                    ModelRow(model, state.offline, actions)
                }
            }
            if (state.missing.isNotEmpty()) {
                Button(
                    onClick = actions.downloadAll,
                    enabled = !state.offline,
                    colors = ButtonDefaults.buttonColors(containerColor = LibraryColors.Inverse, contentColor = LibraryColors.OnInverse),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp).fillMaxWidth(),
                ) {
                    Text(stringResource(R.string.models_download_all, size(state.missingBytes)))
                }
            } else {
                Spacer(Modifier.height(12.dp))
            }
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.models_wifi_only),
                    body = stringResource(R.string.models_wifi_only_body),
                    checked = state.wifiOnly,
                    onChange = actions.setWifiOnly,
                )
            }
            Text(
                stringResource(R.string.models_space, size(state.keptBytes), size(state.freeBytes)),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp),
            )
        }
        TitleBar(stringResource(R.string.models_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun size(bytes: Long): String = Formatter.formatShortFileSize(LocalContext.current, bytes)

/** One model: its name, what it does, how it stands, a bar while downloading, and its button. */
@Composable
private fun ModelRow(model: PhoneModelRow, offline: Boolean, actions: ModelsActions) {
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = 8.dp)) {
                Text(stringResource(modelTitle(model.kind)), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(modelBody(model.kind)), color = LibraryColors.Muted, fontSize = 13.sp)
                if (model.withBytes > 0) {
                    Text(stringResource(R.string.models_words_with, size(model.withBytes)), color = LibraryColors.Muted, fontSize = 13.sp)
                }
                val warning = model.status == PhoneModelStatus.NOT_OFFERED || model.status == PhoneModelStatus.FAILED
                Text(
                    modelStatus(model),
                    color = if (warning) LibraryColors.Warning else LibraryColors.OnBackground,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            val (label, onClick) = when (model.status) {
                PhoneModelStatus.HERE -> R.string.models_delete to { actions.delete(model.kind) }
                PhoneModelStatus.MISSING -> R.string.models_download to { actions.download(model.kind, false) }
                PhoneModelStatus.WAITING_FOR_WIFI -> R.string.models_download_now to { actions.download(model.kind, true) }
                PhoneModelStatus.DOWNLOADING -> R.string.models_pause to { actions.pause(model.kind) }
                PhoneModelStatus.FAILED -> R.string.models_try_again to { actions.download(model.kind, true) }
                PhoneModelStatus.NOT_OFFERED -> null to {}
            }
            if (label != null) {
                // Offline, only what needs no server (Delete, Pause) or tries again on purpose (Try again) is on.
                val enabled = !offline || model.status != PhoneModelStatus.MISSING && model.status != PhoneModelStatus.WAITING_FOR_WIFI
                TextButton(onClick = onClick, enabled = enabled) {
                    Text(stringResource(label), color = if (enabled) LibraryColors.OnBackground else LibraryColors.Muted)
                }
            }
        }
        if (model.status == PhoneModelStatus.DOWNLOADING && model.downloadingBytes > 0) {
            LinearProgressIndicator(
                progress = { (model.downloadedBytes.toFloat() / model.downloadingBytes).coerceIn(0f, 1f) },
                color = LibraryColors.OnBackground,
                trackColor = LibraryColors.Muted.copy(alpha = 0.3f),
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 8.dp),
            )
        }
    }
}

private fun modelTitle(kind: PhoneModelKind): Int = when (kind) {
    PhoneModelKind.PHOTOS -> R.string.models_photos_title
    PhoneModelKind.WORDS -> R.string.models_words_title
}

private fun modelBody(kind: PhoneModelKind): Int = when (kind) {
    PhoneModelKind.PHOTOS -> R.string.models_photos_body
    PhoneModelKind.WORDS -> R.string.models_words_body
}

/** "Downloaded · 11 MB", "Downloading 112 MB of 290 MB", "Not downloaded · 290 MB", "Your server doesn't offer it yet"… */
@Composable
private fun modelStatus(model: PhoneModelRow): String = when (model.status) {
    PhoneModelStatus.HERE -> stringResource(R.string.models_here, size(model.bytes))
    PhoneModelStatus.MISSING ->
        if (model.bytes > 0) stringResource(R.string.models_missing, size(model.bytes)) else stringResource(R.string.models_missing_size_unknown)
    PhoneModelStatus.DOWNLOADING -> stringResource(R.string.models_downloading, size(model.downloadedBytes), size(model.downloadingBytes))
    PhoneModelStatus.WAITING_FOR_WIFI -> stringResource(R.string.models_waiting)
    PhoneModelStatus.NOT_OFFERED -> stringResource(R.string.models_not_offered)
    PhoneModelStatus.FAILED -> stringResource(R.string.models_failed)
}

/** Its row on Settings' first page, under Search: "1 of 2 downloaded". */
@Composable
internal fun ModelsRow(state: PhoneModelsState, onOpen: () -> Unit) {
    PageRow(
        stringResource(R.string.models_title),
        if (state.models.any { it.status == PhoneModelStatus.DOWNLOADING }) {
            stringResource(R.string.models_row_downloading)
        } else {
            stringResource(R.string.models_row, state.here, state.models.size)
        },
        onOpen,
    )
}
