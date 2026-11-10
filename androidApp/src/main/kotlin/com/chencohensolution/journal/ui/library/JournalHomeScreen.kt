package com.chencohensolution.journal.ui.library

import android.os.Process
import android.os.SystemClock
import androidx.compose.runtime.withFrameNanos
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.Measure
import androidx.compose.animation.core.tween
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import com.chencohensolution.journal.R
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.stories.FeedBlock
import com.chencohensolution.journal.stories.ShowOn
import com.chencohensolution.journal.stories.NoteVersion
import com.chencohensolution.journal.stories.FeedReveal
import com.chencohensolution.journal.stories.RevealedPhotos
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.stories.Moment
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.PhotoAccess
import com.chencohensolution.journal.search.SearchState
import com.chencohensolution.journal.settings.GroupingMode
import com.chencohensolution.journal.settings.MomentsPaging
import java.time.Instant
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.Dispatchers
import java.time.ZoneId

internal enum class HomeTab { STORYLINE, MOMENTS, EXPLORE }

/** Named people offered on the search page before anything is searched. */
private const val SEARCH_FACES = 16

internal class HomeActions(
    /** A photo of the Moments tab; the viewer swipes through its row. */
    val openItem: (FeedSection, MediaItem) -> Unit = { _, _ -> },
    /** A photo of the storyline; the viewer swipes through the storyline. */
    val openStoryline: (MediaItem) -> Unit = {},
    val toggleSource: (MediaSource) -> Unit = {},
    val openSettings: () -> Unit = {},
    val openTags: () -> Unit = {},
    val allowAccess: () -> Unit = {},
    val changeSelection: () -> Unit = {},
    val registerAgain: () -> Unit = {},
    /** Moments: the photos of the sets and photos picked become one set; a photo leaves its set. */
    val merge: (itemIds: List<String>) -> Unit = {},
    /** Moments: a set (its photos' ids in order, and those at hand) opens in the viewer on its own, at a photo. */
    val openSet: (itemIds: List<String>, items: List<MediaItem>, item: MediaItem) -> Unit = { _, _, _ -> },
    /** Make a moment: the photos picked become one named set ([StoriesController.makeMoment]); then it opens. */
    val makeMoment: (items: List<MediaItem>, name: String, highlightIds: List<String>, showOn: ShowOn) -> Unit = { _, _, _, _ -> },
    val takeOutOfSet: (itemId: String) -> Unit = {},
    /** From a photo's long-press menu in Moments, by item id. */
    val setWriting: (itemId: String, title: String, text: String) -> Unit = { _, _, _ -> },
    val restoreWriting: (itemId: String, version: NoteVersion) -> Unit = { _, _ -> },
    val crop: (itemId: String) -> Unit = {},
    /** "Remove from storyline" (false) or "Add to storyline" (true) from a photo's menu. */
    val setInStoryline: (itemId: String, inStoryline: Boolean) -> Unit = { _, _ -> },
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
    /** Search, from the 🔍 beside the tabs. */
    val search: SearchActions = SearchActions(),
    /** A sound recording's page, by sha256: from its bar in Moments, a call or a voice message. */
    val openRecording: (sha256: String) -> Unit = {},
    /** Moments is read a page at a time: the next page, as the list nears its end. */
    val loadMoreMoments: () -> Unit = {},
    /**
     * Moments' rows down to the row of a day, for going to a date: calls back with that row's key once it is read (null
     * when no row has the day).
     */
    val loadMomentsTo: (LocalDay, (String?) -> Unit) -> Unit = { _, done -> done(null) },
    /** Moments' rows down to the row of a photo, its day looked up in the library (it may be in no row read yet). */
    val loadMomentsToItem: (itemId: String) -> Unit = {},
)

/**
 * The home screen: in the Storyline tab every photo written about, newest first, and in the Moments tab every photo
 * as a feed that groups itself (by day or run of photos, then time, place, source and looks). The 🔍 beside the tabs
 * opens search over them, until its ✕. See docs/screens/journal-home.md, moments.md and search.md.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun JournalHomeScreen(
    library: LibraryState,
    stories: StoriesState,
    health: ServerHealth,
    actions: HomeActions,
    /** The tab shown first; previews use it to show Moments. */
    initialTab: HomeTab = HomeTab.STORYLINE,
    /** The photo the viewer above shows: its tile is scrolled into view for the photo to land on when it closes. */
    landing: String? = null,
    /** "Show in Moments" from the viewer: the Moments tab, scrolled to that photo. */
    showInMoments: ShowInMoments? = null,
    /** The backup run in progress, for the bottom pill. */
    backupRunning: BackupProgress? = null,
    /** Photos and sets picked at first (by first photo id); previews use it. */
    initialSelection: Set<String> = emptySet(),
    /** The band whose "Showing 6 from WhatsApp" note shows at first; previews use it. */
    initialReveal: FeedReveal? = null,
    /** Places or Years at first in the Explore tab; previews use it. */
    initialExploreView: ExploreView = ExploreView.PLACES,
    /** The last search and what it found. */
    search: SearchState = SearchState(),
    /** Search open at first, over the tab; previews use it. */
    initialSearching: Boolean = false,
    /** Explore's People, when the server has face search; null: no People pill. */
    people: com.chencohensolution.journal.people.PeopleState? = null,
    /** Moments reads its next page when the list is this many rows from the end of what is read (Settings › Loading Moments). */
    loadAhead: Int = MomentsPaging.DEFAULT_LOAD_AHEAD,
) {
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    // Search covers the tab until its ✕ (or back), and the tab is as it was.
    var searching by rememberSaveable { mutableStateOf(initialSearching) }
    var exploreView by rememberSaveable { mutableStateOf(initialExploreView) }
    // The photo being written about or tagged from the picking bar.
    var writingFor by rememberSaveable { mutableStateOf<String?>(null) }
    var tagFor by rememberSaveable { mutableStateOf<String?>(null) }
    // The photos picked for Make a moment while its sheet is open.
    var makingMoment by remember { mutableStateOf<List<MediaItem>?>(null) }
    // Photos and sets picked (a long press in Moments or the storyline); back stops picking.
    val selection = remember { FeedSelection().apply { initialSelection.forEach(::toggle) } }
    BackHandler(enabled = selection.active) { selection.clear() }
    // Search's results picked to mark private; closing search stops picking.
    val searchPicks = remember { FeedSelection() }
    LaunchedEffect(searching) { if (!searching) searchPicks.clear() }
    BackHandler(enabled = searchPicks.active) { searchPicks.clear() }
    BackHandler(enabled = searching && !selection.active && !searchPicks.active) { searching = false }
    // The band just shown, for its note above the tabs: "Showing 6 from WhatsApp on Oct 4 · Hide".
    var reveal by remember { mutableStateOf(initialReveal) }
    val inPreview = LocalInspectionMode.current
    LaunchedEffect(reveal) {
        if (reveal != null && !inPreview) {
            delay(REVEAL_NOTE_MILLIS)
            reveal = null
        }
    }
    val insets = barsRoom()

    // The storyline shows the photos written about; every photo is in the Moments tab, as the feed.
    val moments = tab == HomeTab.MOMENTS
    val storyline = tab == HomeTab.STORYLINE
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
    // Measuring (debug builds; docs/plans/library-on-disk.md): how long after the process started Moments first drew rows.
    if (Measure.log != null) {
        LaunchedEffect(feedRows.isNotEmpty()) {
            if (feedRows.isEmpty() || !stories.ready || firstMomentsFrameLogged) return@LaunchedEffect
            withFrameNanos {}
            firstMomentsFrameLogged = true
            Measure.log?.invoke("First Moments frame ${SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()} ms after start")
        }
    }
    // A moment made by hand shows by its cover, with its name and description (asked Nov 10, 2026).
    val storyRows = remember(stories.storylineDays, stories.notes) { storylineDayRows(stories.storylineDays, stories::storylineMomentOf) { stories.itemsById[it] } }
    // A day's "3 tucked away" pill asks for the phone's lock the first time in a session.
    val tucked = LocalTucked.current
    val tuckedLock = rememberTuckedLock()
    val currentTucked by rememberUpdatedState(tucked)
    val currentLock by rememberUpdatedState(tuckedLock)
    // The month pill grown into the ruler; a touch anywhere else, back, or leaving the tab folds it.
    var timelineOpen by remember { mutableStateOf(false) }
    BackHandler(enabled = timelineOpen) { timelineOpen = false }
    LaunchedEffect(tab, searching, selection.active) { timelineOpen = false }
    val timeline = shownFeed.timeline
    val rowSections = remember(feedRows) { feedRows.sectionKeys() }
    // A row going to a date asked for before it was read: gone to once it is in the list (the list's rows follow the
    // feed only once a scroll stops).
    var jumpTo by remember { mutableStateOf<Pair<String, LocalDay>?>(null) }
    // Which tap asked last: a read that comes back after a later tap doesn't go anywhere.
    val jumpsAsked = remember { IntArray(1) }
    LaunchedEffect(jumpTo, feedRows) {
        val (key, day) = jumpTo ?: return@LaunchedEffect
        val row = feedRows.rowOfDay(key, day)
        if (row < 0) return@LaunchedEffect
        jumpTo = null
        list.scrollToItem(list.layoutInfo.totalItemsCount - feedRows.size + row)
    }
    // The row at the top, under the title: the first whose bottom is below the list's top padding (the row behind the
    // title counts as visible). Its month and day are the pill's; derived, so the screen recomposes only when they change.
    val topSection by remember(feedRows) {
        derivedStateOf {
            val info = list.layoutInfo
            val top = info.visibleItemsInfo.firstOrNull { it.offset + it.size > 0 }?.index ?: list.firstVisibleItemIndex
            rowSections.getOrNull(top - (info.totalItemsCount - feedRows.size))
        }
    }
    // A row can hold several days (and months): the pill names the day of the row at the top, not the row's newest.
    val rowDays = remember(feedRows) { feedRows.rowDays() }
    val topRowDay by remember(feedRows) {
        derivedStateOf {
            val info = list.layoutInfo
            val top = info.visibleItemsInfo.firstOrNull { it.offset + it.size > 0 }?.index ?: list.firstVisibleItemIndex
            rowDays.getOrNull(top - (info.totalItemsCount - feedRows.size))
        }
    }
    val topDay = topRowDay ?: topSection?.let { shownFeed.section(it)?.span?.newestDay }
    val topMonth = topDay?.let { day -> timeline.months.indexOfFirst { it.year == day.year && it.month == day.month }.takeIf { it >= 0 } }
        ?: topSection?.let(timeline::monthOf)
    val scope = rememberCoroutineScope()
    // The title row and the bar under it slide away while the list scrolls down, and back as it scrolls up (asked Oct 24,
    // 2026).
    val titleHeight = remember { IntArray(1) }
    val titleShift = remember { mutableFloatStateOf(0f) }
    // How far the list in view has scrolled from its top, by the scrolls it reported (Explore's lists are their own).
    val scrolledPx = remember { mutableFloatStateOf(0f) }
    // The tabs and the backup pill slide away too, and come back on the way up; the month pill stays (asked Oct 24, 2026).
    var bottomHidden by remember { mutableStateOf(false) }
    val collapse = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                titleShift.floatValue = (titleShift.floatValue + available.y).coerceIn(-titleHeight[0].toFloat(), 0f)
                if (available.y < -SCROLL_DIRECTION_PX && !bottomHidden) bottomHidden = true
                if (available.y > SCROLL_DIRECTION_PX && bottomHidden) bottomHidden = false
                return Offset.Zero
            }

            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                scrolledPx.floatValue = (scrolledPx.floatValue - consumed.y).coerceAtLeast(0f)
                return Offset.Zero
            }
        }
    }
    LaunchedEffect(tab, searching, timelineOpen, selection.active) {
        titleShift.floatValue = 0f
        bottomHidden = false
    }
    LaunchedEffect(tab, searching) { scrolledPx.floatValue = 0f }
    // The top bar's fade fades in while the bar shows over a list scrolled from its top, and out at the top (asked Oct
    // 24, 2026); the bar's own sliding away fades it further (TopBar).
    val scrolledFromTop by remember {
        derivedStateOf { if (tab == HomeTab.EXPLORE || searching) scrolledPx.floatValue > 0f else list.canScrollBackward }
    }
    val topFade = animateFloatAsState(if (scrolledFromTop) 1f else 0f, tween(TOP_FADE_MILLIS), label = "topFade")
    val tabsAway = animateFloatAsState(if (bottomHidden) 1f else 0f, tween(BOTTOM_SLIDE_MILLIS), label = "tabs")
    // The tabs' height and the gap above them: how far the month pill goes down to take their place.
    val tabsRoom = remember { IntArray(1) }
    val bottomGap = with(LocalDensity.current) { BOTTOM_GAP.roundToPx() }
    // What the list shows below its banners: the storyline's or the feed's items.
    val rows = when (tab) {
        HomeTab.MOMENTS -> feedRows
        HomeTab.STORYLINE -> storyRows
        HomeTab.EXPLORE -> emptyList()
    }
    LaunchedEffect(showInMoments) {
        if (showInMoments != null) {
            searching = false
            tab = HomeTab.MOMENTS
        }
    }
    val shownPhoto = showInMoments?.itemId?.takeIf { moments }
    // Moments is read a page at a time: a photo to show further down than what is read reads down to its day first.
    LaunchedEffect(shownPhoto) {
        val id = shownPhoto ?: return@LaunchedEffect
        if (rows.rowOf(id) < 0) actions.loadMomentsToItem(id)
    }
    // …and the next page, once the list is near the end of what is read.
    LaunchedEffect(list, moments, loadAhead) {
        if (!moments) return@LaunchedEffect
        snapshotFlow { list.layoutInfo.let { (it.visibleItemsInfo.lastOrNull()?.index ?: 0) >= it.totalItemsCount - loadAhead } }
            .distinctUntilChanged()
            .collect { near -> if (near) actions.loadMoreMoments() }
    }
    list.LandOn(shownPhoto ?: landing.takeUnless { searching }, rows.size) { id -> rows.rowOf(id) }
    // Sharp photos decode once the list slows down; until then each shows its quick image, loaded ahead of the scroll.
    val scrollSettled = rememberScrollSettled(list)
    PrefetchFirstImages(list, rows, { it.key }) { row ->
        when (row) {
            // A set's second photo is a swipe away.
            is FeedRow.Photo -> row.block.items.take(2)
            is FeedRow.StoryPhoto -> listOf(row.item)
            else -> emptyList()
        }
    }
    // One video plays at a time, the one most in view, and only while the app is in front (not in previews).
    val resumed by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val canPlay = tab != HomeTab.EXPLORE && !searching && resumed.isAtLeast(Lifecycle.State.RESUMED) && !LocalInspectionMode.current
    // Watched in a coroutine, not read here: a change redraws only the videos, not the whole screen (which took 20 ms).
    val playingId = remember { mutableStateOf<String?>(null) }
    val currentRows by rememberUpdatedState(rows)
    val muted = rememberSaveable { mutableStateOf(true) }
    // The same actions object across recompositions, so the feed's items don't all recompose with the screen.
    val currentActions by rememberUpdatedState(actions)
    val currentStories by rememberUpdatedState(stories)
    val video = remember {
        FeedVideo(
            playingId = { playingId.value },
            isMuted = { muted.value },
            toggleMute = { muted.value = !muted.value },
            savedPage = { block -> currentStories.frontOf(block.items) },
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
    // Sound recordings play through the app's player (none in previews); its position is read only by what shows it.
    val player = LocalAudioPlayer.current
    val playback = player?.playback?.collectAsState()
    val audio = remember(player) {
        FeedAudio(
            playback = { playback?.value },
            play = { player?.play(it) },
            pause = { player?.pause() },
            seek = { recording, millis -> player?.seek(recording, millis) },
            open = { currentActions.openRecording(it.sha256) },
        )
    }
    // The bar of the recording whose photos are at the top, pinned under the title once its own bar has scrolled away.
    val pinned by remember(feedRows) {
        derivedStateOf {
            val info = list.layoutInfo
            val first = info.totalItemsCount - feedRows.size
            val top = info.visibleItemsInfo.firstOrNull { it.offset + it.size > 0 } ?: return@derivedStateOf null
            val recording = (feedRows.getOrNull(top.index - first) as? FeedRow.Photo)?.recording ?: return@derivedStateOf null
            val bar = feedRows.indexOfLast { it is FeedRow.RecordingBar && it.moment.recording?.sha256 == recording.sha256 }
            val barItem = info.visibleItemsInfo.firstOrNull { it.index == first + bar }
            (feedRows.getOrNull(bar) as? FeedRow.RecordingBar)?.takeIf { barItem == null || barItem.offset < 0 }?.moment
        }
    }
    val feedActions = remember {
        FeedActions(
            openItem = { section, item -> currentActions.openItem(section, item) },
            openSet = { ids, items, item -> currentActions.openSet(ids, items, item) },
            openStoryline = { currentActions.openStoryline(it) },
            openMoment = { moment, cover -> currentActions.openSet(moment.itemIds, listOf(cover), cover) },
            toggleTucked = { days -> currentLock { currentTucked.toggleDays(days) } },
            showOnRow = { section, source ->
                reveal = FeedReveal(section.key, source)
                currentActions.showOnRow(section.key, source)
            },
            hideOnRow = { section, source ->
                if (reveal == FeedReveal(section.key, source)) reveal = null
                currentActions.hideOnRow(section.key, source)
            },
            openRevealed = { section, band, item -> currentActions.openRevealed(section, band.source, item) },
            alwaysShow = { section, source ->
                if (reveal == FeedReveal(section.key, source)) reveal = null
                currentActions.alwaysShow(section, source)
            },
        )
    }

    Box(Modifier.fillMaxSize().background(LibraryColors.Background).nestedScroll(collapse)) {
        if (searching) {
            SearchContent(
                search,
                stories.places,
                landing,
                actions.search,
                PaddingValues(top = insets.calculateTopPadding() + 80.dp, bottom = insets.calculateBottomPadding() + 150.dp),
                faces = people?.list?.people.orEmpty().sortedByDescending { it.files }.take(SEARCH_FACES),
                selection = searchPicks,
                picturesAhead = loadAhead,
            )
        } else if (tab == HomeTab.EXPLORE) {
            ExploreContent(
                library,
                stories,
                exploreView,
                actions.explore,
                PaddingValues(top = insets.calculateTopPadding() + 118.dp, bottom = insets.calculateBottomPadding() + 150.dp),
                people,
            )
        } else CompositionLocalProvider(LocalScrollSettled provides scrollSettled) {
            val hiding = rowsHiding(remember(rows) { rows.map { it.key } })
            // A list of its own per tab (asked Nov 10, 2026): in one list, the storyline's rows faded out over Moments'
            // when switching, as rows a change took away.
            key(tab) {
            LazyColumn(
                state = list,
                contentPadding = PaddingValues(
                    // Room for the title, and for the filter bar when it shows.
                    top = insets.calculateTopPadding() + if (moments && stories.shownSources.showFilterBar) 118.dp else 72.dp,
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
                // Moments draws once: one spinner until its feed is ready and its rows are worked out (asked Oct 25, 2026).
                val momentsWaiting = moments && (!stories.ready || (feedRows.isEmpty() && stories.feed.entries.isNotEmpty()))
                when {
                    momentsWaiting || (library.loading && library.isEmpty) -> item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = LibraryColors.OnBackground)
                        }
                    }
                    canRead && library.isEmpty && library.loadError == null ->
                        item(key = "empty") { Message(stringResource(R.string.library_empty)) }
                }
                if (storyline && stories.storyline.isEmpty() && !library.isEmpty) {
                    item(key = "no-storyline") { Message(stringResource(R.string.storyline_empty)) }
                }
                if (moments && !momentsWaiting && stories.feed.entries.isEmpty() && !library.isEmpty) {
                    item(key = "nothing-shown") { Message(stringResource(R.string.feed_nothing_shown)) }
                }
                // Last, so landing from the viewer can count them from the end.
                if (!momentsWaiting) feedItems(rows, feedActions, landing, video, stories.notes, selection, audio, hiding)
            }
            }
        }

        if (searching) {
            SearchTopBar(Modifier.align(Alignment.TopCenter))
            BottomControls(
                summary = null,
                modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                insets = navigationBarInsets().union(WindowInsets.ime),
            ) {
                AnimatedContent(searchPicks.active, transitionSpec = { barSwap() }, contentAlignment = Alignment.BottomCenter, label = "search bar") { picking ->
                    if (picking) {
                        SearchPickingBar(search, searchPicks, actions.search)
                    } else {
                        SearchBottomBar(search, actions.search, onClose = { searching = false })
                    }
                }
            }
        } else TopBar(
            health,
            actions,
            titleShift = { titleShift.floatValue },
            titleHeight = titleHeight,
            fade = { topFade.value },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            if (moments && stories.shownSources.showFilterBar) FeedSourceBar(stories.feed, actions.toggleSource, Modifier.padding(top = 4.dp))
            if (tab == HomeTab.EXPLORE) ExploreBar(exploreView, { exploreView = it }, actions.explore.openMap, Modifier.padding(top = 4.dp), people = people != null)
        }

        pinned?.takeIf { moments && !searching }?.let { moment ->
            RecordingBar(moment, audio, Modifier.align(Alignment.TopCenter).padding(top = insets.calculateTopPadding() + 118.dp))
        }

        if (timelineOpen) TimelineScrim { timelineOpen = false }

        if (!searching) BottomControls(
            summary = backupSummary(library, backupRunning).takeIf { !selection.active && !timelineOpen },
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            summaryHidden = bottomHidden,
        ) {
            // The picking bar slides up over the tabs as picking starts, and back down as it ends (Nov 8, 2026).
            AnimatedContent(
                targetState = selection.active && (moments || storyline),
                modifier = Modifier.fillMaxWidth(),
                transitionSpec = { barSwap() },
                contentAlignment = Alignment.BottomCenter,
                label = "bottom bar",
            ) { isPicking ->
                if (isPicking) {
                    val picked = rows.picked(selection)
                    val one = picked.singleOrNull()
                    // Moments: one set picked (whole) can lose the photo the long press was on.
                    val pickedBlocks = feedRows.filterIsInstance<FeedRow.Photo>().filter { moments && it.block.key in selection.keys }.map { it.block }
                    val pressed = selection.pressed
                    val setOfPressed = pickedBlocks.singleOrNull()?.takeIf { it is FeedBlock.Similar && it.items.any { item -> item.id == pressed } }
                    fun then(action: () -> Unit): () -> Unit = {
                        selection.clear()
                        action()
                    }
                    PickingBar(
                        picked = picked,
                        onCancel = selection::clear,
                        onDone = { _, _ -> selection.clear() },
                        extras = PickingExtras(
                            write = one?.let { item -> then { writingFor = item.id } },
                            written = one?.let { stories.notes[it.id]?.hasWriting } == true,
                            crop = one?.takeIf { it.durationMillis == null }?.let { item -> then { actions.crop(item.id) } },
                            tag = one?.let { item -> then { tagFor = item.id } },
                            // Merge stays for sets without a name; a moment made by hand is made again with Make a moment.
                            merge = if (pickedBlocks.size >= 2 && pickedBlocks.none { it.named != null }) then { actions.merge(pickedBlocks.flatMap { it.items }.map { it.id }) } else null,
                            makeMoment = if (moments && picked.size >= 2) then { makingMoment = picked } else null,
                            takeOut = if (setOfPressed != null && pressed != null) then { actions.takeOutOfSet(pressed) } else null,
                        ),
                    )
                } else {
                    // Laid out as BottomControls lays out its own (a column, centred, 10 dp apart): inside
                    // AnimatedContent they would otherwise sit on top of each other.
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val band = reveal?.takeIf { moments }?.let { shown ->
                            stories.feed.section(shown.section)?.let { section -> section.revealed.firstOrNull { it.source == shown.source }?.let { section to it } }
                        }
                        band?.let { (section, photos) ->
                            RevealNote(section, photos) {
                                reveal = null
                                actions.hideOnRow(section.key, photos.source)
                            }
                        }
                        if (moments && band == null && !timeline.isEmpty) {
                            // Slides down with the tabs into their place at the bottom, and back up with them.
                            Box(Modifier.graphicsLayer { translationY = tabsAway.value * tabsRoom[0] }) {
                                TimelineBar(timeline, topMonth, topDay, timelineOpen, onOpen = { timelineOpen = it }) { day ->
                                    val row = feedRows.rowOfDay(day.sectionKey, day.day)
                                    // The feed's rows come last in the list, after its banners. A row not in the list yet is
                                    // read first (down to it), then gone to by the key the read names: the rows may have been
                                    // made again since this timeline, with other keys.
                                    jumpTo = null
                                    val asked = ++jumpsAsked[0]
                                    if (row >= 0) {
                                        scope.launch { list.scrollToItem(list.layoutInfo.totalItemsCount - feedRows.size + row) }
                                    } else {
                                        actions.loadMomentsTo(day.day) { key -> if (asked == jumpsAsked[0]) jumpTo = key?.let { it to day.day } }
                                    }
                                }
                            }
                        }
                        // Slides down off the screen while the list goes down (keeping its place for the month pill to take).
                        Row(
                            Modifier
                                .onSizeChanged { tabsRoom[0] = it.height + bottomGap }
                                .graphicsLayer { translationY = tabsAway.value * (size.height + TABS_AWAY.toPx()) },
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            SegmentedBar(
                                options = listOf(
                                    HomeTab.MOMENTS to stringResource(R.string.library_tab_moments),
                                    HomeTab.STORYLINE to stringResource(R.string.library_tab_storyline),
                                    HomeTab.EXPLORE to stringResource(R.string.library_tab_explore),
                                ),
                                selected = tab,
                                onSelect = {
                                    selection.clear()
                                    tab = it
                                },
                            )
                            SearchButton {
                                selection.clear()
                                reveal = null
                                searching = true
                            }
                        }
                    }
                }
            }
        }

    }

    makingMoment?.let { picked ->
        MakeMomentSheet(
            picked,
            onMake = { name, highlights, showOn ->
                makingMoment = null
                actions.makeMoment(picked, name, highlights, showOn)
            },
            onDismiss = { makingMoment = null },
        )
    }
    writingFor?.let { id ->
        NoteEditor(
            note = stories.notes[id],
            onSave = { title, text ->
                writingFor = null
                actions.setWriting(id, title, text)
            },
            onRestore = { version ->
                writingFor = null
                actions.restoreWriting(id, version)
            },
            onDismiss = { writingFor = null },
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
private fun TopBar(
    health: ServerHealth,
    actions: HomeActions,
    /** How far the bar's controls have slid up (zero or less, in pixels), as the list scrolls. */
    titleShift: () -> Float,
    /** Where the controls' full height is kept for the scroll to know how far they can slide. */
    titleHeight: IntArray,
    /** How much of the fade behind the bar shows (0 to 1) while the bar itself shows: none at the list's top. */
    fade: () -> Float,
    modifier: Modifier = Modifier,
    below: @Composable () -> Unit = {},
) {
    // How much of the controls shows: 1 in place, 0 slid away.
    val shown = { if (titleHeight[0] > 0) 1f + titleShift() / titleHeight[0] else 1f }
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.matchParentSize().graphicsLayer { alpha = fade() * shown() }.background(LibraryColors.TopFade))
        Column(Modifier.fillMaxWidth().statusBarRoom().padding(top = 8.dp, bottom = 8.dp)) {
            // The title row and the bar under it (the filter bar, Explore's) slide up and fade out together, whole.
            Column(
                Modifier
                    .onSizeChanged { titleHeight[0] = it.height }
                    .graphicsLayer {
                        translationY = titleShift()
                        alpha = shown()
                    },
            ) {
                Row(Modifier.padding(start = 16.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                    IconButton(onClick = actions.openSettings) {
                        Icon(LibraryIcons.Settings, stringResource(R.string.library_settings), tint = LibraryColors.OnBackground)
                    }
                }
                below()
            }
        }
    }
}

/** A scroll of more than this many pixels in a frame says which way the list goes, for the bottom controls. */
private const val SCROLL_DIRECTION_PX = 2f

/** How long the top bar's fade takes to fade in or out at the list's top. */
private const val TOP_FADE_MILLIS = 250

/** How long the tabs take to slide away or back, and how far below their place they go (past the gesture bar). */
private const val BOTTOM_SLIDE_MILLIS = 250
private val TABS_AWAY = 64.dp

/** The gap between the bottom controls (BottomControls' spacing). */
private val BOTTOM_GAP = 10.dp

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

/** A request to show [itemId] in the Moments tab; a new one each time, so the same photo can be asked for again. */
internal class ShowInMoments(val itemId: String)

/** Whether the first Moments frame was measured in this process (see [Measure]). */
private var firstMomentsFrameLogged = false
