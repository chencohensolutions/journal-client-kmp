package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.TimelineEntry
import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.PhotoAccess

private enum class HomeTab { JOURNAL, UNSORTED }

internal class HomeActions(
    val openItem: (DayGroup, MediaItem) -> Unit = { _, _ -> },
    val openStory: (String) -> Unit = {},
    val createStory: (title: String, day: LocalDay) -> Unit = { _, _ -> },
    val openAllPhotos: () -> Unit = {},
    val openTags: () -> Unit = {},
    val allowAccess: () -> Unit = {},
    val changeSelection: () -> Unit = {},
    val registerAgain: () -> Unit = {},
)

/**
 * The home screen: the journal as a timeline, newest first: stories, with the photos in no story between them as
 * "unsorted" days. See docs/screens/journal-home.md.
 */
@Composable
internal fun JournalHomeScreen(library: LibraryState, stories: StoriesState, health: ServerHealth, actions: HomeActions) {
    var tab by rememberSaveable { mutableStateOf(HomeTab.JOURNAL) }
    // The day whose "+ Story" was tapped, as yyyy-mm-dd so it survives rotation.
    var newStoryDay by rememberSaveable { mutableStateOf<String?>(null) }
    val insets = WindowInsets.systemBars.asPaddingValues()

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding() + 72.dp,
                bottom = insets.calculateBottomPadding() + 150.dp,
            ),
        ) {
            if (health == ServerHealth.REJECTED) {
                item(key = "rejected") { RegisterAgainBanner(actions.registerAgain) }
            }
            when (library.access) {
                PhotoAccess.NOT_DETERMINED, PhotoAccess.DENIED -> item(key = "no-access") { NoAccessCard(actions.allowAccess) }
                PhotoAccess.LIMITED -> item(key = "limited") { LimitedBanner(library.itemCount, actions.changeSelection) }
                PhotoAccess.FULL -> Unit
            }
            library.loadError?.let { error ->
                item(key = "error") { Message(stringResource(R.string.library_load_error, error)) }
            }
            val canRead = library.access == PhotoAccess.FULL || library.access == PhotoAccess.LIMITED
            when {
                library.loading && library.days.isEmpty() -> item(key = "loading") {
                    Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = LibraryColors.OnBackground)
                    }
                }
                canRead && library.days.isEmpty() && library.loadError == null ->
                    item(key = "empty") { Message(stringResource(R.string.library_empty)) }
            }
            val entries = if (tab == HomeTab.UNSORTED) {
                stories.timeline.filterIsInstance<TimelineEntry.DayEntry>()
            } else {
                stories.timeline
            }
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is TimelineEntry.StoryEntry -> StoryCard(entry.story, onOpen = { actions.openStory(entry.story.id) })
                    is TimelineEntry.DayEntry -> UnsortedDay(
                        entry.day,
                        onOpen = { actions.openItem(entry.day, it) },
                        onNewStory = { newStoryDay = entry.day.day.toString() },
                    )
                }
            }
        }

        TopBar(health, actions.openAllPhotos, actions.openTags, Modifier.align(Alignment.TopCenter))

        BottomControls(
            summary = library.notBackedUpCount.takeIf { it > 0 }?.let {
                pluralStringResource(R.plurals.library_not_backed_up, it, it)
            },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
        ) {
            SegmentedBar(
                options = listOf(
                    HomeTab.JOURNAL to stringResource(R.string.library_tab_journal),
                    HomeTab.UNSORTED to stringResource(R.string.library_tab_unsorted, stories.unsortedCount),
                ),
                selected = tab,
                onSelect = { tab = it },
            )
        }
    }

    newStoryDay?.let(LocalDay::parse)?.let { day ->
        StoryTitleDialog(
            title = stringResource(R.string.library_new_story),
            confirmLabel = stringResource(R.string.library_create),
            onConfirm = {
                newStoryDay = null
                actions.createStory(it, day)
            },
            onDismiss = { newStoryDay = null },
        )
    }
}

private val TimelineEntry.key: String
    get() = when (this) {
        is TimelineEntry.StoryEntry -> "story-${story.id}"
        is TimelineEntry.DayEntry -> "day-${day.day}"
    }

@Composable
private fun TopBar(health: ServerHealth, onAllPhotos: () -> Unit, onTags: () -> Unit, modifier: Modifier = Modifier) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Row(
        modifier
            .fillMaxWidth()
            .background(LibraryColors.TopFade)
            .statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 24.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.library_title),
            color = LibraryColors.OnBackground,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        if (health == ServerHealth.OFFLINE) OfflinePill()
        IconButton(onClick = onTags) {
            Icon(LibraryIcons.Tag, stringResource(R.string.library_tags), tint = LibraryColors.OnBackground)
        }
        Box {
            IconButton(onClick = { menuOpen = true }) {
                Icon(LibraryIcons.More, stringResource(R.string.library_more), tint = LibraryColors.OnBackground)
            }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_all_photos)) },
                    leadingIcon = { Icon(LibraryIcons.Grid, null) },
                    onClick = {
                        menuOpen = false
                        onAllPhotos()
                    },
                )
            }
        }
    }
}

/** One day of photos that are in no story: a heading, "+ Story", and a row of small photos. */
@Composable
private fun UnsortedDay(day: DayGroup, onOpen: (MediaItem) -> Unit, onNewStory: () -> Unit) {
    Column(Modifier.padding(top = 14.dp, bottom = 16.dp)) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    dayLabel(day.day),
                    color = LibraryColors.OnBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    pluralStringResource(R.plurals.library_unsorted_count, day.items.size, day.items.size),
                    color = LibraryColors.Muted,
                    fontSize = 13.sp,
                )
            }
            TextButton(
                onClick = onNewStory,
                shape = RoundedCornerShape(50),
                modifier = Modifier.height(36.dp).border(1.dp, LibraryColors.OnBackground.copy(alpha = 0.28f), RoundedCornerShape(50)),
            ) {
                Icon(LibraryIcons.Plus, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(14.dp))
                Text(
                    stringResource(R.string.library_story),
                    color = LibraryColors.OnBackground,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(day.items, key = { it.id }) { item ->
                MediaTile(
                    item,
                    onClick = { onOpen(item) },
                    badgeSize = 18.dp,
                    modifier = Modifier.size(76.dp).clip(RoundedCornerShape(4.dp)),
                )
            }
        }
    }
}

/** The server refused this device (401) or there is no identity: offer to register again. */
@Composable
private fun RegisterAgainBanner(onRegister: () -> Unit) {
    Card {
        Text(stringResource(R.string.library_rejected_title), color = LibraryColors.OnInverse, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.library_rejected_body), color = LibraryColors.OnInverseMuted, fontSize = 14.sp)
        Button(
            onClick = onRegister,
            colors = ButtonDefaults.buttonColors(containerColor = LibraryColors.Error, contentColor = Color.White),
        ) {
            Text(stringResource(R.string.library_rejected_action))
        }
    }
}

@Composable
private fun NoAccessCard(onAllow: () -> Unit) {
    Card {
        Text(stringResource(R.string.library_no_access_title), color = LibraryColors.OnInverse, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.library_no_access_body), color = LibraryColors.OnInverseMuted, fontSize = 14.sp)
        Button(
            onClick = onAllow,
            colors = ButtonDefaults.buttonColors(containerColor = LibraryColors.Accent, contentColor = Color.White),
        ) {
            Text(stringResource(R.string.library_access_allow))
        }
    }
}

@Composable
private fun LimitedBanner(count: Int, onChange: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(LibraryColors.Raised, RoundedCornerShape(14.dp))
            .padding(start = 14.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            pluralStringResource(R.plurals.library_limited, count, count),
            color = LibraryColors.OnBackground,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onChange) {
            Text(stringResource(R.string.library_limited_change), color = LibraryColors.Warning, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .fillMaxWidth()
            .background(LibraryColors.Inverse, RoundedCornerShape(16.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        content()
        Spacer(Modifier.size(2.dp))
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text,
        color = LibraryColors.Muted,
        fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 32.dp),
    )
}
