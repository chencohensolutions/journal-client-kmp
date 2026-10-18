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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.chencohensolution.journal.stories.Moment
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.TimelineEntry
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.GroupingMode
import java.time.Instant
import java.time.ZoneId

internal enum class HomeTab { JOURNAL, UNSORTED }

internal class HomeActions(
    val openItem: (Moment, MediaItem) -> Unit = { _, _ -> },
    val openStory: (String) -> Unit = {},
    val createStory: (title: String, momentKey: String) -> Unit = { _, _ -> },
    val openAllPhotos: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val openTags: () -> Unit = {},
    val allowAccess: () -> Unit = {},
    val changeSelection: () -> Unit = {},
    val registerAgain: () -> Unit = {},
)

/**
 * The home screen: the journal's stories as a timeline, newest first, and in the Unsorted tab the photos in no story
 * as moments (a day, or a run of photos; see the grouping setting). See docs/screens/journal-home.md.
 */
@Composable
internal fun JournalHomeScreen(
    library: LibraryState,
    stories: StoriesState,
    health: ServerHealth,
    actions: HomeActions,
    /** The tab shown first; previews use it to show Unsorted. */
    initialTab: HomeTab = HomeTab.JOURNAL,
    /** The photo the viewer above shows: its tile is scrolled into view for the photo to land on when it closes. */
    landing: String? = null,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // The key of the moment whose "+ Story" was tapped.
    var newStoryMoment by rememberSaveable { mutableStateOf<String?>(null) }
    val insets = WindowInsets.systemBars.asPaddingValues()

    // Journal shows only stories; photos in no story are in the Unsorted tab.
    val entries = if (tab == HomeTab.UNSORTED) {
        stories.timeline.filterIsInstance<TimelineEntry.MomentEntry>()
    } else {
        stories.timeline.filterIsInstance<TimelineEntry.StoryEntry>()
    }
    val list = rememberLazyListState()
    list.LandOn(landing, entries.size) { id ->
        entries.indexOfFirst { it is TimelineEntry.MomentEntry && it.moment.items.any { item -> item.id == id } }
    }

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(
            state = list,
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
            if (tab == HomeTab.JOURNAL && entries.isEmpty() && library.days.isNotEmpty()) {
                item(key = "no-stories") { Message(stringResource(R.string.library_no_stories)) }
            }
            items(entries, key = { it.key }) { entry ->
                when (entry) {
                    is TimelineEntry.StoryEntry -> StoryCard(
                        cardState(entry.story),
                        onOpen = { actions.openStory(entry.story.id) },
                        // The card grows into the story's page.
                        modifier = Modifier.sharedContainer(SharedKey.story(entry.story.id)),
                    )
                    is TimelineEntry.MomentEntry -> UnsortedMoment(
                        entry.moment,
                        onOpen = { actions.openItem(entry.moment, it) },
                        onNewStory = { newStoryMoment = entry.moment.key },
                        landing = landing,
                    )
                }
            }
        }

        TopBar(health, actions, Modifier.align(Alignment.TopCenter))

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

    newStoryMoment?.let { key ->
        StoryTitleDialog(
            title = stringResource(R.string.library_new_story),
            confirmLabel = stringResource(R.string.library_create),
            onConfirm = {
                newStoryMoment = null
                actions.createStory(it, key)
            },
            onDismiss = { newStoryMoment = null },
        )
    }
}

private val TimelineEntry.key: String
    get() = when (this) {
        is TimelineEntry.StoryEntry -> "story-${story.id}"
        is TimelineEntry.MomentEntry -> "moment-${moment.key}"
    }

@Composable
private fun TopBar(health: ServerHealth, actions: HomeActions, modifier: Modifier = Modifier) {
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
        IconButton(onClick = actions.openTags) {
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
                        actions.openAllPhotos()
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.library_settings)) },
                    leadingIcon = { Icon(LibraryIcons.Settings, null) },
                    onClick = {
                        menuOpen = false
                        actions.openSettings()
                    },
                )
            }
        }
    }
}

/**
 * "Today", or by breaks between photos "Today · 9:14 – 21:40" and "Sat, Oct 4 – Mon, Oct 6" for one that spans days.
 */
@Composable
private fun momentLabel(moment: Moment): String {
    val day = dayLabel(moment.newestDay)
    if (moment.mode == GroupingMode.BY_DAY) return day
    if (moment.oldestDay != moment.newestDay) return stringResource(R.string.library_moment_days, dayLabel(moment.oldestDay), day)
    val start = timeLabel(moment.startMillis)
    val end = timeLabel(moment.endMillis)
    return if (start == end) stringResource(R.string.library_moment_time, day, start) else stringResource(R.string.library_moment_times, day, start, end)
}

/** "9:14" or "9:14 AM", as the locale writes it. */
private fun timeLabel(millis: Long): String = formatDate(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()), "jmm")

/** Photos that are in no story, taken on one day or close together: a heading, "+ Story", and a row of small photos. */
@Composable
private fun UnsortedMoment(moment: Moment, onOpen: (MediaItem) -> Unit, onNewStory: () -> Unit, landing: String?) {
    val strip = rememberLazyListState()
    strip.LandOn(landing, moment.items.size) { id -> moment.items.indexOfFirst { it.id == id } }
    Column(Modifier.padding(top = 14.dp, bottom = 16.dp)) {
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    momentLabel(moment),
                    color = LibraryColors.OnBackground,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.semantics { heading() },
                )
                Text(
                    pluralStringResource(R.plurals.library_unsorted_count, moment.items.size, moment.items.size),
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
            state = strip,
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(moment.items, key = { it.id }) { item ->
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
