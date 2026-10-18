package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import com.chencohensolution.journal.stories.StoryView
import com.chencohensolution.journal.stories.TagSummary

/**
 * Adds photos to stories or takes them out, without listing every story: before typing it suggests stories with
 * photos from the same days, nearby ones and recent ones; typing searches all of them, with "Create" first.
 */
@Composable
internal fun AddToStorySheet(
    itemIds: List<String>,
    stories: StoriesState,
    onToggle: (storyId: String, add: Boolean) -> Unit,
    onCreate: (title: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val contains = { story: StoryView -> itemIds.all { id -> story.story.members.any { it.deviceId == id } } }
    PickerSheet(
        title = stringResource(R.string.library_add_to_story),
        hint = stringResource(R.string.library_search_stories),
        createLabel = { stringResource(R.string.library_create_story, it) },
        createDetail = stringResource(R.string.library_create_story_detail),
        onCreate = onCreate,
        onDismiss = onDismiss,
    ) { query ->
        val storyRow: LazyListScope.(List<StoryView>) -> Unit = { list ->
            items(list, key = { "story-${it.id}" }) { story ->
                val isMember = contains(story)
                PickerRow(
                    title = storyName(story),
                    detail = storySummary(story),
                    leading = { story.firstDay?.let { DateBlock(it) } },
                    checked = isMember,
                    onClick = { onToggle(story.id, !isMember) },
                )
            }
        }
        if (query.isBlank()) {
            val suggestions = stories.storySuggestions(itemIds)
            if (suggestions.onTheseDays.isNotEmpty()) {
                item(key = "h-days") { SectionLabel(stringResource(R.string.library_picker_on_these_days)) }
                storyRow(suggestions.onTheseDays)
            }
            if (suggestions.nearby.isNotEmpty()) {
                item(key = "h-nearby") { SectionLabel(stringResource(R.string.library_picker_nearby)) }
                storyRow(suggestions.nearby)
            }
            if (suggestions.recent.isNotEmpty()) {
                item(key = "h-recent") { SectionLabel(stringResource(R.string.library_picker_recent)) }
                storyRow(suggestions.recent)
            }
            item(key = "count") {
                Text(
                    pluralStringResource(R.plurals.library_picker_search_all, stories.stories.size, stories.stories.size),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        } else {
            val results = stories.searchStories(query, itemIds)
            item(key = "h-results") {
                SectionLabel(pluralStringResource(R.plurals.library_picker_story_results, results.size, results.size))
            }
            storyRow(results)
        }
    }
}

/** Tags photos: recent tags before typing, every matching tag while typing, with "Create" for a new one. */
@Composable
internal fun TagSheet(
    itemIds: List<String>,
    stories: StoriesState,
    onToggle: (tag: String, add: Boolean) -> Unit,
    onCreate: (tag: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val applied = { tag: TagSummary ->
        itemIds.all { id -> stories.note(id)?.tags?.any { it.equals(tag.name, ignoreCase = true) } == true }
    }
    PickerSheet(
        title = stringResource(R.string.library_add_tag),
        hint = stringResource(R.string.library_search_tags),
        createLabel = { stringResource(R.string.library_create_tag, it) },
        createDetail = null,
        hideCreateFor = { query -> stories.tags.any { it.name.equals(query.trim(), ignoreCase = true) } },
        onCreate = onCreate,
        onDismiss = onDismiss,
    ) { query ->
        val list = if (query.isBlank()) stories.tags else stories.searchTags(query)
        if (list.isNotEmpty()) {
            item(key = "h-tags") {
                SectionLabel(if (query.isBlank()) stringResource(R.string.library_picker_recent_tags) else stringResource(R.string.library_picker_tags))
            }
        }
        items(list, key = { "tag-${it.name.lowercase()}" }) { tag ->
            val isApplied = applied(tag)
            PickerRow(
                title = tag.name,
                detail = pluralStringResource(R.plurals.library_story_items, tag.count, tag.count),
                leading = { TagThumbnail(tag) },
                checked = isApplied,
                onClick = { onToggle(tag.name, !isApplied) },
            )
        }
        if (query.isBlank() && list.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.library_tags_empty_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp),
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickerSheet(
    title: String,
    hint: String,
    createLabel: @Composable (String) -> String,
    createDetail: String?,
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit,
    hideCreateFor: (String) -> Boolean = { false },
    content: LazyListScope.(query: String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    // Ready to type: most of the time the answer is a search or a new name.
    LaunchedEffect(Unit) { focus.requestFocus() }
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Text(
                title,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 12.dp).semantics { heading() },
            )
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(hint) },
                leadingIcon = { Icon(LibraryIcons.Search, null) },
                singleLine = true,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            )
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                if (query.isNotBlank() && !hideCreateFor(query)) {
                    item(key = "create") {
                        Row(
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .fillMaxWidth()
                                .background(LibraryColors.Accent.copy(alpha = 0.12f), RoundedCornerShape(12.dp))
                                .clickable(role = Role.Button) { onCreate(query.trim()) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(44.dp).background(LibraryColors.Accent, RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                Icon(LibraryIcons.Plus, null, tint = Color.White)
                            }
                            Column {
                                Text(createLabel(query.trim()), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                                createDetail?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            }
                        }
                    }
                }
                content(query)
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun PickerRow(title: String, detail: String?, leading: @Composable () -> Unit, checked: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Checkbox, onClick = onClick)
            .padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) { leading() }
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            if (!detail.isNullOrEmpty()) Text(detail, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (checked) Icon(LibraryIcons.Check, stringResource(R.string.library_picker_checked), tint = LibraryColors.Accent)
    }
}

/** The newest photo with the tag, or a tag icon. */
@Composable
internal fun TagThumbnail(tag: TagSummary, size: androidx.compose.ui.unit.Dp = 44.dp) {
    val thumbnail = tag.latest?.let { rememberThumbnail(it, THUMB_SMALL) }
    Box(
        Modifier.size(size).clip(RoundedCornerShape(10.dp)).background(LibraryColors.Accent.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(LibraryIcons.Tag, null, tint = LibraryColors.Accent, modifier = Modifier.size(20.dp))
        }
    }
}
