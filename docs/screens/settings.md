# Settings

The user's choices, kept on this device (SharedPreferences / NSUserDefaults). Opened from the Journal's **Settings** button (the **⋯** menu until Oct 24, 2026).

## Layout

Since Oct 24, 2026 (chosen by the owner from the UI lab's options), Settings is a short first page of groups, each row
saying how it stands and opening a page of its own; the switches used most stay on the first page:

- **Top bar:** back and "Settings" (on a page: its name).
- **Library:** **Show in the app ›** ("Showing Camera, Other, Petcube +2", the most photos first), **Moments ›** ("By
  day · similar at 60%", "New row after 12 hours · similar at 85%"), and the **Show the phone badge** switch (below).
- **Privacy:** the **Show private media** switch and **Hidden collections 🔒 ›** (below).
- **Backup and storage** (when the app has them): **Backup ›** (its status line; a screen of its own, below),
  **Storage on this phone ›** ("1.8 GB of 10 GB · WebP, High", or "· Originals" with previews off), **Free up space ›**
  (its line, below).
- **Search ›** on its own (its line, below).

The pages, and what each holds:

- **Show in the app** (a page; first; Oct 21, 2026): a pill per source with its count ("WhatsApp · 10,282", "Petcube · 5"),
  filled with a ✓ when shown, the most photos first (`ShownSources.choices`; pills since Oct 24, 2026, when each folder
  and app became a source, see [Moments](moments.md) for how a photo's source is told, and a switch per source grew too
  long). Everything but WhatsApp and Screenshots is shown at first, a folder seen for the first time too; a hidden
  source stays listed so it can be shown again. Then **Say what's hidden** ("“+6 WhatsApp” on a day, tap to see them"; on), which turns the
  [Moments](moments.md) chips on or off, and **Filter bar in Moments** ("The row of sources above the feed. When off,
  choose them here."; on; asked Oct 24, 2026; `ShownSources.showFilterBar`, kept as `show_filter_bar`), which hides
  the Moments filter bar. Under them: on this phone only; it applies to Moments, Explore and Tags; photos
  you wrote about always show on the storyline; hiding never deletes anything and doesn't change
  what is backed up. The Moments filter bar and Explore's **Showing** sheet change the same setting
  (`ShownSources`, kept as the sources hidden: `hidden_sources` (until Oct 24, 2026 the fixed sources shown,
  `shown_sources`, read once), `say_whats_hidden`, `always_shown_days`). Then **On all your devices**: the Hidden
  collections row, for hiding on every device rather than this phone.
- **Backup** (a screen of its own since Oct 24, 2026; Android; iOS since Oct 21, 2026, without "Only on these Wi-Fi networks": iOS reads the network's name only with the Access WiFi Information
  entitlement and location permission, which couldn't be checked on the simulator (it has no Wi-Fi name) or with the
  owner's signing team; the switch's line on iOS says that swiping the app away stops uploads until it is opened).
  A screen of its own since Oct 24, 2026 (asked when What to back up got a choice per folder and grew too long):
  Settings has a **Backup** row with the status line below and a chevron, opening **Settings › Backup**
  (`BackupSettingsScreen` on both platforms; Android route `backup-settings`), which holds:
  - **Back up to your server** switch (off until turned on), and a status line: "Off. Nothing is uploaded.",
    "Backing up 3 of 120", "12 photos waiting to upload", "Everything chosen is backed up", "Waiting: …" (Wi-Fi network
    not on the list, location needed to check it, register again, photo access), or "Stopped: … It tries again
    later." **Back up now** beside it while not running.
  - **On your server**: what the user's originals take up there, "4.2 GB · 1,234 photos, 56 videos" (server query
    `storageUsage`), also with backup off. Asked when Settings or Backup opens and again when a backup run ends; "Can't reach the
    server right now" until it answers.
  - With backup on: **What to back up**, a pill per source with its count ("Petcube · 5"), filled with a ✓ when on,
    the most photos first (`BackupSettings.choices`: the fixed sources, each folder or app the phone has photos of,
    and those chosen; **Camera** on by default). A new folder is off
    until turned on. Camera is DCIM/Camera only since Oct 24, 2026: other DCIM folders (which Camera took before) are
    choices of their own, off, as the owner chose; what they uploaded stays on the server. The server is told each
    file's source (`camera`, `folder:Petcube`, …), so a photo only on the server keeps its folder.
    **Folders of calls and recordings** share that list (Android, since Oct 25, 2026; UX round
    `ux/backup/2026-10-25`, b2): one list of pills (`BackupPills`, `BackupState.pills`), photo sources and sound folders
    side by side, not grouped, the ones backed up first, each the most files first, a folder with the icon of what it
    holds; **+ Add a folder** last. It opens Android's folder picker at Internal storage › Recordings › Call (where
    Samsung keeps calls); after **Use this folder** and **Allow**, a dialog shows the folder's name and place, what was
    found ("6,196 sound files · 3.2 GB"), **What are they?** (Phone calls, Recordings or Voice messages, the app's guess
    from the names chosen: Samsung's "Call recording …"/"הקלטת שיחה …"/"שיחה …" are calls, WhatsApp's `PTT-`/`AUD-`
    voice messages), and **Back it up**. The folders are found only this way: the owner keeps a `.nomedia` file in them,
    so Android's media index never lists them. The app keeps read and write access to the folder (write for Free up
    space and for keeping FLAC). A tap turns a folder on or off; a long press asks **Stop backing up Call?** (what it
    sent stays on the server); a folder the app can no longer read says "Call · pick again", and a tap opens the picker.
    Under the pills, once a folder is added: "Folders of calls and recordings are backed up even while they are hidden
    in Private settings." Once a folder has WAVs: "WAV recordings are sent as FLAC: the same sound in about half the
    space", with **Keep on this phone**: **WAV** (default; the WAV stays as it is) or **FLAC** (the FLAC replaces the WAV
    once the server confirmed it);
    **When**: **Only on Wi-Fi** (on by default; never on mobile data), **Only while charging** ("Saves the battery;
    uploads wait for the charger."), and, under Wi-Fi only,
    **Only on these Wi-Fi networks** with **Add this network** (asks for location, then lists the network the phone is
    on; × removes one). With a network listed and no "All the time" location, a note asks for it, since the network
    can't be checked in the background otherwise. **Order**: **Oldest first** (default) or **Newest first**.
- **Show the phone badge** (on the first page, under Library; both platforms, asked for Oct 21, 2026), a switch, **off** by default: the
  green phone on grid tiles and Moments photos that are on this phone and backed up (see
  [README.md](README.md#backup-badges)). Off, a photo just taken and uploaded has no badge; waiting, uploading and
  failed photos always have one, and the viewer's details always say where the original is (`show_on_phone_badge`).
- **Storage on this phone** (a page: **Kept on this phone** first, then **Image quality**; Android, asked for Oct 20, 2026; iOS since Oct 21, 2026. iOS
  writes it as a list: Format and the limit (**Up to**) are menus, Quality a segmented control, and ImageIO decodes
  every format itself, JPEG XL from iOS 17):
  - **Previews from your server** switch (on by default; asked for Oct 20, 2026). On: the server makes each photo at
    the exact pixels it is drawn at, in a **Format** (WebP default, HEIF, AVIF, JPEG XL, JPEG; a line says what each is
    good for) and **Quality** (Low, Medium, **High**, Max), and every size is kept as its own file: a size the phone
    has, or a photo whose original it has, is never asked for again, and a smaller preview is never scaled up instead.
    Off: the original files are downloaded (checked against their sha256) into the same space and decoded on the
    phone at the size shown; a file the phone can't decode (Samsung's DNG 1.7) still gets a preview, and videos are
    not downloaded (posters and streaming come later). JPEG XL is decoded by a library the app ships
    (`io.github.awxkee:jxl-coder`, about 6 MB of native code per CPU type), since Android has no decoder of its own.
  - **Kept on this phone**: "2.3 GB of 10 GB used", a limit of **1, 2, 5, 10 (default), 20 or 50 GB**, and **Clear**.
    Previews and downloaded originals of server photos are kept up to the limit (the oldest go first) and fetched
    again when scrolled to; none are pinned, also for photos removed from the phone.
  - Android only, at the end of Image quality: **Quick image while scrolling** (a section of its own, "Advanced",
    until Oct 24, 2026): the first image of a photo only on the server, shown at once and while a list moves fast,
    before its sharp preview: its width in percent of the screen's (or **None**), and its format.
- **Free up space** (Android; iOS since Oct 21, 2026, see Behavior): a row under Backup and storage, "14 GB can be freed" (or "Checking 3 of 120", "120 files (3.2 GB) checked, waiting
  for you", "Nothing to free up right now"), opening its own screen (UX chosen by the owner on Oct 20, 2026: one button
  plus More options, a summary before running, sources to choose, manual only):
  - A summary: the size in large type, what the rule takes ("Photos and videos already on your server", "Taken more
    than 1 year ago", "All but the newest 10 GB"), "1,240 photos, 38 videos · Mar 2019 – Oct 2025", and one button,
    **Free up 14 GB**. No per-photo review.
  - **From**: the same list of pills as What to back up, folders of calls and recordings included (`FreeUpState.pills`),
    each with what of it would go ("Call · 5,810 · 3.2 GB"); all on by default, a new folder too; kept as the sources
    kept on the phone (`free_up_kept`; at least one source stays on) and the sound folders kept (`free_up_kept_folders`).
    The summary counts them: "1,240 photos, 38 videos, 5,810 sound files". Sound files have no trash, so when the plan
    has any, **Free up** first asks **Delete for good?** ("5,810 calls and recordings will be deleted from this phone for
    good: their folders have no trash. Your server keeps them."), and only **Delete and free up** lets the run delete
    them (`FreeUpController.confirmSounds`, for that one run).
  - **More options** (open when a rule other than the default is chosen): **Everything backed up** (default), **Older
    than** 1, 3, 6 months, **1 year** or 2 years (by date taken), or **Keep the newest** 1, 2, 5, **10**, 20 or 50 GB
    (by date taken; everything on the phone counts toward what is kept, backed up or not, of any source). Each choice's
    values show under it while it is chosen.
  - Only photos this phone backed up, as they are now, are removed; a pair (same file name apart from the extension,
    taken within 2 s: a RAW and its JPEG) leaves together or not at all. While it runs, "Checking 3 of 120 ·
    IMG_1234.jpg"; afterwards what it did ("120 files (3.2 GB) moved to the trash", "2 changed on this phone: backed up
    again", "1 not on your server: backed up again", "1 damaged on your server: kept on this phone"), and "120 files
    (3.2 GB) checked, waiting for you" with **Move to trash** when files wait for the system prompt. It never runs by
    itself.
- **Moments** (a page): **Rows in Moments**, **By day** (default; one row for each calendar day) or **By breaks between
  photos** (a new row starts after a break without photos, so an evening that runs past midnight, or a day out that
  starts before dawn, stays in one row), with **New row after a break of** 2, 4, 6, 8, **12** (default), 24 or 48 hours
  right under By breaks while it is chosen (chips on Android, a menu on iOS); then **Sets of similar photos** (called "Suggestions for similar
  photos" until Oct 24, 2026): **How alike** a slider from 50% to 99%, with the value shown: the cosine
  similarity two photos' image embeddings need to be one near-duplicate set in [Moments](moments.md#how-it-is-grouped)
  (since Oct 21, 2026 only there; it also drove story suggestions until stories were removed). Higher sets only
  near-identical photos together; lower makes bigger, looser sets. The default is the platform model's
  own (**60%** Android, **80%** iOS); **Reset to N%** shows when it's changed.
- **Privacy** (on the first page; both platforms; iOS since Oct 21, 2026): one row with a lock, **Private settings**
  (called Hidden collections until Oct 25, 2026), "Private media, nudity, sound recordings, WhatsApp and screenshots by
  app", see below. On Android, **Show private media** moved into that screen on Oct 25, 2026 (iOS still has it here,
  a switch, **off** by default, for the photos the user marked private). The nudity slider
  that was here moved into Hidden collections (Oct 23, 2026), where the server does the hiding.
- **Hidden collections** (its own screen, asked for Oct 22, 2026; both platforms): opens only after the phone's lock
  (Android: fingerprint, face, PIN, pattern or password, the system's `BiometricPrompt` with device credential; iOS:
  Face ID, Touch ID or the passcode, `LAContext` `.deviceOwnerAuthentication`), every time it opens and again after
  the app was in the background; a phone without a screen lock (or passcode) can't open it. An intro says
  hidden means hidden on all devices (not in the library, its counts or search), nothing is deleted, and switching
  off shows them again. Above that, on Android (Oct 25, 2026), **Private media** with **Show private media**, a
  switch, **off** by default, for the photos the user marked private; it is this phone's choice, not the server's.
  **Nudity** (Oct 23, 2026): "Show photos and videos with nudity · N photos and videos · M
  not scanned yet" (a Show switch since Oct 25, 2026 on Android: **off**, so hidden, by default; it read "Photos and
  videos with nudity", on meaning hidden, before), and while off **Hide from**, a slider from 10% to 95% in steps of 5 (default
  **50%**): the nudity score the server's scan must reach for a photo or video to be hidden, saved when let go.
  **Sound recordings** (Android, Oct 25, 2026; on a server that has them): **Show recordings**, **Show phone calls**
  and **Show WhatsApp voice messages**, each with how many there are, all **off** at first (here on means shown,
  unlike the other switches). Shown ones appear in [Moments](moments.md).
  **WhatsApp**: "All WhatsApp photos and videos · N". **Screenshots**: "Without an app name ·
  N", **Find an app** (filters the list), then the apps as pills, most screenshots first ("Bumble 1,458"; on Android
  since Oct 25, 2026, a switch per app before that and still on iOS). A pill is filled with a check while its app's
  screenshots are **shown**, outlined while hidden; tapping it switches. The list is folded at first: **Apps** "5 of 6
  shown" with only the shown apps' pills; tapping the row opens it with every app, **Find an app** and a hint. On means hidden. The list and the choice live on the server (`hiddenCollections`,
  `setHiddenCollections`); each switch is saved at once (the others wait only for that, well under a second), and if
  the server can't be reached the switch goes back with "Not changed: can't reach your server". The library syncs
  once, when the screen is left (`HiddenCollectionsController.close` → `ServerLibrary.syncAgain`), not after each
  switch, because every change makes the whole change feed come again. While that sync reads more than one page, the
  Hidden collections row in Settings shows "Updating your library: 38,000 of 95,412" with a bar
  (`ServerLibrary.syncProgress`, from the feed's `remaining`).
- **Search** (both platforms; its own screen since Oct 24, 2026, see "Search" below): one row, "Server: reading 40 of
  200", "Server: paused at 40 of 200", "Server: waiting to read your photos", or "200 per page · 3 kept on this
  phone".

## Behavior

- A change applies right away; the Journal rebuilds its rows from the same photos.
- The break length is kept while **By day** is chosen, so switching back restores it.
- The similarity value is saved when the slider is let go; Moments' sets update right away (no photo is read again).
  Near-duplicates by dHash are set together at any value. Only the default is stored as "none", so a
  better default later reaches everyone who didn't choose one.
- The grouping setting changes only the rows of the Moments tab (and Explore's pages). Tags stay by
  day, and the storyline has a heading for each day.
- Sound folders are listed on every backup run (the 15-minute runs and Back up now; a `.nomedia` folder never changes
  the media index, so nothing else starts one) through the access the folder picker gave (`AndroidSoundFolders`,
  subfolders included, names starting with a dot left out). A file changed in the last 2 minutes (a call going on, a
  recording not stopped) waits for a later run. They go whatever photo sources are chosen; a calls folder's files go as
  calls (the server reads who from the name), a voice-messages folder's as WhatsApp. A **WAV** is hashed as it is and
  asked about first: the server answers a WAV's sha256 through its FLAC (`encodedFrom`), so a WAV kept as FLAC (by
  this phone, the server or the import) is never encoded or sent again. Only a missing WAV is encoded, with Android's
  own FLAC encoder (`FlacEncoder`, MediaCodec; 16- and 24-bit PCM), and the FLAC is sent with the WAV's sha256 as
  `encodedFrom`. A WAV the encoder doesn't take is sent as it is, and the server keeps it as FLAC. With **Keep on this
  phone: FLAC**, the FLAC is written beside the WAV and the WAV deleted, once the server's verify confirmed the FLAC;
  else the FLAC in the cache is deleted.
- Backup runs in the background with WorkManager, also with the app closed: every 15 minutes, 10 s after new photos
  appear in MediaStore, and right away when turned on or on **Back up now**. All wait for the network (and charging)
  condition; a listed network is checked when a run starts. Progress shows in a notification ("Backing up 3 of 120",
  with a Stop action) and in the bottom pill of the Journal ("Backing up 3 of 120", else "N not backed
  up", counting only the chosen sources while backup is on). Several files upload at once (3 on Android, up to 50 on
  iOS): the count is of finished files plus one ("3 of 120" = 2 finished, the next on its way), the percent includes
  what has arrived of the files on their way, and the notification names the earliest of them. Each photo's badge
  follows (waiting, uploading, backed up, failed); every file on its way shows its own ring. Turning backup on asks for notification permission.
- On iOS each piece goes through a background URLSession, which carries it on while the app is suspended and
  relaunches the app when it is answered; runs start when the app opens, on **Back up now**, 10 s after the library
  changes, on those relaunches, and in background tasks iOS schedules (BGProcessingTask, BGAppRefreshTask). Wi-Fi only
  is checked before a run (`NWPathMonitor`) and set on every piece; charging only is checked with the battery state
  and asked of the processing task. Progress shows in the bottom pill of the Journal ("Backing up 3 of
  120 · 42% · 5.1 MB/s") and in this section; there is no notification. See [design.md](../design.md), "iOS backup".
- What was read and uploaded is remembered per device in `files/backup/ledger.jsonl` (iOS: Application Support
  `backup/ledger.jsonl`) (sha256 per photo), so a photo is
  read once and never sent twice; the server also deduplicates. An edit saved in place uploads as a new version
  (`versionOf`). Uploads resume where they stopped. See [design.md](../design.md) "Upload and sync".

- **Free up space** runs in the background with WorkManager (`FreeUpWorker`, a progress notification with Stop), one
  group of files at a time. For each file the server reads and hashes its copy again (`POST /media/{sha256}/verify`,
  journal-server-go docs/media.md) while the phone hashes its own; both must have the sha256 backup uploaded, else
  the file stays (changed on the phone or missing on the server: backup sends it again; damaged on the server: the
  server logs it). An unreachable server stops the run; nothing unchecked is removed. Files that passed go to the
  phone's trash (kept there 30 days):
  - with **"Media management" access** (`MANAGE_MEDIA`, asked for when **Free up space** is tapped, in a dialog that
    opens the system page; "Ask me at the end" skips it), right away as each group passes, without a prompt;
  - without it (or when Android refuses to start the request from the background), they wait in
    `files/freeup/waiting.json`; when the run ends a notification ("120 files checked: tap to move them to the trash")
    opens the app, which shows **one** system prompt for all of them (also on the next app open, or with **Move to
    trash**). A file changed since it was checked (size or date) is left out of the prompt, with its pair. Either
    answer clears the list.
  - Sound files of the picked folders are checked the same way (the server verifies a WAV by its FLAC) and, after the
    app's own **Delete for good?** dialog, deleted at once (`AndroidSoundRemoval`, `DocumentsContract.deleteDocument`):
    those folders have no trash. One that fails stays.
- **Free up space on iOS** (Oct 21, 2026): the same shared `FreeUpController`, rules, sources, checks and lines; the
  screen is a list like the rest of Settings (From is a switch per source, More options a disclosure with the rule
  as an inline choice and its values in a menu under it). The difference is the deletion: PhotoKit always asks, so
  nothing is ever deleted during a run (`IosPhotoTrash.canTrashWithoutAsking()` is false) and there is no "Media
  management" dialog. The run (plan, the phone's hashing, the server's verify) happens in the app, on **Free up**; it
  goes on for the short time iOS gives after the app leaves the screen (`beginBackgroundTask`), and when iOS ends that
  time it stops, and what was checked by then waits. It does not run in background tasks: Free up space never runs by
  itself, on either platform. Every file that passed waits in Application Support `freeup/waiting.json`; the screen
  shows "120 files (3.2 GB) checked, waiting for you" with **Move to trash**, and only that tap deletes: all of them
  in **one** `deleteAssets` request, so one iOS prompt, and they go to Recently Deleted (30 days). Unlike Android the
  prompt is never shown by itself when a run ends. A run that ends while the app is away posts a local notification
  ("120 files checked: tap to move them to the trash", as Android's), which opens the app; notification permission
  is asked when **Free up** is tapped. Either answer to the prompt clears the list and that notification.
- Private media: while **Show private media** is off, a file the user marked private is left out of the library
  (`mergeWithServer`), so no screen shows it and no preview or original of it is downloaded; the phone's own copy
  (same sha256, or same name and size) is left out too. Turning the switch on applies at once.
- Nudity: the server scores each new file with NudeNet, by itself (journal-server-go docs/media.md "Nudity
  scan") and, since Oct 23, 2026, hides those at or over the user's score itself, like a hidden collection (below);
  the app used to hide them and no longer reads the score for that. It is never a tag. A file not scanned yet is
  shown. Show private media doesn't show them: Hidden collections → Nudity off does, on every device.
- Hidden collections: the server hides them, not the app (journal-server-go docs/media.md "Hidden collections"):
  hidden files come in the change feed as deleted and hidden, so the library drops them like deleted files; "On your
  server" and search don't count them. Whatever the phone keeps of a file the server drops (previews, a downloaded
  original, a kept original, the streamed video) is deleted then (`ServerLibrary`'s `removed`,
  `PhoneStorage.remove`). The phone's own gallery is never touched, but the library leaves out the phone's own copy
  of a hidden file once backup has read it (its sha256 is in `ServerLibrary.hidden`, kept in `server/media.json`); a
  photo backup hasn't read yet still shows until it has. Changing the choice makes every device read the whole change
  feed again on its next sync.
- A lowered preview limit applies at once (the oldest previews are deleted); **Clear previews** deletes them all.

## Search

(Oct 24, 2026, Android and iOS.) Every search setting, opened from Settings' Search row:

- **Indexing** (shown when the server offers it, `serverInfo.indexing`; called "On your server" until the owner renamed
  it on Oct 24, 2026): the server reads each new photo once
  for search (words, people, text in photos, similar, best-looking, the nudity scan), nightly or as they come. One
  card, by the run's state (`indexing`, journal-server-go docs/indexes.md "Started, followed and paused from a
  phone"):
  - **Reading your photos** (or **Nightly run: reading your photos**): "412 of 1,280", a bar, what each index read
    ("Nudity scan 412 · People 410 · Search by words 412 · 2 couldn't be read"), and **Pause**.
  - **Waiting for other runs**: "1 ahead of yours. Only one runs at a time, to keep the server's memory free.", and
    **Pause** (takes it off the queue).
  - **Paused at 412 of 1,280**, a grey bar, "The server let go of its models while paused. Resume goes on from here."
    (on a server that reads as photos come: "New photos wait to be read until you resume."), and **Resume**.
  - Otherwise the last run, "Last read 7 hours ago: 312 photos" (or "Stopped … after … photos", or "No run yet"),
    "New photos are read every night.", why it couldn't go on if it couldn't, and **Read new photos now**.
  - Under the card: "Next nightly run: Sunday 3:00 AM". A failed call shows "Can't reach the server: …" and keeps
    what was shown.
  - A phone sees and acts on its own user's run only. Pausing a nightly run pauses it for this user alone; the other
    users' runs go on.
  - The screen asks the server every 2 s while a run goes or waits, every 30 s otherwise, and only while it shows;
    Settings asks once when it opens, for its row. Start and pause answer with the new state at once, and the screen
    follows a run just started every 2 s from then (not after the 30 s wait it was in).
- **Results**: **Photos per page**, 50, 100, **200** (default), 500 or 1000: how many photos a [search](search.md)
  shows at first; scrolling near the end shows the next page. Applies from the next page.
- **On device search**: the indexes kept on this phone (below), as sections of this screen. It was a screen of its
  own, "On this phone", until the owner moved it here on Oct 24, 2026.
- Code: `ServerIndexing` in [server/ServerIndexing.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/server/ServerIndexing.kt)
  (with `ServerIndexingTest`); Android `SearchSettingsScreen.kt`, iOS `SearchSettingsView.swift` and
  `ServerIndexingViewModel` in `PeopleViewModels.swift`.

## Search › On device search

(Oct 24, 2026, Android and iOS; shown when the server offers indexes, `serverInfo.localIndexes`.) Keeps the server's
indexes on the phone, to search without the server, offline too (journal-server-go `docs/search.md` "On the phone").

- Settings' Search row says how many are kept ("200 per page · 3 kept on this phone").
- The section, under Results: its title, what it is for; a status card (while something is kept): "Downloading Similar photos, 61 MB of 152 MB" with
  a bar and **Pause**, "Waiting for Wi-Fi" with **Download now**, or "Updated 2 hours ago · 12 new photos on your
  server, not here yet" with **Update now** (and the last error, if any); a card with a switch per index
  the server offers, with what it does and its size — Search by words (with its model: the 8-bit one when the server
  has it, about 290 MB, else the full 1.13 GB), People, Text in photos, Similar photos, Best-looking photos (on at
  first: 4 MB, and it picks a set's best photo, covers and Best first); a card with **Only on Wi-Fi** (on); and "Uses
  839 MB of this phone · 48 GB free".
- Turning one on downloads it (now, or on Wi-Fi); turning it off deletes it. Each index catches up when the app comes
  back a day after the last look, and on Update now: only what was added since (the stream's cursor), and what the
  user hid or marked private since is dropped. A broken download goes on from where it broke (the model by Range).
- Search uses the phone when it keeps text search (or text in photos) and is at most 20 files behind, else the server;
  when the server can't be reached, the phone anyway ("Searched on this phone: your server can't be reached."). The
  phone's answers are the server's: same tokens (`SentencePiece.kt`), same text-in-photos matching and set grouping
  (`LocalMatching.kt`), checked against `go run ./scripts/local-fixtures` (`SentencePieceTest`, `LocalFixturesTest`).
  Vectors are kept as 8-bit numbers with a scale each (a quarter of the size; they rank as the full ones to within a
  thousandth). People and a photo's details fall back to what is kept the same way.
- Kept in `files/local` (Android) and Application Support `local` (iOS, out of iCloud backup): `<index>/table.bin`
  (`LocalTables.kt`), `faces/people.json`, `models/`. The faces table keeps each face's box and its vector at full
  precision (asked Oct 24, 2026: about 60 MB for 120,000 faces), for matching faces on the phone; a table from before
  is downloaded again.
- Code: [local/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/local/) (`LocalIndexes`,
  `LocalIndexApi`, `LocalTables`, `LocalSearch`), the model run by `AndroidTextEncoding` (ONNX Runtime for Android) and
  `OnnxTextEncoding` in `PeopleViewModels.swift` (onnxruntime-swift-package-manager); Android
  `PhoneSearchSection` in `PhoneSearchScreen.kt`, iOS `PhoneSearchSections` in `PhoneSearchView.swift`.

## Code

- Shared: `SettingsController`, `TimelineGrouping`, `SimilarityThreshold` and `PrivateMedia` in
  [settings/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/settings/SettingsController.kt);
  the rows are built by `groupMoments` in
  [Moment.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/Moment.kt).
- Backup: [backup/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/backup/BackupController.kt)
  (`BackupController`, `BackupEngine`, `UploadLedger`, `ServerBackupApi`; sound folders in `SoundFolders.kt`, the pills
  in `BackupPills.kt`); Android reads originals in `AndroidMediaFiles.kt`, sound folders in `AndroidSoundFolders.kt`,
  encodes WAVs in `FlacEncoder.kt`, and writes in picked folders only in `AndroidSoundRemoval.kt`; the pills and the add
  dialog are drawn in `BackupPills.kt` (androidApp), and runs it from [work/](../../androidApp/src/main/kotlin/com/chencohensolution/journal/work/BackupWorker.kt)
  (`BackupWorker`, `WorkBackupScheduler`, `currentWifiName`).
- Storage: [FreeUpScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/FreeUpScreen.kt), [storage/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/storage/FreeUpController.kt)
  (`FreeUpController`, `freeUpPlan`, `FreeUpChecker`, `ServerVerifyApi`); previews in `ServerPreviews`. Android:
  [FreeUpWorker.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/work/FreeUpWorker.kt), and the trash
  only in [PhotoRemoval.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/PhotoRemoval.kt)
  (`AndroidPhotoTrash`; see PhotoSafetyTest).
- iOS Free up space: [FreeUpView.swift](../../iosApp/iosApp/FreeUpView.swift),
  [FreeUpViewModel.swift](../../iosApp/iosApp/FreeUpViewModel.swift),
  [FreeUpService.swift](../../iosApp/iosApp/FreeUpService.swift) (runs, the notification), `IosFreeUp` and
  `IosPhotoTrash` in [iosMain/storage/](../../sharedLogic/src/iosMain/kotlin/com/chencohensolution/journal/storage/IosFreeUp.kt)
  (factory `IosJournal.freeUp`, sharing backup's ledger), and the deletion only in
  [PhotoRemoval.swift](../../iosApp/iosApp/PhotoRemoval.swift) (`moveToRecentlyDeleted(checked:)`; see PhotoSafetyTest).
- Android: [SettingsScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/SettingsScreen.kt)
  (the first page and the Show in the app, Moments and Storage on this phone pages, by `SettingsPage`; one route,
  `Route.SettingsPageRoute`, per page; Storage's content in `StorageSettings.kt`), the Backup screen
  (`Route.BackupSettings`) in `BackupSettings.kt`;
  Hidden collections in [HiddenScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/HiddenScreen.kt)
  behind [PhoneLock.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/PhoneLock.kt)
  (`PhoneLocked`). Shared: `HiddenCollectionsController` (`HiddenCollectionsState`: loading, unreachable, ready with
  the filter, saving and "not changed") over `ServerLibrary.hiddenCollections` / `setHiddenCollections`, the
  `HiddenCollections` model, and `ScreenLock` (unlocked or not; the prompt is each platform's). iOS:
  [HiddenView.swift](../../iosApp/iosApp/HiddenView.swift) (`HiddenScreen` drawn from state, also in the UI lab as
  "hidden"; `PhoneLocked` with `LAContext`), and `ServerVideoCache.remove` for a dropped file's video.
- iOS: [SettingsView.swift](../../iosApp/iosApp/SettingsView.swift) (`SettingsScreen`, the first page, drawn from a
  `SettingsContent` so the UI lab shows it; `SettingsView` feeds it from the view models), the pages and each row's line
  in [SettingsSections.swift](../../iosApp/iosApp/SettingsSections.swift), the Backup screen in
  [BackupSettingsView.swift](../../iosApp/iosApp/BackupSettingsView.swift), [SettingsViewModel.swift](../../iosApp/iosApp/SettingsViewModel.swift)
  (also `serverUsage`, the **On your server** line); storage through the shared `IosServerFiles` (`usedBytes`,
  `clear`, `observeUsage`). Previews are cached in `Caches/previews`, the server's list in
  `Application Support/server/media.json`, downloaded originals in `Application Support/originals` (left out of
  iCloud backup). Backup in [BackupViewModel.swift](../../iosApp/iosApp/BackupViewModel.swift) and
  [BackupService.swift](../../iosApp/iosApp/BackupService.swift) (when runs start), with `IosMediaFiles` and
  `BackgroundPieceSender` in [iosMain/backup/](../../sharedLogic/src/iosMain/kotlin/com/chencohensolution/journal/backup/BackgroundPieceSender.kt).

## Design history

- [Backup — calls and recordings from folders](../ux-history/backup/2026-10-25-sound-folders.md) (Oct 25, 2026): one list of pills with "+ Add a folder" (b2) picked.
