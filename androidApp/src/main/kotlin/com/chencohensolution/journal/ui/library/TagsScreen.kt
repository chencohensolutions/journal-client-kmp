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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.chencohensolution.journal.library.MediaItem

/** Every tag, most recently used first, with a search field. See docs/screens/tags.md. */
@Composable
internal fun TagsScreen(stories: StoriesState, onOpenTag: (String) -> Unit, onBack: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    val tags = if (query.isBlank()) stories.tags else stories.searchTags(query)
    val insets = WindowInsets.systemBars.asPaddingValues()

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(contentPadding = PaddingValues(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp)) {
            item(key = "search") {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = { Text(stringResource(R.string.library_find_tag)) },
                    leadingIcon = { Icon(LibraryIcons.Search, null, tint = LibraryColors.Muted) },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
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
                item(key = "empty") { Note(stringResource(R.string.library_tags_empty)) }
            }
            items(tags, key = { it.name.lowercase() }) { tag ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable(role = Role.Button) { onOpenTag(tag.name) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TagThumbnail(tag, 56.dp)
                    Column(Modifier.weight(1f)) {
                        Text(tag.name, color = LibraryColors.OnBackground, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(pluralStringResource(R.plurals.library_story_items, tag.count, tag.count), color = LibraryColors.Muted, fontSize = 13.sp)
                    }
                }
            }
        }
        TitleBar(stringResource(R.string.library_tags), onBack, Modifier.align(Alignment.TopCenter))
    }
}

/** The photos with one tag, by day, newest first; tapping opens the viewer on them. */
@Composable
internal fun TagScreen(tag: String, stories: StoriesState, onOpenItem: (MediaItem) -> Unit, onBack: () -> Unit) {
    val days = stories.tagged(tag)
    val insets = WindowInsets.systemBars.asPaddingValues()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(
            contentPadding = PaddingValues(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item(key = "count") {
                val count = days.sumOf { it.items.size }
                Text(
                    pluralStringResource(R.plurals.library_story_items, count, count),
                    color = LibraryColors.Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
                )
            }
            if (days.isEmpty()) {
                item(key = "empty") { Note(stringResource(R.string.library_tag_empty)) }
            }
            dayGrids(days, onOpen = onOpenItem)
        }
        TitleBar(tag, onBack, Modifier.align(Alignment.TopCenter))
    }
}

@Composable
internal fun TitleBar(title: String, onBack: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(LibraryColors.TopFade)
            .statusBarsPadding()
            .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.OnBackground)
        }
        Text(
            title,
            color = LibraryColors.OnBackground,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.semantics { heading() },
        )
    }
}
