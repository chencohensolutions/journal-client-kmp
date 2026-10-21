package com.chencohensolution.journal.ui.library

import com.chencohensolution.journal.backup.BackupProgress
import androidx.activity.compose.BackHandler
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
import com.chencohensolution.journal.stories.FeedBlock
import com.chencohensolution.journal.stories.FeedReveal
import com.chencohensolution.journal.stories.RevealedPhotos
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

internal enum class HomeTab { STORIES, MOMENTS, EXPLORE }

internal class HomeActions(
    /** A photo of the Moments tab; the viewer swipes through its row. */
    val openItem: (FeedSection, MediaItem) -> Unit = { _, _ -> },
    val openStory: (String) -> Unit = {},
    /** "+ Story" on a row of the Moments tab, or "Save as story" on a trip ([FeedSection.key] or [FeedTrip.key]). */
    val createStory: (title: String, feedKey: String) -> Unit = { _, _ -> },
    val toggleSource: (MediaSource) -> Unit = {},
    val dismissTrip: (key: String) -> Unit = {},
    val openAllPhotos: () -> Unit = {},
    val openSettings: () -> Unit = {},
    val openTags: () -> Unit = {},
    val allowAccess: () -> Unit = {},
    val changeSelection: () -> Unit = {},
    val registerAgain: () -> Unit = {},
    /** Moments: the photos of the sets and photos picked become one set; a photo leaves its set. */
    val merge: (itemIds: List<String>) -> Unit = {},
    val takeOutOfSet: (itemId: String) -> Unit = {},
    /** A swipe set (its photos, in order) was turned to a photo: it opens there next time. */
    val turnSet: (itemIds: List<String>, itemId: String) -> Unit = { _, _ -> },
    /** From a photo's long-press menu in Moments, by item id. */
    val setCaption: (itemId: String, text: String) -> Unit = { _, _ -> },
    val crop: (itemId: String) -> Unit = {},
    /** From a photo's menu, or a band's "Add to a story" with all its photos. */
    val toggleStory: (storyId: String, itemIds: List<String>, add: Boolean) -> Unit = { _, _, _ -> },
    val createStoryWith: (title: String, itemIds: List<String>) -> Unit = { _, _ -> },
    val toggleTag: (itemId: String, tag: String, add: Boolean) -> Unit = { _, _, _ -> },
    /** The Explore tab's towns, years and map. */
    val explore: ExploreActions = ExploreActions(),
    /** A hidden source's photos shown on one row of Moments ([FeedSection.key]), or hidden again; nothing is saved. */
    val showOnRow: (sectionKey: String, MediaSource) -> Unit = { _, _ -> },
    val hideOnRow: (sectionKey: String, MediaSource) -> Unit = { _, _ -> },
    /** A band's "Always show this day": the setting keeps showing [MediaSource] on the row's days. */
    val alwaysShow: (FeedSection, MediaSource) -> Unit = { _, _ -> },
    /** A photo of a band; the viewer swipes through the band. */
    val openRevealed: (FeedSection, MediaSource, MediaItem) -> Unit = { _, _, _ -> },
)

/**
 * The home screen: in the Stories tab the journal's stories as a timeline, newest first, and in the Moments tab every
 * photo as a feed that groups itself (by day or run of photos, then time, place, source and looks). See
 * docs/screens/journal-home.md and docs/screens/moments.md.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun JournalHomeScreen(
    library: LibraryState,
    stories: StoriesState,
    health: ServerHealth,
    actions: HomeActions,
    /** The tab shown first; previews use it to show Moments. */
    initialTab: HomeTab = HomeTab.STORIES,
    /** The photo the viewer above shows: its tile is scrolled into view for the photo to land on when it closes. */
    landing: String? = null,
    /** The backup run in progress, for the bottom pill. */
    backupRunning: BackupProgress? = null,
    /** The photo whose long-press menu is open at first; previews use it. */
    initialMenuItem: String? = null,
    /** Photos and sets picked at first (by first photo id); previews use it. */
    initialSelection: Set<String> = emptySet(),
    /** The band whose "Showing 6 from WhatsApp" note shows at first; previews use it. */
    initialReveal: FeedReveal? = null,
    /** Places or Years at first in the Explore tab; previews use it. */
    initialExploreView: ExploreView = ExploreView.PLACES,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    var exploreView by rememberSaveable { mutableStateOf(initialExploreView) }
    // The row or trip whose "+ Story" or "Save as story" was tapped, and the title to start with.
    var newStoryKey by rememberSaveable { mutableStateOf<String?>(null) }
    var newStoryTitle by rememberSaveable { mutableStateOf("") }
    // The photo whose long-press menu is open, and the one being written about, added to a story or tagged from it.
    var menuItem by rememberSaveable { mutableStateOf(initialMenuItem) }
    var writingFor by rememberSaveable { mutableStateOf<String?>(null) }
    var storyFor by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var tagFor by rememberSaveable { mutableStateOf<String?>(null) }
    // Photos and sets picked to merge; back stops picking.
    val selection = remember { FeedSelection().apply { initialSelection.forEach(::toggle) } }
    BackHandler(enabled = selection.active) { selection.clear() }
    // The band just shown, for its note above the tabs: "Showing 6 from WhatsApp on Oct 4 · Hide".
    var reveal by remember { mutableStateOf(initialReveal) }
    val inPreview = LocalInspectionMode.current
    LaunchedEffect(reveal) {
        if (reveal != null && !inPreview) {
            delay(REVEAL_NOTE_MILLIS)
            reveal = null
        }
    }
    val insets = WindowInsets.systemBars.asPaddingValues()

    // Stories shows only stories; every photo is in the Moments tab, as the feed.
    val moments = tab == HomeTab.MOMENTS
    val entries = if (moments) emptyList() else stories.timeline.filterIsInstance<TimelineEntry.StoryEntry>()
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
    list.LandOn(landing, feedRows.size) { id -> if (moments) feedRows.rowOf(id) else -1 }
    // One video plays at a time, the one most in view, and only while the app is in front (not in previews).
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val canPlay = moments && resumed.isAtLeast(Lifecycle.State.RESUMED) && !LocalInspectionMode.current
    // Watched in a coroutine, not read here: a change redraws only the videos, not the whole screen (which took 20 ms).
    val playingId = remember { mutableStateOf<String?>(null) }
    val currentRows by rememberUpdatedState(feedRows)
    val muted = rememberSaveable { mutableStateOf(true) }
    // The same actions object across recompositions, so the feed's items don't all recompose with the screen.
    val currentActions by rememberUpdatedState(actions)
    val currentNotes by rememberUpdatedState(stories.notes)
    val video = remember {
        FeedVideo(
            playingId = { playingId.value },
            isMuted = { muted.value },
            toggleMute = { muted.value = !muted.value },
            savedPage = { block -> block.items.firstOrNull { currentNotes[it.id]?.front == true }?.id },
            savePage = { block, id -> currentActions.turnSet(block.items.map { it.id }, id) },
        )
    }
    LaunchedEffect(list, canPlay) {
        if (!canPlay) {
            playingId.value = null
            return@LaunchedEffect
        }
        snapshotFlow { list.videoToPlay(currentRows, video) }.collectLatest { inView ->
            // Stops at once; starts only once the scroll has settled on it.
            if (inView != null) delay(VIDEO_SETTLE_MILLIS)
            playingId.value = inView
        }
    }
    val feedActions = remember {
        FeedActions(
            openItem = { section, item -> currentActions.openItem(section, item) },
            saveTrip = { trip ->
                newStoryTitle = trip.place.orEmpty()
                newStoryKey = trip.key
            },
            dismissTrip = { currentActions.dismissTrip(it) },
            itemMenu = { _, item -> menuItem = item.id },
            showOnRow = { section, source ->
                reveal = FeedReveal(section.key, source)
                currentActions.showOnRow(section.key, source)
            },
            hideOnRow = { section, source ->
                if (reveal == FeedReveal(section.key, source)) reveal = null
                currentActions.hideOnRow(section.key, source)
            },
            openRevealed = { section, band, item -> currentActions.openRevealed(section, band.source, item) },
            addRevealedToStory = { band -> storyFor = band.items.map { it.id } },
            alwaysShow = { section, source ->
                if (reveal == FeedReveal(section.key, source)) reveal = null
                currentActions.alwaysShow(section, source)
            },
        )
    }

    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        if (tab == HomeTab.EXPLORE) {
            ExploreContent(
                library,
                stories,
                exploreView,
                actions.explore,
                PaddingValues(top = insets.calculateTopPadding() + 118.dp, bottom = insets.calculateBottomPadding() + 150.dp),
            )
        } else LazyColumn(
            state = list,
            contentPadding = PaddingValues(
                top = insets.calculateTopPadding() + if (moments) 118.dp else 72.dp,
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
            if (tab == HomeTab.STORIES && entries.isEmpty() && library.days.isNotEmpty()) {
                item(key = "no-stories") { Message(stringResource(R.string.library_no_stories)) }
            }
            if (moments && stories.feed.entries.isEmpty() && library.days.isNotEmpty()) {
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
            if (moments) feedItems(feedRows, feedActions, landing, video, stories.notes, selection)
        }

        TopBar(health, actions, Modifier.align(Alignment.TopCenter)) {
            if (moments) FeedSourceBar(stories.feed, actions.toggleSource, Modifier.padding(top = 4.dp))
            if (tab == HomeTab.EXPLORE) ExploreBar(exploreView, { exploreView = it }, actions.explore.openMap, Modifier.padding(top = 4.dp))
        }

        BottomControls(
            summary = backupSummary(library, backupRunning).takeIf { !selection.active },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding(),
        ) {
            if (selection.active && moments) {
                FeedSelectionBar(
                    count = selection.keys.size,
                    onCancel = selection::clear,
                    onMerge = {
                        val ids = feedRows.filterIsInstance<FeedRow.Photo>().filter { it.block.key in selection.keys }.flatMap { it.block.items }.map { it.id }
                        selection.clear()
                        actions.merge(ids)
                    },
                )
            } else {
                val band = reveal?.takeIf { moments }?.let { shown ->
                    stories.feed.section(shown.section)?.let { section -> section.revealed.firstOrNull { it.source == shown.source }?.let { section to it } }
                }
                band?.let { (section, photos) ->
                    RevealNote(section, photos) {
                        reveal = null
                        actions.hideOnRow(section.key, photos.source)
                    }
                }
                SegmentedBar(
                    options = listOf(
                        HomeTab.STORIES to stringResource(R.string.library_tab_stories),
                        HomeTab.MOMENTS to stringResource(R.string.library_tab_moments),
                        HomeTab.EXPLORE to stringResource(R.string.library_tab_explore),
                    ),
                    selected = tab,
                    onSelect = {
                        selection.clear()
                        tab = it
                    },
                )
            }
        }

        menuItem?.let { stories.itemsById[it] }?.let { item ->
            val block = feedRows.firstNotNullOfOrNull { row -> (row as? FeedRow.Photo)?.block?.takeIf { block -> block.items.any { it.id == item.id } } }
            FeedItemMenu(
                item,
                note = stories.notes[item.id]?.caption,
                inSet = block is FeedBlock.Similar,
                onDismiss = { menuItem = null },
                onChoose = { choice ->
                    menuItem = null
                    when (choice) {
                        FeedItemChoice.WRITE -> writingFor = item.id
                        FeedItemChoice.CROP -> actions.crop(item.id)
                        FeedItemChoice.ADD_TO_STORY -> storyFor = listOf(item.id)
                        FeedItemChoice.TAG -> tagFor = item.id
                        FeedItemChoice.SELECT -> selection.toggle(block?.key ?: item.id)
                        FeedItemChoice.TAKE_OUT -> actions.takeOutOfSet(item.id)
                    }
                },
            )
        }
    }

    writingFor?.let { id ->
        WritingEditor(
            title = stringResource(R.string.library_note_title),
            initial = stories.notes[id]?.caption.orEmpty(),
            onSave = {
                writingFor = null
                actions.setCaption(id, it)
            },
            onDismiss = { writingFor = null },
        )
    }
    storyFor?.let { ids ->
        AddToStorySheet(
            itemIds = ids,
            stories = stories,
            onToggle = { storyId, add -> actions.toggleStory(storyId, ids, add) },
            onCreate = { title ->
                storyFor = null
                actions.createStoryWith(title, ids)
            },
            onDismiss = { storyFor = null },
        )
    }
    tagFor?.let { id ->
        TagSheet(
            itemIds = listOf(id),
            stories = stories,
            onToggle = { tag, add -> actions.toggleTag(id, tag, add) },
            onCreate = { tag -> actions.toggleTag(id, tag, true) },
            onDismiss = { tagFor = null },
        )
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

/** Above the tabs while a band is shown: "Showing 6 from WhatsApp on Oct 4 · Hide". */
@Composable
private fun RevealNote(section: FeedSection, band: RevealedPhotos, onHide: () -> Unit) {
    Row(
        Modifier
            .padding(horizontal = 16.dp)
            .fillMaxWidth()
            .background(LibraryColors.Inverse, RoundedCornerShape(12.dp))
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.feed_reveal_showing, band.items.size, sourceName(band.source), formatDate(section.span.newestDay.toLocalDate(), "MMMd")),
            color = LibraryColors.OnInverse,
            fontSize = 14.sp,
            maxLines = 2,
            modifier = Modifier.weight(1f).padding(vertical = 12.dp),
        )
        TextButton(onClick = onHide) {
            Text(stringResource(R.string.feed_reveal_hide), color = LibraryColors.OnInverse, fontWeight = FontWeight.Bold)
        }
    }
}

/** How long the note about a band just shown stays. */
private const val REVEAL_NOTE_MILLIS = 6_000L

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
