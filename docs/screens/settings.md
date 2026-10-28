# Settings

The user's choices, kept on this device (SharedPreferences / NSUserDefaults). Opened from the Journal's **Settings** button (the **⋯** menu until Oct 24, 2026).

## Layout

Since Oct 24, 2026 (chosen by the owner from the UI lab's options), Settings is a short first page of groups, each row
saying how it stands and opening a page of its own; the switches used most stay on the first page:

- **Top bar:** back and "Settings" (on a page: its name).
- **Library:** **Show in the app ›** ("87,343 files · 15 not backed up"; until Oct 27, 2026 "Showing Camera, Other, Petcube +2"), **Your cameras ›**
  (when the server keeps cameras, `ServerFeatures.cameras`: "4 cameras · 1 not yours", "4 cameras" when all are,
  "Moving 312 photos…" while the server moves some, "Which cameras were yours" until the list came), **Sound folders ›**
  (when the server keeps them, `ServerFeatures.soundFolders`: "4 folders · 1 as Calls · 1 as Voice recordings", "3
  folders" when none is merged, "Moving 953 files…" while the server moves some, "Which folders are calls or voice
  recordings" until the list came; both lists are asked when Settings opens and kept up by their pages), **Moments ›** ("By
  day · similar at 60%", "New row after 12 hours · similar at 85%"), and the **Show the phone badge** and **Share JPEG XL as JPEG** switches (below).
- **Privacy:** **Private settings 🔒 ›** (below; Show private media is inside it since Oct 25, 2026).
- **Backup and storage** (when the app has them): **Backup ›** (its status line; a screen of its own, below),
  **Storage on this phone ›** ("1.8 GB of 10 GB · WebP, High", or "· Originals" with previews off), **Free up space ›**
  (its line, below).
- **Search ›** on its own (its line, below).
- **Advanced:** **Loading Moments ›** ("30 rows at a time · 40 ahead"; below).

The pages, and what each holds:

- **Show in the app** (a page; first; since Oct 27, 2026 a status page, kinds c3 of the UI lab, Android; iOS not built
  yet): nothing here changes; it says what the user has and what isn't backed up. At the top "87,343 files" and, in
  orange, "☁ 15 not backed up ›" (or "All backed up"). Then **Photos and videos**, a card with a line per kind:
  Camera, Screenshots, Sent to you, each folder (WhatsApp, Telegram, Petcube…), Other last, the most files first, each
  with its icon, its total in a column at the end, and "12 not backed up ›" in orange under its name where some of
  this phone's files of that kind aren't on the server yet. What the server's import placed counts in its kind
  (`import:camera` is Camera; there is no Imported section any more). Then **Sound**: Calls, Voice recordings, Voice
  messages (those with files), the same way. Kinds Private settings hides on every device aren't named: once Private
  settings was opened in this run of the app (`ServerLibrary.hiddenEverywhere`, never asked for by this page), one line
  counts them, "🔒 2 kinds hidden on all your devices · **Private settings ›**" (apps and folders, screenshot apps,
  Sent to you and sound kinds that have files; nudity isn't a kind); before that the line is left out. Last, a note:
  "To hide a kind on this phone, or show it again, use the filter bar in Moments. This phone remembers it." Every
  "not backed up ›" opens Settings › Backup; "Private settings ›" opens Private settings (behind the lock). The row on
  the first page reads "87,343 files · 15 not backed up" (or "· All backed up").
  **How it counts** (`KindsStatus.of`, shared): the library database's counts per source (`LibraryTable.backupCounts`,
  split by source in `LibraryTotals.bySource`, read as `LibraryState.sourceTotals()`), imported sources folded into
  their kind. "Not backed up" is what Backup counts: with backup on, the phone's files of the sources backup takes
  that the ledger doesn't hold as uploaded as they are now (plus those on their way again); with backup off, every
  file of this phone; sources backup doesn't take count 0. The sum equals Backup's "N waiting". Sound: the server's
  recordings the library shows, by kind, plus the files of the backed-up sound folders not uploaded yet
  (`BackupState.soundsNotBackedUp`: each folder's files at its last run less its uploaded ledger entries), which are
  also its "not backed up". A kind Private settings hides whole is left out with its counts.
  Until Oct 27, 2026 this page was pills to show or hide each source on this phone (since Oct 24; a switch per source
  before), with an Imported pill and its kinds (option b2 of `ux/sources/2026-10-27`), Say what's hidden and Filter
  bar in Moments (now on the Moments page), and a line on what Private settings hides. Hiding on this phone is still
  the Moments filter bar's and Explore's Showing sheet's (`ShownSources`, kept as the sources hidden: `hidden_sources`
  (until Oct 24, 2026 the fixed sources shown, `shown_sources`, read once), `say_whats_hidden`, `always_shown_days`,
  `show_imported`; the old id `whatsapp` there, and in `backup_sources` and `free_up_kept`, is read as the folder
  `folder:whatsapp` it is since Oct 27, 2026, as is any folder kept with capitals: folder ids are in lower case since
  Oct 27, 2026, and written so). The Showing sheets keep b2's pills, Imported box and Private settings line
  (`ShownSourcesPills.kt`, `ShowingSheet`).
- **Your cameras** (a page under Library, Oct 27, 2026; Android only, iOS not built yet): a line on what it does,
  then one card of every camera the user's files name, the most photos first, each with its photos and years
  ("Samsung Galaxy S25 · 8,542 · 2025–2026") and a **Mine** switch; a camera marked not the user's also says "Its
  photos are in Sent to you". **Names and merging are the phone's** (Oct 27, 2026, the owner's choice): the server
  keeps each file's make and model exactly as its tags give them, and the app names them with its own table
  (`CameraNames` in `CameraNames.kt`, easy to extend): a known model, matched ignoring case, has its whole name
  ("SM-S931B" and "Galaxy S25" are "Samsung Galaxy S25", "SM-G781B" "Samsung Galaxy S20 FE 5G", "H8324" "Sony
  Xperia XZ2 Compact", "LG-E450" "LG Optimus L5 II"); any other is its make, spelled as the app spells it ("samsung"
  Samsung, "LGE" and "LG Electronics" LG, "NIKON CORPORATION" Nikon), and its model without the make it repeats
  ("NIKON D800": "Nikon D800"). "Galaxy S25" and "Galaxy S25+" stay two cameras (the whole model must match, "+"
  included). The models that get one name are one row (`CameraGroup`: "Galaxy S25", "SM-S931B" and a make-less
  "Galaxy S25" together): its photos, imported photos and photos still moving summed, its years merged; **Mine** is on
  only when every one of them is the user's. Turning a switch on marks the camera the user's at once, which sends one
  `setCamera` per model with its make and model exactly as the server listed them; turning it off asks first in a
  dialog with the whole camera's count ("312 photos will move to Sent to you": they stay on the server and in the
  app, under Sent to you instead of Camera; **Move** or Cancel). While the server moves a model's
  files the row says "Moving 312 photos…" with a bar, and the page asks again every 3 seconds until none is left; the
  library then syncs, since each moved file comes again in the change feed. Under the card: "A camera you haven't
  marked counts as yours, so nothing moves until you choose." Loading, "Can't reach your server" with Try again, and
  "Not changed" (the switch goes back) as in Private settings. In the UI lab: `./scripts/ui-lab.sh cameras
  current|confirm|moving` (the owner's camera tags with made-up years, so the merging shows; the move timed).
- **Sound folders** (a page under Library after Your cameras, Oct 27, 2026, option b of the UI lab; Android only, iOS
  not built yet): every folder the user's sound recordings came from (`soundFolders`: a phone's Call, an import's
  Calls, Voice Recorder, super_recorder), grouped by where their recordings go: **Calls** ("Their recordings are phone
  calls, in Calls on all your devices."), **Voice recordings**, then **Kept as folders** ("Each stays a folder of its
  own, until you move it."; its own kind, `folder:calls`), each group's folders in a card, the most files first, or "No
  folder yet". Each folder shows its files and how many were imported ("1,623 files · from your phones", "1,520
  files · imported", "168 files · 61 imported") and a **Move to…** menu offering the other two groups; the mark is
  saved on the server for every device. Folders that differ only by case are one (ids are in lower case: the import's
  "Calls" 953 and "calls" 567 are one "Calls", 1,520, named by the spelling most files have). Picking a group asks
  first, inside the folder's row ("1,520 files will become Calls" / "… will become Voice recordings" / "… will go back
  to Calls": only how they are labelled changes on the server; **Move** or Cancel; the folder waiting for its answer is
  kept by id across rotation). While the server moves them a band at the top says "Moving 1,140 files to Calls…" with
  a bar, the folder sits in its new group already with "1,045 left to move", and the page asks again every 3 seconds
  until none is left; the library then syncs (`SoundFolderMark`, `setSoundFolder`, `pending`). Under the groups:
  "WhatsApp voice messages always stay voice messages." Loading, "Can't reach your server" with Try again, and "Not
  changed" as in Private settings. In the UI lab: `./scripts/ui-lab.sh sound-folders current|confirm|moving` (made-up
  folders close to the owner's, Voice Recorder already merged into Voice recordings; the move timed).
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
  - With backup on: **What to back up**, a pill per source, just its name ("Petcube"; no counts since Oct 27, 2026), filled with a ✓ when on,
    the most photos first (`BackupSettings.choices`: the fixed sources, each folder or app the phone has photos of,
    and those chosen; **Camera** on by default). A new folder is off
    until turned on. Camera is DCIM/Camera only since Oct 24, 2026: other DCIM folders (which Camera took before) are
    choices of their own, off, as the owner chose; what they uploaded stays on the server. The server is told each
    file's source (`camera`, `folder:Petcube`, …: a folder spelled as the phone saw it, which the server reads in
    lower case and counts towards the name it shows the folder by), so a photo only on the server keeps its folder.
    **Folders of calls and recordings** share that list (Android since Oct 25, 2026, iOS since Oct 26, 2026; UX round
    `ux/backup/2026-10-25`, b2): one list of pills (`BackupPills`, `BackupState.pills`), photo sources and sound folders
    side by side, not grouped, the ones backed up first, each the most files first, a folder with the icon of what it
    holds; **+ Add a folder** last. It opens Android's folder picker at Internal storage › Recordings › Call (where
    Samsung keeps calls); after **Use this folder** and **Allow**, a dialog shows the folder's name and place, what was
    found ("6,196 sound files · 3.2 GB"), **What are they?** (Phone calls, Recordings or Voice messages, the app's guess
    from the names chosen: Samsung's "Call recording …"/"הקלטת שיחה …"/"שיחה …" are calls, WhatsApp's `PTT-`/`AUD-`
    voice messages), and **Back it up**. The choice is the kind each of its files is uploaded with (since Oct 27,
    2026; before, the folder, `folder:Call`): Phone calls as `call`, Recordings as `recording`, Voice messages as
    `folder:whatsapp`; the server keeps what the phone sent for a sound recording unless the user merged its folder
    otherwise in **Sound folders** (below). Each file also goes with its folder's name as the phone shows it ("Call",
    as the pill says) as the upload's `folder`, so Sound folders lists the phone's folders beside the imported ones
    (`BackupEngine.request`, since Oct 27, 2026). The folders are found only this way: the owner keeps a `.nomedia` file in them,
    so Android's media index never lists them. The app keeps read and write access to the folder (write for Free up
    space and for keeping FLAC). A tap turns a folder on or off; a long press asks **Stop backing up Call?** (what it
    sent stays on the server); a folder the app can no longer read says "Call · pick again", and a tap opens the picker.
    Under the pills, once a folder is added: "Folders of calls and recordings are backed up even while they are hidden
    in Private settings." Then, always (as the owner asked), the **Keep on this phone** card (Android since Oct 28, 2026,
    picked as b2 in [that round](../ux-history/backup/2026-10-28-keep-on-phone.md); until then WAV / FLAC chips): a
    switch per kind backup sends smaller, both **off** by default, each saying what it does:
    - **Recordings as FLAC**: off, "WAVs stay as they are"; on, the FLAC replaces each WAV once the server confirmed it.
    - **Photos as JPEG XL**: off, the JPEGs stay ("every app can show them"); on, the JPEG XL replaces each JPEG once the
      server confirmed it and the JPEG goes to the gallery's trash; only the app can show JPEG XL. While it is on
      without "Media management" access, a row in the card says so, and **Allow** opens the system page.
    Under the card: the server keeps recordings as FLAC and photos as JPEG XL, from which the very same WAV or JPEG
    comes back (`backup_keep_*`; see design.md, "JPEG photos are kept as JPEG XL"). On iOS (`SoundFolderViews.swift`, `IosSoundFolders.kt`) **+ Add a folder** opens the
    Files folder picker; the folder is kept by a security-scoped bookmark (Application Support, `soundFolders/`), and the
    add dialog is a sheet. An iPhone keeps call recordings in Notes, and Voice Memos and WhatsApp's voice notes inside
    their apps, out of the picker's reach: only recordings saved into Files (On My iPhone, iCloud Drive, another app's
    folder there) can be backed up. WAVs are made FLAC with iOS's own encoder (AVAudioFile); Keep on this phone is a
    WAV / FLAC switch;
    **When**: **Only on Wi-Fi** (on by default; never on mobile data), **Only while charging** ("Saves the battery;
    uploads wait for the charger."), and, under Wi-Fi only,
    **Only on these Wi-Fi networks** with **Add this network** (asks for location, then lists the network the phone is
    on; × removes one). With a network listed and no "All the time" location, a note asks for it, since the network
    can't be checked in the background otherwise. **Order**: **Oldest first** (default) or **Newest first**.
- **Show the phone badge** (on the first page, under Library; both platforms, asked for Oct 21, 2026), a switch, **off** by default: the
  green phone on grid tiles and Moments photos that are on this phone and backed up (see
  [README.md](README.md#backup-badges)). Off, a photo just taken and uploaded has no badge; waiting, uploading and
  failed photos always have one, and the viewer's details always say where the original is (`show_on_phone_badge`).
- **Share JPEG XL as JPEG** (on the first page, under Library, below the badge; Android, asked for Oct 28, 2026), a
  switch, **on** by default: the viewer's Share sends a JPEG XL photo as the JPEG it was packed from, byte for byte, as
  few apps open JPEG XL; one not packed from a JPEG goes as it is. Off, it goes as JPEG XL (`share_jxl_as_jpeg`).
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
  own (**60%** Android, **80%** iOS); **Reset to N%** shows when it's changed. Then (Android since Oct 27, 2026, moved
  from Show in the app when it became a status page; iOS still has them there) **What Moments shows**: **Filter bar in
  Moments** ("The row of sources above the feed. When off, choose them here."; on; `ShownSources.showFilterBar`, kept
  as `show_filter_bar`) and **Say what's hidden** ("“+6 WhatsApp” on a day, tap to see them"; on; the
  [Moments](moments.md) chips), with "Photos you wrote about always show in the storyline. Hiding never deletes
  anything and doesn't change what is backed up."
- **Privacy** (on the first page; both platforms; iOS since Oct 21, 2026): one row with a lock, **Private settings**
  (called Hidden collections until Oct 25, 2026), "Private media, nudity, sound recordings, apps and screenshots by
  app", see below. **Show private media** moved into that screen (Android Oct 25, 2026; iOS Oct 26, 2026). The nudity slider
  that was here moved into Hidden collections (Oct 23, 2026), where the server does the hiding.
- **Private settings** (its own screen, asked for Oct 22, 2026 as Hidden collections; both platforms; since Oct 27,
  2026 on Android one list by kind, private c2 of the UI lab; iOS not built yet): opens only after the phone's lock
  (Android: fingerprint, face, PIN, pattern or password, the system's `BiometricPrompt` with device credential; iOS:
  Face ID, Touch ID or the passcode, `LAContext` `.deviceOwnerAuthentication`), every time it opens and again after
  the app was in the background; a phone without a screen lock (or passcode) can't open it. First **Private media**
  with **Show private media** (Android Oct 25, 2026; iOS Oct 26, 2026), a switch, **off** by default, for the photos the
  user marked private; it is this phone's choice, not the server's. Then **Show on all your devices**: "Turn a kind
  off to hide it on all your devices: not in the library, its counts or search. Nothing is deleted. This page opens
  only with the phone's lock.", and one card with every kind the server can hide, once, the hidden ones included, the
  most files first, each with its icon, its files and a **Show** switch (on = shown; off reads "19,700 · Hidden on all
  your devices" in orange): each app and folder (`HiddenCollections.apps`: WhatsApp, Telegram, Petcube…; a hidden one
  with no files left is listed too, so it can be shown again), **Calls**, **Voice recordings** and **Voice messages**
  (those with files; hidden until shown), **Sent to you** (with files, or while hidden), and **Screenshots**, which
  has no switch of its own but a chip per app it was taken in, just the app's name and its count ("Bumble 1,458",
  "No app name 128" for screenshots whose name says no app), filled with a ✓ while shown, outlined while hidden; tap
  to switch. Then **Nudity** (Oct 23, 2026): "Show photos and videos with nudity · N photos and videos · M not scanned
  yet" (a Show switch, **off**, so hidden, by default), and while off **Hide from**, a slider from 10% to 95% in steps
  of 5 (default **50%**): the nudity score the server's scan must reach for a photo or video to be hidden, saved when
  let go. The list is `PrivateKinds` (shared, `HiddenCollectionsState.Ready.kinds`); each switch goes back to the
  server as `setHiddenCollections`' `apps`, `screenshotApps`, `screenshotsWithoutApp`, `audio` (recordings, calls,
  voice messages) or `sentToYou` (source `received`; the server took it on Oct 27, 2026), through
  `HiddenCollectionsController.show`. Before Oct 27, 2026 the page had sections (Nudity, Sound recordings, Apps with
  "Show all", Screenshots with "Find an app" and "Screenshots in Bumble" pills, on meaning hidden for apps and
  screenshots), and couldn't hide Sent to you. The list and the choice live on the server (`hiddenCollections`,
  `setHiddenCollections`); each switch is saved at once (the others wait only for that, well under a second), and if
  the server can't be reached the switch goes back with "Not changed: can't reach your server". The library syncs
  once, when the screen is left (`HiddenCollectionsController.close` → `ServerLibrary.syncAgain`), not after each
  switch, because every change makes the whole change feed come again. While that sync reads more than one page, the
  Private settings row in Settings shows "Updating your library: 38,000 of 95,412" with a bar
  (`ServerLibrary.syncProgress`, from the feed's `remaining`). In the UI lab: `./scripts/ui-lab.sh private current`
  (unlocked, made-up counts, switches working while open) or `locked`.
- **Search** (both platforms; its own screen since Oct 24, 2026, see "Search" below): one row, "Server: reading 40 of
  200", "Server: paused at 40 of 200", "Server: waiting to read your photos", or "200 per page · 3 kept on this
  phone".
- **Loading Moments** (a page, under Advanced at the end of the first page; Android since Oct 26, 2026, chosen by the
  owner from the UI lab, round `ux/settings/2026-10-26`; iOS not yet): how [Moments](moments.md) is read from the
  library database a page at a time. **Rows loaded at a time** (15, **30**, 60, 120: "More: fewer pauses when
  scrolling far, more memory.") and **Load ahead** (10, 20, **40**, 80: how many rows before the end of what is loaded
  the next rows start loading), as chips, the default's marked "(default)"; **Reset to 30 and 40** once either is
  changed. On this phone only, and applied as Moments loads its next rows (no restart). Kept as
  `moments_rows_per_page` and `moments_load_ahead` (`SettingsState.momentsPaging`, `MomentsPaging`).

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
- What was read and uploaded is remembered per device in the library database's `ledger` table (sha256 per photo;
  until Oct 25, 2026 `backup/ledger.jsonl`, read in once and deleted), so a photo is read once and never sent twice; the server also deduplicates. An edit saved in place uploads as a new version
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
  screen is a list like the rest of Settings (From is the same pills as Android since Oct 26, 2026, sound folders and
  **Delete for good?** included, the files deleted by `IosSoundFolders`; More options a disclosure with the rule as an
  inline choice and its values in a menu under it). The difference is the deletion: PhotoKit always asks, so
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
  (same sha256, or same name and size) is left out too. Since Oct 25, 2026 (asked by the owner) a private file is not
  even kept in the library database while the switch is off, only its sha256, name and size to hide the phone's
  copy (`ServerLibrary.privateFiles`). Turning the switch off applies at once; turning it on reads the server's
  change feed again from the start to bring the private files back, which takes a moment with a large library.
- Nudity: the server scores each new file with NudeNet, by itself (journal-server-go docs/media.md "Nudity
  scan") and, since Oct 23, 2026, hides those at or over the user's score itself, like a hidden collection (below);
  the app used to hide them and no longer reads the score for that. It is never a tag. A file not scanned yet is
  shown. Show private media doesn't show them: Hidden collections → Nudity off does, on every device.
- Hidden collections: the server hides them, not the app (journal-server-go docs/media.md "Hidden collections"):
  hidden files come in the change feed as deleted and hidden, so the library drops them like deleted files; "On your
  server" and search don't count them. Whatever the phone keeps of a file the server drops (previews, a downloaded
  original, a kept original, the streamed video) is deleted then (`ServerLibrary`'s `removed`,
  `PhoneStorage.remove`). The phone's own gallery is never touched, but the library leaves out the phone's own copy
  of a hidden file once backup has read it (its sha256 is in `ServerLibrary.hidden`, kept in the library database); a
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
  thousandth). Since Oct 26, 2026 (plan B phase 7) text search's and Similar's vectors stay on disk: a search reads
  the file in chunks and keeps the best in a small heap, and the app holds only which row is which file's (about 5 MB
  for 95,000 photos instead of about 88 MB; a search about 0.2 s on a PC). Text in photos went the same way the same
  day: each photo's lines stay in their file and are read when a photo's details ask for them, and the words search
  looks up are kept compact (each word once, its files as numbers): about 20 MB for 95,000 photos instead of about
  140 MB, with the same results. People and a photo's details fall back to what is kept the same way.
- Kept in `files/local` (Android) and Application Support `local` (iOS, out of iCloud backup): `<index>/table.bin`
  (`LocalTables.kt`; for `search` and `similar`, `vectors.json` and `vectors-<n>.bin` instead, for `faces`,
  `faces.json` and `faces-<n>.bin`, and for `ocr`, `text.json` and `text-<n>.bin`: rows appended as a download goes,
  the file written again without replaced rows once they are an eighth of it; a `table.bin` of theirs from before is
  moved into these once, without downloading), `faces/people.json`, `models/`. The faces table keeps each face's box
  and its vector at full precision (asked Oct 24, 2026: about 60 MB for 120,000 faces), for matching faces on the
  phone. Since Oct 26, 2026 the vectors stay on disk and are read one face at a time (a correction reads the face it
  moves); only the boxes and which row is which file's are held, about 5 MB for 120,000 faces instead of about 63 MB.
  A faces table from before the vectors were kept is downloaded again.
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
  (`Route.BackupSettings`) in `BackupSettings.kt`; Your cameras (`Route.Cameras`) in
  [CamerasScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/CamerasScreen.kt)
  (`CamerasRoute`: a `CamerasController` per opening and the camera waiting for its confirmation; `CamerasScreen`
  drawn from `CamerasState`), its row's line from the last list (asked when Settings opens; `LibraryApp.kt`). Shared:
  `CamerasController`, `CamerasState`, `CameraModel`, `CameraGroup` and `cameraName` in
  [Cameras.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/server/Cameras.kt), the names
  in [CameraNames.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/server/CameraNames.kt), over
  `ServerLibrary.cameras` / `setCamera`; Sound folders (`Route.SoundFolders`) in
  [SoundFoldersScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/SoundFoldersScreen.kt)
  (`SoundFoldersRoute`: a `SoundFoldersController` per opening and the move waiting for its answer; `SoundFoldersScreen`
  drawn from `SoundFoldersState`), its row's line the same way. Shared: `SoundFoldersController`, `SoundFoldersState`,
  `ServerSoundFolder` and `SoundFolderMark` in
  [SoundFolders.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/server/SoundFolders.kt), over
  `ServerLibrary.soundFolders` / `setSoundFolder`;
  Show in the app in [ShowInAppPage.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/ShowInAppPage.kt)
  from the shared `KindsStatus` ([KindsStatus.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/settings/KindsStatus.kt));
  Private settings in [HiddenScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/HiddenScreen.kt)
  behind [PhoneLock.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/PhoneLock.kt)
  (`PhoneLocked`). Shared: `HiddenCollectionsController` (`HiddenCollectionsState`: loading, unreachable, ready with
  the filter, saving, "not changed" and the list `PrivateKinds`) over `ServerLibrary.hiddenCollections` / `setHiddenCollections`, the
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
- [Settings — Loading Moments](../ux-history/settings/2026-10-26-loading-moments.md) (Oct 26, 2026): an Advanced section with a page of chips (a) picked.
- [Show in the app — imported files](../ux-history/sources/2026-10-27-imported.md) (Oct 27, 2026): "Imported" as one source with its kinds, saying what Private settings hides on all devices (b2) picked.
- [Your cameras](../ux-history/cameras/2026-10-27-your-cameras.md) (Oct 27, 2026): one list with a "Mine" switch per camera (a) picked, promoted in c6e46cd.
- [Backup — what stays on the phone](../ux-history/backup/2026-10-28-keep-on-phone.md) (Oct 28, 2026): one Keep on this phone card with a switch per kind (b2) picked, promoted in ecb5d00.
- [Sound folders](../ux-history/sound-folders/2026-10-27-sound-folders.md) (Oct 27, 2026): folders grouped under Calls, Voice recordings and Kept as folders (b) picked, promoted in a2f654b.
- [Show in the app and Private settings by kind](../ux-history/kinds/2026-10-27-kinds-and-private.md) (Oct 27, 2026): a list of kinds with totals (kinds c3) and Private settings choosing what shows, behind the lock (private c2) picked, promoted in 5f663b1.
