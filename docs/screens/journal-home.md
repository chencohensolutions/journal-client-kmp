# Journal (home)

The main screen: the journal as a timeline, newest first. It holds **stories** (a trip, a birthday, a person, anything
worth naming), with the photos that have no place yet shown between them as **unsorted days**. A photo has a
place once it is in a story **or has a tag**. A story sits at its newest photo's day; on the same day the story comes
before the unsorted photos.

## Layout

Photos fill the screen on the page background (light or dark with the system); the controls float on top.

- **Top bar:** "Journal", the **Offline** pill (only when the server can't be reached), a **Tags** button
  ([tags.md](tags.md)) and a **⋯** menu with **All photos**.
- **Cards above the timeline**, when they apply:
  - **This device isn't registered with the server · Register again**: the server answered 401, or there is no
    identity. Opens the enrollment wizard.
  - **Journal can't see your photos · Allow access**: access was denied or skipped. See
    [photo-access.md](photo-access.md).
  - **Journal can see N photos and videos · Change**: limited access. Opens the system picker to change the
    selection.
- **Story cards:** a mosaic of the story's first photos (one full width, two side by side, or a large one with
  smaller ones around it), the first day as a calendar block, the title, and "Oct 8 – 16 · 9 days · 142 photos".
  "N not backed up" sits in the corner while any of its photos isn't on the server. Tapping opens the
  [story](story-page.md).
- **Unsorted days.** Each has a heading ("Today", "Yesterday", "Tue, Oct 7", with the year for other years),
  "N unsorted", a **+ Story** button, and a row of 76 pt photos you can scroll sideways. Tapping a photo opens the
  [viewer](viewer.md) on that day's unsorted photos.
- **+ Story** asks for a title, creates a story from that day's unsorted photos and opens it. More days are added
  from the story page.
- **Bottom controls:**
  - **N not backed up**, while anything isn't on the server.
  - **Journal / Unsorted N** switch. Unsorted hides the story cards and counts the photos in no story.

Empty, loading and error states show a single line of text in place of the days.

## Behavior

- Connects to the server when it opens; the result only shows as the Offline pill or the Register again card.
- Re-reads the library whenever the app comes to the foreground, so new photos and permission changes show up.
- Day headings use the device's locale and time zone.

## Not built yet

These are in the design but wait for features that don't exist:

- **Sub-stories.** They will only show inside a story, with an option in Settings later.
- **Selecting several photos** to put them in a story at once.
- **Month scrubber** on the right edge for fast scrolling.
- **Settings** in the ⋯ menu.

## Code

- Android: [JournalHomeScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/JournalHomeScreen.kt);
  navigation, permission handling and refresh on resume in
  [LibraryApp.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/LibraryApp.kt).
- iOS: [JournalHomeView.swift](../../iosApp/iosApp/JournalHomeView.swift); navigation and refresh in
  [LibraryRootView.swift](../../iosApp/iosApp/LibraryRootView.swift).
