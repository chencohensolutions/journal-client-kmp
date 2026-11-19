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
import com.chencohensolution.journal.stories.TagSummary

/**
 * Tags photos: recent tags before typing, every matching tag while typing, with "Create" for a new one. For several
 * photos (Nov 19, 2026): "Tag 4 photos", each tag with a check (all have it), a dash and "2 of 4" (some) or nothing;
 * tapping gives it to all, or takes it off all once all have it. Tucked tags say so. Done closes.
 */
@Composable
internal fun TagSheet(
    itemIds: List<String>,
    stories: StoriesState,
    onToggle: (tag: String, add: Boolean) -> Unit,
    onCreate: (tag: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val coverage = stories.tagCounts(itemIds).associateBy { it.tag.lowercase() }
    val several = itemIds.size > 1
    PickerSheet(
        title = if (several) pluralStringResource(R.plurals.tag_photos_title, itemIds.size, itemIds.size) else stringResource(R.string.library_add_tag),
        hint = stringResource(R.string.library_search_tags),
        createLabel = { stringResource(R.string.library_create_tag, it) },
        createDetail = null,
        hideCreateFor = { query -> stories.tags.any { it.name.equals(query.trim(), ignoreCase = true) } },
        onCreate = onCreate,
        onDismiss = onDismiss,
        done = onDismiss,
    ) { query ->
        val list = if (query.isBlank()) stories.tags else stories.searchTags(query)
        if (list.isNotEmpty()) {
            item(key = "h-tags") {
                SectionLabel(if (query.isBlank()) stringResource(R.string.library_picker_recent_tags) else stringResource(R.string.library_picker_tags))
            }
        }
        items(list, key = { "tag-${it.name.lowercase()}" }) { tag ->
            val covered = coverage[tag.name.lowercase()]
            val all = covered?.all == true
            val some = several && covered?.some == true
            val count = pluralStringResource(R.plurals.library_story_items, tag.count, tag.count)
            PickerRow(
                title = tag.name,
                detail = if (tag.tucked) "$count · ${stringResource(R.string.tag_tucked_detail)}" else count,
                leading = { TagThumbnail(tag) },
                checked = all,
                partly = if (some) stringResource(R.string.tag_some_of, covered!!.count, covered.of) else null,
                tucked = tag.tucked,
                onClick = { onToggle(tag.name, !all) },
            )
        }
        if (query.isBlank() && list.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.library_tags_empty_hint),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = LibraryType.Body,
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
    /** A Done at the title's end, closing the sheet; null: none. */
    done: (() -> Unit)? = null,
    content: LazyListScope.(query: String) -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    // Ready to type: most of the time the answer is a search or a new name.
    LaunchedEffect(Unit) { focus.requestFocus() }
    PageSheet(onDismiss) {
        Column(Modifier.fillMaxSize().imePadding()) {
            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = LibraryType.PageTitle,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                done?.let { TextAction(stringResource(R.string.tag_sheet_done), onClick = it) }
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text(hint) },
                leadingIcon = { Icon(LibraryIcons.Search, null) },
                singleLine = true,
                shape = LibraryShapes.Tile,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).focusRequester(focus),
            )
            LazyColumn(Modifier.weight(1f).padding(top = 8.dp)) {
                if (query.isNotBlank() && !hideCreateFor(query)) {
                    item(key = "create") {
                        Row(
                            Modifier
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .fillMaxWidth()
                                .background(LibraryColors.OnBackground.copy(alpha = 0.06f), LibraryShapes.Tile)
                                .clickable(role = Role.Button) { onCreate(query.trim()) }
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Box(Modifier.size(44.dp).background(LibraryColors.Inverse, LibraryShapes.Tile), contentAlignment = Alignment.Center) {
                                Icon(LibraryIcons.Plus, null, tint = LibraryColors.OnInverse)
                            }
                            Column {
                                Text(createLabel(query.trim()), style = LibraryType.RowTitle, fontWeight = FontWeight.Bold)
                                createDetail?.let { Text(it, style = LibraryType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant) }
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
        style = LibraryType.Small,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.8.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 16.dp, bottom = 6.dp),
    )
}

@Composable
private fun PickerRow(
    title: String,
    detail: String?,
    leading: @Composable () -> Unit,
    checked: Boolean,
    onClick: () -> Unit,
    /** Some of the photos have it: a dash and this ("2 of 4"). */
    partly: String? = null,
    /** A tucked tag: a crossed eye before its detail. */
    tucked: Boolean = false,
) {
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
            Text(title, style = LibraryType.RowTitle, fontWeight = FontWeight.SemiBold)
            if (!detail.isNullOrEmpty()) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (tucked) Icon(LibraryIcons.EyeOff, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                Text(detail, style = LibraryType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        when {
            checked -> Icon(LibraryIcons.Check, stringResource(R.string.library_picker_checked), tint = LibraryColors.Accent)
            partly != null -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(partly, style = LibraryType.Caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(LibraryIcons.Minus, null, tint = LibraryColors.Accent)
            }
        }
    }
}

/** The newest photo with the tag, or a tag icon; a tucked tag shows a crossed eye instead of its photo. */
@Composable
internal fun TagThumbnail(tag: TagSummary, size: androidx.compose.ui.unit.Dp = 44.dp) {
    if (tag.tucked) {
        Box(Modifier.size(size).clip(LibraryShapes.Tile).background(LibraryColors.Raised), contentAlignment = Alignment.Center) {
            Icon(LibraryIcons.EyeOff, stringResource(R.string.tucked_away), tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
        }
        return
    }
    val thumbnail = tag.latest?.let { rememberThumbnail(it, THUMB_SMALL) }
    Box(
        Modifier.size(size).clip(LibraryShapes.Tile).background(LibraryColors.Accent.copy(alpha = 0.15f)),
        contentAlignment = Alignment.Center,
    ) {
        if (thumbnail != null) {
            Image(thumbnail, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(LibraryIcons.Tag, null, tint = LibraryColors.Accent, modifier = Modifier.size(20.dp))
        }
    }
}
