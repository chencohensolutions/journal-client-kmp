package com.chencohensolution.journal.ui.library

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.local.PhoneSearchState
import com.chencohensolution.journal.server.IndexingCount
import com.chencohensolution.journal.server.IndexingRunState
import com.chencohensolution.journal.server.ServerIndexingState
import com.chencohensolution.journal.settings.SettingsState
import java.text.NumberFormat

// Settings › Search (docs/screens/settings.md "Search"): Indexing, the reading of this user's photos on the server
// (start, follow, pause); photos per page of results; and On device search, the indexes kept on this phone. The indexing state comes from the shared
// ServerIndexing.

internal class SearchSettingsActions(
    val back: () -> Unit = {},
    val setSearchPageSize: (Int) -> Unit = {},
    val startIndexing: () -> Unit = {},
    val pauseIndexing: () -> Unit = {},
    /** On device search. */
    val phoneSearch: PhoneSearchActions = PhoneSearchActions(),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SearchSettingsScreen(
    searchPageSize: Int,
    /** The server's reading of this user's photos; null when the server offers none. */
    indexing: ServerIndexingState?,
    /** On device search, when the server lets phones keep its indexes; null: no section. */
    phoneSearch: PhoneSearchState?,
    actions: SearchSettingsActions,
) {
    val insets = barsRoom()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            if (indexing != null) {
                SectionTitle(stringResource(R.string.indexing_title))
                Text(
                    stringResource(R.string.indexing_intro),
                    color = LibraryColors.Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 32.dp, end = 32.dp, bottom = 8.dp),
                )
                SettingsCard { IndexingRows(indexing, actions) }
                indexing.nextNightlyMillis?.let { next ->
                    val context = LocalContext.current
                    Text(
                        stringResource(
                            R.string.indexing_next,
                            DateUtils.formatDateTime(context, next, DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_SHOW_WEEKDAY),
                        ),
                        color = LibraryColors.Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 8.dp),
                    )
                }
            }
            SectionTitle(stringResource(R.string.search_settings_results_title))
            SettingsCard {
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
                    Text(stringResource(R.string.settings_search_page), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.settings_search_page_body), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(end = 8.dp))
                    FlowRow(Modifier.padding(top = 8.dp, end = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SettingsState.SEARCH_PAGE_OPTIONS.forEach { size ->
                            Chip(size.toString(), searchPageSize == size) { actions.setSearchPageSize(size) }
                        }
                    }
                }
            }
            if (phoneSearch != null) PhoneSearchSection(phoneSearch, actions.phoneSearch)
        }
        TitleBar(stringResource(R.string.settings_search_title), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

/** Reading (with a bar and Pause), waiting (with Pause), paused (with Resume), or the last run (with Read new photos now). */
@Composable
private fun IndexingRows(state: ServerIndexingState, actions: SearchSettingsActions) {
    val format = NumberFormat.getIntegerInstance()
    fun ago(millis: Long) = DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
    Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 6.dp)) {
        when (state.run) {
            IndexingRunState.RUNNING -> {
                Title(stringResource(if (state.nightly) R.string.indexing_reading_nightly else R.string.indexing_reading))
                Body(stringResource(R.string.indexing_of, format.format(state.done), format.format(state.total)))
                LinearProgressIndicator(
                    progress = { state.fraction },
                    color = LibraryColors.OnBackground,
                    trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                )
                if (state.indexes.isNotEmpty()) Body(countsLine(state.indexes, format), Modifier.padding(top = 8.dp, end = 8.dp))
                IndexingButton(stringResource(R.string.indexing_pause), !state.acting, actions.pauseIndexing)
            }
            IndexingRunState.QUEUED -> {
                Title(stringResource(R.string.indexing_waiting))
                val ahead = state.queuedBehind ?: 1
                Body(pluralStringResource(R.plurals.indexing_waiting_body, ahead, ahead))
                IndexingButton(stringResource(R.string.indexing_pause), !state.acting, actions.pauseIndexing)
            }
            IndexingRunState.PAUSED -> {
                Title(stringResource(R.string.indexing_paused, format.format(state.done), format.format(state.total)))
                if (state.total > 0) {
                    LinearProgressIndicator(
                        progress = { state.fraction },
                        color = LibraryColors.Muted,
                        trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp),
                    )
                }
                // Only a nightly server runs its readings in a process of their own, whose models leave with it.
                Body(
                    stringResource(if (state.nextNightlyMillis != null) R.string.indexing_paused_body else R.string.indexing_paused_body_live),
                    Modifier.padding(top = 8.dp, end = 8.dp),
                )
                IndexingButton(stringResource(R.string.indexing_resume), !state.acting, actions.startIndexing)
            }
            IndexingRunState.IDLE -> {
                val last = state.lastRun
                Title(
                    when {
                        last == null -> stringResource(R.string.indexing_never)
                        last.stopped -> stringResource(R.string.indexing_last_stopped, ago(last.finishedAtMillis), format.format(last.files))
                        else -> stringResource(R.string.indexing_last, ago(last.finishedAtMillis), format.format(last.files))
                    },
                )
                Body(stringResource(R.string.indexing_up_to_date))
                last?.error?.let { Warning(stringResource(R.string.indexing_failed, it)) }
                IndexingButton(stringResource(R.string.indexing_start), !state.acting, actions.startIndexing)
            }
        }
        state.error?.let { Warning(stringResource(R.string.indexing_error, it)) }
    }
}

/** "Search by words 40 · People 38 · 2 couldn't be read". */
@Composable
private fun countsLine(counts: List<IndexingCount>, format: NumberFormat): String {
    val parts = counts.map { "${stringResource(indexName(it.name))} ${format.format(it.read)}" }.toMutableList()
    val failed = counts.sumOf { it.failed }
    if (failed > 0) parts += stringResource(R.string.indexing_could_not_read, failed)
    return parts.joinToString(" · ")
}

private fun indexName(name: String): Int = when (name) {
    "search" -> R.string.phone_search_words
    "faces" -> R.string.phone_search_people
    "ocr" -> R.string.phone_search_text
    "similar" -> R.string.phone_search_similar
    "aesthetic" -> R.string.phone_search_aesthetic
    else -> R.string.indexing_nudity
}

@Composable
private fun Title(text: String) = Text(text, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

@Composable
private fun Body(text: String, modifier: Modifier = Modifier) = Text(text, color = LibraryColors.Muted, fontSize = 13.sp, modifier = modifier)

@Composable
private fun Warning(text: String) = Text(text, color = LibraryColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp, end = 8.dp))

@Composable
private fun IndexingButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    TextButton(onClick = onClick, enabled = enabled, modifier = Modifier.padding(top = 2.dp)) {
        Text(label, color = if (enabled) LibraryColors.OnBackground else LibraryColors.Muted, fontWeight = FontWeight.Bold)
    }
}

/** Settings' row for it: "Server: reading 40 of 200", or "20 per page · 3 kept on this phone". */
@Composable
internal fun SearchSettingsRow(searchPageSize: Int, indexing: ServerIndexingState?, phoneSearch: PhoneSearchState?, onOpen: () -> Unit) {
    val format = NumberFormat.getIntegerInstance()
    val kept = phoneSearch?.kept.orEmpty().size
    val body = when (indexing?.run) {
        IndexingRunState.RUNNING -> stringResource(R.string.search_settings_row_reading, format.format(indexing.done), format.format(indexing.total))
        IndexingRunState.PAUSED -> stringResource(R.string.search_settings_row_paused, format.format(indexing.done), format.format(indexing.total))
        IndexingRunState.QUEUED -> stringResource(R.string.search_settings_row_waiting)
        else -> if (kept > 0) stringResource(R.string.search_settings_row_kept, searchPageSize, kept) else stringResource(R.string.search_settings_row_page, searchPageSize)
    }
    PageRow(stringResource(R.string.settings_search_title), body, onOpen)
}
