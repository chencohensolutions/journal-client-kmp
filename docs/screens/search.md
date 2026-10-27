# Search

Find photos and videos by describing them in a sentence ("a dog on the beach at sunset", "birthday cake", "snow in
the mountains"). The server reads every picture with a SigLIP 2 model and answers with the closest, best first; see
`docs/search.md` in journal-server-go. Built Oct 22, 2026 on Android and iOS. The design was picked in the iOS UI lab
from five suggestions ("one per row, full width"); the lab now shows the real screen (`search`, `search-empty`).

## Opening and closing

- A round **🔍** button sits beside the tab bar (Storyline · Moments · Explore). Tapping it turns the bottom bar into
  the **search field**, with a round **✕** beside it, and the page into the results. The keyboard comes up when
  nothing was searched yet.
- **✕** (or back) returns to the tab the user was on, as it was. The search is kept: 🔍 again shows the same words
  and results. The field's small ✕ clears the words and the results.
- The field sits above the keyboard while typing. The keyboard's search key sends the sentence.

## The page

- "**Search**" at the top. Before anything is searched: a line on what to type and that search looks at the
  pictures, not their names.
- While searching: a spinner in the field (and in the page the first time).
- Results: "**36 photos, best first**", and while the server hasn't read every file yet "**Still reading your
  photos: 12,480 of 14,200**" with a small bar (those it hasn't read can't be found yet). Then the photos, **one per
  row, the screen's full width**, at their own shape (wide panoramas cropped to 3:1, tall ones to 0.6:1, about a
  screen), each with its **date** (bold), **time**, and **📍 town, country** under it (no place line for a photo
  without one, or whose place isn't named yet).
- **Pages** (Oct 22, 2026): a search shows a page of photos (Settings › Search › Photos per page: 50, 100, **200** by
  default, 500 or 1000). A few rows before the end the next page follows, with a spinner at the bottom while it comes;
  the count above says how many are shown so far. A page never moves what is shown: its photos of a set shown
  already join that set (more dots), the rest come after.
- **Sets and moments** (Oct 22, 2026): almost identical photos (a burst, a pose taken again) are one row at the
  best one's shape that you swipe, with "2 / 5" at the top and dots at the bottom, as in Moments; the caption is the
  photo shown. Sets taken within two hours of each other stay together, each such moment placed by its best photo and
  its sets in their own order. The server decides both (see its docs/search.md: at least 0.93 alike and within ten
  minutes for a set), so they work for every source, whatever Moments shows. The viewer swipes through the photos in
  this order, set by set.
- **Filter sheet** (chosen in the UI lab, Oct 22, 2026, from five ways to filter): once there are matches to narrow, a
  **Filter** pill floats above the field ("Filter · 2", dark, while a year and a town are chosen). It opens a sheet:
  **Year**, the matches' years as a grid of four across with how many each (newest first), and **Place**, their towns
  as a list with how many (most first, ✓ on the chosen one), then **Show N photos**. A tap narrows at once, a second
  drops it, and Clear drops both; each section is counted within the other's choice. A section with one value and
  nothing chosen isn't shown, and with neither there is no pill. The server picks the best of that year or town (see
  below), not the overall best that happen to match.
- Nothing found: "Nothing found. Try other words." Offline: "Can't reach your server…". Search off on the server
  (its `SEARCH_OFF` code): "Search is off on your server." Any other error of the server's: "The server couldn't
  search: …".
- **Getting ready** (Oct 24, 2026): opening the search screen asks the server to load its model (`prepareSearch`);
  while it loads (a few seconds, on a server that indexes once a day), a search shows "Getting search ready…" under
  its spinner (`searchModel(wait: true)`).
- **By person** (Oct 24, 2026, when the server has face search): before anything is searched, "Search by person"
  with the named people's faces (most photos first, 16); a tap puts the person in a chip over the results (× takes
  them out) and shows their photos, the last taken first; words then find the best matches among them
  (`searchMedia(people:, text:)`). With people chosen there is no year or town filter.
- **On this phone** (Oct 24, 2026): with Settings › Search › On device search keeping text search, the phone searches
  itself (offline too); offline it says "Searched on this phone: your server can't be reached." See
  [settings.md](settings.md#search--on-this-phone).
- Tapping a photo opens the viewer, which swipes through the results in their order; closing it lands back on that
  photo. "Show in Moments" from there closes search and goes to the Moments tab.
- **Mark private, many at once** (asked Oct 26, 2026): a **long press** on a result starts picking it, as Moments'
  Select does (a check at its corner, the photo outlined); a set is picked whole. While picking, a tap picks or
  unpicks instead of opening, and the field gives way to **Cancel · N selected · Mark private** (N counts photos, a
  set each of its own). Mark private asks first ("Mark 12 photos private?", as the viewer's does), then sends them all
  (`setPrivate`, 1000 at a time); picking stops, and with Show private media off they leave the results. Offline it
  says so and keeps the picks. Back, Cancel or ✕ stop picking.

## What is shown

A search first asks for the best 1000 matches (only their sha256s); the filter counts all 1000, and the pages come
from them while they last, then from the server (`offset`, up to 10000 down). With a year or town, every page comes
from the server. A choice in it asks again with `years` or `area` (at most 100): the server applies them before picking the best. A town
is sent as a box of latitudes and longitudes around this device's photos of it, a kilometer wider, since the server
has no place names; what the box catches of a neighboring town is left out here. Dropping both shows the first answer
again without asking. The server answers with sha256s. They are shown as the library has those photos, so what the
library hides stays hidden: private files while "Show private media" is off, and files
the server's list hasn't synced yet. A photo on this phone that backup hasn't read is matched by the server file's
name and size, as the library matches them. When the library changes after an answer (a photo made private), the
results follow without searching again.

**Search follows Show in the app** (the owner, Oct 27, 2026; before, every source was searched whatever Moments
showed): a match in a source Settings › Show in the app hides is left out, as Moments leaves it out: a hidden source
(WhatsApp and Screenshots at first), what was imported while Imported is off, but not the photos of a day shown
anyway ("Always show this day"). The filter's years and towns count only what shows. Under the count a line says how
many were left out of the pages shown so far, "**12 more in sources you hide · Show**"; a tap shows them for this
search only ("Including 12 from sources you hide · Hide" takes them out again), and the next search hides them again.
Show in the app itself doesn't change. A change to Show in the app while results are up shows them again through it.
What Private settings hides on all devices never reaches the phone: the server leaves it out. Android only; on iOS
the line is still to do.

## Code

- Shared: `SearchController`, `SearchState` in
  [search/SearchController.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/search/SearchController.kt)
  (`search(text)`, `chooseYear`, `chooseTown`, `loadMore()`, `clear()`, `showHiddenSources(show)`; `status` IDLE /
  SEARCHING / DONE / FAILED, `error` OFFLINE or SERVER, `yearChips` / `townChips`, the filter sheet's counts,
  `hiddenBySources` / `showingHidden`; the sources shown come from `StoriesState.shownSources`), the query in
  `graphql/Search.graphql`, the schema copied to `graphql/search.graphqls`. Made by `createSearchController` (Android)
  and `IosJournal.createSearchController`. Tests: `SearchTest`.
- Android: [SearchViews.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/SearchViews.kt)
  (page, top bar, field, Filter pill and sheet, 🔍 button, `SearchPickingBar`), wired in `JournalHomeScreen` (`searching`,
  kept across rotation; `searchPicks`) and
  `LibraryApp` (`Route.Viewer(search = true)`). Previews: `SearchScreenshots.kt`.
- iOS: [SearchViews.swift](../../iosApp/iosApp/SearchViews.swift) and `SearchViewModel.swift`,
  wired in `JournalHomeView` (`searching`, `search`, `searchPicks`) and `LibraryRootView` (`ViewerRequest(search: true)`).
