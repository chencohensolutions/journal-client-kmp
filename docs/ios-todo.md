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

- **Crop chip always on screen** (Android Nov 9, 2026; [viewer.md](screens/viewer.md)): in the viewer, the Cropped ·
  Show all chip stays over a cropped photo instead of showing only with the bars (no hold-to-peek); in a set it shows
  over a recomposed photo too. Android: the `CropChip` visibility in `ViewerScreen.kt`. No new strings.
- **Slideshow waits for the next photo** (Android Nov 9, 2026; [viewer.md](screens/viewer.md#a-set-nov-9-2026)):
  while one photo shows, the next is decoded at the viewer's full size (downloaded if server-only), and the switch
  waits for it (at most 15 s), so it never shows blurred first; the photo page starts from those kept images.
  Android: `Slideshow(prepare:)` in `MomentSets.kt`, `viewerImages` / `viewerImage` in `ViewerScreen.kt`. No strings.
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
