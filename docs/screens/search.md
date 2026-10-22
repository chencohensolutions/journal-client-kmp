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
- Nothing found: "Nothing found. Try other words." Offline: "Can't reach your server…". The server's own error (for
  example, search turned off there) is shown as "The server couldn't search: …".
- Tapping a photo opens the viewer, which swipes through the results in their order; closing it lands back on that
  photo. "Show in Moments" from there closes search and goes to the Moments tab.

## What is shown

The server answers with sha256s (at most 100 per search). They are shown as the library has those photos, so what the
library hides stays hidden: private files and nudity over the threshold while "Show private media" is off, and files
the server's list hasn't synced yet. A photo on this phone that backup hasn't read is matched by the server file's
name and size, as the library matches them. When the library changes after an answer (a photo made private), the
results follow without searching again. All sources are searched, whatever Moments shows.

## Code

- Shared: `SearchController`, `SearchState` in
  [search/SearchController.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/search/SearchController.kt)
  (`search(text)`, `clear()`; `status` IDLE / SEARCHING / DONE / FAILED, `error` OFFLINE or SERVER), the query in
  `graphql/Search.graphql`, the schema copied to `graphql/search.graphqls`. Made by `createSearchController` (Android)
  and `IosJournal.createSearchController`. Tests: `SearchTest`.
- Android: [SearchViews.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/SearchViews.kt)
  (page, top bar, field, 🔍 button), wired in `JournalHomeScreen` (`searching`, kept across rotation) and
  `LibraryApp` (`Route.Viewer(search = true)`). Previews: `SearchScreenshots.kt`.
- iOS: [SearchViews.swift](../../iosApp/iosApp/SearchViews.swift) and `SearchViewModel.swift`,
  wired in `JournalHomeView` (`searching`, `search`) and `LibraryRootView` (`ViewerRequest(search: true)`).
