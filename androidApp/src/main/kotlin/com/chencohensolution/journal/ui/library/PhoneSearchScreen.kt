package com.chencohensolution.journal.ui.library

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
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
import com.chencohensolution.journal.local.PhoneIndex
import com.chencohensolution.journal.local.PhoneIndexKind
import com.chencohensolution.journal.local.PhoneSearchState

// Settings › Search › On this phone (docs/screens/settings.md): which of the server's indexes this phone keeps, to
// search without the server, offline too. The state comes from the shared LocalIndexes.

internal class PhoneSearchActions(
    val back: () -> Unit = {},
    val set: (PhoneIndexKind, Boolean) -> Unit = { _, _ -> },
    val setWifiOnly: (Boolean) -> Unit = {},
    val updateNow: () -> Unit = {},
    val pause: () -> Unit = {},
)

/**
 * On this phone: what it is for, then a switch per index with its size, Only on Wi-Fi, and how up to date it is (a
 * download's progress, "Waiting for Wi-Fi", or "Updated 2 hours ago" with Update now), and the space it takes.
 */
@Composable
internal fun PhoneSearchScreen(state: PhoneSearchState, actions: PhoneSearchActions) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val context = LocalContext.current
    fun size(bytes: Long) = Formatter.formatShortFileSize(context, bytes)
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            Text(
                stringResource(R.string.phone_search_intro),
                color = LibraryColors.Muted,
                fontSize = 14.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 4.dp),
            )
            if (state.kept.isNotEmpty()) {
                SectionTitle(stringResource(R.string.phone_search_status_title))
                SettingsCard { StatusRows(state, actions, ::size) }
            }
            SectionTitle(stringResource(R.string.phone_search_keep_title))
            SettingsCard {
                state.indexes.filter { it.available }.forEachIndexed { i, index ->
                    if (i > 0) HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
                    SwitchRow(
                        title = stringResource(indexTitle(index.kind)),
                        body = indexBody(index, ::size),
                        checked = index.on,
                        onChange = { actions.set(index.kind, it) },
                    )
                }
            }
            SectionTitle(stringResource(R.string.phone_search_download_title))
            SettingsCard {
                SwitchRow(
                    title = stringResource(R.string.phone_search_wifi_only),
                    body = stringResource(R.string.phone_search_wifi_only_body),
                    checked = state.wifiOnly,
                    onChange = actions.setWifiOnly,
                )
            }
            Text(
                stringResource(R.string.phone_search_space, size(state.keptBytes), size(state.freeBytes)),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 12.dp),
            )
        }
        TitleBar(stringResource(R.string.phone_search_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

private fun indexTitle(kind: PhoneIndexKind): Int = when (kind) {
    PhoneIndexKind.WORDS -> R.string.phone_search_words
    PhoneIndexKind.PEOPLE -> R.string.phone_search_people
    PhoneIndexKind.TEXT -> R.string.phone_search_text
    PhoneIndexKind.SIMILAR -> R.string.phone_search_similar
    PhoneIndexKind.AESTHETIC -> R.string.phone_search_aesthetic
}

/** What it finds, and its size: "Find photos by describing them · 450 MB, and the 290 MB model that reads your words". */
@Composable
private fun indexBody(index: PhoneIndex, size: (Long) -> String): String {
    val what = stringResource(
        when (index.kind) {
            PhoneIndexKind.WORDS -> R.string.phone_search_words_body
            PhoneIndexKind.PEOPLE -> R.string.phone_search_people_body
            PhoneIndexKind.TEXT -> R.string.phone_search_text_body
            PhoneIndexKind.SIMILAR -> R.string.phone_search_similar_body
            PhoneIndexKind.AESTHETIC -> R.string.phone_search_aesthetic_body
        },
    )
    val bytes = if (index.modelBytes > 0) {
        stringResource(R.string.phone_search_size_with_model, size(index.bytes), size(index.modelBytes))
    } else {
        size(index.bytes)
    }
    return stringResource(R.string.feed_place_time, what, bytes)
}

/** Downloading (with Pause), waiting for Wi-Fi, or up to date ("Updated 2 hours ago · 12 new on your server") with Update now. */
@Composable
private fun StatusRows(state: PhoneSearchState, actions: PhoneSearchActions, size: (Long) -> String) {
    val download = state.download
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)) {
        when {
            download != null -> {
                Text(
                    stringResource(R.string.phone_search_downloading, stringResource(indexTitle(download.kind))),
                    color = LibraryColors.OnBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    stringResource(R.string.phone_search_downloaded, size(download.doneBytes), size(download.totalBytes)),
                    color = LibraryColors.Muted,
                    fontSize = 13.sp,
                )
                LinearProgressIndicator(
                    progress = { download.doneBytes.toFloat() / download.totalBytes.coerceAtLeast(1) },
                    color = LibraryColors.OnBackground,
                    trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                )
                Text(stringResource(R.string.phone_search_downloading_body), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp, end = 8.dp))
                StatusButton(stringResource(R.string.phone_search_pause), actions.pause)
            }
            state.waitingForWifi -> {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(LibraryIcons.Wifi, null, tint = LibraryColors.Warning, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.phone_search_waiting_wifi), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
                Text(stringResource(R.string.phone_search_waiting_wifi_body), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                StatusButton(stringResource(R.string.phone_search_download_now), actions.updateNow)
            }
            else -> {
                Text(
                    state.updatedAtMillis?.let {
                        stringResource(R.string.phone_search_updated, DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS))
                    } ?: stringResource(R.string.phone_search_never),
                    color = LibraryColors.OnBackground,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    if (state.newOnServer > 0) stringResource(R.string.phone_search_new, state.newOnServer) else stringResource(R.string.phone_search_up_to_date),
                    color = LibraryColors.Muted,
                    fontSize = 13.sp,
                )
                state.error?.let { Text(stringResource(R.string.phone_search_error, it), color = LibraryColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, end = 8.dp)) }
                StatusButton(stringResource(R.string.phone_search_update_now), actions.updateNow)
            }
        }
    }
}

@Composable
private fun StatusButton(label: String, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.padding(top = 2.dp)) {
        Text(label, color = LibraryColors.OnBackground, fontWeight = FontWeight.Bold)
    }
}

/** Settings' row for it, under Search: "3 kept · 760 MB · Updated 2 hours ago", or "Off: search needs your server". */
@Composable
internal fun PhoneSearchRow(state: PhoneSearchState?, onOpen: () -> Unit) {
    val context = LocalContext.current
    val kept = state?.kept.orEmpty()
    val download = state?.download
    val body = when {
        state == null || kept.isEmpty() -> stringResource(R.string.phone_search_row_off)
        download != null -> stringResource(
            R.string.phone_search_row_downloading,
            (download.doneBytes * 100 / download.totalBytes.coerceAtLeast(1)).toInt(),
        )
        else -> stringResource(
            R.string.phone_search_row_kept,
            kept.size,
            Formatter.formatShortFileSize(context, state.keptBytes),
            state.updatedAtMillis?.let { DateUtils.getRelativeTimeSpanString(it, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString() }
                ?: stringResource(R.string.phone_search_never_short),
        )
    }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.phone_search_title), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = LibraryColors.Muted, fontSize = 13.sp)
        }
        Icon(LibraryIcons.ChevronRight, contentDescription = null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
    }
}
