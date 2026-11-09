package com.chencohensolution.journal.ui.library

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalInspectionMode
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
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.people.PageSort
import com.chencohensolution.journal.people.PeopleState
import com.chencohensolution.journal.stories.bestFirst
import com.chencohensolution.journal.stories.bestLooking
import com.chencohensolution.journal.stories.CountrySummary
import com.chencohensolution.journal.stories.ExploreScope
import com.chencohensolution.journal.stories.ExploreSummary
import com.chencohensolution.journal.stories.FeedSection
import com.chencohensolution.journal.stories.PlaceSummary
import com.chencohensolution.journal.stories.StoriesState
import com.chencohensolution.journal.stories.YearSummary
import com.chencohensolution.journal.stories.sourceCounts
import java.time.Month
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// The Explore tab: every photo by country and town or by year, and the map (see Explore.kt in the shared logic and
// docs/screens/explore.md). A town's or a year's page shows its photos like the Moments tab.

internal enum class ExploreView { PLACES, YEARS, PEOPLE }

internal class ExploreActions(
    val openPlace: (town: String) -> Unit = {},
    /** A country's page of towns; null for the towns whose country isn't known. */
    val openCountry: (country: String?) -> Unit = {},
    /** A year's page, on one of its months when [month] is set. */
    val openYear: (year: Int, month: Int?) -> Unit = { _, _ -> },
    val openMap: () -> Unit = {},
    /** A switch of the "Showing" sheet, or a year's "Show them": the setting changes everywhere. */
    val toggleSource: (MediaSource) -> Unit = {},
    /** A person's page from People; for a group of faces no one has named, "Who's this?". */
    val openPerson: (id: String) -> Unit = {},
    /** People shown: load the list (again, so new faces come in). */
    val loadPeople: () -> Unit = {},
    /** People couldn't be loaded: Try again. */
    val reloadPeople: () -> Unit = {},
)

/** Under the top bar: Places, Years or (when the server has face search) People, and the map. */
@Composable
internal fun ExploreBar(view: ExploreView, onView: (ExploreView) -> Unit, onMap: () -> Unit, modifier: Modifier = Modifier, people: Boolean = false) {
    Row(modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        ExploreChip(stringResource(R.string.explore_places), view == ExploreView.PLACES) { onView(ExploreView.PLACES) }
        ExploreChip(stringResource(R.string.explore_years), view == ExploreView.YEARS) { onView(ExploreView.YEARS) }
        if (people) ExploreChip(stringResource(R.string.explore_people), view == ExploreView.PEOPLE) { onView(ExploreView.PEOPLE) }
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

/** The Explore tab under its bar: the towns by country, home first, or the years, newest first. */
@Composable
internal fun ExploreContent(
    library: LibraryState,
    stories: StoriesState,
    view: ExploreView,
    actions: ExploreActions,
    contentPadding: PaddingValues,
    people: PeopleState? = null,
) {
    if (view == ExploreView.PEOPLE) {
        PeopleList(people ?: PeopleState.NONE, actions, contentPadding)
        return
    }
    // Worked out at once in previews, where the background work never lands in the image; in the app from the database.
    val preview = LocalInspectionMode.current
    val reads = LocalExploreReads.current
    val summaries by produceState(if (preview) ItemsExploreReads.summaryOf(library, stories) else null, library, stories.places, stories.shownSources, stories.home) {
        value = reads.summary(library, stories)
    }
    val loaded = summaries ?: return
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val shown = stories.shownSources.shownAmong(loaded.counts)
    val line: @Composable () -> Unit = {
        ShowingLine(shown, presentSources(loaded.counts, shown), onChange = { sheetOpen = true }, Modifier.padding(bottom = 4.dp))
    }
    when (view) {
        ExploreView.PLACES -> PlacesList(loaded, actions, contentPadding, line)
        ExploreView.YEARS -> YearsList(loaded.years, shown, actions, contentPadding, line)
        ExploreView.PEOPLE -> Unit
    }
    if (sheetOpen) ShowingSheet(loaded.counts, onDismiss = { sheetOpen = false }, whatsAppNote = true)
}

/**
 * Places: the home town and a row of the home country's other towns, then every other country as one row, so all of
 * them fit on a screen or two. A country opens its own page of towns.
 */
@Composable
private fun PlacesList(summaries: ExploreSummary, actions: ExploreActions, contentPadding: PaddingValues, line: @Composable () -> Unit) {
    val homeCountry = summaries.countries.firstOrNull { it.home }
    val others = summaries.countries.filter { it !== homeCountry }
    LazyColumn(state = rememberLazyListState(), contentPadding = contentPadding) {
        item(key = "showing") { Box(Modifier.padding(horizontal = 16.dp)) { line() } }
        if (summaries.countries.isEmpty()) {
            item(key = "empty") {
                // Places are looked up in the background; until one is named there is nothing to list.
                Box(Modifier.padding(horizontal = 16.dp)) {
                    ExploreMessage(stringResource(if (summaries.anyLocated || summaries.years.isNotEmpty()) R.string.explore_finding_places else R.string.explore_no_places))
                }
            }
        }
        homeCountry?.let { country ->
            val (home, towns) = country.places.partition { it.home }
            home.firstOrNull()?.let { place ->
                item(key = "home") {
                    Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) { HomeCard(place) { actions.openPlace(place.town) } }
                }
            }
            if (towns.isNotEmpty()) {
                item(key = "home-country") { HomeCountryTowns(country, towns, actions) }
            }
        }
        if (others.isNotEmpty()) {
            item(key = "countries") {
                Text(
                    stringResource(if (homeCountry != null) R.string.explore_other_countries else R.string.explore_countries),
                    color = LibraryColors.OnBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp).semantics { heading() },
                )
            }
            items(others, key = { "country:${it.country}" }) { country -> CountryRow(country) { actions.openCountry(country.country) } }
        }
    }
}

/** "More in Israel", "11 places · 1,708 photos" and See all, over a row of its towns besides home to swipe through. */
@Composable
private fun HomeCountryTowns(country: CountrySummary, towns: List<PlaceSummary>, actions: ExploreActions) {
    Column(Modifier.padding(top = 20.dp)) {
        Row(
            Modifier.fillMaxWidth().clickable(role = Role.Button) { actions.openCountry(country.country) }.padding(start = 16.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.explore_more_in, countryName(country)),
                    color = LibraryColors.OnBackground,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.semantics { heading() },
                )
                val photos = towns.sumOf { it.count }
                Text(
                    stringResource(
                        R.string.feed_place_time,
                        pluralStringResource(R.plurals.explore_country_places, towns.size, towns.size),
                        pluralStringResource(R.plurals.library_story_items, photos, photos),
                    ),
                    color = LibraryColors.Muted,
                    fontSize = 13.sp,
                )
            }
            Row(Modifier.height(44.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.explore_see_all), color = LibraryColors.OnBackground, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Icon(LibraryIcons.ChevronRight, null, tint = LibraryColors.OnBackground, modifier = Modifier.size(18.dp))
            }
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.padding(top = 10.dp),
        ) {
            items(towns, key = { it.town }) { place -> PlaceCard(place, Modifier.width(140.dp)) { actions.openPlace(place.town) } }
        }
    }
}

/** A country in the list: a few of its photos, its name, its main towns ("Paris, Lyon, Nice +3"), its count and years. */
@Composable
private fun CountryRow(country: CountrySummary, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CoverMosaic(country.covers, Modifier.size(72.dp).clip(RoundedCornerShape(12.dp)))
        Column(Modifier.weight(1f)) {
            Text(countryName(country), color = LibraryColors.OnBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val names = country.places.take(COUNTRY_ROW_TOWNS).joinToString(", ") { it.town }
            val more = country.places.size - COUNTRY_ROW_TOWNS
            Text(
                if (more > 0) stringResource(R.string.explore_towns_more, names, more) else names,
                color = LibraryColors.Muted,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                stringResource(
                    R.string.feed_place_time,
                    pluralStringResource(R.plurals.library_story_items, country.photoCount, country.photoCount),
                    yearRange(country.firstYear, country.lastYear),
                ),
                color = LibraryColors.Muted,
                fontSize = 12.sp,
            )
        }
        Icon(LibraryIcons.ChevronRight, null, tint = LibraryColors.Muted, modifier = Modifier.size(20.dp))
    }
}

/** The towns a country's row names before "+3". */
private const val COUNTRY_ROW_TOWNS = 3

/** Up to four photos in a square: one whole, two side by side, one beside two, or two by two. */
@Composable
private fun CoverMosaic(covers: List<MediaItem>, modifier: Modifier) {
    val columns = if (covers.size <= 2) covers.map { listOf(it) } else listOf(covers.take(covers.size - 2), covers.drop(covers.size - 2))
    Row(modifier.background(LibraryColors.Raised), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        columns.forEach { column ->
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                column.forEach { item -> CroppedThumbnail(item, Modifier.weight(1f).fillMaxWidth()) }
            }
        }
    }
}

@Composable
private fun countryName(country: CountrySummary): String = country.country ?: stringResource(R.string.explore_unknown_country)

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
private fun PlaceCard(place: PlaceSummary, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onOpen), verticalArrangement = Arrangement.spacedBy(2.dp)) {
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
            stringResource(R.string.feed_place_time, "${place.count}", yearRange(place.firstYear, place.lastYear)),
            color = LibraryColors.Muted,
            fontSize = 12.sp,
        )
    }
}

/** "4,812 photos · 2017 – 2026". */
@Composable
private fun placeLine(place: PlaceSummary): String = stringResource(
    R.string.feed_place_time,
    pluralStringResource(R.plurals.library_story_items, place.count, place.count),
    yearRange(place.firstYear, place.lastYear),
)

/** "2019 – 2026", or "2024" for one year. */
@Composable
private fun yearRange(first: Int, last: Int): String =
    if (first == last) "$first" else stringResource(R.string.explore_year_range, first, last)

@Composable
private fun YearsList(years: List<YearSummary>, shown: Set<MediaSource>, actions: ExploreActions, contentPadding: PaddingValues, line: @Composable () -> Unit) {
    LazyColumn(state = rememberLazyListState(), contentPadding = contentPadding) {
        item(key = "showing") { Box(Modifier.padding(horizontal = 16.dp)) { line() } }
        items(years, key = { it.year }) { year -> YearCard(year, shown, actions) }
    }
}

/**
 * A year: its count and how many it hides ("+2,410 WhatsApp hidden"), the towns of most of its photos, a row of its
 * photos, and its months to jump to. A year with only hidden photos says so, with "Show them".
 */
@Composable
private fun YearCard(year: YearSummary, shown: Set<MediaSource>, actions: ExploreActions) {
    val onlyHidden = year.count == 0
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = !onlyHidden) { actions.openYear(year.year, null) }
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
            Column(Modifier.padding(bottom = 6.dp), horizontalAlignment = Alignment.End) {
                Text(pluralStringResource(R.plurals.library_story_items, year.count, year.count), color = LibraryColors.Muted, fontSize = 13.sp)
                if (year.hidden.isNotEmpty() && !onlyHidden) {
                    Text(
                        stringResource(R.string.explore_year_hidden, sourceCountsText(year.hidden)),
                        color = LibraryColors.Muted.copy(alpha = 0.75f),
                        fontSize = 12.sp,
                        maxLines = 1,
                    )
                }
            }
        }
        if (onlyHidden) {
            OnlyHiddenYear(year) { year.hidden.keys.filter { it !in shown }.forEach(actions.toggleSource) }
            return@Column
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

/** A year whose photos are all hidden: "Only WhatsApp photos this year (412)" and "Show them", in a dashed box. */
@Composable
private fun OnlyHiddenYear(year: YearSummary, onShow: () -> Unit) {
    val dash = LibraryColors.Muted.copy(alpha = 0.5f)
    Row(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .drawBehind {
                val radius = 12.dp.toPx()
                drawRoundRect(
                    color = dash,
                    cornerRadius = CornerRadius(radius),
                    style = Stroke(width = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx()))),
                )
            }
            .padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            stringResource(R.string.explore_only_hidden, year.hidden.keys.map { sourceName(it) }.joinToString(", "), year.hidden.values.sum()),
            color = LibraryColors.Muted,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onShow) {
            Text(stringResource(R.string.explore_show_them), color = LibraryColors.OnBackground, fontWeight = FontWeight.Bold)
        }
    }
}

/** "Oct", as the locale writes it. */
internal fun monthName(month: Int): String = Month.of(month).getDisplayName(TextStyle.SHORT, Locale.getDefault())

@Composable
private fun ExploreMessage(text: String) {
    Text(text, color = LibraryColors.Muted, fontSize = 15.sp, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 32.dp))
}

/**
 * A town's or a year's page as worked out: its photos as the Moments tab's list items, and its photos per source
 * shown or not ([counts]), for the "Showing" sheet.
 */
private class ExplorePage(val all: List<MediaItem>, val rows: List<FeedRow>, val counts: Map<MediaSource, Int>, val shown: List<MediaItem>)

/**
 * A town's page: its photo and name, the years to narrow it to, and its photos like the Moments tab. [onOpenItem]
 * opens the viewer on a row of it, with the [ExploreScope] shown and the page's photos of it (for Best first).
 */
@Composable
internal fun PlaceScreen(
    town: String,
    library: LibraryState,
    stories: StoriesState,
    year: Int?,
    onYear: (Int?) -> Unit,
    onOpenItem: (ExploreScope, FeedSection?, MediaItem, List<MediaItem>) -> Unit,
    onBack: () -> Unit,
    landing: String?,
    onToggleSource: (MediaSource) -> Unit = {},
    sort: PageSort = PageSort.NEWEST,
    onSort: ((PageSort) -> Unit)? = null,
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
        cover = bestLooking(all.filter { it.type == MediaType.IMAGE }) ?: all.firstOrNull { it.type == MediaType.IMAGE } ?: all.firstOrNull(),
        chips = listOf<Pair<Int?, String>>(null to stringResource(R.string.explore_all_years)) +
            years.map { (y, count) -> y to stringResource(R.string.feed_source_count, "$y", count) },
        selected = year,
        onSelect = onYear,
        rows = page?.rows.orEmpty(),
        stories = stories,
        onOpenItem = { section, item -> onOpenItem(scope, section, item, page?.shown.orEmpty()) },
        onBack = onBack,
        landing = landing,
        counts = page?.counts,
        onToggleSource = onToggleSource,
        sort = sort,
        onSort = onSort,
        pagePhotos = page?.shown.orEmpty(),
        onOpenShown = { item -> onOpenItem(scope, null, item, page?.shown.orEmpty()) },
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
    onOpenItem: (ExploreScope, FeedSection?, MediaItem, List<MediaItem>) -> Unit,
    onBack: () -> Unit,
    landing: String?,
    onToggleSource: (MediaSource) -> Unit = {},
    sort: PageSort = PageSort.NEWEST,
    onSort: ((PageSort) -> Unit)? = null,
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
        cover = bestLooking(all.filter { it.type == MediaType.IMAGE }) ?: all.firstOrNull { it.type == MediaType.IMAGE } ?: all.firstOrNull(),
        chips = listOf<Pair<Int?, String>>(null to stringResource(R.string.explore_whole_year)) +
            all.map { it.day.month }.distinct().map { it to monthName(it) },
        selected = month,
        onSelect = onMonth,
        rows = page?.rows.orEmpty(),
        stories = stories,
        onOpenItem = { section, item -> onOpenItem(scope, section, item, page?.shown.orEmpty()) },
        onBack = onBack,
        landing = landing,
        counts = page?.counts,
        onToggleSource = onToggleSource,
        sort = sort,
        onSort = onSort,
        pagePhotos = page?.shown.orEmpty(),
        onOpenShown = { item -> onOpenItem(scope, null, item, page?.shown.orEmpty()) },
    )
}

/**
 * Everything in [whole] (read from the database in the app, docs/plans/library-on-disk.md step 6c), and the feed of
 * what [shown] takes in, worked out off the main thread.
 */
@Composable
private fun rememberExplorePage(library: LibraryState, stories: StoriesState, whole: ExploreScope, shown: ExploreScope): ExplorePage? {
    val reads = LocalExploreReads.current
    val page by produceState<ExplorePage?>(null, library, stories, shown) {
        // What is tucked away never shows in Explore.
        val everything = reads.scope(library, stories, whole).filterNot { it.isTucked }
        value = withContext(Dispatchers.Default) {
            val all = everything.filter(stories::shows)
            val inScope = all.filter { shown.matches(it, stories.places) }
            ExplorePage(all, stories.feedOf(inScope).rows(), sourceCounts(everything), inScope)
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
    counts: Map<MediaSource, Int>?,
    onToggleSource: (MediaSource) -> Unit,
    sort: PageSort = PageSort.NEWEST,
    onSort: ((PageSort) -> Unit)? = null,
    /** The page's photos (of the chosen year or month), for Best first. */
    pagePhotos: List<MediaItem> = emptyList(),
    onOpenShown: (MediaItem) -> Unit = {},
) {
    val insets = barsRoom()
    var sheetOpen by rememberSaveable { mutableStateOf(false) }
    val shown = stories.shownSources.shownAmong(counts)
    val list = rememberLazyListState()
    list.LandOn(landing, rows.size) { rows.rowOf(it) }
    val actions = FeedActions(openItem = onOpenItem)
    // A long press picks photos, to mark private or tuck away (the bar of Moments); back stops picking.
    val selection = remember { FeedSelection() }
    BackHandler(enabled = selection.active) { selection.clear() }
    LaunchedEffect(sort) { selection.clear() }
    val hiding = rowsHiding(remember(rows) { rows.map { it.key } })
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(state = list, contentPadding = PaddingValues(bottom = insets.calculateBottomPadding() + 48.dp)) {
            item(key = "cover") { PageCover(title, subtitle, cover) }
            item(key = "chips") {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    chips.forEach { (value, label) -> ExploreChip(label, value == selected) { onSelect(value) } }
                }
            }
            // Only when the page has photos the setting hides, or of more than one source.
            if (counts != null && presentSources(counts, emptySet()).let { present -> present.size > 1 || present.any { it !in shown } }) {
                item(key = "showing") {
                    ShowingLine(shown, presentSources(counts, emptySet()), onChange = { sheetOpen = true }, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp))
                }
            }
            if (onSort != null) item(key = "sort") { SortChips(sort, onSort, Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp)) }
            if (sort == PageSort.BEST) photoGrid(bestFirst(pagePhotos), onOpenShown) else feedItems(rows, actions, landing, notes = stories.notes, selection = selection, hiding = hiding)
        }
        PageBack(onBack)
        // Slides up as picking starts, down as it ends.
        AnimatedVisibility(
            selection.active,
            Modifier.align(Alignment.BottomCenter).navigationBarRoom().padding(bottom = 16.dp, start = 12.dp, end = 12.dp),
            enter = barIn,
            exit = barOut,
            label = "picking bar",
        ) {
            PickingBar(
                picked = rows.picked(selection),
                onCancel = selection::clear,
                onDone = { _, _ -> selection.clear() },
            )
        }
    }
    if (sheetOpen && counts != null) ShowingSheet(counts, onDismiss = { sheetOpen = false }, whatsAppNote = true)
}

/** Across the top of a page, under the status bar: its photo with the title and [subtitle] written over it. */
@Composable
internal fun PageCover(title: String, subtitle: String?, cover: MediaItem?) {
    val insets = barsRoom()
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

/** The back button over a page's photo. */
@Composable
internal fun PageBack(onBack: () -> Unit) {
    IconButton(
        onClick = onBack,
        modifier = Modifier
            .statusBarRoom()
            .padding(start = 8.dp, top = 8.dp)
            .background(LibraryColors.PillScrim, CircleShape),
    ) {
        Icon(LibraryIcons.Back, stringResource(R.string.library_back), tint = LibraryColors.PhotoText)
    }
}

/**
 * A country's page: its photo and name, then its towns as on the Places list, the home town across the top and the
 * others two to a row. [country] is null for the towns whose country isn't known ("Other places").
 */
@Composable
internal fun CountryScreen(
    country: String?,
    library: LibraryState,
    stories: StoriesState,
    onOpenPlace: (town: String) -> Unit,
    onBack: () -> Unit,
) {
    val preview = LocalInspectionMode.current
    val reads = LocalExploreReads.current
    fun find(summary: ExploreSummary) = summary.countries.firstOrNull { it.country == country }
    val summary by produceState(if (preview) find(ItemsExploreReads.summaryOf(library, stories)) else null, library, stories.places, stories.shownSources, stories.home) {
        value = find(reads.summary(library, stories))
    }
    val insets = barsRoom()
    val (home, towns) = summary?.places.orEmpty().partition { it.home }
    Box(Modifier.fillMaxSize().background(LibraryColors.Background)) {
        LazyColumn(
            contentPadding = PaddingValues(bottom = insets.calculateBottomPadding() + 48.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "cover") {
                PageCover(
                    country ?: stringResource(R.string.explore_unknown_country),
                    summary?.let {
                        stringResource(
                            R.string.feed_place_time,
                            pluralStringResource(R.plurals.explore_country_places, it.places.size, it.places.size),
                            pluralStringResource(R.plurals.library_story_items, it.photoCount, it.photoCount),
                        )
                    },
                    summary?.covers?.firstOrNull(),
                )
            }
            items(home, key = { it.town }) { place ->
                Box(Modifier.padding(horizontal = 16.dp)) { HomeCard(place) { onOpenPlace(place.town) } }
            }
            items(towns.chunked(2), key = { it.first().town }) { pair ->
                Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { place -> PlaceCard(place, Modifier.weight(1f)) { onOpenPlace(place.town) } }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
        PageBack(onBack)
    }
}
