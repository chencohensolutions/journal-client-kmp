# iOS: not built yet

Work done on the Windows machine is Android only (no Xcode there). Each entry says what Android has and what the
SwiftUI side needs, so it can be finished on the Mac. Remove an entry once iOS has it.

Built on iOS on the Mac (Oct 21, 2026): Moments, Explore, backup and its settings, and the where-the-original-is
badge, plus crop, server-only photos and videos, Storage on this phone, Private media and Free up space; then, the
same day on both platforms, writing with a title, paragraph and history, and the storyline in place of stories.

Still missing on iOS:

- **Explore › Places as a list of countries, and a country's page** (Oct 21, 2026) — so the other countries are not
  below every town of the home country. Described in [screens/explore.md](screens/explore.md) (Places and "A
  country's page"). In `ExploreViews.swift`, replace the Places grid with: the home town card; for the home country
  (`CountrySummary.home`), if it has towns besides home, a header "More in %@" + "N places · M photos" (towns
  besides home only) + "See all ›" that opens the country page, over a horizontal `ScrollView` of the town cards
  (140 pt wide); then a heading "Other countries" ("Countries" when no country is home) and one row per other
  country: a 72 pt square of `CountrySummary.covers` (1: whole, 2: side by side, 3: one beside two, 4: two by two;
  2 pt gaps, 12 pt corners), the name ("Other places" for `country == nil`), the first three towns joined with ", "
  plus " +N" for the rest, "640 photos · 2016 – 2025" from `photoCount`, `firstYear`, `lastYear`, and a chevron.
  A country page route (country name or nil) in `LibraryRootView.swift`: the page cover of a town's page with
  `covers.first`, title, "N places · M photos", then the home town card (home country) and the other towns two to a
  row, each opening the town's page; find the country with
  `countriesOf(placesOf(items, places)).first { $0.country == name }`. New strings: "More in %@" (`עוד ב%@`), "See all"
  (`הכול`), "Other countries" (`מדינות נוספות`), "Countries" (`מדינות`), "%@ +%d". Mirror `PlacesList`,
  `HomeCountryTowns`, `CountryRow`, `CoverMosaic` and `CountryScreen` in Android's `ExploreViews.kt`.

- **Backup → "Only on these Wi-Fi networks"** — Android lists the networks backup may run on. iOS reads the
  network's name only with the Access WiFi Information entitlement plus location permission
  (`NEHotspotNetwork.fetchCurrent`), which needs the owner's Apple developer team and a real iPhone to check; the row
  is hidden on iOS until then. See [screens/settings.md](screens/settings.md).

Planned, not built on either platform yet:

- **Hiding one photo or moment** (screen 4 of [plans/hide-whatsapp.md](plans/hide-whatsapp.md)); needs the server.
