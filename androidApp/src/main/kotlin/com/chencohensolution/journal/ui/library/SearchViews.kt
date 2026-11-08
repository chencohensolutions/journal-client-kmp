package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import android.widget.Toast
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Button
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.layout.LazyLayoutCacheWindow
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.search.SearchChip
import com.chencohensolution.journal.people.Person
import com.chencohensolution.journal.search.SearchError
import com.chencohensolution.journal.search.SearchState
import com.chencohensolution.journal.search.SearchStatus
import java.text.NumberFormat

// The home screen's search: the 🔍 beside the tabs turns the bottom bar into a field, and the page lists the photos the
// server finds for the sentence typed, best first, each across the screen with its date, time and place under it, and
// almost identical ones as one photo you swipe (the server's sets, a moment's sets kept together). A
// Filter pill above the field opens a sheet of the matches' years and towns to narrow them. ✕ goes back to the tab,
// keeping the search for next time. A long press starts picking photos (a set is picked whole) to mark them private
// together. See docs/screens/search.md.

internal class SearchActions(
    val search: (text: String) -> Unit = {},
    val clear: () -> Unit = {},
    /** A year or town in the filter sheet, or null to drop it. */
    val chooseYear: (Int?) -> Unit = {},
    val chooseTown: (String?) -> Unit = {},
    /** The next page of results, when the list nears its end. */
    val loadMore: () -> Unit = {},
    /** A photo of the results; the viewer swipes through the results. */
    val openItem: (MediaItem) -> Unit = {},
    /** The search screen opened: the server gets its model ready (SearchController.prepare). */
    val prepare: () -> Unit = {},
    /** A face before anything is searched: the person becomes a chip, and the search their photos. */
    val addPerson: (Person) -> Unit = {},
    /** A person chip's ×. */
    val removePerson: (String) -> Unit = {},
    /** The server took the picked results as private, or tucked them away: they leave the results now. */
    val madePrivate: (List<MediaItem>) -> Unit = {},
    val madeTucked: (List<MediaItem>) -> Unit = {},
    /** "N more in sources you hide · Show" (true) and its "Hide" (false), for this search only. */
    val showHiddenSources: (Boolean) -> Unit = {},
)

/** The results page, under [SearchTopBar] and above [SearchBottomBar]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun SearchContent(
    search: SearchState,
    places: Places,
    landing: String?,
    actions: SearchActions,
    contentPadding: PaddingValues,
    /** Named people to search by, before anything is searched (when the server has face search). */
    faces: List<Person> = emptyList(),
    /** The sets picked, by their first photo's id; a tap picks while any is. */
    selection: FeedSelection = FeedSelection(),
) {
    // Rows about two screens ahead are composed in spare time between frames, so their pictures are loading before
    // they scroll into view.
    val list = rememberLazyListState(cacheWindow = remember { LazyLayoutCacheWindow(ahead = SEARCH_AHEAD, behind = SEARCH_BEHIND) })
    list.LandOn(landing, search.sets.size) { id -> search.sets.indexOfFirst { set -> set.any { it.id == id } } }
    // The next page once the last rows come into view: a few rows ahead, so it is usually there by the end.
    val currentSearch by rememberUpdatedState(search)
    val currentActions by rememberUpdatedState(actions)
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .collect { (last, total) ->
                if (last != null && last >= total - LOAD_AHEAD_ROWS && currentSearch.canLoadMore && !currentSearch.loadingMore) currentActions.loadMore()
            }
    }
    LaunchedEffect(Unit) { currentActions.prepare() }
    // As in Moments: sharp photos decode once the list slows down; until then each shows its quick image, loaded further
    // ahead of the scroll (a set's second photo is a swipe away).
    val scrollSettled = rememberScrollSettled(list)
    PrefetchFirstImages(list, search.sets, { it.first().id }) { set -> set.take(2) }
    val hiding = rowsHiding(remember(search.sets) { search.sets.map { it.first().id } })
    LazyColumn(state = list, contentPadding = contentPadding) {
        if (search.people.isNotEmpty()) item(key = "people-chips") { PersonChips(search.people, actions.removePerson) }
        when (search.status) {
            SearchStatus.IDLE -> {
                item(key = "hint") { SearchMessage(stringResource(R.string.search_hint)) }
                if (faces.isNotEmpty()) item(key = "faces") { SearchFaces(faces, actions.addPerson) }
            }
            SearchStatus.SEARCHING -> if (search.items.isEmpty() && search.yearChips.isEmpty()) item(key = "searching") {
                Column(Modifier.fillMaxWidth().padding(48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator(color = LibraryColors.OnBackground)
                    if (search.preparing) {
                        Text(stringResource(R.string.search_preparing), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
            SearchStatus.FAILED -> item(key = "failed") {
                SearchMessage(
                    when (search.error) {
                        SearchError.OFFLINE -> stringResource(R.string.search_offline)
                        SearchError.OFF -> stringResource(R.string.search_off)
                        else -> stringResource(R.string.search_server_error, search.errorDetail.orEmpty())
                    },
                )
            }
            SearchStatus.DONE -> Unit
        }
        if (search.status == SearchStatus.DONE && search.onPhone) item(key = "on-phone") {
            Text(stringResource(R.string.search_on_phone), color = LibraryColors.Muted, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }
        if (search.status == SearchStatus.DONE) item(key = "found") { FoundNote(search) }
        if (search.status == SearchStatus.DONE && search.hiddenBySources > 0) item(key = "hidden-sources") {
            val count = search.hiddenBySources
            Text(
                if (search.showingHidden) {
                    pluralStringResource(R.plurals.search_hidden_shown, count, count)
                } else {
                    pluralStringResource(R.plurals.search_hidden_more, count, count)
                },
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.fillMaxWidth()
                    .clickable { actions.showHiddenSources(!search.showingHidden) }
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
        // Last, so landing from the viewer can count them from the end. Kept while a new search runs; the field shows its
        // spinner.
        if (search.status == SearchStatus.DONE || search.status == SearchStatus.SEARCHING) {
            items(search.sets, key = { it.first().id }) { set ->
                val key = set.first().id
                val picked = if (selection.active) key in selection.keys else null
                val pick = { selection.toggle(key) }
                // Results made private or tucked away fade out, and the rest slide up into their place.
                Box(animatedRow(hiding)) {
                    CompositionLocalProvider(LocalScrollSettled provides scrollSettled) {
                        if (set.size == 1) {
                            FoundPhoto(set.first(), places, picked, onClick = { if (selection.active) pick() else actions.openItem(set.first()) }, onLongClick = pick)
                        } else {
                            FoundSet(set, places, landing, picked, onOpen = { if (selection.active) pick() else actions.openItem(it) }, onLongClick = pick)
                        }
                    }
                }
            }
            if (search.loadingMore) item(key = "more") {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LibraryColors.Muted, strokeWidth = 2.dp, modifier = Modifier.size(24.dp))
                }
            }
        }
    }
}

/** How far past the screen [SearchContent] composes rows ahead of the scroll, and keeps them behind it. */
private val SEARCH_AHEAD = 1600.dp
private val SEARCH_BEHIND = 400.dp

/** "36 photos, best first", or "Nothing found", and how far the server has read the library while it still is. */
@Composable
private fun FoundNote(search: SearchState) {
    if (search.items.isEmpty()) {
        SearchMessage(stringResource(R.string.search_nothing))
    } else {
        Text(
            pluralStringResource(R.plurals.search_found, search.items.size, search.items.size),
            color = LibraryColors.Muted,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 4.dp),
        )
    }
    if (search.stillReading) {
        val numbers = NumberFormat.getIntegerInstance()
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            LinearProgressIndicator(
                progress = { search.indexed.toFloat() / search.total.coerceAtLeast(1) },
                color = LibraryColors.Accent,
                trackColor = LibraryColors.OnBackground.copy(alpha = 0.12f),
                modifier = Modifier.width(48.dp),
            )
            Text(
                stringResource(R.string.search_still_reading, numbers.format(search.indexed), numbers.format(search.total)),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
            )
        }
    } else {
        Box(Modifier.height(8.dp))
    }
}

/**
 * A photo found: the screen's full width at its own shape (very tall ones kept to about a screen), and when and where
 * under it. [picked] is null while not picking.
 */
@Composable
private fun FoundPhoto(item: MediaItem, places: Places, picked: Boolean?, onClick: () -> Unit, onLongClick: () -> Unit) {
    Column(Modifier.padding(bottom = 14.dp)) {
        MediaTile(item, onClick, Modifier.fillMaxWidth().aspectRatio(shapeOf(item)), onLongClick = onLongClick, selected = picked)
        Caption(item, places)
    }
}

/** Its shape (width / height), very tall and very wide ones cropped to [MIN_RATIO] and [MAX_RATIO]. */
private fun shapeOf(item: MediaItem): Float =
    (if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 1f).coerceIn(MIN_RATIO, MAX_RATIO)

/** "Thu, Oct 8  9:03 PM", and "📍 Herzliya, Israel" at the end. */
@Composable
private fun Caption(item: MediaItem, places: Places) {
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text(dayLabel(item.day), color = LibraryColors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text(timeLabel(item.takenAtMillis), color = LibraryColors.Muted, fontSize = 14.sp)
        Box(Modifier.weight(1f))
        placeLabel(item, places)?.let { place ->
            Icon(LibraryIcons.Pin, null, tint = LibraryColors.Muted, modifier = Modifier.size(15.dp))
            Text(place, color = LibraryColors.Muted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * Almost identical photos as one, at the best one's shape: swipe for the others ("2 / 4" and dots), with the date, time
 * and place of the one shown. Turns to the photo the viewer closes on. Picked ([picked]) as a whole.
 */
@Composable
private fun FoundSet(
    set: List<MediaItem>,
    places: Places,
    landing: String?,
    picked: Boolean?,
    onOpen: (MediaItem) -> Unit,
    onLongClick: () -> Unit,
) {
    val pager = rememberPagerState { set.size }
    LaunchedEffect(landing) {
        val page = set.indexOfFirst { it.id == landing }
        if (page >= 0 && page != pager.currentPage) pager.scrollToPage(page)
    }
    val shown = set[pager.currentPage.coerceIn(set.indices)]
    Column(Modifier.padding(bottom = 14.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(shapeOf(set.first()))) {
            HorizontalPager(pager, Modifier.fillMaxSize(), pageSpacing = TILE_GAP, beyondViewportPageCount = 1) { page ->
                MediaTile(set[page], onClick = { onOpen(set[page]) }, modifier = Modifier.fillMaxSize(), onLongClick = onLongClick, selected = picked)
            }
            Row(
                Modifier.align(Alignment.TopEnd).padding(10.dp)
                    .background(LibraryColors.BadgeScrim, RoundedCornerShape(50))
                    .padding(horizontal = 9.dp, vertical = 5.dp),
                horizontalArrangement = Arrangement.spacedBy(5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(LibraryIcons.Stack, null, tint = LibraryColors.PhotoText, modifier = Modifier.size(13.dp))
                Text(
                    stringResource(R.string.feed_similar_position, pager.currentPage + 1, set.size),
                    color = LibraryColors.PhotoText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                repeat(set.size.coerceAtMost(MAX_DOTS)) { i ->
                    val current = i == pager.currentPage.coerceAtMost(MAX_DOTS - 1)
                    Box(Modifier.size(if (current) 7.dp else 6.dp).background(Color.White.copy(alpha = if (current) 1f else 0.5f), CircleShape))
                }
            }
        }
        Caption(shown, places)
    }
}

/** A set longer than this shows this many dots, the last one standing for the rest. */
private const val MAX_DOTS = 8

/** "Haifa, Israel"; null until the place is looked up, or for a photo without one. */
private fun placeLabel(item: MediaItem, places: Places): String? {
    val name = places.location(item)?.let(places::name) ?: return null
    return listOfNotNull(name.town, name.country).distinct().joinToString(", ").ifEmpty { null }
}

/** Before anything is searched: "Search by person", named people's faces to swipe through; a tap adds a chip. */
@Composable
private fun SearchFaces(people: List<Person>, onAdd: (Person) -> Unit) {
    Column(Modifier.padding(top = 8.dp)) {
        Text(
            stringResource(R.string.search_by_person),
            color = LibraryColors.OnBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp),
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 6.dp)) {
            people.forEach { person ->
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable { onAdd(person) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FaceAvatar(person.cover, 56.dp)
                    Text(person.name.orEmpty(), color = LibraryColors.OnBackground, fontSize = 13.sp, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

/** The people chosen, as chips with ×, over the results. */
@Composable
private fun PersonChips(people: List<Person>, onRemove: (String) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        people.forEach { person ->
            Row(
                Modifier.height(36.dp).background(LibraryColors.Raised, RoundedCornerShape(50)).padding(start = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FaceAvatar(person.cover, 28.dp)
                Text(person.name ?: "?", color = LibraryColors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 8.dp))
                IconButton(onClick = { onRemove(person.id) }, modifier = Modifier.size(32.dp)) {
                    Icon(LibraryIcons.Close, stringResource(R.string.search_remove_person, person.name ?: "?"), tint = LibraryColors.Muted, modifier = Modifier.size(14.dp))
                }
            }
        }
    }
}

@Composable
private fun SearchMessage(text: String) {
    Text(
        text,
        color = LibraryColors.Muted,
        fontSize = 15.sp,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
    )
}

/** "Search" at the top of the page; the field is at the bottom. */
@Composable
internal fun SearchTopBar(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().statusBarsPadding().padding(start = 16.dp, top = 8.dp, bottom = 8.dp)) {
        Text(
            stringResource(R.string.search_title),
            color = LibraryColors.OnBackground,
            fontSize = 30.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.height(48.dp).semantics { heading() },
        )
    }
}

/**
 * The tab bar turned into the field: the search when the keyboard's search key is pressed, ✕ ([onClose]) back to the
 * tabs, and above it the Filter pill once there are matches to narrow. It opens with the keyboard up when nothing was
 * searched yet.
 */
@Composable
internal fun SearchBottomBar(search: SearchState, actions: SearchActions, onClose: () -> Unit) {
    var filtering by rememberSaveable { mutableStateOf(false) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (search.canFilter) FilterPill(search) { filtering = true }
        SearchField(search, actions.search, actions.clear, onClose)
    }
    if (filtering) FilterSheet(search, actions) { filtering = false }
}

/**
 * While picking, in place of the field: Cancel, "3 selected" (the photos, a set counting each of its own), Mark
 * private (asked first, as the viewer's is) and Tuck away ([PickingBar]). Picking stops once the server took them;
 * offline, it stays for another try.
 */
@Composable
internal fun SearchPickingBar(search: SearchState, selection: FeedSelection, actions: SearchActions) {
    val picked = search.sets.filter { it.first().id in selection.keys }.flatten()
    // The picking bar of Moments too (one bar for both): Mark private, and Tuck away. Search never shows what is tucked.
    PickingBar(
        picked = picked,
        onCancel = selection::clear,
        onDone = { items, tucked ->
            selection.clear()
            if (tucked == true) actions.madeTucked(items) else if (tucked == null) actions.madePrivate(items)
        },
        canBringBack = false,
    )
}

/** Whether there is a year or a town to choose: more than one of either among the matches, or one chosen already. */
private val SearchState.canFilter: Boolean
    get() = (status == SearchStatus.DONE || status == SearchStatus.SEARCHING) && (showsYears || showsTowns)
private val SearchState.showsYears: Boolean get() = yearChips.size > 1 || year != null
private val SearchState.showsTowns: Boolean get() = townChips.size > 1 || town != null

/** "Filter", or "Filter · 2" with a year and a town chosen, dark while any is. */
@Composable
private fun FilterPill(search: SearchState, onClick: () -> Unit) {
    val chosen = listOfNotNull(search.year, search.town).size
    val color = if (chosen > 0) LibraryColors.OnInverse else LibraryColors.OnBackground
    Row(
        Modifier
            .height(36.dp)
            .shadow(8.dp, RoundedCornerShape(50))
            .background(if (chosen > 0) LibraryColors.Inverse else LibraryColors.Raised, RoundedCornerShape(50))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(LibraryIcons.Settings, null, tint = color, modifier = Modifier.size(18.dp))
        Text(
            if (chosen > 0) stringResource(R.string.search_filter_count, chosen) else stringResource(R.string.search_filter),
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/**
 * The years of the matches as a grid and their towns as a list, each with how many; a tap narrows to it at once (the
 * server finds the best of that year or town) and a second drops it. "Show N photos" closes the sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterSheet(search: SearchState, actions: SearchActions, onDone: () -> Unit) {
    // Opened whole, as the tag sheets are: half open, "Show N photos" would be below the screen's edge.
    ModalBottomSheet(
        onDismissRequest = onDone,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LibraryColors.Background,
    ) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.search_filter),
                    color = LibraryColors.OnBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                TextButton(
                    onClick = {
                        actions.chooseYear(null)
                        actions.chooseTown(null)
                    },
                    enabled = search.year != null || search.town != null,
                ) { Text(stringResource(R.string.search_clear)) }
            }
            if (search.showsYears) {
                SheetLabel(stringResource(R.string.search_filter_year))
                val year = search.year?.toString()
                search.yearChips.chunked(YEAR_COLUMNS).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { chip ->
                            YearCell(chip, selected = chip.value == year, Modifier.weight(1f)) {
                                actions.chooseYear(if (chip.value == year) null else chip.value.toInt())
                            }
                        }
                        repeat(YEAR_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
                    }
                }
            }
            if (search.showsTowns) {
                SheetLabel(stringResource(R.string.search_filter_place))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    search.townChips.forEach { chip ->
                        TownRow(chip, selected = chip.value == search.town) {
                            actions.chooseTown(if (chip.value == search.town) null else chip.value)
                        }
                    }
                }
            }
            Button(
                onClick = onDone,
                colors = ButtonDefaults.buttonColors(containerColor = LibraryColors.Accent, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) {
                if (search.status == SearchStatus.SEARCHING) {
                    CircularProgressIndicator(color = Color.White, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text(pluralStringResource(R.plurals.search_filter_show, search.items.size, search.items.size), fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SheetLabel(text: String) {
    Text(text, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun YearCell(chip: SearchChip, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val color = if (selected) LibraryColors.OnInverse else LibraryColors.OnBackground
    Column(
        modifier
            .height(48.dp)
            .background(if (selected) LibraryColors.Inverse else LibraryColors.Raised, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .semantics { this.selected = selected },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(chip.value, color = color, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        Text(chip.count.toString(), color = color.copy(alpha = 0.7f), fontSize = 11.sp)
    }
}

@Composable
private fun TownRow(chip: SearchChip, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .semantics { this.selected = selected }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(chip.value, color = LibraryColors.OnBackground, fontSize = 16.sp)
        Text(chip.count.toString(), color = LibraryColors.Muted, fontSize = 16.sp, modifier = Modifier.weight(1f))
        if (selected) Icon(LibraryIcons.Check, null, tint = LibraryColors.Accent, modifier = Modifier.size(20.dp))
    }
}

/** The next page is asked for when the list is this many rows from its end. */
private const val LOAD_AHEAD_ROWS = 5

/** Years per row of the filter sheet. */
private const val YEAR_COLUMNS = 4

/** The field itself, with its clear button or spinner, and ✕. */
@Composable
private fun SearchField(search: SearchState, onSearch: (String) -> Unit, onClear: () -> Unit, onClose: () -> Unit) {
    var text by rememberSaveable { mutableStateOf(search.query) }
    val keyboard = LocalSoftwareKeyboardController.current
    val focus = remember { FocusRequester() }
    val inPreview = LocalInspectionMode.current
    LaunchedEffect(Unit) { if (search.query.isEmpty() && !inPreview) focus.requestFocus() }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(
            Modifier
                .weight(1f)
                .height(48.dp)
                .shadow(12.dp, RoundedCornerShape(50))
                .background(LibraryColors.Raised, RoundedCornerShape(50))
                .padding(start = 14.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(LibraryIcons.Search, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
            Box(Modifier.weight(1f).padding(start = 8.dp), contentAlignment = Alignment.CenterStart) {
                if (text.isEmpty()) Text(stringResource(R.string.search_field_hint), color = LibraryColors.Muted, fontSize = 17.sp, maxLines = 1)
                BasicTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    textStyle = TextStyle(color = LibraryColors.OnBackground, fontSize = 17.sp),
                    cursorBrush = SolidColor(LibraryColors.Accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = {
                        keyboard?.hide()
                        onSearch(text)
                    }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (search.status == SearchStatus.SEARCHING) {
                CircularProgressIndicator(color = LibraryColors.Muted, strokeWidth = 2.dp, modifier = Modifier.padding(horizontal = 10.dp).size(18.dp))
            } else if (text.isNotEmpty()) {
                IconButton(onClick = {
                    text = ""
                    onClear()
                    focus.requestFocus()
                }) {
                    Icon(LibraryIcons.Close, stringResource(R.string.search_clear), tint = LibraryColors.Muted, modifier = Modifier.size(18.dp))
                }
            }
        }
        RoundBarButton(onClick = {
            keyboard?.hide()
            onClose()
        }) {
            Icon(LibraryIcons.Close, stringResource(R.string.search_close), tint = LibraryColors.OnBackground)
        }
    }
}

/** 🔍 beside the tabs: opens search. */
@Composable
internal fun SearchButton(onClick: () -> Unit) {
    RoundBarButton(onClick) {
        Icon(LibraryIcons.Search, stringResource(R.string.search_open), tint = LibraryColors.OnBackground)
    }
}

/** A round button that floats with the bottom bar, in its colors. */
@Composable
private fun RoundBarButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .shadow(12.dp, CircleShape)
            .background(LibraryColors.Raised.copy(alpha = 0.95f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        IconButton(onClick = onClick, modifier = Modifier.fillMaxSize()) { content() }
    }
}

/** Wide panoramas and tall screenshots are cropped to these shapes (width / height), so a tall one stays about a screen high. */
private const val MIN_RATIO = 0.6f
private const val MAX_RATIO = 3f
