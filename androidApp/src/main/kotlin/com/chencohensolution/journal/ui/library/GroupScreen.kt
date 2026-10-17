package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.chencohensolution.journal.stories.StoryView
import com.chencohensolution.journal.stories.GroupView
import com.chencohensolution.journal.library.MediaItem

internal class GroupActions(
    val openItem: (MediaItem) -> Unit = {},
    val rename: (String) -> Unit = {},
    val setText: (String) -> Unit = {},
    val ungroup: () -> Unit = {},
    val back: () -> Unit = {},
)

/** A group: its cover, title and story text, then all its photos. See docs/screens/group.md. */
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
    val insets = WindowInsets.systemBars.asPaddingValues()
    val name = group.title ?: timeRange(group.items)

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = insets.calculateBottomPadding() + 32.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "cover") {
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
            item(key = "title") {
                Column(Modifier.offset(y = (-36).dp).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(story.title.uppercase(), color = LibraryColors.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.7.sp)
                    Text(
                        name,
                        color = Color.White,
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
            item(key = "story") {
                Box(Modifier.offset(y = (-24).dp)) {
                    WritingBlock(group.text, stringResource(R.string.library_writing_group_prompt)) { editing = true }
                }
            }
            items(group.items.chunked(3), key = { row -> row.first().id }) { row ->
                Row(Modifier.fillMaxWidth().aspectRatio(3f), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    row.forEach { item ->
                        Box(Modifier.weight(1f).fillMaxHeight()) {
                            MediaTile(item, onClick = { actions.openItem(item) }, modifier = Modifier.fillMaxSize())
                            if (item.id == group.cover.id) {
                                Text(
                                    stringResource(R.string.library_cover),
                                    color = LibraryColors.Ink,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier
                                        .padding(6.dp)
                                        .background(LibraryColors.Light, RoundedCornerShape(50))
                                        .padding(horizontal = 8.dp, vertical = 3.dp),
                                )
                            }
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }

        Row(
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(LibraryColors.TopFade)
                .statusBarsPadding()
                .padding(start = 4.dp, end = 8.dp, top = 4.dp, bottom = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = actions.back) {
                Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = Color.White)
            }
            Box(Modifier.weight(1f))
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(LibraryIcons.More, stringResource(R.string.library_more), tint = Color.White)
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
            allowBlank = true,
            onConfirm = {
                renaming = false
                actions.rename(it)
            },
            onDismiss = { renaming = false },
        )
    }
}
