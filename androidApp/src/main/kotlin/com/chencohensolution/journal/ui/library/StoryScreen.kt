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
import androidx.compose.material3.SnackbarResult
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
import com.chencohensolution.journal.stories.StoryView
import com.chencohensolution.journal.stories.GroupView
import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
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
    val createGroup: (itemIds: List<String>) -> String? = { null },
    val dismissSuggestion: (key: String) -> Unit = {},
    val ungroup: (groupId: String) -> Unit = {},
    val removeItems: (itemIds: List<String>) -> Unit = {},
    val back: () -> Unit = {},
)

/**
 * One story: its title, dates and story, burst suggestions, then its photos and groups day by day, oldest first.
 * Long-press a photo to select photos and group them. See docs/screens/story.md.
 */
@Composable
internal fun StoryScreen(story: StoryView?, neighborDays: List<DayGroup>, health: ServerHealth, actions: StoryActions) {
    if (story == null) {
        // Deleted, here or on another screen.
        LaunchedEffect(Unit) { actions.back() }
        return
    }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var confirmingDelete by rememberSaveable { mutableStateOf(false) }
    var editingIntro by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val insets = WindowInsets.systemBars.asPaddingValues()

    val groupedMessage = stringResource(R.string.library_grouped)
    val undoLabel = stringResource(R.string.library_undo)
    fun offerUndo(groupId: String?, count: Int) {
        groupId ?: return
        scope.launch {
            val result = snackbar.showSnackbar(groupedMessage.format(count), actionLabel = undoLabel, withDismissAction = false)
            if (result == SnackbarResult.ActionPerformed) actions.ungroup(groupId)
        }
    }
    fun endSelection() {
        selecting = false
        selected = emptyList()
    }
    BackHandler(enabled = selecting, onBack = ::endSelection)

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(
            contentPadding = PaddingValues(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 110.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "header") { Header(story) }
            item(key = "intro") {
                WritingBlock(story.intro, stringResource(R.string.library_writing_story_prompt)) { editingIntro = true }
            }
            if (neighborDays.isNotEmpty() && !selecting) {
                item(key = "neighbors") { NeighborDays(neighborDays, actions.addDay) }
            }
            if (!selecting) {
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
                onLongPress = { item ->
                    selecting = true
                    if (item.id !in selected) selected = selected + item.id
                },
                selection = if (selecting) {
                    GridSelection(selected.toSet()) { id -> selected = if (id in selected) selected - id else selected + id }
                } else {
                    null
                },
            )
        }

        if (selecting) {
            SelectionTopBar(selected.size, onClose = ::endSelection, modifier = Modifier.align(Alignment.TopCenter))
            SelectionBottomBar(
                canGroup = selected.size >= 2,
                onGroup = {
                    val count = selected.size
                    offerUndo(actions.createGroup(selected), count)
                    endSelection()
                },
                onRemove = {
                    actions.removeItems(selected)
                    endSelection()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else {
            StoryTopBar(
                health = health,
                onBack = actions.back,
                onRename = { renaming = true },
                onDelete = { confirmingDelete = true },
                modifier = Modifier.align(Alignment.TopCenter),
            )
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (selecting) 96.dp else 16.dp))
    }

    if (editingIntro) {
        WritingEditor(
            title = story.title,
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
            title = { Text(stringResource(R.string.library_delete_story_title, story.title)) },
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
private fun StoryTopBar(health: ServerHealth, onBack: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit, modifier: Modifier = Modifier) {
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
private fun SelectionTopBar(count: Int, onClose: () -> Unit, modifier: Modifier = Modifier) {
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

@Composable
private fun SelectionBottomBar(canGroup: Boolean, onGroup: () -> Unit, onRemove: () -> Unit, modifier: Modifier = Modifier) {
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
        TextButton(onClick = onRemove, modifier = Modifier.weight(1f).height(52.dp)) {
            Icon(LibraryIcons.Minus, null, tint = LibraryColors.Warning, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.library_remove), color = LibraryColors.Warning, modifier = Modifier.padding(start = 8.dp))
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
            Text(
                story.title,
                color = LibraryColors.OnBackground,
                fontSize = 28.sp,
                lineHeight = 32.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.semantics { heading() },
            )
            val groups = story.groups.size
            val summary = storySummary(story)
            Text(
                if (groups > 0) summary + " · " + pluralStringResource(R.plurals.library_story_groups, groups, groups) else summary,
                color = LibraryColors.Muted,
                fontSize = 14.sp,
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
