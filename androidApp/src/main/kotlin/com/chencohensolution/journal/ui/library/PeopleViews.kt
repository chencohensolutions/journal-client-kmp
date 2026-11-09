package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.people.Face
import com.chencohensolution.journal.people.FoundFace
import com.chencohensolution.journal.people.PageSort
import com.chencohensolution.journal.people.PeopleState
import com.chencohensolution.journal.people.PeopleStatus
import com.chencohensolution.journal.people.Person
import com.chencohensolution.journal.people.PersonState
import com.chencohensolution.journal.people.PersonStatus
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.stories.StoriesState
import java.text.NumberFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// People: the faces the server found (docs/screens/people.md). The Explore tab's People list, a person's page and
// "Who's this?" (naming a group). The state comes from the shared PeopleController and PersonController.

/** [face] in a circle [size] across: a crop of its photo from the server. */
@Composable
internal fun FaceAvatar(face: Face, size: Dp, modifier: Modifier = Modifier) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image = rememberFace(face, px)
    Box(modifier.size(size).clip(CircleShape).background(LibraryColors.Raised)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** A person or group in a grid: the face, the name (none for a group), and how many photos. */
@Composable
private fun PersonCell(person: Person, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen).padding(vertical = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        FaceAvatar(person.cover, 72.dp)
        if (person.name != null) {
            Text(
                person.name!!,
                color = LibraryColors.OnBackground,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        Text(
            NumberFormat.getIntegerInstance().format(person.files),
            color = LibraryColors.Muted,
            fontSize = 12.sp,
            modifier = Modifier.padding(top = if (person.name == null) 6.dp else 0.dp),
        )
    }
}

/** [people] four to a row. */
@Composable
private fun PeopleGrid(people: List<Person>, onOpen: (Person) -> Unit) {
    Column(Modifier.padding(horizontal = 8.dp)) {
        people.chunked(GRID_COLUMNS).forEach { row ->
            Row {
                row.forEach { person -> PersonCell(person, Modifier.weight(1f)) { onOpen(person) } }
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private const val GRID_COLUMNS = 4

/** Groups shown before "See all". */
private const val GROUPS_SHOWN = 8

/**
 * Explore's People: the named people, then "Who's this?" with the groups to name. While the server is still reading
 * files for faces, a line says how far it is.
 */
@Composable
internal fun PeopleList(people: PeopleState, actions: ExploreActions, contentPadding: PaddingValues) {
    var allGroups by remember { mutableStateOf(false) }
    val list = people.list
    androidx.compose.runtime.LaunchedEffect(Unit) { actions.loadPeople() }
    LazyColumn(state = rememberLazyListState(), contentPadding = contentPadding) {
        if (people.stillReading) item(key = "reading") { ReadingLine(list.read, list.total) }
        when {
            people.status == PeopleStatus.LOADING && list.people.isEmpty() && list.groups.isEmpty() ->
                item(key = "loading") { PeopleMessage(stringResource(R.string.hidden_loading)) }
            people.status == PeopleStatus.UNREACHABLE && list.people.isEmpty() && list.groups.isEmpty() ->
                item(key = "offline") { PeopleRetry(stringResource(R.string.hidden_unreachable), actions.reloadPeople) }
            people.status == PeopleStatus.FAILED && list.people.isEmpty() && list.groups.isEmpty() ->
                item(key = "failed") { PeopleRetry(stringResource(R.string.people_failed, people.error.orEmpty()), actions.reloadPeople) }
            list.people.isEmpty() && list.groups.isEmpty() -> item(key = "empty") { PeopleMessage(stringResource(R.string.people_none_yet)) }
            list.people.isEmpty() -> item(key = "no-names") { PeopleMessage(stringResource(R.string.people_no_names)) }
            else -> item(key = "people") { PeopleGrid(list.people) { actions.openPerson(it.id) } }
        }
        if (list.groups.isNotEmpty()) {
            item(key = "groups-title") {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            stringResource(R.string.people_whos_this),
                            color = LibraryColors.OnBackground,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.semantics { heading() },
                        )
                        Text(stringResource(R.string.people_whos_this_body), color = LibraryColors.Muted, fontSize = 13.sp)
                    }
                    if (list.groups.size > GROUPS_SHOWN) {
                        TextButton(onClick = { allGroups = !allGroups }) {
                            Text(
                                if (allGroups) stringResource(R.string.people_see_fewer) else stringResource(R.string.people_see_all, list.groups.size),
                                color = LibraryColors.OnBackground,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }
            item(key = "groups") {
                PeopleGrid(if (allGroups) list.groups else list.groups.take(GROUPS_SHOWN)) { actions.openPerson(it.id) }
            }
        }
    }
}

/** "Still reading your photos for faces: 12,480 of 14,200", with a bar. */
@Composable
private fun ReadingLine(read: Int, total: Int) {
    val format = NumberFormat.getIntegerInstance()
    Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)) {
        Text(stringResource(R.string.people_reading, format.format(read), format.format(total)), color = LibraryColors.Muted, fontSize = 13.sp)
        LinearProgressIndicator(
            progress = { read.toFloat() / total },
            color = LibraryColors.OnBackground,
            trackColor = LibraryColors.Muted.copy(alpha = 0.2f),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
    }
}

@Composable
private fun PeopleMessage(text: String) {
    Text(text, color = LibraryColors.Muted, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp))
}

@Composable
private fun PeopleRetry(text: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 24.dp)) {
        Text(text, color = LibraryColors.Muted, fontSize = 15.sp)
        TextButton(onClick = onRetry) { Text(stringResource(R.string.hidden_retry), color = LibraryColors.OnBackground) }
    }
}

internal class PersonActions(
    val back: () -> Unit = {},
    val setQuery: (String) -> Unit = {},
    val setSort: (PageSort) -> Unit = {},
    /** The page of [id]: someone they are often with. */
    val openPerson: (id: String) -> Unit = {},
    /** The viewer on [item], swiping through the page's photos in their order. */
    val openItem: (MediaItem) -> Unit = {},
    val rename: (String) -> Unit = {},
    /** This person and [id] (a person or a group) become one, under this person's name. */
    val merge: (id: String) -> Unit = {},
    val forget: () -> Unit = {},
    /** "Not Mom" on a photo: the server never gives that face to them again. */
    val notThem: (MediaItem) -> Unit = {},
    val retry: () -> Unit = {},
    /** Edit faces › Done: the faces marked ✗ aren't them. */
    val notThemFaces: (List<Face>) -> Unit = {},
    /** "Who is it?" after one face was marked. */
    val faces: FaceActions = FaceActions(),
)

/** What a person's page has open over it; previews pass one to [PersonScreen]. */
internal sealed interface PersonSheet {
    data object Menu : PersonSheet
    data object Rename : PersonSheet
    data object Merge : PersonSheet
    data object Forget : PersonSheet
    data class NotThem(val item: MediaItem) : PersonSheet
}

/** The page's photos as the Moments tab's list items (newest first, no words), worked out off the main thread. */
@Composable
private fun rememberPageRows(stories: StoriesState, items: List<MediaItem>): List<FeedRow> {
    val preview = LocalInspectionMode.current
    val rows by produceState(if (preview) stories.feedOf(items).rows() else emptyList(), stories, items) {
        value = withContext(Dispatchers.Default) { stories.feedOf(items).rows() }
    }
    return rows
}

/** Whether a page shows its photos like the Moments tab (by day), or as a grid in their own order (best first, or best match). */
private fun PersonState.byDay(): Boolean = sort == PageSort.NEWEST && query.isBlank()

/**
 * A person's page: their best photo with the name, ⋯ (Rename, Merge with…, Forget), a search of their photos, the
 * sort, who they are often with, then their photos like the Moments tab (or a grid, best first or best match). Press
 * and hold a photo for "Not Mom".
 */
@Composable
internal fun PersonScreen(
    state: PersonState,
    stories: StoriesState,
    /** Merge with…: the other people and groups. */
    others: List<Person>,
    actions: PersonActions,
    initialSheet: PersonSheet? = null,
) {
    val insets = barsRoom()
    var sheet by remember { mutableStateOf(initialSheet) }
    var editing by remember { mutableStateOf(false) }
    var marked by remember { mutableStateOf(emptySet<String>()) }
    var askWho by remember { mutableStateOf<Face?>(null) }
    val name = state.person?.name.orEmpty()
    val rows = rememberPageRows(stories, if (state.byDay()) state.items else emptyList())
    val feedActions = FeedActions(openItem = { _: FeedSection, item: MediaItem -> actions.openItem(item) }, itemMenu = { sheet = PersonSheet.NotThem(it) })
    val cover = remember(state.items) { state.items.filter { it.type == com.chencohensolution.journal.library.MediaType.IMAGE }.maxByOrNull { it.aesthetic ?: 0f } }
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(contentPadding = PaddingValues(bottom = insets.calculateBottomPadding() + 48.dp)) {
            item(key = "cover") {
                val first = state.firstYear
                val last = state.lastYear
                PageCover(
                    name,
                    pluralStringResource(R.plurals.library_story_items, state.person?.files ?: state.items.size, state.person?.files ?: state.items.size) +
                        if (first != null && last != null) " · " + (if (first == last) "$first" else stringResource(R.string.explore_year_range, first, last)) else "",
                    cover,
                )
            }
            item(key = "search") {
                PageSearchField(state.query, stringResource(R.string.person_search_hint, name), actions.setQuery, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))
            }
            item(key = "sort") { SortChips(state.sort, actions.setSort, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
            if (state.together.isNotEmpty() && state.query.isBlank()) {
                item(key = "together") { TogetherRow(name, state.together.map { it.person to it.photos }, actions.openPerson) }
            }
            pageStatus(state, actions.retry)
            if (editing) {
                item(key = "edit-note") {
                    Text(
                        stringResource(R.string.face_edit_body, name),
                        color = LibraryColors.Muted,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    )
                }
                item(key = "edit-faces") {
                    EditFacesGrid(state, marked) { face -> marked = if (face.id in marked) marked - face.id else marked + face.id }
                }
            } else if (state.byDay()) {
                feedItems(rows, feedActions, landing = null, notes = stories.notes)
            } else {
                photoGrid(state.items, actions.openItem) { sheet = PersonSheet.NotThem(it) }
            }
        }
        PageBack(actions.back)
        if (editing) {
            Row(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(LibraryColors.Background).navigationBarRoom()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    pluralStringResource(R.plurals.face_marked_count, marked.size, marked.size, name),
                    color = LibraryColors.OnBackground,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    editing = false
                    marked = emptySet()
                }) { Text(stringResource(R.string.library_cancel), color = LibraryColors.OnBackground) }
                TextButton(
                    onClick = {
                        val faces = state.itemFaces.map { it.second }.filter { it.id in marked }
                        actions.notThemFaces(faces)
                        if (faces.size == 1) askWho = faces.first()
                        editing = false
                        marked = emptySet()
                    },
                    modifier = Modifier.background(LibraryColors.Inverse, RoundedCornerShape(50)),
                ) { Text(stringResource(R.string.face_done), color = LibraryColors.OnInverse, fontWeight = FontWeight.Bold) }
            }
        }
        Box(Modifier.align(Alignment.TopEnd).statusBarRoom().padding(end = 8.dp, top = 8.dp)) {
            IconButton(onClick = { sheet = PersonSheet.Menu }, modifier = Modifier.background(LibraryColors.PillScrim, CircleShape)) {
                Icon(LibraryIcons.More, stringResource(R.string.person_more), tint = LibraryColors.PhotoText)
            }
            DropdownMenu(expanded = sheet == PersonSheet.Menu, onDismissRequest = { sheet = null }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.face_edit)) }, onClick = {
                    sheet = null
                    editing = true
                })
                DropdownMenuItem(text = { Text(stringResource(R.string.person_rename)) }, onClick = { sheet = PersonSheet.Rename })
                DropdownMenuItem(text = { Text(stringResource(R.string.person_merge)) }, onClick = { sheet = PersonSheet.Merge })
                DropdownMenuItem(text = { Text(stringResource(R.string.person_forget), color = LibraryColors.Error) }, onClick = { sheet = PersonSheet.Forget })
            }
        }
    }
    when (val open = sheet) {
        PersonSheet.Rename -> PageSheet({ sheet = null }) {
            RenameSheet(name, onRename = { actions.rename(it); sheet = null }, onCancel = { sheet = null })
        }
        PersonSheet.Merge -> PageSheet({ sheet = null }) {
            MergeSheet(name, others, onMerge = { actions.merge(it.id); sheet = null })
        }
        PersonSheet.Forget -> PageSheet({ sheet = null }) {
            ConfirmSheet(
                title = stringResource(R.string.person_forget_title, name),
                body = stringResource(R.string.person_forget_body),
                confirm = stringResource(R.string.person_forget_confirm),
                destructive = true,
                onConfirm = { actions.forget(); sheet = null },
                onCancel = { sheet = null },
            )
        }
        is PersonSheet.NotThem -> PageSheet({ sheet = null }) {
            ConfirmSheet(
                title = stringResource(R.string.person_not_them_title, name),
                body = stringResource(R.string.person_not_them_body, name),
                confirm = stringResource(R.string.person_not_them, name),
                destructive = false,
                photo = open.item,
                onConfirm = { actions.notThem(open.item); sheet = null },
                onCancel = { sheet = null },
            )
        }
        PersonSheet.Menu, null -> Unit
    }
    askWho?.let { face ->
        // One face marked not them: who is it? (Skip leaves it with no one.)
        PageSheet({ askWho = null }) {
            FaceSheet(FoundFace(face, null), others.filter { !it.isGroup }, actions.faces, onDone = { askWho = null }, initialAsking = true)
        }
    }
}

/** Loading, offline, "Not changed", or nothing found, under a page's header. */
private fun LazyListScope.pageStatus(state: PersonState, retry: () -> Unit) {
    when {
        state.notChanged -> item(key = "not-changed") {
            Text(stringResource(R.string.hidden_not_changed), color = LibraryColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
        state.status == PersonStatus.LOADING && state.items.isEmpty() -> item(key = "loading") { PeopleMessage(stringResource(R.string.hidden_loading)) }
        state.status == PersonStatus.UNREACHABLE && state.items.isEmpty() -> item(key = "offline") { PeopleRetry(stringResource(R.string.hidden_unreachable), retry) }
        state.status == PersonStatus.FAILED && state.items.isEmpty() -> item(key = "failed") { PeopleRetry(stringResource(R.string.people_failed, state.error.orEmpty()), retry) }
        state.status == PersonStatus.READY && state.items.isEmpty() && state.query.isNotBlank() -> item(key = "none") { PeopleMessage(stringResource(R.string.search_nothing)) }
    }
}

/** [items] three to a row, square, in their order; press and hold for [onHold]. */
@OptIn(ExperimentalFoundationApi::class)
internal fun LazyListScope.photoGrid(items: List<MediaItem>, onOpen: (MediaItem) -> Unit, onHold: ((MediaItem) -> Unit)? = null) {
    items(items.chunked(3), key = { row -> "grid:" + row.first().id }) { row ->
        Row(Modifier.padding(horizontal = 2.dp, vertical = 1.dp), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            row.forEach { item ->
                CroppedThumbnail(
                    item,
                    Modifier.weight(1f).aspectRatio(1f).combinedClickable(onLongClick = onHold?.let { { it(item) } }, onClick = { onOpen(item) }),
                )
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

/** A rounded search field on a page: "Search Mom's photos". */
@Composable
internal fun PageSearchField(query: String, hint: String, onQuery: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().height(44.dp).background(LibraryColors.Raised, RoundedCornerShape(50)).padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(LibraryIcons.Search, null, tint = LibraryColors.Muted, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f)) {
            if (query.isEmpty()) Text(hint, color = LibraryColors.Muted, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = query,
                onValueChange = onQuery,
                singleLine = true,
                textStyle = TextStyle(color = LibraryColors.OnBackground, fontSize = 15.sp),
                cursorBrush = SolidColor(LibraryColors.OnBackground),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (query.isNotEmpty()) {
            IconButton(onClick = { onQuery("") }, modifier = Modifier.size(32.dp)) {
                Icon(LibraryIcons.Close, stringResource(R.string.search_clear), tint = LibraryColors.Muted, modifier = Modifier.size(16.dp))
            }
        }
    }
}

/** Newest / Best first, for town, year and person pages. */
@Composable
internal fun SortChips(sort: PageSort, onSort: (PageSort) -> Unit, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExploreChip(stringResource(R.string.page_sort_newest), sort == PageSort.NEWEST) { onSort(PageSort.NEWEST) }
        ExploreChip(stringResource(R.string.page_sort_best), sort == PageSort.BEST) { onSort(PageSort.BEST) }
    }
}

/** "Often with Mom": faces with their names and how many photos they share, to swipe through. */
@Composable
private fun TogetherRow(name: String, together: List<Pair<Person, Int>>, onOpen: (String) -> Unit) {
    Column(Modifier.padding(top = 20.dp)) {
        Text(
            stringResource(R.string.person_together, name),
            color = LibraryColors.OnBackground,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp).semantics { heading() },
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            together.forEach { (other, photos) ->
                Column(
                    Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable(role = Role.Button) { onOpen(other.id) }.padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    FaceAvatar(other.cover, 52.dp)
                    Text(
                        other.name ?: "?",
                        color = LibraryColors.OnBackground,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                    Text("$photos", color = LibraryColors.Muted, fontSize = 12.sp)
                }
            }
        }
        HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f), modifier = Modifier.padding(top = 8.dp))
    }
}

/** A sheet over a page; drawn in place in previews, where a sheet doesn't open. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PageSheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    if (LocalInspectionMode.current) {
        Box(Modifier.fillMaxSize().background(LibraryColors.BadgeScrim), contentAlignment = Alignment.BottomCenter) {
            Column(Modifier.fillMaxWidth().background(LibraryColors.Background, RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)).padding(top = 20.dp)) {
                content()
            }
        }
    } else {
        ModalBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = LibraryColors.Background,
        ) { content() }
    }
}

@Composable
private fun SheetTitle(text: String) {
    Text(
        text,
        color = LibraryColors.OnBackground,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 24.dp).semantics { heading() },
    )
}

/** A question with Cancel and its button; [photo] shown above it ("Not Mom" on a photo). */
@Composable
private fun ConfirmSheet(
    title: String,
    body: String,
    confirm: String,
    destructive: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
    photo: MediaItem? = null,
) {
    Column(Modifier.fillMaxWidth().navigationBarRoom().padding(bottom = 16.dp)) {
        photo?.let {
            CroppedThumbnail(it, Modifier.padding(start = 24.dp, bottom = 14.dp).size(96.dp).clip(RoundedCornerShape(12.dp)))
        }
        SheetTitle(title)
        Text(body, color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.library_cancel), color = LibraryColors.OnBackground) }
            TextButton(onClick = onConfirm) {
                Text(confirm, color = if (destructive) LibraryColors.Error else LibraryColors.OnBackground, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Rename: the name in a field, and Save. */
@Composable
private fun RenameSheet(name: String, onRename: (String) -> Unit, onCancel: () -> Unit) {
    var text by remember { mutableStateOf(name) }
    Column(Modifier.fillMaxWidth().navigationBarRoom().imePadding().padding(bottom = 16.dp)) {
        SheetTitle(stringResource(R.string.person_rename))
        NameField(text, { text = it }, Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp))
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = onCancel) { Text(stringResource(R.string.library_cancel), color = LibraryColors.OnBackground) }
            TextButton(onClick = { onRename(text.trim()) }, enabled = text.isNotBlank()) {
                Text(stringResource(R.string.person_save), color = LibraryColors.OnBackground, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Merge with…: the other people, then the groups; a tap merges that one into this person. */
@Composable
private fun MergeSheet(name: String, others: List<Person>, onMerge: (Person) -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarRoom().padding(bottom = 8.dp)) {
        SheetTitle(stringResource(R.string.person_merge_title, name))
        Text(stringResource(R.string.person_merge_body, name), color = LibraryColors.Muted, fontSize = 14.sp, modifier = Modifier.padding(start = 24.dp, end = 24.dp, top = 6.dp, bottom = 8.dp))
        Column(Modifier.verticalScroll(rememberScrollState())) {
            others.forEach { other -> PersonRow(other, onClick = { onMerge(other) }) }
        }
    }
}

/** A person or group in a list: the face, the name ("Unnamed" for a group) and the count. */
@Composable
private fun PersonRow(person: Person, trailing: String? = null, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FaceAvatar(person.cover, 44.dp)
        Column(Modifier.weight(1f)) {
            Text(
                person.name ?: stringResource(R.string.people_unnamed),
                color = if (person.name == null) LibraryColors.Muted else LibraryColors.OnBackground,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
            )
            Text(pluralStringResource(R.plurals.library_story_items, person.files, person.files), color = LibraryColors.Muted, fontSize = 13.sp)
        }
        trailing?.let { Text(it, color = LibraryColors.OnBackground, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
    }
}

/** A name field on the page's raised color. */
@Composable
private fun NameField(name: String, onName: (String) -> Unit, modifier: Modifier = Modifier, hint: String? = null, onDone: () -> Unit = {}) {
    Box(
        modifier.fillMaxWidth().height(52.dp).background(LibraryColors.Raised, RoundedCornerShape(14.dp))
            .border(1.dp, LibraryColors.Muted.copy(alpha = 0.35f), RoundedCornerShape(14.dp)).padding(horizontal = 16.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (name.isEmpty() && hint != null) Text(hint, color = LibraryColors.Muted, fontSize = 17.sp)
        BasicTextField(
            value = name,
            onValueChange = onName,
            singleLine = true,
            textStyle = TextStyle(color = LibraryColors.OnBackground, fontSize = 17.sp),
            cursorBrush = SolidColor(LibraryColors.OnBackground),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = androidx.compose.foundation.text.KeyboardActions(onDone = { onDone() }),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

internal class GroupActions(
    val back: () -> Unit = {},
    val setName: (String) -> Unit = {},
    /** A new person called [name] (or, when someone has that name already, them: the server matches ignoring case). */
    val name: (String) -> Unit = {},
    /** The group's faces are [id]'s. */
    val join: (id: String) -> Unit = {},
    val openItem: (MediaItem) -> Unit = {},
    val retry: () -> Unit = {},
)

/**
 * "Who's this?": the group's faces, four to a row, and at the bottom a name field. While typing, the people whose
 * names match are listed above it ("Mom · 812 photos · Joins Mom"), then "New person “Mo”".
 */
@Composable
internal fun GroupScreen(state: PersonState, actions: GroupActions) {
    val insets = barsRoom()
    val format = NumberFormat.getIntegerInstance()
    val faces = state.itemFaces
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(contentPadding = PaddingValues(top = insets.calculateTopPadding() + 64.dp, bottom = 200.dp)) {
            item(key = "count") {
                Text(
                    stringResource(R.string.group_photos, format.format(state.person?.files ?: state.items.size)),
                    color = LibraryColors.Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
                )
            }
            pageStatus(state, actions.retry)
            items(faces.chunked(GRID_COLUMNS), key = { row -> row.first().first.id }) { row ->
                Row(Modifier.padding(horizontal = 14.dp, vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    row.forEach { (item, face) ->
                        BoxWithConstraints(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)).clickable { actions.openItem(item) }) {
                            FaceSquare(face, maxWidth)
                        }
                    }
                    repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
        TitleBar(stringResource(R.string.people_whos_this), actions.back, Modifier.align(Alignment.TopCenter))
        NameBar(state, actions, Modifier.align(Alignment.BottomCenter))
    }
}

/** Looks like this › More: the similar photos, three to a row, most alike first. */
@Composable
internal fun SimilarScreen(items: List<MediaItem>, onOpen: (MediaItem) -> Unit, onBack: () -> Unit) {
    val insets = barsRoom()
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(contentPadding = PaddingValues(top = insets.calculateTopPadding() + 64.dp, bottom = insets.calculateBottomPadding() + 24.dp)) {
            if (items.isEmpty()) item(key = "none") { PeopleMessage(stringResource(R.string.search_nothing)) }
            photoGrid(items, onOpen)
        }
        TitleBar(stringResource(R.string.findings_similar), onBack, Modifier.align(Alignment.TopCenter))
    }
}

/** A face filling a square [size] across. */
@Composable
private fun FaceSquare(face: Face, size: Dp) {
    val px = with(LocalDensity.current) { size.roundToPx() }
    val image = rememberFace(face, px)
    Box(Modifier.size(size).background(LibraryColors.Raised)) {
        image?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
    }
}

/** At the bottom of "Who's this?": the people matching the name typed, then the field and Name. */
@Composable
private fun NameBar(state: PersonState, actions: GroupActions, modifier: Modifier) {
    val typed = state.name.trim()
    val ready = typed.isNotEmpty() && !state.saving
    Column(
        modifier
            .fillMaxWidth()
            .background(LibraryColors.Background, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .navigationBarRoom()
            .imePadding()
            .padding(top = 12.dp, bottom = 12.dp),
    ) {
        if (state.notChanged) {
            Text(stringResource(R.string.hidden_not_changed), color = LibraryColors.Warning, fontSize = 13.sp, modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 8.dp))
        }
        if (typed.isNotEmpty()) {
            state.matches.forEach { person ->
                PersonRow(person, trailing = stringResource(R.string.group_joins, person.name.orEmpty())) { if (!state.saving) actions.join(person.id) }
            }
            if (state.matches.none { it.name.equals(typed, ignoreCase = true) }) {
                Row(
                    Modifier.fillMaxWidth().clickable(enabled = ready) { actions.name(typed) }.padding(horizontal = 24.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(Modifier.size(44.dp).border(1.dp, LibraryColors.Muted.copy(alpha = 0.5f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(LibraryIcons.Plus, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(20.dp))
                    }
                    Text(stringResource(R.string.group_new_person, typed), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            HorizontalDivider(color = LibraryColors.Muted.copy(alpha = 0.2f), modifier = Modifier.padding(vertical = 6.dp))
        } else {
            Text(
                stringResource(R.string.group_name_body),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 10.dp),
            )
        }
        Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            NameField(state.name, actions.setName, Modifier.weight(1f), hint = stringResource(R.string.group_name_hint), onDone = { if (ready) actions.name(typed) })
            TextButton(
                onClick = { actions.name(typed) },
                enabled = ready,
                modifier = Modifier.height(52.dp).background(if (ready) LibraryColors.Inverse else LibraryColors.Raised, RoundedCornerShape(14.dp)),
            ) {
                Text(
                    stringResource(R.string.group_name),
                    color = if (ready) LibraryColors.OnInverse else LibraryColors.Muted,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}


/** What a face's sheet can do; see PeopleController's corrections. */
internal class FaceActions(
    /** "This is Mom". */
    val confirm: (Face, personId: String) -> Unit = { _, _ -> },
    /** "Not Mom", and Skip on "Who is it?". */
    val notPerson: (Face, personId: String) -> Unit = { _, _ -> },
    /** "Who is it?" › a person. */
    val move: (Face, personId: String) -> Unit = { _, _ -> },
    /** "Who is it?" › a new name. */
    val name: (Face, name: String) -> Unit = { _, _ -> },
    /** "Open Mom's page". */
    val openPerson: (String) -> Unit = {},
)

/**
 * A face tapped in a photo's details: the face large, then for a named person "This is Mom ✓", "Not Mom" (which asks
 * "Who is it?") and Open Mom's page; for a face no one is named for, "Who is this?" at once: the people to pick, a
 * new name, or Skip.
 */
@Composable
internal fun FaceSheet(found: FoundFace, people: List<Person>, actions: FaceActions, onDone: () -> Unit, initialAsking: Boolean = false) {
    val person = found.person?.takeIf { !it.isGroup }
    var asking by remember(found.face.id) { mutableStateOf(initialAsking || person == null) }
    var newName by remember(found.face.id) { mutableStateOf("") }
    Column(Modifier.fillMaxWidth().navigationBarRoom().imePadding().padding(bottom = 16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        FaceAvatar(found.face, 112.dp)
        if (!asking && person != null) {
            Text(person.name.orEmpty(), color = LibraryColors.OnBackground, fontSize = 20.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 10.dp))
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                SheetRow(LibraryIcons.Check, stringResource(R.string.face_this_is, person.name.orEmpty())) {
                    actions.confirm(found.face, person.id)
                    onDone()
                }
                SheetRow(LibraryIcons.Close, stringResource(R.string.person_not_them, person.name.orEmpty())) { asking = true }
                SheetRow(LibraryIcons.ChevronRight, stringResource(R.string.face_open_page, person.name.orEmpty())) {
                    actions.openPerson(person.id)
                    onDone()
                }
            }
        } else {
            Text(
                stringResource(if (person != null) R.string.face_who_is_it else R.string.face_who_is_this),
                color = LibraryColors.OnBackground,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 10.dp),
            )
            val others = people.filter { it.id != person?.id }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp)) {
                others.forEach { other ->
                    Column(
                        Modifier.width(76.dp).clip(RoundedCornerShape(12.dp)).clickable {
                            actions.move(found.face, other.id)
                            onDone()
                        }.padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        FaceAvatar(other.cover, 52.dp)
                        Text(other.name.orEmpty(), color = LibraryColors.OnBackground, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                    }
                }
            }
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                NameField(newName, { newName = it }, Modifier.weight(1f), hint = stringResource(R.string.face_new_name), onDone = {
                    if (newName.isNotBlank()) {
                        actions.name(found.face, newName)
                        onDone()
                    }
                })
                TextButton(
                    onClick = {
                        actions.name(found.face, newName)
                        onDone()
                    },
                    enabled = newName.isNotBlank(),
                ) { Text(stringResource(R.string.group_name), color = LibraryColors.OnBackground, fontWeight = FontWeight.Bold) }
            }
            TextButton(onClick = {
                // Skip: for a named person, still "Not Mom".
                if (person != null) actions.notPerson(found.face, person.id)
                onDone()
            }, modifier = Modifier.padding(top = 6.dp)) {
                Text(stringResource(if (person != null) R.string.face_skip_not else R.string.face_skip), color = LibraryColors.Muted)
            }
        }
    }
}

@Composable
private fun SheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 24.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(22.dp))
        Text(label, color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * Edit faces on a person's page: their faces, four to a row; a tap marks one ✗ (not them) or takes the mark back.
 * Done sends them all at once; with one marked, it then asks "Who is it?".
 */
@Composable
internal fun EditFacesGrid(state: PersonState, marked: Set<String>, onToggle: (Face) -> Unit) {
    val faces = state.itemFaces
    Column(Modifier.padding(horizontal = 14.dp)) {
        faces.chunked(GRID_COLUMNS).forEach { row ->
            Row(Modifier.padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { (_, face) ->
                    val off = face.id in marked
                    BoxWithConstraints(Modifier.weight(1f).aspectRatio(1f).clip(RoundedCornerShape(10.dp)).clickable { onToggle(face) }) {
                        FaceSquare(face, maxWidth)
                        if (off) {
                            Box(Modifier.fillMaxSize().background(LibraryColors.BadgeScrim), contentAlignment = Alignment.Center) {
                                Box(Modifier.size(34.dp).background(LibraryColors.Error, CircleShape), contentAlignment = Alignment.Center) {
                                    Icon(LibraryIcons.Close, stringResource(R.string.face_marked_not), tint = LibraryColors.PhotoText, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                repeat(GRID_COLUMNS - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}
