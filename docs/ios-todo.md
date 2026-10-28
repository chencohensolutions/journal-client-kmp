# iOS: not built yet

Work done on the Windows machine is Android only (no Xcode there). Each entry says what Android has and what the
SwiftUI side needs, so it can be finished on the Mac. Remove an entry once iOS has it.

Built on iOS on the Mac (Oct 21, 2026): Moments, Explore, backup and its settings, and the where-the-original-is
badge, plus crop, server-only photos and videos, Storage on this phone, Private media and Free up space; then, the
same day on both platforms, writing with a title, paragraph and history, and the storyline in place of stories. On
Oct 22, 2026: the viewer's zoom (with the minimap, remembered zoom, download cloud and hidden system bars), Explore's
list of countries with a country's page, and the feed's spacing, day lines, chips and hidden-source band matched to
Android's. On Oct 26, 2026: the library database (plan B phases 1–6), Private settings, sound recordings (Moments and
their page), and backup of calls and recordings from folders with Free up space by groups.

Still missing on iOS:

- **Backup › Copies on other disks** (Android Oct 28, 2026; [screens/settings.md](screens/settings.md), Backup).
  Shared logic is done: `ServerCopies` (`server/ServerCopies.kt`) with `currentState: ServerCopiesState`, `refresh()`
  (when Backup opens), `watch()` (while the page shows; cancel the handle on leave), `copyNow(folder)`, `stop(folder)`,
  `observe`; a pass's `CopyRunning` (`counting`, `fraction`, `remainingMillis(now)`, done and total files and bytes)
  and `DataCopy.heldFiles`;
  `ServerFeatures.copies`. Each `DataCopy` has its counts, `health` (`CopyHealth` OK/BUSY/WARN/ERROR, for colors and
  marks), `upToDate`, `failedFiles`, `sameDisk`, `problem` (`CopyProblem`); the row's line is
  `ServerCopiesState.summary` (`CopiesSummary` NeedsALook/Copying/Checking/AllUpToDate/SomeUpToDate, checked with `is`).
  iOS needs: an `IosJournal` factory like `serverIndexing`'s; the row at the end of Backup's top card when
  `features.copies` and the state is loaded with copies; a Copies page mirroring `androidApp/.../CopiesScreen.kt`
  (`CopiesRow`, `CopiesScreen`, `CopyCard`, `PassProgress`, `ButtonLine`, `etaText`, `copyStatus`, `CopyDetails`,
  `CopyMark`). Strings: the `copies_*` ones in
  `androidApp/src/main/res/values{,-iw}/strings.xml`.

- **JPEG photos as JPEG XL** (Android Oct 28, 2026; see [design.md](design.md), "JPEG photos are kept as JPEG XL").
  Shared logic is done (`BackupEngine` sends a JPEG as its JPEG XL when `MediaFiles.encodeJxl` gives one; matching by
  `encoded_sha256`). iOS needs: libjxl (an xcframework) for `IosMediaFiles.encodeJxl`, so iPhones pack JPEGs too
  (until then they send the JPEG and the server packs it); **Share JPEG XL as JPEG** in Settings › Library (iOS 17
  decodes JPEG XL, but sharing as JPEG needs libjxl or the JPEG fetched from the server by its sha256); and Backup's
  **Keep on this phone** card ([screens/settings.md](screens/settings.md)): `KeepOnPhoneSection` becomes a card with a
  switch per kind, "Recordings as FLAC" and "Photos as JPEG XL" (the latter only once PhotoKit can take the JPEG XL in
  place of the JPEG, which needs the owner's approval as a PhotoKit write, PhotoSafetyTest).

- **Lowercase folder ids, sound kinds, Sound folders** (Android Oct 27, 2026; shared logic done). See
  [screens/settings.md](screens/settings.md) (Sound folders) and [moments.md](screens/moments.md).
  - **Names:** folder ids are in lower case (`folder:petcube`); show a folder by `MediaSource.folderName` (the app
    table, the server's `folderNames`, the phone's spelling, else a capital), never by `folder`. Only
    `MomentsFeedViews.swift`'s `title` named folders, and it uses `folderName` now (build to check).
  - **Sound kinds:** `MediaSource.companion.CALL` and `RECORDING` exist; name them "Calls" / "שיחות" and "Voice
    recordings" / "הקלטות קול" where a source is named (Android: `FeedChrome.kt` `sourceName`, strings
    `feed_source_call`, `feed_source_recording`). They are never photo pills (`MediaSource.choices` leaves them out).
  - **Backup:** nothing to do: a sound folder's `source` (shared) now uploads calls as `call` and recordings as
    `recording`, and its files go with the folder's name as their `folder` (shared `BackupEngine`).
  - **Sound folders page** (Android: option b, promoted Oct 27, 2026; `SoundFoldersScreen.kt`): a **Sound folders**
    row in `SettingsSections.swift` under Library after Your cameras, shown when `features.soundFolders`, its line
    ("4 folders · 1 as Calls · 1 as Voice recordings", "Moving 953 files…", "Which folders are calls or voice
    recordings" before the list; ask `library.soundFolders()` when Settings opens), and a SwiftUI page: groups Calls,
    Voice recordings, Kept as folders, a "Move to…" menu per folder, the question inside the row (Move / Cancel), a
    band at the top while moving. Built on `SoundFoldersController(library:)` (`load()`, `setMark(folder:mark:)`,
    `retry()`, `close()`, `observe`, `currentState`: `SoundFoldersStateLoading`, `SoundFoldersStateUnreachable`,
    `SoundFoldersStateReady` with `folders: [ServerSoundFolder]` (`shownName`, `files`, `imported`, `mark`,
    `pending`), `saving`, `notChanged`, `pending`), as `CamerasController` is. Strings: Android's `sound_folders_*` and
    `settings_sound_folders_*`.

- **Sources: WhatsApp as a folder, Sent to you, Imported, search, Private settings' apps, a photo's camera**
  (Android Oct 27, 2026; shared logic done, iOS won't compile until the first step). See
  [screens/settings.md](screens/settings.md), [moments.md](screens/moments.md), [search.md](screens/search.md),
  [viewer.md](screens/viewer.md).
  - **`MediaSource` is a class now, not an enum with `.whatsapp`:** the fixed ones are `MediaSource.companion.CAMERA`,
    `SCREENSHOTS`, `RECEIVED` (new: "Sent to you" / "נשלח אליך", Android's icon is an inbox tray, `FeedIcons.Inbox`)
    and `OTHER`; WhatsApp is the folder `MediaSource.companion.WHATSAPP` (`folder:whatsapp`); an imported one has
    `isImported` and `kind` (Camera for `import:camera`). Switch on `kind`, and name a folder by `kind.folderName`
    (`kind.folder` is the id's name, in lower case since Oct 27, 2026; `MomentsFeedViews.swift`'s `title` already
    uses `folderName`, written on Windows and not compiled):
    `MomentsFeedViews.swift` ~927–945 (`sourceName` and the icon), `MediaViewerView.swift` ~720,
    `ShownSourcesViews.swift:65` (`counts[.whatsapp]` becomes `counts[MediaSource.companion.WHATSAPP]`).
  - **Showing sheets (option b2 of the UI lab):** Explore's and Moments' Showing sheets draw
    `ShownSourcesPills.companion.of(shownSources, counts, serverLibrary.hiddenEverywhere.value)`: `fromPhones` pills
    (dimmed with a lock and opening Private settings when `allHiddenEverywhere`, a lock after the count when
    `hiddenEverywhere > 0`), then an **Imported** pill (`hasImported`, `importedCount`, on = `importedShown`,
    `settings.toggleImported()`), and while on a tinted box "Of what was imported" with `importedKinds` as smaller
    pills (`settings.toggleFeedSource`); the Other line and the Private settings line under them
    (`ShownSourcesViews.swift`). Android: `ShownSourcesPills.kt`, `ShownSourcesViews.kt`. Settings › Show in the app
    no longer draws them: see "Show in the app as a status page" below.
  - **Moments' bar:** an **Imported** chip after the sources' chips while `feed.importedCount > 0` (box icon kept
    when on, `FeedIcons.Imported`); tap: `toggleImported`. Android: `FeedChrome.kt` `FeedSourceBar`.
  - **Search:** "12 more in sources you hide · Show" under the count from `SearchState.hiddenBySources`, Show calling
    `showHiddenSources()`, in `SearchViews.swift` and `SearchViewModel.swift` (Android: `SearchViews.kt`).
  - **Private settings:** replaced by "Private settings by kind" below (the Apps section and "Screenshots in Bumble"
    pills Android had for a few hours are gone).
  - **A photo's info:** "Saved in: Telegram" for a folder's kind (else "From"), "· Imported" for imported ones, and
    "Camera: Canon EOS 80D" from `serverLibrary.file(sha256).camera` (find the sha with `sha256Of(item)`). Android:
    `ViewerInfoPanel.kt` `sourceFacts`. Swift: `ViewerOriginalCard.swift`.
  - **Strings** (`Localizable.xcstrings`, English and Hebrew as Android's `values` / `values-iw`): `sources_imported`,
    `sources_of_imported`, `sources_everywhere`, `sources_more`, `sources_private_about`, `sources_other_about`,
    `feed_source_received`, `library_info_saved_in`, `library_info_camera`, `search_hidden_more`,
    `search_hidden_shown`.
  - **Show in the app as a status page** (Android Oct 27, 2026, kinds c3 of the UI lab; shared logic done; see
    [screens/settings.md](screens/settings.md)): the page has no switches. Build `KindsStatus.companion.of(
    libraryState.sourceTotals(), libraryState.sounds, backupState.soundsNotBackedUp(),
    serverLibrary.hiddenEverywhere.value)` and draw: "87,343 files" (`files`) with "15 not backed up ›" in orange
    (`notBackedUp`, opens Backup) or "All backed up"; **Photos and videos**, a card of `kinds` (icon, `sourceName`,
    the total right-aligned in a column, "12 not backed up ›" under the name when `notBackedUp > 0`, opening Backup);
    **Sound**, a card of `sounds` (Calls, Voice recordings, Voice messages by `AudioKind`); when `hiddenEverywhere`
    is above 0, "🔒 2 kinds hidden on all your devices · **Private settings ›**" (opens it); then the note on the
    Moments filter bar. Settings' row line: "87,343 files · 15 not backed up" (or "· All backed up"). **Say what's
    hidden** and **Filter bar in Moments** move to Settings › Moments, under "What Moments shows", with the storyline
    note. Android: `ShowInAppPage.kt`, `SettingsScreen.kt` (`MomentsPage`), `LibraryApp.kt`. Strings: `kinds_*`,
    `settings_moments_filter_title`.
  - **Private settings by kind** (Android Oct 27, 2026, private c2 of the UI lab; shared logic done): still behind
    the lock, Show private media first; then **Show on all your devices** with `private_intro`, and one card from
    `HiddenCollectionsStateReady.kinds` (`PrivateKinds`): each kind a Show toggle (on = shown; off says "19,700 ·
    Hidden on all your devices" in orange), the most files first; the `SCREENSHOTS` kind has no toggle but a row of
    chips from `kinds.screenshotApps`, each just the app's name ("No app name" for the empty one) and its count,
    filled with a ✓ while shown. Every switch and chip calls `controller.show(kind:shown:)` (ignored while `saving`).
    Nudity's card stays as it is, after the list; Loading, Unreachable and "Not changed" as before. The old Apps,
    Sound recordings and Screenshots sections, Find an app and the folded app list go (`query`/`shownApps` stay in
    the state for now). Android: `HiddenScreen.kt`. Strings: `private_everywhere`, `private_intro`,
    `private_hidden`, `private_screenshots_body`, `private_no_app`, `kinds_voice_messages`. **The query asks
    `sentToYou`**, so iOS needs a server with it (journal-server-go, Oct 27, 2026).
  - **Your cameras** (Android Oct 27, 2026; shared parts done; see [screens/settings.md](screens/settings.md)). Left
    in Swift: (1) a **Your cameras ›** row in `SettingsSections.swift`'s Library section, after Show in the app, shown
    only when `serverLibrary.features.value?.cameras == true`; its line from `serverLibrary.cameras()` asked when
    Settings opens (and the page's last `CamerasStateReady`): "4 cameras · 1 not yours" (`settings_cameras_count`,
    `settings_cameras_not_yours`, joined by " · "), "4 cameras" when all are, "Moving 312 photos…" (`cameras_moving`)
    while `pending > 0`, "Which cameras were yours" (`settings_cameras_unknown`) before the list came. (2) A SwiftUI
    page: a `CamerasController(library: serverLibrary)` per opening (`load()` on appear, `close()` on disappear,
    `observe` / `currentState`); `CamerasStateLoading` / `Unreachable` (`retry()`) / `Ready` (`cameras`, `saving`,
    `notChanged`); a card of rows `camera.name`, "1,240 · 2025–2026" (`files`, `firstYear`–`lastYear`, the years kept
    left to right in Hebrew), "Its photos are in Sent to you" when `!mine && pending == 0`, a **Mine** toggle (disabled
    while `saving`); on → `setMine(camera, mine: true)`, off → an alert ("312 photos will move to Sent to you",
    `cameras_confirm_title` / `cameras_confirm_body`, **Move** → `setMine(camera, mine: false)`), the camera asked
    about kept in `@State` by make and model; while `pending > 0` "Moving 312 photos…" and a bar of
    `1 - pending / files`. Android: `CamerasScreen.kt`, `SettingsScreen.kt` (`camerasSummary`), `LibraryApp.kt`
    (`Route.Cameras`). Strings: `cameras_title`, `cameras_intro`, `cameras_unmarked`, `cameras_mine`,
    `cameras_sent_to_you`, `cameras_move`, `cameras_confirm_body`, `cameras_confirm_title`, `cameras_moving`,
    `settings_cameras_*`.

- **Settings › Advanced › Loading Moments** (Android since Oct 26, 2026; see [screens/settings.md](screens/settings.md)):
  an Advanced section after Search with a Loading Moments row ("30 rows at a time · 40 ahead") opening a page with
  Rows loaded at a time (15, 30, 60, 120) and Load ahead (10, 20, 40, 80), the defaults marked, and Reset to 30 and
  40. The shared part is done: `settings.state.momentsPaging`, `setMomentsRowsPerPage`, `setMomentsLoadAhead`,
  `resetMomentsPaging`, and the pager already reads the rows per page on iOS. Left in Swift: the section and page
  (`SettingsSections.swift`), and `JournalHomeView.swift`'s `loadAheadRows` (40 today) read from
  `momentsPaging.loadAhead`.

- **Backup → "Only on these Wi-Fi networks"** — Android lists the networks backup may run on. iOS reads the
  network's name only with the Access WiFi Information entitlement plus location permission
  (`NEHotspotNetwork.fetchCurrent`), which needs the owner's Apple developer team and a real iPhone to check; the row
  is hidden on iOS until then. See [screens/settings.md](screens/settings.md).

- **Sound recordings in Ogg or Opus** (some WhatsApp voice messages and recorders): AVPlayer can't play them, so iOS
  says "The recording couldn’t be played" (sound recordings were built on iOS on Oct 26, 2026). They need another
  decoder or a stream the server converts.

- **The library database, what is left** (plan B; built on iOS on Oct 26, 2026: the database and its `-lsqlite3` link
  flag in `Config.xcconfig`, Moments read a page at a time with going to a date and "show in Moments" reading down to
  their row, Explore, its pages and the map from the database through `ExploreReads.swift`, the viewer, similar photos
  and crop keeping the items they were opened with and asking `StoriesController.want(ids:)`, and the debug made-up
  library from the launch arguments, `-madeUpLibrary 300000`). Still to do: PhotoKit is read whole on every load
  (about 11 s for the simulator's 122 photos with 300,000 made-up rows beside them); make `IosMediaLibrary` a
  `ChangingLibrary` with `PHPhotoLibrary.fetchPersistentChanges(since:)` and the token kept as the mark. Not checked
  yet on iOS: that `server/media.json` is read in once and deleted, that face avatars still get their photo's size,
  and that marking a photo private still finds its sha256 (both read from the database now).

Planned, not built on either platform yet:

- **Hiding one photo or moment** (screen 4 of [plans/hide-whatsapp.md](plans/hide-whatsapp.md)); needs the server.
