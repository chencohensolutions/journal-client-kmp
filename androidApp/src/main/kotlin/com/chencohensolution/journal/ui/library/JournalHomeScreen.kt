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
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.stories.FeedTrip
import com.chencohensolution.journal.stories.Moment
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.TimelineEntry
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.settings.GroupingMode
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import java.time.ZoneId

internal enum class HomeTab { JOURNAL, UNSORTED }

internal class HomeActions(
    /** A photo of the Unsorted tab; the viewer swipes through its row. */
    val openItem: (FeedSection, MediaItem) -> Unit = { _, _ -> },
    val openStory: (String) -> Unit = {},
    /** "+ Story" on a row of the Unsorted tab, or "Save as story" on a trip ([FeedSection.key] or [FeedTrip.key]). */
    val createStory: (title: String, feedKey: String) -> Unit = { _, _ -> },
    val toggleSource: (MediaSource) -> Unit = {},
    val dismissTrip: (key: String) -> Unit = {},
    val openAllPhotos: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val openTags: () -> Unit = {},
    val allowAccess: () -> Unit = {},
    val changeSelection: () -> Unit = {},
    val registerAgain: () -> Unit = {},
)

/**
 * The home screen: the journal's stories as a timeline, newest first, and in the Unsorted tab the photos in no story
 * as a feed that groups itself (by day or run of photos, then time, place, source and looks). See
 * docs/screens/journal-home.md and docs/screens/unsorted.md.
 */
@OptIn(ExperimentalFoundationApi::class)
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
    // The row or trip whose "+ Story" or "Save as story" was tapped, and the title to start with.
    var newStoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    var newStoryTitle by rememberSaveable { mutableStateOf("") }
    val insets = WindowInsets.systemBars.asPaddingValues()

    // Journal shows only stories; photos in no story are in the Unsorted tab, as the feed.
    val unsorted = tab == HomeTab.UNSORTED
    val entries = if (unsorted) emptyList() else stories.timeline.filterIsInstance<TimelineEntry.StoryEntry>()
    // Composes items about two screens ahead in spare time between frames, not in the frame they scroll into view.
    val list = rememberLazyListState(cacheWindow = remember { LazyLayoutCacheWindow(ahead = 1200.dp, behind = 400.dp) })
    // A feed that changes mid-scroll (fingerprints and places keep arriving while a library is read) is shown once the
    // scroll stops, and its list items are worked out off the main thread.
    var shownFeed by remember { mutableStateOf(stories.feed) }
    LaunchedEffect(stories.feed) {
        snapshotFlow { list.isScrollInProgress }.first { !it }
        shownFeed = stories.feed
    }
    val feedRows by produceState(remember { shownFeed.rows() }, shownFeed) {
        value = withContext(Dispatchers.Default) { shownFeed.rows() }
    }
    list.LandOn(landing, feedRows.size) { id -> if (unsorted) feedRows.rowOf(id) else -1 }
    // One video plays at a time, the one most in view, and only while the app is in front (not in previews).
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val canPlay = unsorted && resumed.isAtLeast(Lifecycle.State.RESUMED) && !LocalInspectionMode.current
    // Watched in a coroutine, not read here: a change redraws only the videos, not the whole screen (which took 20 ms).
    val playingId = remember { mutableStateOf<String?>(null) }
    val currentRows by rememberUpdatedState(feedRows)
    LaunchedEffect(list, canPlay) {
        if (!canPlay) {
            playingId.value = null
            return@LaunchedEffect
        }
        snapshotFlow { list.videoToPlay(currentRows) }.collectLatest { inView ->
            // Stops at once; starts only once the scroll has settled on it.
            if (inView != null) delay(VIDEO_SETTLE_MILLIS)
            playingId.value = inView
        }
    }
    val muted = rememberSaveable { mutableStateOf(true) }
    val video = remember { FeedVideo(playingId = { playingId.value }, isMuted = { muted.value }, toggleMute = { muted.value = !muted.value }) }
    // The same actions object across recompositions, so the feed's items don't all recompose with the screen.
    val currentActions by rememberUpdatedState(actions)
    val feedActions = remember {
        FeedActions(
            openItem = { section, item -> currentActions.openItem(section, item) },
            newStory = { key ->
                newStoryTitle = ""
                newStoryKey = key
            },
            saveTrip = { trip ->
                newStoryTitle = trip.place.orEmpty()
                newStoryKey = trip.key
            },
            dismissTrip = { currentActions.dismissTrip(it) },
        )
    }

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(
            state = list,
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding() + if (unsorted) 118.dp else 72.dp,
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
            if (unsorted && stories.feed.entries.isEmpty() && stories.unsortedCount > 0) {
                item(key = "nothing-shown") { Message(stringResource(R.string.feed_nothing_shown)) }
            }
            items(entries, key = { "story-${it.story.id}" }) { entry ->
                StoryCard(
                    cardState(entry.story),
                    onOpen = { actions.openStory(entry.story.id) },
                    // The card grows into the story's page.
                    modifier = Modifier.sharedContainer(SharedKey.story(entry.story.id)),
                )
            }
            // Last, so landing from the viewer can count them from the end.
            if (unsorted) feedItems(feedRows, feedActions, landing, video)
        }

        TopBar(health, actions, Modifier.align(Alignment.TopCenter)) {
            if (unsorted) FeedSourceBar(stories.feed, actions.toggleSource, Modifier.padding(top = 4.dp))
        }

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

    newStoryKey?.let { key ->
        StoryTitleDialog(
            title = stringResource(R.string.library_new_story),
            initial = newStoryTitle,
            confirmLabel = stringResource(R.string.library_create),
            onConfirm = {
                newStoryKey = null
                actions.createStory(it, key)
            },
            onDismiss = { newStoryKey = null },
        )
    }
}

@Composable
private fun TopBar(health: ServerHealth, actions: HomeActions, modifier: Modifier = Modifier, below: @Composable () -> Unit = {}) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    Column(modifier.fillMaxWidth().background(LibraryColors.TopFade).statusBarsPadding().padding(top = 8.dp, bottom = 24.dp)) {
        Row(
            Modifier.padding(start = 16.dp, end = 8.dp),
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
        below()
    }
}

/**
 * "Today", or by breaks between photos "Today · 9:14 – 21:40" and "Sat, Oct 4 – Mon, Oct 6" for one that spans days.
 */
@Composable
internal fun momentLabel(moment: Moment): String {
    val day = dayLabel(moment.newestDay)
    if (moment.mode == GroupingMode.BY_DAY) return day
    if (moment.oldestDay != moment.newestDay) return stringResource(R.string.library_moment_days, dayLabel(moment.oldestDay), day)
    val start = timeLabel(moment.startMillis)
    val end = timeLabel(moment.endMillis)
    return if (start == end) stringResource(R.string.library_moment_time, day, start) else stringResource(R.string.library_moment_times, day, start, end)
}

/** "9:14" or "9:14 AM", as the locale writes it. */
internal fun timeLabel(millis: Long): String = formatDate(Instant.ofEpochMilli(millis).atZone(ZoneId.systemDefault()), "jmm")

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
