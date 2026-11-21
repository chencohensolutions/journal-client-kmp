package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.chencohensolution.journal.R
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.TagSummary
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import com.chencohensolution.journal.library.MediaItem

/**
 * Every tag, most recently used first, with a search field; tucked-away tags in their own section at the end. Each row's
 * ⋯ (or a long press) opens its sheet: Tuck away this tag, or Bring back. See docs/screens/tags.md.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TagsScreen(
    stories: StoriesState,
    onOpenTag: (String) -> Unit,
    onBack: () -> Unit,
    onGoToMoments: (() -> Unit)? = null,
    /** Tucks a tag away (true) or brings it back. */
    onSetTucked: (tag: String, on: Boolean) -> Unit = { _, _ -> },
    /** Delete tag, once confirmed: it comes off every photo. */
    onDelete: (tag: String) -> Unit = {},
    /** Previews: the ⋯ sheet of this tag open. */
    initialMenu: String? = null,
    /** Previews: its Delete tag confirmation open. */
    initialConfirm: Boolean = false,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var menuFor by rememberSaveable { mutableStateOf(initialMenu) }
    val tags = if (query.isBlank()) stories.tags else stories.searchTags(query)
    val (tucked, shown) = tags.partition { it.tucked }
    val insets = barsRoom()

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(contentPadding = PaddingValues(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp)) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.library_find_tag)) },
                    leadingIcon = { Icon(LibraryIcons.Search, null, tint = LibraryColors.Muted) },
                    singleLine = true,
                    shape = LibraryShapes.Tile,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = LibraryColors.OnBackground,
                        unfocusedTextColor = LibraryColors.OnBackground,
                        focusedBorderColor = LibraryColors.OnBackground,
                        unfocusedBorderColor = LibraryColors.Muted.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (stories.tags.isEmpty()) {
                item(key = "empty") { Note(stringResource(R.string.library_tags_empty), onGoToMoments?.let { stringResource(R.string.library_go_to_moments) }, onGoToMoments) }
            }
            items(shown, key = { it.name.lowercase() }) { tag -> TagRow(tag, { onOpenTag(tag.name) }) { menuFor = tag.name } }
            if (tucked.isNotEmpty()) {
                item(key = "h-tucked") {
                    Text(
                        stringResource(R.string.tags_tucked_section),
                        color = LibraryColors.Muted,
                        style = LibraryType.SectionLabel,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp).semantics { heading() },
                    )
                }
                items(tucked, key = { "tucked-" + it.name.lowercase() }) { tag -> TagRow(tag, { onOpenTag(tag.name) }) { menuFor = tag.name } }
            }
        }
        TitleBar(stringResource(R.string.library_tags), onBack, Modifier.align(Alignment.TopCenter))
    }
    menuFor?.let { name ->
        val tag = stories.tags.firstOrNull { it.name.equals(name, ignoreCase = true) }
        if (tag == null) {
            menuFor = null
        } else {
            TagMenuSheet(
                tag,
                onTuck = { on ->
                    menuFor = null
                    onSetTucked(tag.name, on)
                },
                onDelete = {
                    menuFor = null
                    onDelete(tag.name)
                },
                onDismiss = { menuFor = null },
                initialConfirm = initialConfirm,
            )
        }
    }
}

/** A tag in the list: its newest photo (a crossed eye when tucked away), name and count, and ⋯ for its sheet. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TagRow(tag: TagSummary, onOpen: () -> Unit, onMenu: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(role = Role.Button, onClick = onOpen, onLongClick = onMenu)
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TagThumbnail(tag, 56.dp)
        Column(Modifier.weight(1f)) {
            Text(tag.name, color = LibraryColors.OnBackground, style = LibraryType.RowTitle, fontWeight = FontWeight.SemiBold)
            Text(pluralStringResource(R.plurals.library_story_items, tag.count, tag.count), color = LibraryColors.Muted, style = LibraryType.Caption)
        }
        IconButton(onClick = onMenu) {
            Icon(LibraryIcons.More, stringResource(R.string.tag_menu, tag.name), tint = LibraryColors.Muted)
        }
    }
}

/**
 * A tag's sheet: its name and count, then Tuck away this tag (with what that does) or Bring back, and Delete tag (red,
 * confirmed first). No rename or merge.
 */
@Composable
private fun TagMenuSheet(
    tag: TagSummary,
    onTuck: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    initialConfirm: Boolean = false,
) {
    var confirming by remember { mutableStateOf(initialConfirm) }
    if (confirming) {
        ConfirmSheet(
            title = stringResource(R.string.tag_delete_title, tag.name),
            body = pluralStringResource(R.plurals.tag_delete_body, tag.count, tag.count),
            confirmLabel = stringResource(R.string.tag_delete_confirm),
            destructive = true,
            onConfirm = onDelete,
            onDismiss = onDismiss,
        )
        return
    }
    PageSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TagThumbnail(tag, 48.dp)
                Column {
                    Text(tag.name, color = LibraryColors.OnBackground, style = LibraryType.PageTitle, modifier = Modifier.semantics { heading() })
                    Text(pluralStringResource(R.plurals.library_story_items, tag.count, tag.count), color = LibraryColors.Muted, style = LibraryType.Caption)
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onTuck(!tag.tucked) }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Icon(if (tag.tucked) LibraryIcons.Eye else LibraryIcons.EyeOff, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(24.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(if (tag.tucked) R.string.tag_bring_back else R.string.tag_tuck_away),
                        color = LibraryColors.OnBackground,
                        style = LibraryType.RowTitle,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        stringResource(if (tag.tucked) R.string.tag_bring_back_detail else R.string.tag_tuck_away_detail),
                        color = LibraryColors.Muted,
                        style = LibraryType.Caption,
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { confirming = true }
                    .padding(horizontal = 24.dp, vertical = 14.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LibraryIcons.Trash, null, tint = LibraryColors.Error, modifier = Modifier.size(24.dp))
                Text(stringResource(R.string.tag_delete), color = LibraryColors.Error, style = LibraryType.RowTitle, fontWeight = FontWeight.SemiBold)
            }
        }
    }
}

/**
 * The photos with one tag, by day, newest first; tapping opens the viewer on them. A tucked tag's page asks for the
 * phone's lock once per session, and starts with a card: what tucking does, Show everywhere for now (or Hide again)
 * and Bring back.
 */
@Composable
internal fun TagScreen(
    tag: String,
    stories: StoriesState,
    onOpenItem: (MediaItem) -> Unit,
    onBack: () -> Unit,
    /** The photo the viewer above shows: its tile is scrolled into view for the photo to land on when it closes. */
    landing: String? = null,
    /** Delete tag from its ⋯, once confirmed; the page then closes. */
    onDelete: (tag: String) -> Unit = {},
) {
    val tuckedActions = LocalTucked.current
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val tucked = stories.isTagTucked(tag)
    val lock = rememberTuckedLock()
    // A tucked tag's photos show once the phone's lock was passed this session.
    var unlocked by remember(tag) { mutableStateOf(!tucked || !tuckedActions.needsLock()) }
    if (tucked && !unlocked) LaunchedEffect(tag) { lock { unlocked = true } }
    val days = if (!tucked || unlocked) stories.tagged(tag) else emptyList()
    val insets = barsRoom()
    val list = rememberLazyListState()
    val rows = remember(days) { dayGridRows(days) }
    list.LandOn(landing, rows.size) { id -> rows.indexOfFirst { id in it } }
    CompositionLocalProvider(LocalScrollSettled provides rememberDayGridsLoading(list, days)) {
        Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
                verticalArrangement = Arrangement.spacedBy(TILE_GAP),
            ) {
                if (tucked) {
                    item(key = "tucked") {
                        val total = stories.tags.firstOrNull { it.name.equals(tag, ignoreCase = true) }?.count ?: 0
                        TuckedTagCard(
                            tag = stories.tags.firstOrNull { it.name.equals(tag, ignoreCase = true) }?.name ?: tag,
                            count = total,
                            everywhere = stories.tagged.session.showsEverywhere(tag),
                            onShowEverywhere = { on -> if (on) lock { tuckedActions.showEverywhere(tag, true) } else tuckedActions.showEverywhere(tag, false) },
                            onBringBack = { tuckedActions.setTagTucked(tag, false) },
                        )
                    }
                }
                if (tucked && !unlocked) {
                    item(key = "locked") { Note(stringResource(R.string.tag_locked), stringResource(R.string.tag_unlock)) { lock { unlocked = true } } }
                } else {
                    item(key = "count") {
                        val count = days.sumOf { it.items.size }
                        Text(
                            pluralStringResource(R.plurals.library_story_items, count, count),
                            color = LibraryColors.Muted,
                            style = LibraryType.Body,
                            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                        )
                    }
                    if (days.isEmpty()) {
                        item(key = "empty") { Note(stringResource(R.string.library_tag_empty)) }
                    }
                    dayGrids(days, onOpen = onOpenItem)
                }
            }
            TitleBar(tag, onBack, Modifier.align(Alignment.TopCenter), onMenu = { menuOpen = true })
        }
    }
    val summary = stories.tags.firstOrNull { it.name.equals(tag, ignoreCase = true) }
    if (menuOpen && summary != null) {
        TagMenuSheet(
            summary,
            onTuck = { on ->
                menuOpen = false
                tuckedActions.setTagTucked(summary.name, on)
            },
            onDelete = {
                menuOpen = false
                onDelete(summary.name)
                onBack()
            },
            onDismiss = { menuOpen = false },
        )
    }
}

/** On top of a tucked tag's page: crossed eye and "Tucked away", what it means, Show everywhere for now and Bring back. */
@Composable
private fun TuckedTagCard(tag: String, count: Int, everywhere: Boolean, onShowEverywhere: (Boolean) -> Unit, onBringBack: () -> Unit) {
    Column(
        Modifier
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp)
            .fillMaxWidth()
            .background(LibraryColors.Raised, LibraryShapes.Card)
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(LibraryIcons.EyeOff, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(20.dp))
            Text(stringResource(R.string.tucked_away), color = LibraryColors.OnBackground, style = LibraryType.RowTitle, fontWeight = FontWeight.SemiBold)
        }
        Text(pluralStringResource(R.plurals.tag_tucked_card, count, count, tag), color = LibraryColors.Muted, style = LibraryType.Body)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            PrimaryButton(
                stringResource(if (everywhere) R.string.tag_hide_again else R.string.tag_show_everywhere),
                onClick = { onShowEverywhere(!everywhere) },
                icon = if (everywhere) LibraryIcons.EyeOff else LibraryIcons.Eye,
            )
            TextAction(stringResource(R.string.tag_bring_back), onClick = onBringBack)
        }
    }
}

/** A page's title row: back, the title, and an optional [caption] under it. */
@Composable
internal fun TitleBar(
    title: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    /** A ⋯ at the end (a tag's page): its sheet. */
    onMenu: (() -> Unit)? = null,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(LibraryColors.TopFade)
            .statusBarRoom()
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.OnBackground)
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = LibraryColors.OnBackground,
                style = LibraryType.PageTitle,
                modifier = Modifier.semantics { heading() },
            )
            if (caption != null) Text(caption, color = LibraryColors.Muted, style = LibraryType.Caption)
        }
        if (onMenu != null) IconButton(onClick = onMenu) {
            Icon(LibraryIcons.More, stringResource(R.string.tag_menu, title), tint = LibraryColors.OnBackground)
        }
    }
}

/** A muted line in a list: an empty state. */
@Composable
private fun Note(text: String, actionLabel: String? = null, onAction: (() -> Unit)? = null) = EmptyState(text, actionLabel = actionLabel, onAction = onAction)
