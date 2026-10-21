# iOS: not built yet

Work done on the Windows machine is Android only (no Xcode there). Each entry says what Android has and what the
SwiftUI side needs, so it can be finished on the Mac. Remove an entry once iOS has it.

Built on iOS on the Mac (Oct 21, 2026): Moments, Explore, backup and its settings, and the where-the-original-is
badge, plus crop, server-only photos and videos, Storage on this phone, Private media and Free up space; then, the
same day on both platforms, writing with a title, paragraph and history, and the storyline in place of stories.

Still missing on iOS:

- **Zoom in the viewer** (Oct 21, 2026) — described in [screens/viewer.md](screens/viewer.md) (Zoom). In
  `MediaViewerView.swift`: double-taps step fitted → fill → original size → fitted around the tap; pinch at any zoom,
  snapping within 15% of a step on release; zoomed in, one finger pans past the touch slop and the `TabView` must not
  page (e.g. `.scrollDisabled` or a paging `ScrollView` replacing it); a tap still toggles the bars. No zoom button.
  Scales are relative to the fitted photo: fill = max(screen w / fitted w, screen h / fitted h); original = longer
  side of the photo's own pixels (`item.width`/`height`) / longer fitted side; maximum = max(2 × original, fill, 1).
  A minimap top right under the bar (96 pt longer side, part on screen outlined, the rest dimmed) while moving, 1.5 s
  after and with the bars. Each photo opens at its note's `zoom` (shared: `PhotoNote.zoom`,
  `StoriesController.setZoom(itemId, zoom)`, called on leaving a photo with the part on screen or nil when fitted);
  restore by gliding, once the zoom transition and paging have settled, from the fitted photo (or its crop) to the
  part: scale interpolated geometrically, the middle linearly, 0.55 s ease-in-out. A cropped photo shows whole
  while zoomed. On first zoom-in, decode the original at full size (ImageIO, cap around 24 MP), for server photos
  through `IosServerFiles.viewer` with a large `maxSide`. Then the full-detail parts: after 150 ms still, decode the
  visible part plus 15% from the original (`CGImageSourceCreateImageAtIndex` + `cropping(to:)`), placed on the
  original's whole pixels, faded in over the previous part. Mirror `PhotoZoom`, `zoomGestures` and `ZoomMinimap` in
  Android's `ViewerZoom.kt`, and `ViewerDetail.kt`. Also (same day): the minimap grey until the photo is at the
  original's quality; closing while zoomed zooms from the zoomed view into the tile; the viewer hides the status bar
  and home indicator (`.statusBarHidden()`, `.persistentSystemOverlays(.hidden)`); and a server photo's download
  shows a cloud filling with progress, top left, after 300 ms, instead of the spinner (shared:
  `ServerPreviews.originalProgress`, a StateFlow of sha256 → 0..1).

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
