package com.chencohensolution.journal.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.stories.Story
import com.chencohensolution.journal.stories.StoryTile
import com.chencohensolution.journal.stories.StoryView
import com.chencohensolution.journal.stories.FocusPoint
import com.chencohensolution.journal.stories.GroupView
import com.chencohensolution.journal.stories.TileSize
import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.stories.photo
import kotlinx.coroutines.launch

internal class StoryActions(
    val openItem: (MediaItem) -> Unit = {},
    val openGroup: (GroupView) -> Unit = {},
    val addDay: (LocalDay) -> Unit = {},
    val rename: (String) -> Unit = {},
    val delete: () -> Unit = {},
    val setIntro: (String) -> Unit = {},
    /** Each returns the new group's id. */
    val acceptSuggestion: (key: String) -> String? = { null },
    /** Photos and groups ([StoryTile.key]s) into a new group; returns its id. */
    val groupTiles: (tileKeys: List<String>) -> String? = { null },
    /** Photos and groups ([StoryTile.key]s) into the first picked group; returns its id. */
    val mergeTiles: (tileKeys: List<String>) -> String? = { null },
    /** Puts the story back as it was before a [groupTiles] or [mergeTiles]. */
    val restore: (Story) -> Unit = {},
    val dismissSuggestion: (key: String) -> Unit = {},
    val ungroup: (groupId: String) -> Unit = {},
    val removeItems: (itemIds: List<String>) -> Unit = {},
    /** Layout, by tile key ([StoryTile.key]); a null size is automatic. */
    val setTileSize: (tileKey: String, size: TileSize?) -> Unit = { _, _ -> },
    val setTileFocus: (tileKey: String, focus: FocusPoint, zoom: Float) -> Unit = { _, _, _ -> },
    val moveTile: (tileKey: String, offset: Int) -> Unit = { _, _ -> },
    val resetLayout: () -> Unit = {},
    /** Removes every group and brings suggestions back; Undo puts the story back with [restore]. */
    val resetGroups: () -> Unit = {},
    val back: () -> Unit = {},
)

/**
 * One story: its title, dates and story, burst suggestions, then its photos and groups day by day, oldest first.
 * Long-press a photo or group to select tiles and group them (groups nest) or merge them; ⋯ › Arrange to size, reorder and crop the tiles. See
 * docs/screens/story-page.md.
 */
@Composable
internal fun StoryScreen(
    story: StoryView?,
    neighborDays: List<DayGroup>,
    health: ServerHealth,
    actions: StoryActions,
    /** The photo the viewer above shows: its tile is scrolled into view for the photo to land on when it closes. */
    landing: String? = null,
) {
    if (story == null) {
        // Deleted, here or on another screen.
        LaunchedEffect(Unit) { actions.back() }
        return
    }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var editingIntro by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    /** Tile keys ([StoryTile.key]): photos' device ids and groups' keys. */
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    val selectedTiles = story.tiles.filter { it.key in selected }
    val selectedItemIds = selectedTiles.flatMap { tile ->
        when (tile) {
            is StoryTile.Media -> listOf(tile.item.id)
            is StoryTile.Group -> tile.group.items.map { it.id }
        }
    }
    var arranging by rememberSaveable { mutableStateOf(false) }
    var arrangeKey by rememberSaveable { mutableStateOf<String?>(null) }
    /** The tile whose focus is being chosen; kept apart from [arrangeKey] so the editor stays on it. */
    var focusKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmingReset by rememberSaveable { mutableStateOf(false) }
    var confirmingGroupsReset by rememberSaveable { mutableStateOf(false) }
    fun placed(key: String?) = key?.let { story.tileDays.asSequence().flatMap { it.bands }.flatMap { it.tiles }.firstOrNull { it.tile.key == key } }
    val arranged = placed(arrangeKey)
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val insets = WindowInsets.systemBars.asPaddingValues()

    val groupedMessage = stringResource(R.string.library_grouped)
    val undoLabel = stringResource(R.string.library_undo)
    fun offerUndo(groupId: String?, count: Int, undo: () -> Unit = { groupId?.let(actions.ungroup) }) {
        groupId ?: return
        scope.launch {
            if (snackbar.offerUndo(groupedMessage.format(count), undoLabel)) undo()
        }
    }
    fun endSelection() {
        selecting = false
        selected = emptyList()
    }
    fun endArranging() {
        arranging = false
        arrangeKey = null
    }
    BackHandler(enabled = selecting, onBack = ::endSelection)
    BackHandler(enabled = arranging, onBack = ::endArranging)

    val list = rememberLazyListState()
    // A group stands in the viewer as its cover.
    val bands = story.tileDays.flatMap { it.bands }
    list.LandOn(landing, bands.size) { id -> bands.indexOfFirst { band -> band.tiles.any { it.tile.photo.id == id } } }

    Box(Modifier.fillMaxSize().sharedContainer(SharedKey.story(story.id)).background(LibraryColors.Background)) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding() + 64.dp,
                bottom = insets.calculateBottomPadding() + if (arranging) 200.dp else 110.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        ) {
            item(key = "header") { Header(story) }
            if (!arranging) {
                item(key = "intro") {
                    WritingBlock(story.intro, stringResource(R.string.library_writing_story_prompt)) { editingIntro = true }
                }
            }
            if (neighborDays.isNotEmpty() && !selecting && !arranging) {
                item(key = "neighbors") { NeighborDays(neighborDays, actions.addDay) }
            }
            if (!selecting && !arranging) {
                items(story.suggestions, key = { "suggestion-${it.key}" }) { suggestion ->
                    BurstSuggestionCard(
                        suggestion,
                        onGroup = { offerUndo(actions.acceptSuggestion(suggestion.key), suggestion.count) },
                        onDismiss = { actions.dismissSuggestion(suggestion.key) },
                    )
                }
            }
            if (story.missingCount > 0) {
                item(key = "missing") {
                    Note(pluralStringResource(R.plurals.library_story_missing, story.missingCount, story.missingCount))
                }
            }
            if (story.items.isEmpty()) {
                item(key = "empty") { Note(stringResource(R.string.library_story_empty)) }
            }
            item(key = "space") { Box(Modifier.height(8.dp)) }
            storyGrids(
                story.tileDays,
                label = { day ->
                    val label = dayLabel(day.day)
                    // "Day 2" helps in a trip, not in a story spanning months.
                    if (story.isShortSpan && story.dayCount > 1) {
                        val number = (day.day.toEpochDay() - story.firstDay!!.toEpochDay() + 1).toInt()
                        stringResource(R.string.library_story_day, number, label)
                    } else {
                        label
                    }
                },
                onOpenItem = actions.openItem,
                onOpenGroup = actions.openGroup,
                onLongPress = { key ->
                    selecting = true
                    if (key !in selected) selected = selected + key
                },
                selection = if (selecting) {
                    GridSelection(selected.toSet()) { id -> selected = if (id in selected) selected - id else selected + id }
                } else {
                    null
                },
                arranging = if (arranging) GridArranging(arrangeKey) { key -> arrangeKey = key.takeIf { it != arrangeKey } } else null,
            )
        }

        if (arranging) {
            ArrangeTopBar(onReset = { confirmingReset = true }, onDone = ::endArranging, modifier = Modifier.align(Alignment.TopCenter))
            ArrangePanel(
                tile = arranged,
                onSize = { size -> arranged?.let { actions.setTileSize(it.tile.key, size) } },
                onMove = { offset -> arranged?.let { actions.moveTile(it.tile.key, offset) } },
                onFocus = { focusKey = arrangeKey },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else if (selecting) {
            SelectionTopBar(selected.size, onClose = ::endSelection, modifier = Modifier.align(Alignment.TopCenter))
            SelectionBottomBar(
                canGroup = selectedTiles.size >= 2,
                canMerge = selectedTiles.size >= 2 && selectedTiles.any { it is StoryTile.Group },
                onGroup = {
                    val before = story.story
                    offerUndo(actions.groupTiles(selected), selectedItemIds.size) { actions.restore(before) }
                    endSelection()
                },
                onMerge = {
                    val before = story.story
                    offerUndo(actions.mergeTiles(selected), selectedItemIds.size) { actions.restore(before) }
                    endSelection()
                },
                removeLabel = stringResource(R.string.library_remove),
                onRemove = {
                    actions.removeItems(selectedItemIds)
                    endSelection()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            StoryTopBar(
                health = health,
                onBack = actions.back,
                onRename = { renaming = true },
                onArrange = { arranging = true },
                onResetGroups = { confirmingGroupsReset = true }.takeIf { story.groups.isNotEmpty() || story.story.dismissedSuggestions.isNotEmpty() },
                onDelete = { confirmingDelete = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (selecting) 96.dp else 16.dp))
    }

    placed(focusKey)?.let { tile ->
        FocusEditor(
            tile = tile,
            onSave = { focus, zoom ->
                focusKey = null
                actions.setTileFocus(tile.tile.key, focus, zoom)
            },
            onDismiss = { focusKey = null },
        )
    }
    if (confirmingReset) {
        AlertDialog(
            onDismissRequest = { confirmingReset = false },
            title = { Text(stringResource(R.string.library_arrange_reset_title)) },
            text = { Text(stringResource(R.string.library_arrange_reset_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingReset = false
                    arrangeKey = null
                    actions.resetLayout()
                }) { Text(stringResource(R.string.library_arrange_reset)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingReset = false }) { Text(stringResource(R.string.library_cancel)) }
            },
        )
    }
    if (confirmingGroupsReset) {
        val resetMessage = stringResource(R.string.library_reset_groups_done)
        AlertDialog(
            onDismissRequest = { confirmingGroupsReset = false },
            title = { Text(stringResource(R.string.library_reset_groups_title)) },
            text = { Text(stringResource(R.string.library_reset_groups_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingGroupsReset = false
                    val before = story.story
                    actions.resetGroups()
                    scope.launch { if (snackbar.offerUndo(resetMessage, undoLabel)) actions.restore(before) }
                }) { Text(stringResource(R.string.library_reset_groups)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingGroupsReset = false }) { Text(stringResource(R.string.library_cancel)) }
            },
        )
    }
    if (editingIntro) {
        WritingEditor(
            title = storyName(story),
            initial = story.intro,
            onSave = {
                editingIntro = false
                actions.setIntro(it)
            },
            onDismiss = { editingIntro = false },
        )
    }
    if (renaming) {
        StoryTitleDialog(
            title = stringResource(R.string.library_rename_story),
            initial = story.title,
            confirmLabel = stringResource(R.string.library_save),
            onConfirm = {
                renaming = false
                actions.rename(it)
            },
            onDismiss = { renaming = false },
        )
    }
    if (confirmingDelete) {
        AlertDialog(
            onDismissRequest = { confirmingDelete = false },
            title = { Text(stringResource(R.string.library_delete_story_title, storyName(story))) },
            text = { Text(stringResource(R.string.library_delete_story_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmingDelete = false
                    actions.delete()
                }) { Text(stringResource(R.string.library_delete), color = LibraryColors.Error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmingDelete = false }) { Text(stringResource(R.string.library_cancel)) }
            },
        )
    }
}

@Composable
private fun StoryTopBar(
    health: ServerHealth,
    onBack: () -> Unit,
    onRename: () -> Unit,
    onArrange: () -> Unit,
    /** Null when the story has no groups and nothing dismissed: then there is nothing to reset. */
    onResetGroups: (() -> Unit)?,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .background(LibraryColors.TopFade)
            .statusBarsPadding()
            .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.OnBackground)
        }
        Box(Modifier.weight(1f))
        if (health == ServerHealth.OFFLINE) OfflinePill()
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(LibraryIcons.More, stringResource(R.string.library_more), tint = LibraryColors.OnBackground)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_rename)) },
                    leadingIcon = { Icon(LibraryIcons.Edit, null) },
                    onClick = {
                        menuOpen = false
                        onRename()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_arrange)) },
                    leadingIcon = { Icon(LibraryIcons.Grid, null) },
                    onClick = {
                        menuOpen = false
                        onArrange()
                    },
                )
                if (onResetGroups != null) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_reset_groups)) },
                        leadingIcon = { Icon(LibraryIcons.Stack, null) },
                        onClick = {
                            menuOpen = false
                            onResetGroups()
                        },
                    )
                }
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_delete_story)) },
                    leadingIcon = { Icon(LibraryIcons.Trash, null) },
                    onClick = {
                        menuOpen = false
                        onDelete()
                    },
                )
            }
        }
    }
}

/** "4 selected" with ✕ to leave selection mode. */
@Composable
internal fun SelectionTopBar(count: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(LibraryColors.Raised)
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onClose) {
            Icon(LibraryIcons.Close, stringResource(R.string.library_cancel_selection), tint = LibraryColors.OnBackground)
        }
        Text(
            pluralStringResource(R.plurals.library_selected, count, count),
            color = LibraryColors.OnBackground,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * The bar under a selection: **Group** puts the picked tiles in a new group, **Merge** (when a group is picked)
 * joins them into the first picked group, and [removeLabel] takes them out.
 */
@Composable
internal fun SelectionBottomBar(
    canGroup: Boolean,
    canMerge: Boolean,
    onGroup: () -> Unit,
    onMerge: () -> Unit,
    removeLabel: String,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(LibraryColors.Raised)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onGroup,
            enabled = canGroup,
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = LibraryColors.Inverse, contentColor = LibraryColors.OnInverse),
            modifier = Modifier.weight(1f).height(52.dp),
        ) {
            Icon(LibraryIcons.Stack, null, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.library_group_action), fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 8.dp))
        }
        if (canMerge) {
            TextButton(onClick = onMerge, modifier = Modifier.weight(1f).height(52.dp)) {
                Text(stringResource(R.string.library_merge_action), color = LibraryColors.Accent, fontWeight = FontWeight.SemiBold)
            }
        }
        TextButton(onClick = onRemove, modifier = Modifier.weight(1f).height(52.dp)) {
            Icon(LibraryIcons.Minus, null, tint = LibraryColors.Warning, modifier = Modifier.size(20.dp))
            Text(removeLabel, color = LibraryColors.Warning, modifier = Modifier.padding(start = 8.dp))
        }
    }
}

@Composable
private fun Header(story: StoryView) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        story.firstDay?.let { DateBlock(it) }
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (story.hasTitle) {
                Text(
                    story.title,
                    color = LibraryColors.OnBackground,
                    fontSize = 28.sp,
                    lineHeight = 32.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
            }
            val groups = story.groups.size
            val summary = storySummary(story)
            // Untitled, the dates are the heading.
            Text(
                if (groups > 0) summary + " · " + pluralStringResource(R.plurals.library_story_groups, groups, groups) else summary,
                color = if (story.hasTitle) LibraryColors.Muted else LibraryColors.OnBackground,
                fontSize = if (story.hasTitle) 14.sp else 18.sp,
                fontWeight = if (story.hasTitle) null else FontWeight.SemiBold,
            )
        }
    }
}

/** "Add Tue, Oct 7 · 6 photos": unsorted days right before or after the story, one tap to include them. */
@Composable
private fun NeighborDays(days: List<DayGroup>, onAdd: (LocalDay) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(vertical = 6.dp),
    ) {
        items(days, key = { it.day.toString() }) { day ->
            TextButton(
                onClick = { onAdd(day.day) },
                shape = RoundedCornerShape(50),
                modifier = Modifier
                    .height(40.dp)
                    .border(1.dp, LibraryColors.OnBackground.copy(alpha = 0.28f), RoundedCornerShape(50)),
            ) {
                Icon(LibraryIcons.Plus, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(16.dp))
                Text(
                    stringResource(
                        R.string.library_story_add_day,
                        dayLabel(day.day),
                        pluralStringResource(R.plurals.library_story_items, day.items.size, day.items.size),
                    ),
                    color = LibraryColors.OnBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}
