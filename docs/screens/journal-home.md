# Journal (home)

The main screen, with two tabs. **Journal** is the timeline of **stories** (a trip, a birthday, a person, anything
worth naming), newest first; a story sits at its newest photo's day. **Unsorted** holds the photos that have no place
yet, as **unsorted moments**: one row per day, or with the [grouping setting](settings.md) one row per run of photos
without a long break. A photo has a place once it is in a story **or has a tag**. Unsorted photos never show on the
Journal tab.

## Layout

Photos fill the screen on the page background (light or dark with the system); the controls float on top.

- **Top bar:** "Journal", the **Offline** pill (only when the server can't be reached), a **Tags** button
  ([tags.md](tags.md)) and a **⋯** menu with **All photos** and **Settings** ([settings.md](settings.md)).
- **Cards above the timeline**, when they apply:
  - **This device isn't registered with the server · Register again**: the server answered 401, or there is no
    identity. Opens the enrollment wizard.
  - **Journal can't see your photos · Allow access**: access was denied or skipped. See
    [photo-access.md](photo-access.md).
  - **Journal can see N photos and videos · Change**: limited access. Opens the system picker to change the
    selection.
- **Story cards:** the title above, then "Oct 8 – 16 · 9 days · 142 photos" (an untitled story shows only that line,
  in bold, as its heading), with a cloud-off icon and count at the right while any photo isn't on the server. Under
  them a mosaic of the story's **main photos**: its first six tiles in the story's order, a group as its cover. One
  photo is full width, two sit side by side, three to five are a large one with two beside it, and six add a row of
  three under them. The last photo shows "+N" for the rest of the story. Tapping opens the [story](story-page.md).
- **Unsorted moments.** Each has a heading, "N unsorted", a **+ Story** button, and a row of 76 pt photos you can
  scroll sideways. Tapping a photo opens the [viewer](viewer.md) on that moment's unsorted photos. Headings:
  - By day: "Today", "Yesterday", "Tue, Oct 7", with the year for other years.
  - By breaks, within one day: the day and the times, "Today · 9:14 – 21:40" (one time for a single photo).
  - By breaks, across days: "Sat, Oct 4 – Mon, Oct 6".
- **+ Story** asks for a title (optional), creates a story from that moment's unsorted photos and opens it. More days are added
  from the story page.
- **Bottom controls:**
  - **N not backed up**, while anything isn't on the server.
  - **Journal / Unsorted N** switch: Journal shows only story cards, Unsorted only the unsorted moments; N counts
    the photos in no story.

Empty, loading and error states show a single line of text in place of the days. With photos but no stories yet,
the Journal tab says "No stories yet. Make one from a day in Unsorted."

## Behavior

- Connects to the server when it opens; the result only shows as the Offline pill or the Register again card.
- Re-reads the library whenever the app comes to the foreground, so new photos and permission changes show up.
- Headings use the device's locale and time zone.
- Moments are keyed by their oldest photo's id, so a moment keeps its place (and an open "+ Story" dialog its
  target) when newer photos join it.

## Not built yet

These are in the design but wait for features that don't exist:

- **Sub-stories.** They will only show inside a story, with an option in Settings later.
- **Selecting several photos** to put them in a story at once.
- **Month scrubber** on the right edge for fast scrolling.

## Code

- Android: [JournalHomeScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/JournalHomeScreen.kt);
  navigation, permission handling and refresh on resume in
  [LibraryApp.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/LibraryApp.kt).
- iOS: [JournalHomeView.swift](../../iosApp/iosApp/JournalHomeView.swift); navigation and refresh in
  [LibraryRootView.swift](../../iosApp/iosApp/LibraryRootView.swift).
