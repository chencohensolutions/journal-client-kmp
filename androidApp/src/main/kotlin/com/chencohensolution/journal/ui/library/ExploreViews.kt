package com.chencohensolution.journal.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.chencohensolution.journal.R
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.stories.CountrySummary
import com.chencohensolution.journal.stories.ExploreScope
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.stories.PlaceSummary
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.YearSummary
import com.chencohensolution.journal.stories.countriesOf
import com.chencohensolution.journal.stories.placesOf
import com.chencohensolution.journal.stories.yearsOf
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The Explore tab: every photo by country and town or by year, and the map (see Explore.kt in the shared logic and
// docs/screens/explore.md). A town's or a year's page shows its photos like the Moments tab.

internal enum class ExploreView { PLACES, YEARS }

internal class ExploreActions(
    val openPlace: (town: String) -> Unit = {},
    /** A year's page, on one of its months when [month] is set. */
    val openYear: (year: Int, month: Int?) -> Unit = { _, _ -> },
    val openMap: () -> Unit = {},
)

/** What Explore works from: the library, newest first, of the sources the Moments tab shows. */
internal fun exploreItems(library: LibraryState, stories: StoriesState): List<MediaItem> =
    library.days.flatMap { it.items }.filter { it.source in stories.feed.shown }

/** Under the top bar: Places or Years, and the map. */
@Composable
internal fun ExploreBar(view: ExploreView, onView: (ExploreView) -> Unit, onMap: () -> Unit, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExploreChip(stringResource(R.string.explore_places), view == ExploreView.PLACES) { onView(ExploreView.PLACES) }
        ExploreChip(stringResource(R.string.explore_years), view == ExploreView.YEARS) { onView(ExploreView.YEARS) }
        Spacer(Modifier.weight(1f))
        ExploreChip(stringResource(R.string.explore_map), on = false, icon = LibraryIcons.Map, onClick = onMap)
    }
}

/** A choice like the Moments filter bar's: filled when on, outlined when off. */
@Composable
internal fun ExploreChip(label: String, on: Boolean, icon: ImageVector? = null, onClick: () -> Unit) {
    val content = if (on) LibraryColors.OnInverse else LibraryColors.OnBackground
    Row(
        Modifier
            .height(34.dp)
            .clip(RoundedCornerShape(50))
            .then(
                if (on) {
                    Modifier.background(LibraryColors.Inverse, RoundedCornerShape(50))
                } else {
                    Modifier.border(1.dp, LibraryColors.Muted.copy(alpha = 0.45f), RoundedCornerShape(50))
                },
            )
            .clickable(onClick = onClick)
            .semantics {
                role = Role.Tab
                selected = on
            }
            .padding(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, tint = content, modifier = Modifier.size(16.dp)) }
        Text(label, color = content, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private class ExploreSummaries(val countries: List<CountrySummary>, val years: List<YearSummary>, val anyLocated: Boolean)

/** The Explore tab under its bar: the towns by country, home first, or the years, newest first. */
@Composable
internal fun ExploreContent(
    library: LibraryState,
    stories: StoriesState,
    view: ExploreView,
    actions: ExploreActions,
    contentPadding: PaddingValues,
) {
    val summaries by produceState<ExploreSummaries?>(null, library, stories.places, stories.feed.shown) {
        value = withContext(Dispatchers.Default) {
            val items = exploreItems(library, stories)
            ExploreSummaries(countriesOf(placesOf(items, stories.places)), yearsOf(items, stories.places), items.any { stories.places.location(it) != null })
        }
    }
    val loaded = summaries ?: return
    when (view) {
        ExploreView.PLACES -> PlacesGrid(loaded, actions, contentPadding)
        ExploreView.YEARS -> YearsList(loaded.years, actions, contentPadding)
    }
}

@Composable
private fun PlacesGrid(summaries: ExploreSummaries, actions: ExploreActions, contentPadding: PaddingValues) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = rememberLazyGridState(),
        contentPadding = PaddingValues(
            start = 16.dp,
            end = 16.dp,
            top = contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding(),
        ),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (summaries.countries.isEmpty()) {
            item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
                // Places are looked up in the background; until one is named there is nothing to list.
                ExploreMessage(stringResource(if (summaries.anyLocated || summaries.years.isNotEmpty()) R.string.explore_finding_places else R.string.explore_no_places))
            }
        }
        summaries.countries.forEach { country ->
            item(key = "country:${country.country}", span = { GridItemSpan(maxLineSpan) }) { CountryHeader(country) }
            items(country.places, key = { it.town }, span = { GridItemSpan(if (it.home) maxLineSpan else 1) }) { place ->
                if (place.home) HomeCard(place) { actions.openPlace(place.town) } else PlaceCard(place) { actions.openPlace(place.town) }
            }
        }
    }
}

/** A country above its towns: "France", "3 places · 1,240 photos". */
@Composable
private fun CountryHeader(country: CountrySummary) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            country.country ?: stringResource(R.string.explore_unknown_country),
            color = LibraryColors.OnBackground,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.semantics { heading() },
        )
        Text(
            stringResource(
                R.string.feed_place_time,
                pluralStringResource(R.plurals.explore_country_places, country.places.size, country.places.size),
                pluralStringResource(R.plurals.library_story_items, country.photoCount, country.photoCount),
            ),
            color = LibraryColors.Muted,
            fontSize = 13.sp,
        )
    }
}

/** The home town, across the grid: its photo with the name written over it. */
@Composable
private fun HomeCard(place: PlaceSummary, onOpen: () -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(176.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(LibraryColors.Raised)
            .clickable(onClick = onOpen),
    ) {
        CroppedThumbnail(place.cover, Modifier.fillMaxSize())
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .background(LibraryColors.PhotoFade)
                .padding(start = 14.dp, end = 14.dp, top = 28.dp, bottom = 14.dp),
        ) {
            Text(stringResource(R.string.explore_home).uppercase(), color = LibraryColors.PhotoTextMuted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)
            Text(place.town, color = LibraryColors.PhotoText, fontSize = 22.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(placeLine(place), color = LibraryColors.PhotoTextMuted, fontSize = 13.sp)
        }
    }
}

@Composable
private fun PlaceCard(place: PlaceSummary, onOpen: () -> Unit) {
    Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Box(Modifier.fillMaxWidth().height(132.dp).clip(RoundedCornerShape(12.dp)).background(LibraryColors.Raised)) {
            CroppedThumbnail(place.cover, Modifier.fillMaxSize())
        }
        Text(
            place.town,
            color = LibraryColors.OnBackground,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            stringResource(R.string.feed_place_time, "${place.items.size}", yearRange(place.firstYear, place.lastYear)),
            color = LibraryColors.Muted,
            fontSize = 12.sp,
        )
    }
}

/** "4,812 photos · 2017 – 2026". */
@Composable
private fun placeLine(place: PlaceSummary): String = stringResource(
    R.string.feed_place_time,
    pluralStringResource(R.plurals.library_story_items, place.items.size, place.items.size),
    yearRange(place.firstYear, place.lastYear),
)

/** "2019 – 2026", or "2024" for one year. */
@Composable
private fun yearRange(first: Int, last: Int): String =
    if (first == last) "$first" else stringResource(R.string.explore_year_range, first, last)

@Composable
private fun YearsList(years: List<YearSummary>, actions: ExploreActions, contentPadding: PaddingValues) {
    LazyColumn(state = rememberLazyListState(), contentPadding = contentPadding) {
        items(years, key = { it.year }) { year -> YearCard(year, actions) }
    }
}

/** A year: its count, the towns of most of its photos, a row of its photos, and its months to jump to. */
@Composable
private fun YearCard(year: YearSummary, actions: ExploreActions) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { actions.openYear(year.year, null) }
            .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                "${year.year}",
                color = LibraryColors.OnBackground,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f).semantics { heading() },
            )
            Text(
                pluralStringResource(R.plurals.library_story_items, year.items.size, year.items.size),
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 6.dp),
            )
        }
        if (year.towns.isNotEmpty()) {
            Text(year.towns.joinToString(" · "), color = LibraryColors.Muted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Row(
            Modifier.padding(top = 10.dp).fillMaxWidth().height(86.dp).clip(RoundedCornerShape(12.dp)),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
        ) {
            year.covers.forEach { item ->
                Box(Modifier.weight(1f).fillMaxHeight().background(LibraryColors.Raised)) {
                    CroppedThumbnail(item, Modifier.fillMaxSize())
                }
            }
        }
        Row(
            Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            year.months.forEach { month ->
                Text(
                    monthName(month),
                    color = LibraryColors.OnBackground,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(LibraryColors.Raised)
                        .clickable(role = Role.Button) { actions.openYear(year.year, month) }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }
    }
}

/** "Oct", as the locale writes it. */
internal fun monthName(month: Int): String = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault())

@Composable
private fun ExploreMessage(text: String) {
    Text(text, color = LibraryColors.Muted, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 32.dp))
}

/** A town's or a year's page as worked out: its photos as the Moments tab's list items. */
private class ExplorePage(val all: List<MediaItem>, val rows: List<FeedRow>)

/**
 * A town's page: its photo and name, the years to narrow it to, and its photos like the Moments tab. [onOpenItem]
 * opens the viewer on a row of it, with the [ExploreScope] shown.
 */
@Composable
internal fun PlaceScreen(
    town: String,
    library: LibraryState,
    stories: StoriesState,
    year: Int?,
    onYear: (Int?) -> Unit,
    onOpenItem: (ExploreScope, FeedSection, MediaItem) -> Unit,
    onBack: () -> Unit,
    landing: String?,
) {
    val scope = ExploreScope(town = town, year = year)
    val page = rememberExplorePage(library, stories, ExploreScope(town = town), scope)
    val all = page?.all.orEmpty()
    val subtitle = if (all.isEmpty()) {
        null
    } else {
        stringResource(
            R.string.feed_place_time,
            pluralStringResource(R.plurals.library_story_items, all.size, all.size),
            yearRange(all.last().day.year, all.first().day.year),
        )
    }
    val years = all.groupingBy { it.day.year }.eachCount().toList().sortedByDescending { it.first }
    ExplorePageLayout(
        title = town,
        subtitle = subtitle,
        cover = all.firstOrNull { it.type == MediaType.IMAGE } ?: all.firstOrNull(),
        chips = listOf<Pair<Int?, String>>(null to stringResource(R.string.explore_all_years)) +
            years.map { (y, count) -> y to stringResource(R.string.feed_source_count, "$y", count) },
        selected = year,
        onSelect = onYear,
        rows = page?.rows.orEmpty(),
        stories = stories,
        onOpenItem = { section, item -> onOpenItem(scope, section, item) },
        onBack = onBack,
        landing = landing,
    )
}

/** A year's page: its photos like the Moments tab, narrowed to one of its months. */
@Composable
internal fun YearScreen(
    year: Int,
    library: LibraryState,
    stories: StoriesState,
    month: Int?,
    onMonth: (Int?) -> Unit,
    onOpenItem: (ExploreScope, FeedSection, MediaItem) -> Unit,
    onBack: () -> Unit,
    landing: String?,
) {
    val scope = ExploreScope(year = year, month = month)
    val page = rememberExplorePage(library, stories, ExploreScope(year = year), scope)
    val all = page?.all.orEmpty()
    val towns = all.mapNotNull { item -> stories.places.location(item)?.let(stories.places::name)?.town }
        .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(3).map { it.key }
    val count = pluralStringResource(R.plurals.library_story_items, all.size, all.size)
    ExplorePageLayout(
        title = "$year",
        subtitle = if (all.isEmpty()) null else (listOf(count) + towns).joinToString(" · "),
        cover = all.firstOrNull { it.type == MediaType.IMAGE } ?: all.firstOrNull(),
        chips = listOf<Pair<Int?, String>>(null to stringResource(R.string.explore_whole_year)) +
            all.map { it.day.month }.distinct().map { it to monthName(it) },
        selected = month,
        onSelect = onMonth,
        rows = page?.rows.orEmpty(),
        stories = stories,
        onOpenItem = { section, item -> onOpenItem(scope, section, item) },
        onBack = onBack,
        landing = landing,
    )
}

/** Everything in [whole], and the feed of what [shown] takes in, worked out off the main thread. */
@Composable
private fun rememberExplorePage(library: LibraryState, stories: StoriesState, whole: ExploreScope, shown: ExploreScope): ExplorePage? {
    val page by produceState<ExplorePage?>(null, library, stories, shown) {
        value = withContext(Dispatchers.Default) {
            val all = exploreItems(library, stories).filter { whole.matches(it, stories.places) }
            ExplorePage(all, stories.feedOf(all.filter { shown.matches(it, stories.places) }).rows())
        }
    }
    return page
}

@Composable
private fun <T> ExplorePageLayout(
    title: String,
    subtitle: String?,
    cover: MediaItem?,
    chips: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    rows: List<FeedRow>,
    stories: StoriesState,
    onOpenItem: (FeedSection, MediaItem) -> Unit,
    onBack: () -> Unit,
    landing: String?,
) {
    val insets = WindowInsets.systemBars.asPaddingValues()
    val list = rememberLazyListState()
    list.LandOn(landing, rows.size) { rows.rowOf(it) }
    val actions = FeedActions(openItem = onOpenItem)
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = insets.calculateBottomPadding() + 48.dp)) {
            item(key = "cover") {
                Box(Modifier.fillMaxWidth().height(260.dp + insets.calculateTopPadding()).background(LibraryColors.Raised)) {
                    cover?.let { CroppedThumbnail(it, Modifier.fillMaxSize()) }
                    Column(
                        Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .background(LibraryColors.PhotoFade)
                            .padding(start = 16.dp, end = 16.dp, top = 40.dp, bottom = 16.dp),
                    ) {
                        Text(title, color = LibraryColors.PhotoText, fontSize = 28.sp, fontWeight = FontWeight.Bold, modifier = Modifier.semantics { heading() })
                        subtitle?.let { Text(it, color = LibraryColors.PhotoTextMuted, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                    }
                }
            }
            item(key = "chips") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chips.forEach { (value, label) -> ExploreChip(label, value == selected) { onSelect(value) } }
                }
            }
            feedItems(rows, actions, landing, notes = stories.notes)
        }
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .statusBarsPadding()
                .padding(start = 8.dp, top = 8.dp)
                .background(LibraryColors.PillScrim, CircleShape),
        ) {
            Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.PhotoText)
        }
    }
}
