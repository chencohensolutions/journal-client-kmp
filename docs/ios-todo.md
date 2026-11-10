# iOS: not built yet

Work done on the Windows machine is Android only (no Xcode there). Each entry says what Android has and what the
SwiftUI side needs, so it can be finished on the Mac. Remove an entry once iOS has it.

Built on iOS on the Mac (Oct 21, 2026): Moments, Explore, backup and its settings, and the where-the-original-is
badge, plus crop, server-only photos and videos, Storage on this phone, Private media and Free up space; then, the
same day on both platforms, writing with a title, paragraph and history, and the storyline in place of stories. On
Oct 22, 2026: the viewer's zoom (with the minimap, remembered zoom, download cloud and hidden system bars), Explore's
list of countries with a country's page, and the feed's spacing, day lines, chips and hidden-source band matched to
Android's. On Oct 26, 2026: the library database (plan B phases 1–6), Private settings, sound recordings (Moments and
their page), and backup of calls and recordings from folders with Free up space by groups. On Oct 29, 2026: Backup › Copies on
other disks, Share JPEG XL as JPEG (the JPEG made again by the server, `GET /media/<the JPEG's sha256>`), Keep on
this phone as a card of switches, the sound kinds' names and Settings › Sound folders, the sources rework (Sent to
you, Imported in the Showing sheets and the Moments bar, the locks of what Private settings hides, search's "12 more
in sources you hide", a photo's "Saved in" and Camera), Show in the app as a status page, Private settings by kind,
Your cameras, Settings › Advanced › Loading Moments, Quick image while scrolling, the note in the feed's long-press
menu, and PhotoKit read by its persistent changes (`IosMediaLibrary` is a `ChangingLibrary`). Later that day: Ogg Opus
recordings decoded on the phone (`OggOpus.swift`: the Ogg pages read here, the Opus packets decoded by AudioToolbox,
checked against ffmpeg's decode on the Mac) and Backup's "Only on these Wi-Fi networks" (`WifiNetworks.swift`; the Access
WiFi Information entitlement is opt-in, `WIFI_ENTITLEMENTS=iosApp/WifiInfo.entitlements` in Local.xcconfig, as the
owner's team must have it).

Still missing on iOS:

- **Casting: fill kept, info and map on the TV** (Android Nov 10, 2026; [cast.md](cast.md#info-and-map),
  [viewer.md](screens/viewer.md) "Showing on a TV"): when casting is built on iOS, a double tap that fills the TV
  keeps filling for the next photos (Android: `castFill` in `ViewerScreen.kt`); call `cast.info(id, CastInfo(text,
  time, place, latitude, longitude))` for the item shown, and while casting a bar under the viewer's header with Info
  and Map pills that call `showInfo(!infoOnTv)` / `showMap(!mapOnTv)` (Map only with a place; the photo's room
  starts below the bar; Android: `CastOverlayBar` in `CastViews.kt`). Strings: `cast_info_chip`, `cast_map_chip`,
  `cast_info_on`, `cast_info_off`, `cast_map_on`, `cast_map_off`.
- **Volume buttons while casting** (Android Nov 10, 2026; [cast.md](cast.md#volume)): the phone's volume buttons
  set the Cast session's volume (steps of 0.05) while casting. On iOS, when casting is built, set
  `GCKCastOptions.physicalVolumeButtonsWillControlDeviceVolume = true`.

- **Moments' window** (Android Nov 10, 2026; [moments.md](screens/moments.md)): the shared pager now holds a window
  of rows, so after going to a date the rows above it aren't read. iOS must call `StoriesController.loadNewerMoments()`
  when the list nears the top of what is read while `feed.hasNewer` (as it calls `loadMoreMoments()` near the end),
  keeping the rows shown in place as rows come in above them; the viewer likewise near its first photo, putting the
  newer photos first. Until then, on iOS, scrolling up after a jump stops at the window's top.

- **Make cover, counts in the storyline, no repeated day** (Android Nov 10, 2026): the set viewer's bar has Make
  cover (photo icon, filled on the cover) calling `StoriesController.setMomentCover(itemIds, itemId)`; `NamedSet.coverId`
  is the cover, which the storyline shows and `frontOf` prefers. The storyline's moment line reads "Fri – Sun · 12
  photos · 3 videos" (`set_photos`, `set_videos`); in Moments and the storyline a one-day moment shows no day under
  its name. Strings `set_make_cover`, `set_is_cover`.

- **Moments made by hand in the storyline** (Android Nov 10, 2026; [journal-home.md](screens/journal-home.md)): the
  shared storyline now holds each hand-made moment's cover (a note with `setInfo`, unless `inStoryline` is false);
  `StoriesState.storylineMomentOf(itemId)` gives its `NamedSet`. Draw that row with the moment's name as the title,
  its description and the photo's caption under it, "⧉ N" in the corner, and open the set viewer on tap (as Moments
  opens a set). Android: `FeedRow.StoryPhoto(item, moment)`, `FeedActions.openMoment` in `MomentsFeedViews.kt`.

- **Set viewer header, the moment's name and description; Write without a title** (Android Nov 10, 2026;
  [viewer.md](screens/viewer.md)): the set's top bar is back, "12 of 36" (no date), Share and the grid; no ⋮. Over
  the photo's info at the bottom, the moment's name (17 pt semibold) and its description (up to 3 lines); unnamed,
  "Name this moment". Tap: Show in Moments (from Moments: back); long press: a dialog with the name and a description
  field (`set_description_hint`), saved with the new `StoriesController.nameMoment(itemIds, name, description)`
  (`NamedSet.description`, note `setInfo.description`, additive). The note editor has only the paragraph box (the title
  field went; saving clears an old title), and photos' titles are no longer shown anywhere (feed, storyline). A single photo shows the highlight ★ over the photo only while a slideshow plays.
  Android: `MomentTitle`, `MomentNameDialog` in `ViewerScreen.kt`, `NoteEditor.kt`.

- **A single photo's viewer as a set's; one crop screen** (Android Nov 10, 2026; [viewer.md](screens/viewer.md),
  [crop.md](screens/crop.md)): the viewer of a single photo has the set's top bar (position "4 of 29 · day" in white,
  Share, no ⋮; the Moments day chip stays when opened from elsewhere; the set's ⋮ lost Details, the ⓘ opens it) and floating bar (✎ Write · ⌗ Crop). It shows
  each photo at its Moments crop (no more portrait/landscape crops). The old crop screen (Moments · Portrait ·
  Landscape) is gone: Moments' long press Crop and the viewer's ⌗ open the set's Recompose editor on that one photo
  (title Crop, hint `crop_hint_moments`, no strip or Same frame for all), saving with `setMomentsCrops`. Android:
  `ViewerScreen.kt` (top bar, `ViewerActions.crop`), `SetCropEditor` in `CropScreen.kt`, `LibraryApp.kt`.

- **Moments' month ruler fixes** (Android Nov 10, 2026; [moments.md](screens/moments.md)): a touch anywhere on the
  ruler is the ruler's (never folds it); the day strip takes touches over its whole height, day numbers included, and
  the touch going down always goes, even to the day picked last; the touch handlers call the latest callbacks (on
  Android they kept old ones, which looked for the day in rows from before more were read). The month shown after a
  jump waits 10 s (was 4) for the feed to get there. A feed row can hold several days: a day lands on that
  day's first moment label or photo in its row (Android `rowOfDay`), not the row's heading, and the pill and ruler
  name the day of the row at the top (`rowDays`), not its row's newest day. Check the SwiftUI ruler for the same.

- **Moment screen polish** (Android Nov 10, 2026; [viewer.md](screens/viewer.md), [moments.md](screens/moments.md)):
  - Moments' set cover: no ▶ (play in place) and no position line; only "⧉ 36" stays. Hold-and-slide and tap as before.
  - The viewer's floating bars are icons only (each with an accessibility label): a set has ☆/★ Highlight · ▶ Play ·
    ✎ Write · ⌗ Recompose; a single photo ✎ Write. **Share** is an icon in the top bar of both (a spinner while the
    original is fetched); the set's ⋯ menu lost Write and Share. Android: `BarPill`, `ShareTopButton` in
    `ViewerScreen.kt`.
  - Slideshow bar: "12 of 36" (`set_position`) instead of the progress line. ▶ Videos (`SettingsState.autoplayVideos`)
    now only rules the slideshow: outside it videos always play by themselves; in it, off, a video shows its poster
    for a slide's time with no Play button.
  - Casting a slideshow: every video plays to its end on the TV (`show(autoplay = true)`, then `await cast.videoDone(id)`),
    ▶ Videos is hidden, and a photo's slide time starts once the TV reports `shownId` (at most 10 s). Android:
    `Slideshow(shownBy = …)` in `MomentSets.kt`.
  - Cast zoom: the phone shows the TV's frame as large as fits (`PhoneRect.fitted(w, h, tvRatio)`), the photo drawn at
    `TvZoom.photoOn(thatRect)` and dimmed outside it; pinch/drag map to the photo's rect there (drag moves the photo,
    so the frame pans by `-pan / photoWidth`). Double tap: `TvZoom.toggled()` (fill the TV, or back to whole).
    Android: `castPlaced`, `castGestures`, `CastFrameOverlay` in `CastViews.kt`.
  - Swiping the Moments viewer onto a photo of a set (once settled, not on opening) replaces it with the set's viewer
    at that photo: `MomentsFeed.setShowing(id)`; Android `ViewerActions.settled` wired in `LibraryApp.kt`.
  - A set's highlight shows a gold ★ (on a small scrim) at the top right of the photo, also during the slideshow.
  - The reel's white outline is one overlay fixed in the reel's middle; the reel scrolls under it and the outline's width changes, both 0.3 s ease-in-out (`SetReel` `Outline`, `REEL_GLIDE`).
  - The top bar's position line is white (was grey), and the ⋯ menu's text has no shadow.
  - Strings: `cast_frame_hint` reworded; `set_play_here` removed.

- **Showing on a TV with Google Cast** (Android Nov 9, 2026; [cast.md](cast.md), [viewer.md](screens/viewer.md)
  "Showing on a TV"): the receiver page and the shared `CastController` / `TvZoom` (`sharedLogic/.../cast/`) are
  ready. iOS needs: the Google Cast SDK for iOS (`GCKCastContext` with `JournalCast.APP_ID`'s id; `NSBonjourServices`
  `_googlecast._tcp` and the receiver's `_<APP_ID>._googlecast._tcp`, and `NSLocalNetworkUsageDescription`, in
  Info.plist); a `GCKGenericChannel` on `CAST_NAMESPACE` whose `didReceiveTextMessage` calls `controller.received` and
  whose `sendTextMessage` is the `CastChannel`; `connected`/`disconnected` from the session manager's listener; a small
  HTTP server on the Wi-Fi like `CastServer.kt` (Range, CORS, Private Network Access headers, PhotoKit's original via
  `PHAssetResourceManager`, JPEG XL packed from a JPEG as that JPEG, DNG as a 3840 px JPEG); in the viewer a Cast
  button and TV list, `show` with `castAhead`, the frame overlay with `TvZoom.zoomBy`/`panBy` from a pinch and a drag,
  and `CastVideoControls`' equivalent. Strings: `cast_*` in `values/strings.xml` (and `values-iw/`).
  Learned on the TV with Android (Nov 9): the phone's server must stay reachable while the app is in the background
  (Android needed a foreground service, `CastService`, and a Wi-Fi lock; iOS suspends background apps, so it needs a
  background task or AV session, or casting only while the app is open), a video only on the server is downloaded
  whole first and the TV reads that file (`VideoDownload` in `CastServer.kt`), and the Cast button shows filled in
  while connected (`LibraryIcons.CastOn`). Nothing changes in `receiver/`.

- **Viewer without gradients; feed-style floating bar; reel at the bottom** (Android Nov 9, 2026;
  [viewer.md](screens/viewer.md)): no scrims; mild text and icon shadows over the photo; the actions in a bar styled as
  the Moments tabs (Write · Share for a photo, Highlight · Play · Recompose in a set); the set's reel along the bottom
  edge under it; in landscape the reel column slides out past its own edge (left in RTL). Check iOS mirrors RTL.
- **Viewer bottom redone** (Android Nov 9, 2026; [viewer.md](screens/viewer.md)): Highlight · Play · Recompose in a
  floating see-through bar (44 dp Play); reel 64 dp tall with ▶ and length on videos, 88 dp wide down the right in
  landscape; the slideshow's bar hides and shows on a tap; video controls one slim 32 dp row with sound at its end; a
  video's poster prepared ahead and faded over its first frame (no black).
  The reel takes its own room: the photo is centred above it (left of it in landscape), and when the bars hide the
  reel slides out and the photo glides to the screen's middle.
- **Viewer videos: own controls, slideshow to their end, ▶ Videos, landscape reel** (Android Nov 9, 2026;
  [viewer.md](screens/viewer.md)): the video's controls (play/pause, time, seek bar, length) drawn under the info line
  above the reel; the slideshow plays a video to its end before going on; **▶ Videos** beside the speeds turns
  autoplay off (`SettingsState.autoplayVideos`, shared); in landscape the set's reel is a column down the right edge.
  Android: `VideoControls`, `Slideshow(hold)`, `SetBar(landscape)`, `SetReel(vertical)`. Strings: `library_viewer_pause`,
  `set_autoplay_videos`.
- **Setting: photos made ready while swiping** (Android Nov 9, 2026; [settings.md](screens/settings.md)): under Load
  ahead in Loading while scrolling, chips 0, 2, **5**, 8, 10 for how many photos on each side the viewer prepares at
  screen size (`MomentsPaging.viewerAhead`, shared; iOS has no such prefetch yet). Strings: `settings_viewer_ahead`,
  `settings_viewer_ahead_body`.
- **Swiping between screen-size photos** (Android Nov 9, 2026; [viewer.md](screens/viewer.md)): three photos on each
  side of the one shown are made ready fitted to the screen (server previews at that size for photos only there), the
  way the swipes go first, so each slides in sharp; the full image or original only for the photo shown. Android:
  `screenPreviews`, `screenPreview`, the settled-page `LaunchedEffect` and `PhotoPage(shown)` in `ViewerScreen.kt`.
- **Crop on and off glides** (Android Nov 9, 2026; [viewer.md](screens/viewer.md)): the viewer's Show all / Show crop
  animates the part shown between the crop and the whole photo (0.3 s, ease), with the same image. Android: the frame
  `Animatable` in `ViewerImage` (`ViewerScreen.kt`).
- **Viewer videos play at once, muted, and resume** (Android Nov 9, 2026; [viewer.md](screens/viewer.md)): a video
  plays as soon as it is shown, muted, with a sound button (top right) for the viewer's videos; swiped away and back,
  it goes on from where it was (positions by id while the viewer is open). Android: `VideoPage`, `ViewerVideos` in
  `ViewerScreen.kt`. Strings: the feed's mute/unmute.
- **Crop chip as a toggle** (Android Nov 9, 2026; [viewer.md](screens/viewer.md)): in the viewer, the Cropped ·
  Show all chip toggles crop/whole (no hold-to-peek) and shows and hides with the bars (a tap hides it with the rest;
  hidden during a slideshow); in a set it shows over a recomposed photo too. Android: the `CropChip` visibility in `ViewerScreen.kt`. No new strings.
- **Swiping through all of Moments, and brief info** (Android Nov 9, 2026; [viewer.md](screens/viewer.md)): the
  viewer opened from a Moments row swipes through every row read so far (`viewerItems`: all sections' items), calls
  `loadMoreMoments()` within 20 of the end and appends the new rows. Over the photo, with the bars: place (label, city,
  country from `places`), date and time, `#tags`, and an ⓘ for the details panel (replaces the Details button); in a
  set above the reel. Android: `PhotoBrief` in `ViewerScreen.kt`, `LibraryApp.kt`, `LibraryRoutes.kt`. No new strings.
- **Swiping past a set's ends** (Android Nov 9, 2026; [viewer.md](screens/viewer.md#a-set-nov-9-2026)): in the
  set viewer, a swipe past the last photo (first, backwards) asks `feed.beside(itemIds:forward:)` and replaces the
  viewer with that neighbour: a `FeedBlockSimilar` in set mode at its first (last) photo, a single photo in the usual
  viewer on `section.key`. Android: `ViewerSet.beside` in `ViewerScreen.kt`, wired in `LibraryApp.kt`. No strings.
- **The set viewer's reel** (Android Nov 9, 2026; [viewer.md](screens/viewer.md#a-set-nov-9-2026)): the scrubber
  became a scrolling row of every photo at its own shape (Moments crop, else whole; whole while Show all is on), the
  shown one centered and outlined; dragging it shows the photo reaching the middle (selection haptic), a tap jumps.
  Android: `SetReel` / `ReelThumb` in `MomentSets.kt`. No strings.
- **Naming or renaming a moment** (Android Nov 9, 2026; [viewer.md](screens/viewer.md#a-set-nov-9-2026)): the set
  viewer's ⋯ menu ends with "Name this moment" / "Rename moment" (מתן שם לרגע / שינוי שם הרגע), opening an alert
  "Moment name" (שם הרגע) with a text field (placeholder "No name" / ללא שם), Save (שמירה) and Cancel →
  `StoriesController.nameMoment(itemIds:name:)` with the set's ids; blank leaves it unnamed. Android: `MomentNameDialog`
  and `ViewerSet.rename` in `ViewerScreen.kt`, wired in `LibraryApp.kt`.
- **Slideshow waits for the next photo** (Android Nov 9, 2026; [viewer.md](screens/viewer.md#a-set-nov-9-2026)):
  while one photo shows, the next is decoded at the viewer's full size (downloaded if server-only), and the switch
  waits for it (at most 15 s), so it never shows blurred first; the photo page starts from those kept images.
  Android: `Slideshow(prepare:)` in `MomentSets.kt`, `viewerImages` / `viewerImage` in `ViewerScreen.kt`. No strings.
  The feed's in-place ▶ does the same at the feed's size (`loadCover` in `Thumbnails.kt`, `SetCover`).
- **Unnamed moments and the name's look** (Android Nov 9, 2026; [moments.md](screens/moments.md)): Make a moment
  no longer needs a name (`makeMoment` saves `setInfo` with `""`; `NamedSet.name` is now null then). Over a moment in
  Moments: the name not bold (17), its days on a line of their own under it, smaller and muted (12); nothing over an
  unnamed one; its "Part of" link says "Part of a moment" (`set_part_of_unnamed`, Hebrew חלק מרגע). The name field is
  "Name (optional)" / "שם (לא חובה)". Android: `SetName`, `PartOfLink` in `MomentsFeedViews.kt`, `MakeMomentSheet.kt`.
- **Moment sets: highlights, flipping in the feed, the viewer's set mode, Recompose, Make a moment** (Android Nov 9,
  2026; [moments.md](screens/moments.md#sets-nov-9-2026), [viewer.md](screens/viewer.md#a-set-nov-9-2026),
  [crop.md](screens/crop.md#a-set-recompose-nov-9-2026); plan [plans/moment-sets.md](plans/moment-sets.md)). Shared
  logic is done and already applies on iOS (notes format 5, `MomentSetsTest`); `turnSet` is gone (iOS's
  `StoriesViewModel.turnSet` is a no-op for now: turning a set is kept in `playback.pages` while the app runs, and a
  set opens on `note.highlight == 1`, else the best-looking). To build:
  1. **Feed** (`MomentsFeedViews.swift`, mirror `SetBlock` / `SetCover` / `SetLine` / `PartOfLink` / `SetName` in
     Android's `MomentsFeedViews.kt`): a `FeedBlockSimilar` with `highlights` (non-empty) draws each highlight as a
     single full-width photo with its own title and paragraph, then the line "⧉ %d photos in this set ›" (a tap opens
     the set on its first highlight). Without highlights: the cover only (no pager): `stories.currentState.frontOf(items:)`
     unless turned while the feed shows; others fitted inside on black at their Moments crop
     (`MediaItem.shownRatio`-like: `crop.ratioOn(photoRatio)`); "⧉ N" badge top right; a position line at the bottom
     (`SetPositionLine`: a mark at the photo shown, a dot at the cover); **hold, then slide sideways** flips (10 pt per
     photo, a selection haptic per photo, "12 of 36" pill while sliding; a hold released without sliding is the long
     press = picking; a vertical move cancels it); a small ▶ bottom-left plays it in place every 0.5 s, muted;
     flips cross-fade 0.12 s; a tap opens the viewer on the set. `block.named` (a `NamedSet`): when
     `!named.isPartOf(block:)` the name over it, "Beach weekend · Fri – Sun" (weekday skeleton "EEE"); when it is a
     part (each day of a moment shown on every day; also on a `FeedBlockSingle`), a pill "Part of Beach weekend ›"
     under it that opens `named.itemIds` (read them first: `StoriesController.want(ids:)`).
  2. **Viewer set mode** (`MediaViewerView.swift`; mirror `ViewerSet` / `SetBar` in Android's `ViewerScreen.kt` and
     `MomentSets.kt`): opened with the set's ids (Android: `Route.Viewer(list = setListKey(ids))`, ids joined by ","
     after "set:"). Swipes cross-fade 0.12 s (no slide); bars visible from the start, no auto-hide; top bar: back, the
     name (`stories.currentState.namedSetOf(itemId:)?.name`), "12 of 36 · day", a grid button and a ⋯ menu with
     Write, Details, Share (and Show in Moments). Bottom: the scrubber (at most 48 cells, the photo shown raised, dots
     over highlights, slide to flip with a tick), then ☆ Highlight / ★ Highlight
     (`StoriesController.setHighlight(itemIds:itemId:on:)`) · a big ▶ Play · ⌗ Recompose (photos only). Play: every
     0.8 s (Fast 0.35, Slow 2), cross-fading, round and round, bars hidden, a thin progress line, the speed picker
     and Stop; a tap stops; `UIApplication.shared.isIdleTimerDisabled` while it plays. Grid sheet: all photos as
     squares (4 across), highlights numbered (★ the first), a tap goes there; above them the highlights in a row,
     drag to reorder (`moveHighlight(itemIds:itemId:to:)`, `to` from 0). Photos in a set show at their **Moments**
     crop. Highlights for the bar: `stories.currentState.highlightsOf(items:)`.
  3. **Recompose** (`CropView.swift`; mirror `SetCropEditor` in Android's `CropScreen.kt`): every photo's Moments crop
     (videos left out), the photo with its free frame, Reset and Done, shape chips Original / Square / 4:5 / Wide
     (`CropRect.companion.largest(ratio:photoRatio:around:)` with `aspect` cleared), the set's strip (a dot on each
     cropped), "⧉ Same frame for all" (every photo of the same shape, within 0.01, gets the frame shown). Done:
     `StoriesController.setMomentsCrops(crops:)` with every photo's crop (null for none), or close if unchanged.
  4. **Make a moment** (`JournalHomeView.swift` picking bar; mirror `MakeMomentSheet.kt`): with two or more photos
     picked in Moments, "Make a moment" opens a sheet: Name (optional; "Name (optional)", Make it always enabled), Highlights (tap thumbnails, oldest first; the
     order tapped, numbered), "Show it in Moments on" first day / last day / "Every day it has" (only first when all
     on one day) with its hint, **Make it** → `StoriesController.makeMoment(itemIds:name:highlightIds:showOn:)` (ids
     oldest first; `ShowOn.first/last/each`), then the viewer's set mode on its cover. **Merge** only when no picked
     set has `named`.
  - Strings (English / Hebrew), as Android's `values/strings.xml` `set_*`, `recompose_*`, `make_moment_*`: Hold and
    slide to flip / החזיקו והחליקו כדי לדפדף; %d photos in this set / %d תמונות בסט; Part of %@ / חלק מ%@; Highlight /
    הדגשה; Play / הפעלה; Stop / עצירה; Recompose / מסגור מחדש; Fast / Normal / Slow = מהיר / רגיל / איטי; All photos /
    כל התמונות; Highlights / הדגשות; Hold one and drag to change the order / החזיקו אחת וגררו כדי לשנות את הסדר; Same
    frame for all / אותה מסגרת לכולן; Original / Square / 4:5 / Wide = מקורי / ריבוע / 4:5 / רחב; Make a moment / יצירת
    רגע; New moment / רגע חדש; Name / שם; Show it in Moments on / להציג ברגעים ביום; Every day it has / בכל יום שלו;
    Each day shows its own photos, with a link to the whole moment. / כל יום מציג את התמונות שלו, עם קישור לרגע כולו.;
    Its photos leave their days and show here together. / התמונות עוזבות את הימים שלהן ומוצגות כאן יחד.; Make it /
    יצירה.
  - Head start: the iOS lab's SwiftUI parts (`SetScrubber`, `SetSlideshow`, `SetSpeedPicker`, `SetFadePhoto`,
    `SetRecompose`, option d's naming screen) at the tag `ux/moment-set/2026-11-09`:
    `git checkout ux/moment-set/2026-11-09 -- iosApp/iosApp/Lab/MomentSet` (deleted from the lab after promotion).
  - Left to decide on the device: whether the feed's flipping should start without the hold.

- **Tucked away and long-press picking** (Android Nov 8, 2026; [moments.md](screens/moments.md#tucked-away-nov-8-2026),
  [journal-home.md](screens/journal-home.md), [search.md](screens/search.md), [viewer.md](screens/viewer.md),
  [explore.md](screens/explore.md)). Shared logic is done and already applies on iOS: tucked photos leave Moments, the
  storyline and Explore, and search never shows them. What iOS still lacks is the way to tuck, reveal and bring back:
  1. **Picking from a long press** in Moments, the storyline and Explore's town and year pages, replacing the context
     menu (Select goes). Taps pick/unpick, a set is picked whole. One bar for all of them and for search's picking:
     **Cancel · N selected · Mark private · Tuck away** (N counts photos), or **Bring back** in place of Tuck away when
     `allTucked(picked)`; above it, with one photo, **Write/Edit · Crop · Tag**; in Moments **Merge** (2+ picked) and
     **Take out** (one set picked: the photo long-pressed). Mark private asks first, as search's does; Tuck away
     doesn't. Offline: the toast text of `library_private_unreachable`, picks kept. Tuck away only when
     `serverLibrary.currentFeatures.tuckedAway`. Calls: `ServerLibrary.setTuckedItems(items:tucked:)` (and for search
     `SearchController.madeTucked(items:)` after it).
  2. **Day pills**: `FeedSection.tucked` (count) and `tuckedShown` on each Moments row: a "%d tucked away" pill under
     the divider (outlined, eye-slash; filled with an eye while shown), also on a row with no moments (a day all
     tucked: show its divider and pill only, no count line). Storyline: draw from `StoriesState.storylineDays`
     (`StorylineDay.day`, `items`, `tucked`, `revealed`) instead of `storyline`, a pill under a day's heading. A tap:
     `storiesController.tucked.toggle(days:)` with the row's `section.days` (or the storyline day), after the phone's
     lock when `tucked.needsLock` (`LAContext` `.deviceOwnerAuthentication`, the prompt Private settings uses), then
     `tucked.unlocked()`. A revealed tucked photo (`MediaItem.isTucked`) shows an eye-slash mark at its top corner.
  3. **Session**: call `storiesController.tucked.wentToBackground()` when the scene goes to the background and
     `cameBack()` when it is active again (5 minutes away resets it; the class keeps the clock).
  4. **Viewer details**: for `item.isTucked`, a "Tucked away" heading, the `tucked_viewer_body` text and **Bring back**
     (`setTuckedItems([item], false)`), behind the lock as in 2; **Unmark private** behind the same lock.
  Android files to mirror: `TuckedViews.kt` (pill, mark, lock, `PickingBar`), `MomentsFeedViews.kt` (`FeedSelection`,
  `picked`, `DayDivider`, `StorylineDay`, `storylineDayRows`), `JournalHomeScreen.kt` (the bar's extras),
  `ExploreViews.kt` (`ExplorePageLayout` picking), `SearchViews.kt` (`SearchPickingBar`), `ViewerInfoPanel.kt`,
  `LibraryApp.kt` (`TuckedActions`). Strings (both languages in `values*/strings.xml`): `tucked_away`,
  `tucked_tuck_away`, `tucked_bring_back`, `tucked_bringing_back`, plurals `tucked_pill`, `tucked_viewer_body`,
  `picking_write`, `picking_edit`, `picking_take_out` (Hebrew: מוצנע, הצנעה, החזרה, "%d מוצנעים"). Left to decide on
  iOS: whether the long press still lifts the photo (the context menu's preview) before picking.
  5. **Motion** (Android, Nov 8, 2026; `PickingMotion.kt`, see Motion in moments.md): ~250 ms, standard easing. Rows
     leave and come back in two steps (asked Nov 8, 2026): leaving, fade out 200 ms in place, then the space closes
     250 ms; coming back, the space opens 250 ms, then fade in 200 ms (stable `id`s; e.g. a row's opacity animated
     first, then removed from the data in a second `withAnimation`, and the reverse); the pill's fill/eye/count cross-fade (`.contentTransition(.numericText())` for the
     count); the picking bar slides up + fades over the tabs (`.transition(.move(edge: .bottom).combined(with:
     .opacity))`), its extras grow in and out; a picked photo scales to 0.92 with 14pt corners and its check springs in
     (`.spring`). Honour Reduce Motion.

- **Video thumbnails from the very first frame, and Make video thumbnails on this phone** (Android Nov 8, 2026;
  [settings.md](screens/settings.md), Image quality). Shared logic is done: server-only videos already ask for
  `frame=first` (`ServerPreviews.tile` and `firstImage(sha, w, h, isVideo)`), and with
  `PreviewOptions.videoFramesOnPhone` on and `ServerFeatures.videoFrames`, `tile` fetches the one-frame MP4
  (`ServerPreviews.firstFrameVideo`, `GET /media/{sha256}/frame`) and hands its path to the `original` decoder. iOS
  needs: (1) `ServerImages.videoFrame(url:maxSide:)` to take time `.zero` with `requestedTimeToleranceBefore` and
  `requestedTimeToleranceAfter` set to `.zero` (now 0.5 s, which a one-frame video doesn't reach); (2) videos in
  PhotoKit tiles from their first frame too (`PhotoLibraryImages`: `PHImageManager.requestAVAsset`, then the same
  generator) rather than Photos' own key frame, if the owner wants that on iOS as well; (3) in Settings › Image
  quality, last, a switch "Make video thumbnails on this phone" bound to `previewOptions.videoFramesOnPhone`, calling
  `SettingsController.setVideoFramesOnPhone(on:)`, with the body text of Android's
  `settings_video_frames_on_phone_body` (strings `settings_video_frames_on_phone` and `_body` in
  `values/strings.xml` and `values-iw/strings.xml`).

- **Onboarding › Models on this phone** (Android Nov 8, 2026; [enrollment.md](screens/enrollment.md#models-on-this-phone-nov-8-2026-android-only)).
  Shared logic is done: `ModelsOnboarding` (`local/ModelsOnboarding.kt`) with `state` / `currentState` / `observe`
  (`ModelsOnboardingState`: `show`, `models` (a `PhoneModelsState`, with `known`), `searchByWords`) and `enrolled()`,
  `start()`, `setSearchByWords(on)`, `setWifiOnly(on)`, `finish()`, `close()`; it needs the `PhoneModels` of the entry
  below. iOS needs: an `IosJournal` factory (Android: `modelsOnboarding` in `AndroidJournal.kt`), `enrolled()` when the
  wizard ends (Android: `MainActivity`'s `onOpenJournal`), the step after photo access while `show` is true, calling
  `start()` once when it shows (Android: `LibraryApp`), a view mirroring `ModelsOnboardingScreen.kt` (system theme,
  a card per offered model with its size and a bar, Search by words' check box, Only on Wi-Fi, Continue), and the
  `models_onboarding_*` strings. On iOS only Search by words is listed (no photo model), so with a server without
  local indexes the step skips itself.
- **Settings › Models on this phone** (Android Nov 8, 2026; [settings.md](screens/settings.md#models-on-this-phone),
  round `ux/settings/2026-11-08`). Shared logic is done: `PhoneModels` (`local/PhoneModels.kt`) with `state` /
  `currentState` / `observe` (`PhoneModelsState`: `models` as `PhoneModelRow`s with `kind`, `status`, `bytes`,
  `downloadedBytes`, `downloadingBytes`, `withBytes`; `wifiOnly`, `offline`, `freeBytes`, `here`, `missing`,
  `missingBytes`, `keptBytes`) and `refresh()`, `download(kind, force)`, `pause(kind)`, `delete(kind)`,
  `downloadAll()`, `setWifiOnly(on)`, `close()`. On iOS the photo model doesn't exist (Vision's feature print is
  built in), so build it as `PhoneModels(photos = null, indexes = localIndexes, searchOffered = <serverInfo.localIndexes as a StateFlow>)`:
  the page lists Search by words alone. iOS needs: an `IosJournal` factory for that, a `ModelsView` mirroring
  Android's `ModelsScreen.kt` (intro, the offline line, a row per model with its status and one button, Download
  all, Only on Wi-Fi, the space line), its row in Settings' Search card (`ModelsRow`: "1 of 2 downloaded" /
  "Downloading…", shown when `models` isn't empty), and the `models_*` strings (English and Hebrew in Android's
  `strings.xml`) in `Localizable.xcstrings`. Left to decide: whether a page with one model is worth a row on iOS, or
  Search by words' model is enough in On device search there.

- **Certificate renewal** (Android Oct 30, 2026; [design.md](design.md), "Certificate renewal"). Shared logic is done:
  `CertificateRenewal` (`enrollment/CertificateRenewal.kt`) with `renewIfDue(onDone)`, `expiresAt()` and `close()`.
  iOS needs: an `IosJournal` factory like Android's `createCertificateRenewal` (in `AndroidJournal.kt`: settings, the
  key store, `HttpRenewApi(serverHttp)`, `CertificateRenewal.connectionTest`, `journal::useRenewedIdentity`), a call
  to `renewIfDue` at app start (Android: `JournalViewModel`'s `init`), and `IosDeviceKeyStore.certificateNotAfter`
  (the Keychain certificate's notValidAfter) for iPhones enrolled before Oct 30, 2026, which don't have the end in
  settings. No screen and no strings.

- **Copies on a friend's server** (Android Oct 29, 2026; [screens/settings.md](screens/settings.md), Backup). iOS
  has the Copies page; what it lacks: `DataCopy.onPeer` (name the card with
  `copies_on_peer`), `encrypted` (a lock, `copies_encrypted` for VoiceOver), `CopyProblem.PEER_UNREACHABLE` / `QUOTA_FULL`
  (`copies_peer_unreachable`, `copies_quota_full`), `freeBytes` read with `copies_quota_left` when `onPeer`, and
  `DataCopy.canCopyNow` for whether Copy now shows.

- **JPEG photos packed as JPEG XL on the phone** (Android Oct 28, 2026; see [design.md](design.md), "JPEG photos are
  kept as JPEG XL"). `IosMediaFiles.encodeJxl` needs libjxl (an xcframework), so iPhones send the JPEG and the server
  packs it. **Photos as JPEG XL** in Backup's Keep on this phone card isn't offered: it would put the JPEG XL in the
  photo library in place of the JPEG, a PhotoKit write (PhotoSafetyTest), which needs the owner's approval first.

- **Quick image while scrolling, while the list moves fast**: Android shows only the quick image while a list moves
  fast (`Thumbnails.kt`); iOS shows it first and asks for the sharp one once a tile has stayed 0.3 s, so a fling still
  asks the server for the sharp tiles it settles on.

- **Not checked on iOS yet** (no server with these on the simulator on Oct 29, 2026): an Ogg Opus voice message played
  in the app (the decoder was checked on the Mac only); "Only on these Wi-Fi networks" on a real iPhone (the simulator
  has no Wi-Fi name to read); Copies on other disks, Your
  cameras, Sound folders and Private settings by kind against a server that has them; Share JPEG XL as JPEG with a JPEG
  XL photo; and, from the library database (Oct 26, 2026), that `server/media.json` is read in once and deleted, that
  face avatars still get their photo's size, and that marking a photo private still finds its sha256.

Planned, not built on either platform yet:

- **Hiding one photo or moment** (screen 4 of [plans/hide-whatsapp.md](plans/hide-whatsapp.md)); needs the server.
