package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.chencohensolution.journal.search.SearchError
import com.chencohensolution.journal.search.SearchState
import com.chencohensolution.journal.search.SearchStatus
import java.text.NumberFormat

// The home screen's search: the 🔍 beside the tabs turns the bottom bar into a field, and the page lists the photos the
// server finds for the sentence typed, best first, each across the screen with its date, time and place under it. ✕ goes
// back to the tab, keeping the search for next time. See docs/screens/search.md.

internal class SearchActions(
    val search: (text: String) -> Unit = {},
    val clear: () -> Unit = {},
    /** A photo of the results; the viewer swipes through the results. */
    val openItem: (MediaItem) -> Unit = {},
)

/** The results page, under [SearchTopBar] and above [SearchBottomBar]. */
@Composable
internal fun SearchContent(search: SearchState, places: Places, landing: String?, onOpen: (MediaItem) -> Unit, contentPadding: PaddingValues) {
    val list = rememberLazyListState()
    list.LandOn(landing, search.items.size) { id -> search.items.indexOfFirst { it.id == id } }
    LazyColumn(state = list, contentPadding = contentPadding) {
        when (search.status) {
            SearchStatus.IDLE -> item(key = "hint") { SearchMessage(stringResource(R.string.search_hint)) }
            SearchStatus.SEARCHING -> if (search.items.isEmpty()) item(key = "searching") {
                Box(Modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = LibraryColors.OnBackground)
                }
            }
            SearchStatus.FAILED -> item(key = "failed") {
                SearchMessage(
                    if (search.error == SearchError.OFFLINE) stringResource(R.string.search_offline)
                    else stringResource(R.string.search_server_error, search.errorDetail.orEmpty()),
                )
            }
            SearchStatus.DONE -> item(key = "found") { FoundNote(search) }
        }
        // Last, so landing from the viewer can count them from the end. Kept while a new search runs; the field shows its
        // spinner.
        if (search.status == SearchStatus.DONE || search.status == SearchStatus.SEARCHING) {
            items(search.items, key = { it.id }) { item -> FoundPhoto(item, places, onClick = { onOpen(item) }) }
        }
    }
}

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

/** A photo found: the screen's full width at its own shape (very tall ones kept to about a screen), and when and where under it. */
@Composable
private fun FoundPhoto(item: MediaItem, places: Places, onClick: () -> Unit) {
    val ratio = if (item.width > 0 && item.height > 0) item.width.toFloat() / item.height else 1f
    Column(Modifier.padding(bottom = 14.dp)) {
        MediaTile(item, onClick, Modifier.fillMaxWidth().aspectRatio(ratio.coerceIn(MIN_RATIO, MAX_RATIO)))
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
}

/** "Haifa, Israel"; null until the place is looked up, or for a photo without one. */
private fun placeLabel(item: MediaItem, places: Places): String? {
    val name = places.location(item)?.let(places::name) ?: return null
    return listOfNotNull(name.town, name.country).distinct().joinToString(", ").ifEmpty { null }
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
    Box(modifier.fillMaxWidth().background(LibraryColors.TopFade).statusBarsPadding().padding(start = 16.dp, top = 8.dp, bottom = 24.dp)) {
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
 * The tab bar turned into the field: [onSearch] when the keyboard's search key is pressed, ✕ ([onClose]) back to the
 * tabs. It opens with the keyboard up when nothing was searched yet.
 */
@Composable
internal fun SearchBottomBar(search: SearchState, onSearch: (String) -> Unit, onClear: () -> Unit, onClose: () -> Unit) {
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
