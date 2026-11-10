# Journal (home)

The main screen, with three tabs (renamed on Oct 19, 2026 from Journal / Unsorted; **Explore** added Oct 20, 2026,
on iOS since Oct 21, 2026). **Storyline** (it replaced the Stories tab on Oct 21, 2026, owner's decision; the
stories themselves were deleted) holds every photo you wrote about. **Moments** holds **every photo**, in rows: one
row per day, or with the [grouping setting](settings.md) one row per run of photos without a long break.

A round **🔍** beside the tabs opens [search](search.md) over them (Oct 22, 2026).

## Layout

Photos fill the screen on the page background (light or dark with the system); the controls float on top.

- **Top bar:** "Journal", the **Offline** pill (only when the server can't be reached), a **Tags** button
  ([tags.md](tags.md)) and a **Settings** button ([settings.md](settings.md)). The title row and the bar under
  it (Moments' filter bar, Explore's) slide up and fade out, whole, as the list scrolls down, and come back as it
  scrolls up; they always show at the top of the list and when the tab changes. Behind them, once the list is away
  from its top, the fade comes back with them (fading in and out); at the top there is none (Oct 24, 2026). The list starts under the bar: with Moments' filter bar off
  it leaves no room for it.
- **Cards above the timeline**, when they apply:
  - **This device isn't registered with the server · Register again**: the server answered 401, or there is no
    identity. Opens the enrollment wizard.
  - **Journal can't see your photos · Allow access**: access was denied or skipped. See
    [photo-access.md](photo-access.md).
  - **Journal can see N photos and videos · Change**: limited access. Opens the system picker to change the
    selection.
- **Storyline:** every photo or video with a title or a paragraph (see the [note editor](viewer.md)), one each and
  never a set, newest first by the date taken. A small day heading comes before each new day; then (no title since Nov 10, 2026)
  the photo full width with its Moments crop, and the paragraph under it (4 lines, then **more**), as in
  Moments. It shows photos of every source, also those Show in the app hides. Writing about a photo puts it here; to
  have one photo of a set here, open that photo and write on it. Every **moment made by hand** is here too (Nov 10,
  2026, Android only), by its cover: its name over it as Moments shows it (not bold), and under it its days when more than one and how many photos and
  videos ("Fri – Sun · 12 photos · 3 videos"), its description right under the name, "⧉ 36" in the corner, and a tap opens
  the whole moment in the set viewer (`StoriesState.storylineMomentOf`); Remove from storyline on the cover takes it
  out. Tapping opens the [viewer](viewer.md), swiping
  through the storyline; a long press starts picking, as in Moments (Android, Nov 8, 2026; iOS: its menu, with Remove
  from storyline). **Remove from storyline** (the writing stays and still shows in Moments; `"storyline": false` in
  its note file), or once removed **Add to storyline**, is in the viewer's details panel. A photo
  [tucked away](moments.md#tucked-away-nov-8-2026) leaves it with its writing; its day says "1 tucked away" under its
  heading (the day stays, with only the pill, when all its photos are tucked), and tapping that shows them for the
  session.
- **Moments:** a feed of every photo that groups itself, full-width photos split by time, place, source and
  looks, with the source filter bar under the title; see [moments.md](moments.md). On iOS since Oct 21, 2026 (until
  then it showed rows of small unsorted photos with **+ Story**).
- **Bottom controls:**
  - While the list scrolls down, the tabs and **N not backed up** slide away and Moments' month pill slides down into
    the tabs' place at the bottom; they
    come back as it scrolls up, at the top of the list, and when the tab changes, the ruler opens or photos are picked
    (Oct 24, 2026).
  - **N not backed up**, while anything isn't on the server.
  - No fade behind them (removed Oct 24, 2026): the controls float over the photos, above the system's gesture bar.
  - **Moments / Storyline / Explore** switch (Moments first, and the tab home opens on, since Oct 24, 2026): Moments
    every photo, Storyline the photos you wrote about,
    Explore the photos by town and year and the map ([explore.md](explore.md)). No counts. While photos are picked in
    Moments or the storyline, the picking bar takes its place: **Cancel · N selected · Mark private · Tuck away** (see
    [moments.md](moments.md)). The bar and the tabs trade places with a short slide and fade (Android, Nov 8, 2026;
    see Motion in moments.md).

Empty, loading and error states show a single line of text in place of the days. With nothing written yet, the
Storyline tab says "Nothing here yet. Open a photo and tap Write: what you write shows here."

## Behavior

- Connects to the server when it opens; the result only shows as the Offline pill or the Register again card.
- Re-reads the library whenever the app comes to the foreground, so new photos and permission changes show up.
- Headings use the device's locale and time zone.
- Feed rows, moments and sets are keyed by their oldest photo's id, so they keep their place (and a set its page)
  when newer photos join them.

## Not built yet

These are in the design but wait for features that don't exist:

- **More photos with one entry** of the storyline (the owner will decide how, later); for now each entry is one
  photo.
- **Month scrubber** on the right edge for fast scrolling.

## Code

- Android: [JournalHomeScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/JournalHomeScreen.kt);
  navigation, permission handling and refresh on resume in
  [LibraryApp.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/LibraryApp.kt).
- iOS: [JournalHomeView.swift](../../iosApp/iosApp/JournalHomeView.swift), the Moments feed in
  [MomentsFeedViews.swift](../../iosApp/iosApp/MomentsFeedViews.swift) and
  [FeedVideo.swift](../../iosApp/iosApp/FeedVideo.swift); navigation and refresh in
  [LibraryRootView.swift](../../iosApp/iosApp/LibraryRootView.swift).
