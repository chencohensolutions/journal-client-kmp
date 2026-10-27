package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import com.chencohensolution.journal.settings.ShownSourcesPills
import com.chencohensolution.journal.server.HiddenCollections
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.PrivateMedia
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.settings.SimilarityThreshold
import com.chencohensolution.journal.settings.TimelineGrouping
import androidx.compose.material3.LinearProgressIndicator
import com.chencohensolution.journal.server.CamerasState
import com.chencohensolution.journal.server.ServerIndexingState
import com.chencohensolution.journal.server.SyncProgress
import java.text.NumberFormat

internal class SettingsActions(
    val setGroupingMode: (GroupingMode) -> Unit = {},
    val setGapHours: (Int) -> Unit = {},
    val setSimilarityPercent: (Int) -> Unit = {},
    val resetSimilarity: () -> Unit = {},
    /** Opens Hidden collections (behind the phone's lock). */
    val openHidden: () -> Unit = {},
    /** Opens Settings › Your cameras. */
    val openCameras: () -> Unit = {},
    val setShowOnPhoneBadge: (Boolean) -> Unit = {},
    /** Opens Settings › Search. */
    val openSearch: () -> Unit = {},
    /** Opens one of Settings' pages (Show in the app, Moments, Storage on this phone). */
    val openPage: (SettingsPage) -> Unit = {},
    /** Opens Settings › Backup. */
    val openBackup: () -> Unit = {},
    /** Show in the app: a source on or off everywhere (the same as the Moments filter bar), and "Say what's hidden". */
    val toggleSource: (MediaSource) -> Unit = {},
    /** Show in the app: Imported, all of it, on or off. */
    val toggleImported: () -> Unit = {},
    val setSayWhatsHidden: (Boolean) -> Unit = {},
    /** "Filter bar in Moments". */
    val setShowFilterBar: (Boolean) -> Unit = {},
    /** Settings › Loading Moments: rows read at a time, how near the end the next are read, and both back to the defaults. */
    val setMomentsRowsPerPage: (Int) -> Unit = {},
    val setMomentsLoadAhead: (Int) -> Unit = {},
    val resetMomentsPaging: () -> Unit = {},
    val back: () -> Unit = {},
)

/** Settings' first page, and the pages its rows open. Backup, Free up space, Hidden collections and Search are screens of their own. */
internal enum class SettingsPage { MAIN, SHOWN, MOMENTS, STORAGE, LOADING_MOMENTS }

/**
 * The user's choices, kept on this device: on [SettingsPage.MAIN] a short page of groups (Library, Privacy, Backup and
 * storage, Search), each row saying how it stands and opening a page of its own; the switches used most stay there.
 * See docs/screens/settings.md.
 */
@Composable
internal fun SettingsScreen(
    settings: SettingsState,
    actions: SettingsActions,
    page: SettingsPage = SettingsPage.MAIN,
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
    /** What Private settings hides on every device, for Show in the app; null until it was opened in this run. */
    hiddenEverywhere: HiddenCollections? = null,
    /** Your cameras' last list, for its row; null when the server doesn't keep cameras (no row). */
    cameras: CamerasState? = null,
) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val title = when (page) {
        SettingsPage.MAIN -> R.string.library_settings
        SettingsPage.SHOWN -> R.string.settings_shown_title
        SettingsPage.MOMENTS -> R.string.settings_moments_title
        SettingsPage.STORAGE -> R.string.settings_storage_title
        SettingsPage.LOADING_MOMENTS -> R.string.settings_loading_title
    }

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
        ) {
            when (page) {
                SettingsPage.MAIN -> MainPage(settings, actions, sourceCounts, backup, storage, storageActions, syncProgress, phoneSearch, indexing, cameras)
                SettingsPage.SHOWN -> ShownSourcesSection(settings.shownSources, sourceCounts, hiddenEverywhere, actions)
                SettingsPage.MOMENTS -> MomentsPage(settings, actions)
                SettingsPage.STORAGE -> if (storage != null) StorageSection(storage, storageActions)
                SettingsPage.LOADING_MOMENTS -> LoadingMomentsPage(settings.momentsPaging, actions)
            }
        }
        TitleBar(stringResource(title), actions.back, Modifier.align(Alignment.TopCenter))
    }
}

/** The groups, each row with its line. Backup and storage only when the app has them. */
@Composable
private fun MainPage(
    settings: SettingsState,
    actions: SettingsActions,
    sourceCounts: Map<MediaSource, Int>,
    backup: BackupUi?,
    storage: StorageUi?,
    storageActions: StorageActions,
    syncProgress: SyncProgress?,
    phoneSearch: com.chencohensolution.journal.local.PhoneSearchState?,
    indexing: ServerIndexingState?,
    cameras: CamerasState?,
) {
    SectionTitle(stringResource(R.string.settings_library_title))
    Card {
        PageRow(stringResource(R.string.settings_shown_title), shownSummary(settings.shownSources, sourceCounts)) { actions.openPage(SettingsPage.SHOWN) }
        RowDivider()
        if (cameras != null) {
            PageRow(stringResource(R.string.cameras_title), camerasSummary(cameras), actions.openCameras)
            RowDivider()
        }
        PageRow(stringResource(R.string.settings_moments_title), momentsSummary(settings)) { actions.openPage(SettingsPage.MOMENTS) }
        RowDivider()
        SwitchRow(
            title = stringResource(R.string.settings_on_phone_badge),
            body = stringResource(R.string.settings_on_phone_badge_body),
            checked = settings.showOnPhoneBadge,
            onChange = actions.setShowOnPhoneBadge,
        )
    }
    SectionTitle(stringResource(R.string.settings_privacy_title))
    // Show private media is in Private settings, behind the phone's lock (asked Oct 25, 2026).
    Card { HiddenRow(syncProgress, actions.openHidden) }
    if (backup != null || storage != null) {
        SectionTitle(stringResource(R.string.settings_backup_storage_title))
        Card {
            if (backup != null) PageRow(stringResource(R.string.settings_backup_title), backupStatus(backup), actions.openBackup)
            if (storage != null) {
                if (backup != null) RowDivider()
                PageRow(stringResource(R.string.settings_storage_title), storageSummary(storage)) { actions.openPage(SettingsPage.STORAGE) }
                RowDivider()
                PageRow(stringResource(R.string.settings_free_up_title), freeUpRowText(storage.freeUp), storageActions.openFreeUp)
            }
        }
    }
    Column(Modifier.padding(top = 20.dp)) {
        Card { SearchSettingsRow(settings.searchPageSize, indexing, phoneSearch, actions.openSearch) }
    }
    AdvancedSection(settings.momentsPaging) { actions.openPage(SettingsPage.LOADING_MOMENTS) }
}

/** Moments: how its rows are made, with the break's length under "By breaks", and how alike a set's photos are. */
@Composable
private fun MomentsPage(settings: SettingsState, actions: SettingsActions) {
    val grouping = settings.grouping
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
        if (grouping.mode == GroupingMode.BY_GAP) {
            OptionChips {
                TimelineGrouping.GAP_HOURS_OPTIONS.forEach { hours ->
                    Chip(pluralStringResource(R.plurals.settings_gap_hours, hours, hours), grouping.gapHours == hours) { actions.setGapHours(hours) }
                }
            }
        }
    }
    SectionTitle(stringResource(R.string.settings_similarity_title))
    SimilarityCard(settings.similarity, actions)
}

/** A row that opens a page: its title, how it stands, and a chevron. */
@Composable
internal fun PageRow(title: String, body: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text(body, color = LibraryColors.Muted, fontSize = 13.sp)
        }
        Icon(LibraryIcons.ChevronRight, contentDescription = null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
    }
}

/** Show in the app's row: "Showing Camera, Other, Petcube +2", the most photos first, then Imported while on. */
@Composable
private fun shownSummary(shown: ShownSources, counts: Map<MediaSource, Int>): String {
    val imported = shown.importedShown && shown.importedChoices(counts).any { shown.isShown(it) }
    val names = shown.choices(counts).filter { shown.isShown(it) }.map { sourceName(it) } +
        listOfNotNull(stringResource(R.string.sources_imported).takeIf { imported })
    if (names.isEmpty()) return stringResource(R.string.settings_shown_none)
    val first = names.take(3).joinToString(", ")
    return stringResource(R.string.settings_shown_summary, if (names.size > 3) "$first +${names.size - 3}" else first)
}

/**
 * Your cameras' row: "4 cameras · 1 not yours", "4 cameras" when all are, "Moving 312 photos…" while the server moves
 * some, and "Which cameras were yours" before the list came.
 */
@Composable
private fun camerasSummary(cameras: CamerasState): String {
    val ready = cameras as? CamerasState.Ready ?: return stringResource(R.string.settings_cameras_unknown)
    val format = NumberFormat.getIntegerInstance()
    if (ready.pending > 0) return pluralStringResource(R.plurals.cameras_moving, ready.pending, format.format(ready.pending))
    val count = pluralStringResource(R.plurals.settings_cameras_count, ready.cameras.size, format.format(ready.cameras.size))
    val notYours = ready.cameras.count { !it.mine }
    if (notYours == 0) return count
    return stringResource(R.string.feed_place_time, count, pluralStringResource(R.plurals.settings_cameras_not_yours, notYours, format.format(notYours)))
}

/** Moments' row: "By day · similar at 60%", "New row after 12 hours · similar at 85%". */
@Composable
private fun momentsSummary(settings: SettingsState): String {
    val grouping = settings.grouping
    val rows = if (grouping.mode == GroupingMode.BY_GAP) {
        pluralStringResource(R.plurals.settings_moments_gap_summary, grouping.gapHours, grouping.gapHours)
    } else {
        stringResource(R.string.settings_grouping_by_day)
    }
    return stringResource(R.string.feed_place_time, rows, stringResource(R.string.settings_moments_similar_summary, settings.similarity.percent))
}

@Composable
internal fun RowDivider() = HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))

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
 * Show in the app (option b2 of the UI lab, Oct 27, 2026): a pill per source with its count, the most photos first
 * (asked Oct 24, 2026, when each folder became a source), one "Imported" with its kinds under it while on, and what
 * Private settings hides on every device ([ShownSourcesPillsSection]); then "Say what's hidden" and "Filter bar in
 * Moments", then where it applies and that hiding never deletes or changes backup.
 */
@Composable
private fun ShownSourcesSection(shown: ShownSources, counts: Map<MediaSource, Int>, hiddenEverywhere: HiddenCollections?, actions: SettingsActions) {
    SectionTitle(stringResource(R.string.settings_shown_title))
    val pills = remember(shown, counts, hiddenEverywhere) { ShownSourcesPills.of(shown, counts, hiddenEverywhere) }
    ShownSourcesPillsSection(pills, ShownSourcesActions(actions.toggleSource, actions.toggleImported, actions.openHidden))
    Column(Modifier.padding(top = 20.dp)) {
        Card {
            SwitchRow(
                title = stringResource(R.string.settings_say_hidden),
                body = stringResource(R.string.settings_say_hidden_body),
                checked = shown.sayWhatsHidden,
                onChange = actions.setSayWhatsHidden,
            )
            HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f))
            SwitchRow(
                title = stringResource(R.string.settings_filter_bar),
                body = stringResource(R.string.settings_filter_bar_body),
                checked = shown.showFilterBar,
                onChange = actions.setShowFilterBar,
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

/** Private settings (Show private media, nudity, sound recordings, WhatsApp, screenshots), with how far the library is in reading the server's list again after a change there. */
@Composable
private fun HiddenRow(syncProgress: SyncProgress?, openHidden: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = openHidden).padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 14.dp),
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
