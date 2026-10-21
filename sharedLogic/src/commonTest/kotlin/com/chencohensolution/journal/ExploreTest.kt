package com.chencohensolution.journal

import com.chencohensolution.journal.library.DayGroup
import com.chencohensolution.journal.library.GeoPoint
import com.chencohensolution.journal.library.LibraryState
import com.chencohensolution.journal.library.LocalDay
import com.chencohensolution.journal.library.MediaItem
import com.chencohensolution.journal.library.MediaSource
import com.chencohensolution.journal.library.MediaType
import com.chencohensolution.journal.places.PlaceName
import com.chencohensolution.journal.places.Places
import com.chencohensolution.journal.places.cellKey
import com.chencohensolution.journal.settings.TimelineGrouping
import com.chencohensolution.journal.stories.ExploreScope
import com.chencohensolution.journal.stories.FeedEntry
import com.chencohensolution.journal.stories.GeoArea
import com.chencohensolution.journal.stories.buildState
import com.chencohensolution.journal.stories.countriesOf
import com.chencohensolution.journal.stories.placesOf
import com.chencohensolution.journal.stories.yearsOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

private val TEL_AVIV = GeoPoint(32.0853, 34.7818)
private val JAFFA = GeoPoint(32.0504, 34.7522)
private val HAIFA = GeoPoint(32.7940, 34.9896)
private val PARIS = GeoPoint(48.8566, 2.3522)
private val LYON = GeoPoint(45.7640, 4.8357)
private val ROME = GeoPoint(41.9028, 12.4964)
private val NOWHERE_KNOWN = GeoPoint(10.0, 10.0)

private val NAMED = Places(
    emptyMap(),
    mapOf(
        cellKey(TEL_AVIV) to PlaceName("Old North", "Tel Aviv"),
        cellKey(JAFFA) to PlaceName("Jaffa Port", "Jaffa"),
        cellKey(HAIFA) to PlaceName(null, "Haifa"),
    ),
)

/** A photo taken on [day], [hour] hours into it (UTC). */
private fun shot(id: String, day: LocalDay, at: GeoPoint? = null, hour: Int = 12, type: MediaType = MediaType.IMAGE) = MediaItem(
    id, type, day.toEpochDay() * 86_400_000 + hour * 3_600_000L, day, null, 4, 3, 100, "$id.jpg",
    source = MediaSource.CAMERA, location = at,
)

/** Newest first, as the library lists them. */
private fun library(vararg items: MediaItem) = items.sortedByDescending { it.takenAtMillis }

class ExploreTest {
    @Test
    fun homeTownComesFirstThenTownsByCount() {
        // Twenty photos at home tell where home is; Haifa has more photos than Jaffa.
        val home = (1..20).map { shot("t$it", LocalDay(2025, 3, it), TEL_AVIV) }
        val haifa = (1..3).map { shot("h$it", LocalDay(2024, 5, it), HAIFA) }
        val jaffa = listOf(shot("j1", LocalDay(2019, 7, 1), JAFFA), shot("j2", LocalDay(2026, 7, 1), JAFFA))
        val unknown = shot("u", LocalDay(2026, 1, 1))
        val places = placesOf(library(*(home + haifa + jaffa + unknown).toTypedArray()), NAMED)

        assertEquals(listOf("Tel Aviv", "Haifa", "Jaffa"), places.map { it.town })
        assertEquals(listOf(true, false, false), places.map { it.home })
        val jaffaPlace = places.last()
        assertEquals(2019 to 2026, jaffaPlace.firstYear to jaffaPlace.lastYear)
        assertEquals(listOf(2026 to 1, 2019 to 1), jaffaPlace.years)
        assertEquals("j2", jaffaPlace.cover.id)
    }

    @Test
    fun withoutEnoughPhotosNoTownIsHome() {
        val places = placesOf(library(shot("a", LocalDay(2026, 1, 1), JAFFA)), NAMED)
        assertEquals(listOf(false), places.map { it.home })
    }

    @Test
    fun countriesHomeFirstThenByCountWithUnknownLast() {
        // Home is in Israel, which has fewer photos than France; Italy has the fewest.
        val home = (1..20).map { shot("t$it", LocalDay(2025, 3, it), TEL_AVIV) }
        val paris = (1..15).map { shot("p$it", LocalDay(2024, 5, it), PARIS) }
        val lyon = (1..10).map { shot("l$it", LocalDay(2024, 6, it), LYON) }
        val rome = (1..2).map { shot("r$it", LocalDay(2023, 6, it), ROME) }
        val unknown = shot("u", LocalDay(2022, 1, 1), NOWHERE_KNOWN)
        val named = Places(
            emptyMap(),
            mapOf(
                cellKey(TEL_AVIV) to PlaceName("Old North", "Tel Aviv", "Israel"),
                cellKey(PARIS) to PlaceName("Le Marais", "Paris", "France"),
                cellKey(LYON) to PlaceName(null, "Lyon", "France"),
                cellKey(ROME) to PlaceName(null, "Rome", "Italy"),
                cellKey(NOWHERE_KNOWN) to PlaceName(null, "Somewhere"),
            ),
        )
        val countries = countriesOf(placesOf(library(*(home + paris + lyon + rome + unknown).toTypedArray()), named))

        assertEquals(listOf("Israel", "France", "Italy", null), countries.map { it.country })
        assertEquals(listOf(true, false, false, false), countries.map { it.home })
        assertEquals(listOf("Paris", "Lyon"), countries[1].places.map { it.town })
        assertEquals(25, countries[1].photoCount)
        assertEquals(listOf("Somewhere"), countries.last().places.map { it.town })
    }

    @Test
    fun yearsListTheirTownsCoversAndMonths() {
        val items = library(
            shot("a", LocalDay(2026, 10, 1), JAFFA),
            shot("b", LocalDay(2026, 10, 2), JAFFA),
            shot("c", LocalDay(2026, 3, 1), HAIFA),
            shot("v", LocalDay(2026, 2, 1), type = MediaType.VIDEO),
            shot("old", LocalDay(2024, 6, 1)),
        )
        val years = yearsOf(items, NAMED)

        assertEquals(listOf(2026, 2024), years.map { it.year })
        val year = years.first()
        assertEquals(listOf("Jaffa", "Haifa"), year.towns)
        assertEquals(listOf(10, 3, 2), year.months)
        // Photos rather than videos, spread across the year.
        assertEquals(listOf("b", "a", "c"), year.covers.map { it.id })
        assertEquals(listOf("old"), years.last().covers.map { it.id })
    }

    @Test
    fun scopesMatchByTownYearMonthAndArea() {
        val jaffaInMay = shot("j", LocalDay(2024, 5, 3), JAFFA)
        assertTrue(ExploreScope(town = "Jaffa", year = 2024, month = 5).matches(jaffaInMay, NAMED))
        assertFalse(ExploreScope(town = "Haifa").matches(jaffaInMay, NAMED))
        assertFalse(ExploreScope(month = 6).matches(jaffaInMay, NAMED))
        assertTrue(ExploreScope(fromYear = 2020, toYear = 2024).matches(jaffaInMay, NAMED))
        assertFalse(ExploreScope(fromYear = 2025).matches(jaffaInMay, NAMED))
        assertTrue(ExploreScope(area = GeoArea(31.0, 34.0, 33.0, 35.0)).matches(jaffaInMay, NAMED))
        assertFalse(ExploreScope(area = GeoArea(32.5, 34.0, 33.0, 35.0)).matches(jaffaInMay, NAMED))
        // A photo without a place is in no town and no area, but in its year.
        val nowhere = shot("n", LocalDay(2024, 5, 3))
        assertFalse(ExploreScope(town = "Jaffa").matches(nowhere, NAMED))
        assertTrue(ExploreScope(year = 2024).matches(nowhere, NAMED))
    }

    @Test
    fun areasCanCrossTheDateLine() {
        val area = GeoArea(south = -20.0, west = 170.0, north = -10.0, east = -170.0)
        assertTrue(GeoPoint(-15.0, 178.0) in area)
        assertTrue(GeoPoint(-15.0, -175.0) in area)
        assertFalse(GeoPoint(-15.0, 0.0) in area)
    }

    @Test
    fun aPagesFeedHasOnlyItsPhotosAndNoTrips() {
        // Three days in Haifa, far from a home of twenty photos: a trip in the Moments tab, but not on Haifa's page.
        val home = (1..20).map { shot("t$it", LocalDay(2025, 3, it), TEL_AVIV) }
        val trip = (1..3).map { shot("h$it", LocalDay(2025, 6, it), HAIFA) }
        val items = library(*(home + trip).toTypedArray())
        val state = buildState(
            LibraryState(days = items.groupBy { it.day }.map { (day, dayItems) -> DayGroup(day, dayItems) }),
            stories = emptyList(),
            notes = emptyMap(),
            error = null,
            grouping = TimelineGrouping(),
            places = NAMED,
        )
        assertTrue(state.feed.entries.any { it is FeedEntry.Trip })

        val page = state.feedOf(items.filter { ExploreScope(town = "Haifa").matches(it, NAMED) })
        assertTrue(page.entries.all { it is FeedEntry.Section })
        assertEquals(listOf("h3", "h2", "h1"), page.entries.flatMap { (it as FeedEntry.Section).section.items }.map { it.id })
    }
}
