package com.chencohensolution.journal.ui.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.stories.BurstSuggestion
import com.chencohensolution.journal.stories.GroupView
import com.chencohensolution.journal.stories.Story
import com.chencohensolution.journal.stories.StoryTile
import com.chencohensolution.journal.stories.StoryView
import com.chencohensolution.journal.library.MediaItem
import kotlinx.coroutines.launch

internal class GroupActions(
    val openItem: (MediaItem) -> Unit = {},
    val openGroup: (GroupView) -> Unit = {},
    val rename: (String) -> Unit = {},
    val setText: (String) -> Unit = {},
    val ungroup: () -> Unit = {},
    /** Photos and subgroups ([StoryTile.key]s) into a new subgroup; returns its id. */
    val groupTiles: (tileKeys: List<String>) -> String? = { null },
    /** Photos and subgroups ([StoryTile.key]s) into the first picked subgroup; returns its id. */
    val mergeTiles: (tileKeys: List<String>) -> String? = { null },
    /** Puts the story back as it was before a [groupTiles] or [mergeTiles]. */
    val restore: (Story) -> Unit = {},
    /** Takes photos out of this group, into the level above. */
    val takeOut: (itemIds: List<String>) -> Unit = {},
    /** "Group them" on a suggestion ([BurstSuggestion.key]): its photos become a subgroup. Returns its id. */
    val acceptSuggestion: (key: String) -> String? = { null },
    val dismissSuggestion: (key: String) -> Unit = {},
    val back: () -> Unit = {},
)

/**
 * A group: its cover, title and story text, then its photos and subgroups. Long-press a tile to select tiles and
 * group them into a subgroup, merge them, or take them out. See docs/screens/group.md.
 */
@Composable
internal fun GroupScreen(story: StoryView?, group: GroupView?, actions: GroupActions) {
    if (story == null || group == null) {
        // Ungrouped or deleted.
        LaunchedEffect(Unit) { actions.back() }
        return
    }
    var renaming by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var selecting by rememberSaveable { mutableStateOf(false) }
    /** Tile keys ([StoryTile.key]): photos' device ids and subgroups' keys. */
    var selected by rememberSaveable { mutableStateOf(listOf<String>()) }
    val selectedTiles = group.tiles.filter { it.key in selected }
    val selectedItemIds = selectedTiles.flatMap { tile ->
        when (tile) {
            is StoryTile.Media -> listOf(tile.item.id)
            is StoryTile.Group -> tile.group.items.map { it.id }
        }
    }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val insets = WindowInsets.systemBars.asPaddingValues()
    val name = group.title ?: timeRange(group.items)
    // "JAPAN TRIP › DAY AT THE SEA": the story, then the groups around this one.
    val path = (listOf(storyName(story)) + story.parentsOf(group.id).map { it.title ?: timeRange(it.items) }).joinToString(" › ")

    val groupedMessage = stringResource(R.string.library_grouped)
    val undoLabel = stringResource(R.string.library_undo)
    fun endSelection() {
        selecting = false
        selected = emptyList()
    }
    fun change(action: (List<String>) -> String?) {
        val before = story.story
        val count = selectedItemIds.size
        if (action(selected) != null) {
            scope.launch {
                if (snackbar.offerUndo(groupedMessage.format(count), undoLabel)) actions.restore(before)
            }
        }
        endSelection()
    }
    fun acceptSuggestion(suggestion: BurstSuggestion) {
        val before = story.story
        if (actions.acceptSuggestion(suggestion.key) != null) {
            scope.launch {
                if (snackbar.offerUndo(groupedMessage.format(suggestion.count), undoLabel)) actions.restore(before)
            }
        }
    }
    fun toggle(key: String) {
        selected = if (key in selected) selected - key else selected + key
    }
    fun startSelecting(key: String) {
        selecting = true
        if (key !in selected) selected = selected + key
    }
    BackHandler(enabled = selecting, onBack = ::endSelection)

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(3),
            contentPadding = PaddingValues(bottom = insets.calculateBottomPadding() + if (selecting) 110.dp else 32.dp),
            horizontalArrangement = Arrangement.spacedBy(TILE_GAP),
            verticalArrangement = Arrangement.spacedBy(TILE_GAP),
        ) {
            item(key = "cover", span = { GridItemSpan(maxLineSpan) }) {
                val cover = rememberThumbnail(group.cover, THUMB_LARGE)
                Box(Modifier.fillMaxWidth().height(300.dp).background(LibraryColors.Raised)) {
                    if (cover != null) Image(cover, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    Box(
                        Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .height(120.dp)
                            .background(Brush.verticalGradient(listOf(Color.Transparent, LibraryColors.Background))),
                    )
                }
            }
            item(key = "title", span = { GridItemSpan(maxLineSpan) }) {
                Column(Modifier.offset(y = (-36).dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(path.uppercase(), color = LibraryColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
                    Text(
                        name,
                        color = LibraryColors.OnBackground,
                        fontSize = 28.sp,
                        lineHeight = 32.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        // An untitled group already shows its time as the title.
                        listOfNotNull(
                            dateRange(group.items.first().day, group.items.last().day),
                            timeRange(group.items).takeIf { group.title != null },
                            pluralStringResource(R.plurals.library_story_items, group.itemCount, group.itemCount),
                        ).joinToString(" · "),
                        color = LibraryColors.Muted,
                        fontSize = 14.sp,
                    )
                }
            }
            item(key = "story", span = { GridItemSpan(maxLineSpan) }) {
                Box(Modifier.offset(y = (-24).dp)) {
                    WritingBlock(group.text, stringResource(R.string.library_writing_group_prompt)) { editing = true }
                }
            }
            if (!selecting) {
                items(group.suggestions, key = { "suggestion-${it.key}" }, span = { GridItemSpan(maxLineSpan) }) { suggestion ->
                    BurstSuggestionCard(
                        suggestion,
                        onGroup = { acceptSuggestion(suggestion) },
                        onDismiss = { actions.dismissSuggestion(suggestion.key) },
                    )
                }
            }
            items(group.tiles, key = { it.key }) { tile ->
                val isSelected = if (selecting) tile.key in selected else null
                Box(Modifier.aspectRatio(1f)) {
                    when (tile) {
                        is StoryTile.Media -> MediaTile(
                            tile.item,
                            onClick = { if (selecting) toggle(tile.key) else actions.openItem(tile.item) },
                            modifier = Modifier.fillMaxSize(),
                            onLongClick = { startSelecting(tile.key) },
                            selected = isSelected,
                        )
                        is StoryTile.Group -> GroupTile(
                            tile.group,
                            Modifier.fillMaxSize(),
                            thumbnailSize = THUMB_SMALL,
                            onLongClick = { startSelecting(tile.key) },
                            selected = isSelected,
                        ) { if (selecting) toggle(tile.key) else actions.openGroup(tile.group) }
                    }
                    // The tile holding the cover: the photo itself, or the subgroup it is in. Hidden while selecting, where
                    // the check circle takes its corner.
                    val holdsCover = when (tile) {
                        is StoryTile.Media -> tile.item.id == group.cover.id
                        is StoryTile.Group -> tile.group.items.any { it.id == group.cover.id }
                    }
                    if (holdsCover && !selecting) {
                        Text(
                            stringResource(R.string.library_cover),
                            color = LibraryColors.PaperInk,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier
                                .padding(6.dp)
                                .background(LibraryColors.Paper, RoundedCornerShape(50))
                                .padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }
        }

        if (selecting) {
            SelectionTopBar(selected.size, onClose = ::endSelection, modifier = Modifier.align(Alignment.TopCenter))
            SelectionBottomBar(
                // Every tile into one subgroup would only wrap the group again.
                canGroup = selectedTiles.size >= 2 && selectedTiles.size < group.tiles.size,
                canMerge = selectedTiles.size >= 2 && selectedTiles.any { it is StoryTile.Group },
                onGroup = { change(actions.groupTiles) },
                onMerge = { change(actions.mergeTiles) },
                removeLabel = stringResource(R.string.library_take_out),
                onRemove = {
                    actions.takeOut(selectedItemIds)
                    endSelection()
                },
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        } else Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(LibraryColors.TopFade)
                .statusBarsPadding()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.back) {
                Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.OnBackground)
            }
            Box(Modifier.weight(1f))
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
                            renaming = true
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.library_ungroup)) },
                        leadingIcon = { Icon(LibraryIcons.Stack, null) },
                        onClick = {
                            menuOpen = false
                            actions.ungroup()
                        },
                    )
                }
            }
        }
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = if (selecting) 96.dp else 16.dp))
    }

    if (editing) {
        WritingEditor(
            title = name,
            initial = group.text,
            onSave = {
                editing = false
                actions.setText(it)
            },
            onDismiss = { editing = false },
        )
    }
    if (renaming) {
        StoryTitleDialog(
            title = stringResource(R.string.library_rename_group),
            initial = group.title.orEmpty(),
            confirmLabel = stringResource(R.string.library_save),
            onConfirm = {
                renaming = false
                actions.rename(it)
            },
            onDismiss = { renaming = false },
        )
    }
}
