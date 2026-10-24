package com.chencohensolution.journal.ui.library

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.settings.ShownSources
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.PrivateMedia
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.settings.SimilarityThreshold
import com.chencohensolution.journal.settings.TimelineGrouping
import androidx.compose.material3.LinearProgressIndicator
import com.chencohensolution.journal.server.ServerIndexingState
import com.chencohensolution.journal.server.SyncProgress
import java.text.NumberFormat

internal class SettingsActions(
    val setGroupingMode: (GroupingMode) -> Unit = {},
    val setGapHours: (Int) -> Unit = {},
    val setSimilarityPercent: (Int) -> Unit = {},
    val resetSimilarity: () -> Unit = {},
    val setShowPrivateMedia: (Boolean) -> Unit = {},
    /** Opens Hidden collections (behind the phone's lock). */
    val openHidden: () -> Unit = {},
    val setShowOnPhoneBadge: (Boolean) -> Unit = {},
    /** Opens Settings › Search. */
    val openSearch: () -> Unit = {},
    /** Opens Settings › Backup. */
    val openBackup: () -> Unit = {},
    /** Show in the app: a source on or off everywhere (the same as the Moments filter bar), and "Say what's hidden". */
    val toggleSource: (MediaSource) -> Unit = {},
    val setSayWhatsHidden: (Boolean) -> Unit = {},
    val back: () -> Unit = {},
)

/** The user's choices, kept on this device. See docs/screens/settings.md. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SettingsScreen(
    settings: SettingsState,
    actions: SettingsActions,
    /** The library's photos per source, shown or not, for Show in the app. */
    sourceCounts: Map<MediaSource, Int> = emptyMap(),
    backup: BackupUi? = null,
    backupActions: BackupActions = BackupActions(),
    storage: StorageUi? = null,
    storageActions: StorageActions = StorageActions(),
    /** How far the library's sync is, shown on Hidden collections' row (after a change there); null when not syncing. */
    syncProgress: SyncProgress? = null,
    /** Search › On this phone, when the server lets phones keep its indexes; null: none. For Search's row. */
    phoneSearch: com.chencohensolution.journal.local.PhoneSearchState? = null,
    /** Search › On your server, when the server offers it; null: none. For Search's row. */
    indexing: ServerIndexingState? = null,
) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val grouping = settings.grouping

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            ShownSourcesSection(settings.shownSources, sourceCounts, actions)
            if (backup != null) {
                SectionTitle(stringResource(R.string.settings_backup_title))
                Card { BackupSettingsRow(backup, actions.openBackup) }
            }
            SectionTitle(stringResource(R.string.settings_badges_title))
            Card {
                SwitchRow(
                    title = stringResource(R.string.settings_on_phone_badge),
                    body = stringResource(R.string.settings_on_phone_badge_body),
                    checked = settings.showOnPhoneBadge,
                    onChange = actions.setShowOnPhoneBadge,
                )
            }
            if (storage != null) StorageSection(storage, storageActions)
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
            SectionTitle(stringResource(R.string.settings_private_title))
            PrivateMediaCard(settings.privateMedia, syncProgress, actions)
            SectionTitle(stringResource(R.string.settings_search_title))
            Card { SearchSettingsRow(settings.searchPageSize, indexing, phoneSearch, actions.openSearch) }
            if (storage != null) AdvancedSection(storage, storageActions)
        }
        TitleBar(stringResource(R.string.library_settings), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
internal fun Chip(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        colors = FilterChipDefaults.filterChipColors(
            labelColor = LibraryColors.OnBackground,
            selectedContainerColor = LibraryColors.Inverse,
            selectedLabelColor = LibraryColors.OnInverse,
        ),
    )
}

/** A Settings card, for the screens opened from Settings. */
@Composable
internal fun SettingsCard(content: @Composable () -> Unit) = Card(content)

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(LibraryColors.Raised, RoundedCornerShape(14.dp))) { content() }
}

@Composable
internal fun SwitchRow(title: String, body: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .selectable(selected = checked, onClick = { onChange(!checked) }, role = Role.Switch)
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (body != null) Text(body, color = LibraryColors.Muted, fontSize = 13.sp)
        }
        Switch(
            checked = checked,
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
}

/**
 * Show in the app: a pill per source with its count, the most photos first (asked Oct 24, 2026, when each folder
 * became a source), then "Say what's hidden", then where it applies and that hiding never deletes or changes backup.
 */
@Composable
private fun ShownSourcesSection(shown: ShownSources, counts: Map<MediaSource, Int>, actions: SettingsActions) {
    SectionTitle(stringResource(R.string.settings_shown_title))
    SourcePills(shown.choices(counts), counts, shown::isShown, actions.toggleSource)
    Column(Modifier.padding(top = 12.dp)) {
        Card {
            SwitchRow(
                title = stringResource(R.string.settings_say_hidden),
                body = stringResource(R.string.settings_say_hidden_body),
                checked = shown.sayWhatsHidden,
                onChange = actions.setSayWhatsHidden,
            )
        }
    }
    listOf(R.string.settings_shown_where, R.string.settings_shown_stories).forEach { note ->
        Text(stringResource(note), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 10.dp))
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

/**
 * "Show private media" (off by default), and Hidden collections (where nudity is hidden too), with how far the library
 * is in reading the server's list again after a change there.
 */
@Composable
private fun PrivateMediaCard(privateMedia: PrivateMedia, syncProgress: SyncProgress?, actions: SettingsActions) {
    Card {
        SwitchRow(
            title = stringResource(R.string.settings_private_switch),
            body = stringResource(R.string.settings_private_switch_body),
            checked = privateMedia.show,
            onChange = actions.setShowPrivateMedia,
        )
        HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = actions.openHidden).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_hidden_title), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.settings_hidden_row_body), color = LibraryColors.Muted, fontSize = 13.sp)
                if (syncProgress != null) {
                    val format = NumberFormat.getIntegerInstance()
                    Text(
                        stringResource(R.string.settings_hidden_syncing, format.format(syncProgress.done), format.format(syncProgress.total)),
                        color = LibraryColors.OnBackground,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    LinearProgressIndicator(
                        progress = { syncProgress.done.toFloat() / syncProgress.total.coerceAtLeast(1) },
                        color = LibraryColors.OnBackground,
                        trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
                        modifier = Modifier.fillMaxWidth().padding(top = 6.dp, end = 12.dp),
                    )
                }
            }
            Icon(LibraryIcons.Lock, contentDescription = null, tint = LibraryColors.Muted, modifier = Modifier.size(18.dp))
            Icon(LibraryIcons.ChevronRight, contentDescription = null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text,
        color = LibraryColors.Muted,
        fontSize = 13.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 32.dp, end = 32.dp, top = 20.dp, bottom = 8.dp).semantics { heading() },
    )
}

@Composable
internal fun ModeOption(title: String, body: String, selected: Boolean, onSelect: () -> Unit) {
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
