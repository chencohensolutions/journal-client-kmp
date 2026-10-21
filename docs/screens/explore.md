# Explore (places, years, map)

The **Explore** tab of the [Journal](journal-home.md) (asked Oct 20, 2026): every photo by the **country** and **town** it was taken
in or by **year**, and on a **map**. Like [Moments](moments.md) nothing is saved for it: it is worked out again from
the library and the places found so far, and it shows the same sources as the Moments filter bar. On Android and, since Oct 21, 2026, on iOS (see [iOS](#ios)).

## Layout

- **Bar under the title:** **Places** / **Years** (filled when chosen), and **Map** at the right.
- **What it shows (Oct 21, 2026):** only what [Settings › Show in the app](settings.md) shows; covers too. The first
  row of Places and Years is a line "Camera · Other · WhatsApp, Screenshots hidden · Change" opening the **Showing**
  sheet: the same switches, each with "N here" ("—" for none; the whole library here and on the map, the page's own
  photos on a town's or year's page), and a note that most WhatsApp photos have no location. Town and year pages show
  the line only when they have hidden photos or more than one source; the map has it at the top of its panel
  (Android) or as a "Camera · Other" pill beside the back button (iOS, where MapLibre's logo is top right).
- **Hidden in a year:** the year says "+2,410 WhatsApp hidden". A year with only hidden photos stays, with "0 photos"
  and "Only WhatsApp photos this year (412) · Show them", which turns those sources on everywhere; it has no covers or
  months and can't be opened.
- **Places**, by country (asked Oct 21, 2026; the other countries as one row each since Oct 21, 2026, so they no
  longer sit below every town of the home country): the home town across the top, its photo with "HOME", the name
  and "4,812 photos · 2017 – 2026" written over it. Under it **More in Israel** with "11 places · 1,708 photos" and
  **See all**, over a row of the home country's other towns to swipe through, most photos first: a photo, the name
  and "312 · 2019 – 2026". Then **Other countries** (**Countries** when no town is home), by number of photos, with
  "Other places" last for towns whose country isn't known: each a row with four of its photos in a square (each
  town's photo, most photos first, then more of the country's photos for a country of fewer towns), its name, its
  first three towns ("Paris, Lyon, Nice +3") and "640 photos · 2016 – 2025". Until a place is named it reads
  "Finding where your photos were taken…" ("None of these photos has a location." when none has one). Tapping a
  town opens its page; a country or See all opens the country's page.
- **A country's page:** its first photo across the top with the name and "6 places · 640 photos", a back button
  over it, then its towns: the home town across the top for the home country, the others two to a row as above.
  Tapping a town opens its page.
- **Years**, newest first: the year, its count, the towns most of it was taken in ("Tel Aviv · Jaffa · Haifa"), four
  photos spread across the year, and its months. Tapping the year opens its page; a month opens it on that month.
- **A town's or a year's page:** its photo across the top with the name and "312 photos · 2019 – 2026" (a year:
  "1,284 photos · Tel Aviv · Jaffa · Haifa"), a back button over it, then a row of choices that narrow it: **All
  years**, "2026 · 21", "2024 · 88"… for a town, **Whole year**, Oct, Sep… for a year. Under them its photos exactly
  as in the Moments tab (day dividers, moments, near-duplicate sets, writing). Tapping a photo opens
  the [viewer](viewer.md) on its row; the choice is kept while the viewer is open. Videos don't play in the list here.
- **Map:** every located photo as a pin; pins near each other are one circle with their count, bigger for more.
  Tapping a circle zooms in until it splits; a circle left at the closest zoom (photos taken in one spot), or a pin,
  opens the viewer on the photos in view. A sheet at the bottom shows the years ("All years", "2019 – 2024"), "312
  photos here" with **Show** (the viewer on them), and a slider over the years with photos to narrow them. The map
  opens fitted to all pins, keeps where it was while the viewer is open, and follows the system theme. No rotating or
  tilting.

## iOS

The same tab, pages and map, from the same shared logic, with these differences:

- **Places is still the older layout** (every country with all its towns two to a row) and there is no country page:
  the list of countries was built on Android only (Oct 21, 2026). See [ios-todo.md](../ios-todo.md).

- The summaries, pages and pins are worked out in a detached task, again whenever the library, the places or the
  sources shown change.
- A town's or a year's page has the system navigation bar (back, and swiping back) over its photo, with a scrim
  behind it. A long press on its photos does nothing, as on Android.
- The map is MapLibre Native iOS (Swift package `maplibre-gl-native-distribution`, pinned to 6.31.0) in a
  `UIViewRepresentable`: the photos are an `MLNShapeSource` clustered at a 48 pt radius up to zoom 16, drawn by three
  layers (circles with the count, and single pins) in the library colors of the scheme shown. The year slider is a
  two-thumb slider of the app's own (SwiftUI has none), stopping at each year, left to right also in Hebrew. The
  credit is MapLibre's logo and its ⓘ button at the top right, which lists the credits the style gives. The
  back button is drawn over the map, so the map has no swipe back.
- Fitting all pins on open stops at the closest zoom out a tall screen allows: photos on several continents can sit
  at the screen's edges.

## Where it comes from

Shared logic in [Explore.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/Explore.kt)
(tested in `ExploreTest`):

- `placesOf` groups photos by their place's town (`PlaceName.town`, see [moments.md](moments.md#where-locations-come-from));
  a town's country is the one most of its photos were named with (`PlaceName.country`). `countriesOf` groups the towns
  by country; a country's `covers` are the four photos of its row and `firstYear`/`lastYear` its years.
  The **home town** is the town most photos within 10 km of home were taken in, home being the 10 km square most
  located photos were taken in (`home`, 20 located photos or more). A town's photo is its newest photo (not a video).
- `yearsOf`: per year its towns (most photos first, up to four) and four photos evenly spread across it.
- `ExploreScope` is what a page or the viewer shows: a town, a year and month, a range of years, an area of the map.
- `StoriesState.feedOf(items)` is the Moments feed of only those photos, with the same grouping, look-alike sets and
  hand-made sets.
- Places are looked up for the whole library in the background, so towns and pins fill in
  while that runs. Names cached before countries were (cache version 1) are looked up again; photo locations are kept.

## The map's tiles

Drawn with [MapLibre](https://maplibre.org) (`org.maplibre.gl:android-sdk`), with map tiles from
[OpenFreeMap](https://openfreemap.org) (styles `liberty` and `dark`, no account or key; chosen by the owner on
Oct 20, 2026). OpenFreeMap sees which areas are looked at and the phone's address, never the photos or where they
were taken: the pins are drawn on the phone. The map needs a network connection; © OpenStreetMap is credited at its
top right.

## Code

Android: [ExploreViews.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/ExploreViews.kt)
(the tab, a town's and a year's page) and
[MapScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/MapScreen.kt).

iOS: [ExploreViews.swift](../../iosApp/iosApp/ExploreViews.swift) (the tab's rows, a town's and a year's page, with
the feed rows of [MomentsFeedViews.swift](../../iosApp/iosApp/MomentsFeedViews.swift)),
[ExploreMapView.swift](../../iosApp/iosApp/ExploreMapView.swift), the tab in
[JournalHomeView.swift](../../iosApp/iosApp/JournalHomeView.swift) and the routes and what the viewer swipes through
in [LibraryRootView.swift](../../iosApp/iosApp/LibraryRootView.swift).
