# Search

Find photos and videos by describing them in a sentence ("a dog on the beach at sunset", "birthday cake", "snow in
the mountains"). The server reads every picture with a SigLIP 2 model and answers with the closest, best first; see
`docs/search.md` in journal-server-go. Built Oct 22, 2026 on Android and iOS. The design was picked in the iOS UI lab
from five suggestions ("one per row, full width"); the lab now shows the real screen (`search`, `search-by-date`, `search-empty`).

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
- **Sort** (chosen in the UI lab, option a3, Nov 19, 2026; Android and iOS): a **Sort** pill floats beside the Filter
  pill ("↓ Newest first · 29", "✦ Most relevant · 46"). It opens a sheet with the three orders, each with a line on
  what it does, a tap choosing at once:
  - **Most relevant** (the default): every match, the closest first, as search always was.
  - **Newest first** / **Oldest first**: only the matches at or over a threshold, by when they were taken. The sheet
    then shows **How close a match**, a slider from Close to More (the score a match needs, 0.25 down to 0.05,
    starting at 0.12), "29 photos. The weakest kept:" with the four weakest matches the cut still keeps, so you see
    where it falls, and **Show 29 photos**. The sheet opens tall enough for all of it.
  The order and the threshold are remembered (`JournalSettings.searchSort` / `searchThreshold`) for the next searches.
  In a date order the results are a feed of days: "Sat, Jun 1" on a line as Moments' days, "Tel Aviv → Jaffa · 6
  photos" under it (the day's towns in the order they were taken), then its photos with no caption under each; sets
  still swipe as one row. The note says "29 photos, newest first" (all the cut keeps), and the pages come from the
  first answer here, a page at a time.
  How (`SearchController.sort` / `setThreshold`): the query asks each hit's `score` (cosine similarity; a photo whose
  own text has the words scores 1, so it always stays; the phone's own search scores the same way). A date order cuts
  the best 1000 (the first answer) at the threshold, keeps the same hiding as Most relevant (private, tucked, tucked
  tags, hidden sources with their "N more" line, On this phone, people), sorts by time taken and pages here
  (`SearchState.days`, `matchCount`, `weakest`). A year or town chosen in a date order narrows the first answer here;
  Most relevant chosen again asks the server for the best of that year or town.
- **Town chips** (Nov 19, 2026, with Sort): under the title, "All places 48 · Tel Aviv 14 · Paris 6 …", one town at a
  time; choosing one is the same as the Filter sheet's Place (`SearchController.chooseTown`). Not shown with people
  chosen or with only one town among the matches.
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
  (`setPrivate`, 1000 at a time), the button spinning until the server answers (the viewer's shows "Marking private…"
  meanwhile, asked Oct 26, 2026); picking stops, and with Show private media off they leave the results at once
  (`SearchController.madePrivate`), not after the sync that brings the change to the library. Offline it
  says so and keeps the picks. Back, Cancel or ✕ stop picking. Since Nov 8, 2026 (Android) the bar is Moments'
  picking bar, with **Tuck away** too (no confirmation; on a server with `serverInfo.tuckedAway`): tucked photos leave
  the results at once (`SearchController.madeTucked`). Motion (Android, Nov 8, 2026): the picking bar and the field
  trade places with a slide and fade, results that leave fade out first and then the rest slide up into their place, and a picked result's outline
  fades in as its check springs in.
- **Pictures load ahead of the scroll** (asked Nov 8, 2026; before, each loaded only as it came on screen): photos only
  on the server show their quick image first and their sharp one once the list slows down, as in Moments. Android
  composes the rows about two screens ahead (and keeps those half a screen behind), so their pictures load before they
  show, and loads the quick images of the next rows while the list moves (a set's first two photos). iOS loads the
  sharp pictures of the next 3 rows and the quick images further on as each row comes into view, one at a time. How
  many rows of quick images: Settings › Advanced › Loading while scrolling › **Load ahead** (40 by default; asked Nov 8, 2026).
- **Tucked away** (Nov 8, 2026): search never shows what is [tucked away](moments.md#tucked-away-nov-8-2026): the
  server leaves it out, the app drops what the library knows as tucked from its results, and On this phone's search
  skips it (`shownFiles`).

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
(WhatsApp and Screenshots at first; an imported file is hidden with its kind since Nov 16, 2026, and with Imported
off until then), but not the photos of a day shown
anyway ("Always show this day"). The filter's years and towns count only what shows. Under the count a line says how
many were left out of the pages shown so far, "**12 more in sources you hide · Show**"; a tap shows them for this
search only ("Including 12 from sources you hide · Hide" takes them out again), and the next search hides them again.
Show in the app itself doesn't change. A change to Show in the app while results are up shows them again through it.
What Private settings hides on all devices never reaches the phone: the server leaves it out. Android, and iOS since
Oct 29, 2026.

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

## UX review changes (Nov 16, 2026)

Android only; iOS not built yet ([ios-todo.md](../ios-todo.md)). The rules are in [style.md](style.md).

- The round ✕ beside the field is gone: a **back arrow** at the start of the "Search" title row (30 sp) closes search,
  as the system's back does, and the field takes the full width.

## Design history

- [Results by date, sorting and a match threshold](../ux-history/search/2026-11-19-sort.md) (Nov 19, 2026, iOS lab): a3 picked, the Sort sheet with a slider; promoted in c13accd4.
