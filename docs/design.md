# Journal: design and status

What the app is, what was decided and why, what is built, and what comes next. Screen-level detail is in
[screens/](screens/README.md). Mockups: the design canvas at https://claude.ai/artifact/QKsfaEgFs3bCC4Qeqt4qPf
(screens 1–12; 9–12 are not built yet).

## The product

A private journal built from the owner's own photos and videos, served by their own server (journal-server-go,
mTLS, one user per certificate). Like Google Photos or iCloud, everything taken on the phone should eventually be
backed up automatically, with the option to keep originals only on the server. Unlike them, it is a journal: the owner
writes a title and a paragraph on the photos that matter, and those make up the **storyline**; every photo is also
grouped into moments, and can be tagged.

## Concepts

| Concept | What it is |
|---|---|
| **Photo library** | Every photo and video on the device, read through MediaStore (Android) / PhotoKit (iOS). Read only, always. |
| **Note** | A photo's own writing, a title and a paragraph (`caption`) with earlier versions, shown wherever the photo appears. |
| **Storyline** | Every photo with writing, one each (never a set), newest first, unless removed from it. Replaced stories on Oct 21, 2026. |
| **Moments** | Every photo, in rows by day (or by breaks), split by time, place and source, near-duplicates as one set you swipe. Worked out, never saved, apart from sets merged or split by hand. |
| **Tag** | A label on a photo, for photos that belong somewhere without writing about them. Free text, compared ignoring case. |

Stories (a title over a set of photos, with groups, covers and an arranged layout), "Unsorted", and trip
suggestions existed until **Oct 21, 2026**, when the owner replaced them with the storyline and had the existing
stories deleted (see "Storyline" below).

## Decisions (and why)

**Look and navigation**
- The library screens are immersive: photos edge to edge, controls floating over them (over a fade; on the home
  screen only behind the top bar while the list is away from its top, since Oct 24, 2026). They follow the system
  light or dark theme; the photo viewer is always dark.
- Home has three tabs: **Moments**, **Storyline** and **Explore**, and opens on Moments (first and the default since
  Oct 24, 2026, as the owner asked; Storyline was before). (Journal / Unsorted until Oct 19, 2026, then Stories /
  Moments; Explore from Oct 20; Storyline replaced Stories on Oct 21, 2026.) Moments shows **every photo**, also those
  tagged or written about.
- Moments are **by day** by default; Settings can switch to **by breaks between photos**
  (a new row after 12 h without photos by default, 2–48 h), so a night out that crosses midnight stays together.
- Moments reads **like a story that arranged itself** (asked Oct 18, 2026; Android first, iOS since Oct 21, 2026): full-width photos at
  their own shape, moments split by time, GPS place and source folder ("place · time" labels), near-duplicates as
  one photo you swipe (no peek), and a filter bar of source toggles. See [screens/moments.md](screens/moments.md).
- Top bar: **Tags** and **Settings** buttons; the title row and the bar under it slide away while scrolling down. Bottom bar: Moments / Storyline / Explore, plus "N not
  backed up", and over it the month pill with the day at the top of the feed.
- Server status shows only when something is wrong: an **Offline** pill, or a **Register again** card on 401.

**Storyline** (owner, Oct 21, 2026)
- Stories asked the owner to sort photos ahead of time; the storyline grows by itself from what is written. **Write**
  opens a title box and a paragraph box; on Moments and the storyline the title is above the photo and the paragraph
  under it.
- Every photo with writing is on the storyline, **newest first**, one photo per entry: to have the best photo of a
  set there, open it and write on it. Sets don't show on the storyline yet; adding more photos to one entry is for
  later.
- **Remove from storyline** keeps the writing (it still shows in Moments); **Add to storyline** puts it back.
- Each save keeps the earlier writing as a version (saves less than 10 minutes apart are one version); History shows
  them with what changed, and Restore never loses anything. Moments doesn't show history.
- Existing stories, with their groups, covers, layouts and story.md text, were **deleted**, as the owner asked; the
  app deletes `journal/stories` (and the old `journal/events`) on start. Photos, notes, tags and crops were kept.
- Tags stay.

**Sets in Moments, by looks** (asked Oct 18, 2026, first for story suggestions)
- Photos that look alike are one set however far apart they were taken, and quick shots of different things are
  split; two are enough. Time (10 s) is only the fallback for photos not read yet. Looks: a dHash for
  near-duplicates plus each platform's own image embedding for the same scene (Vision feature print on iOS,
  MediaPipe MobileNet-V3 large on Android, downloaded on first use since Nov 8, 2026, to keep 10.9 MB out of the APK:
  from Google's pinned link first, else the user's server), zoom-aware (each photo's zoomed centers are compared with the other's
  whole frame). The owner chose native per platform for this, and **one shared open model** (commercial use allowed,
  e.g. SigLIP 2 or DINOv2; not MobileCLIP, whose weights are research-only) for search across the library, which the
  server now has (`searchMedia`). Fingerprints are a per-device cache, never synced.
- **Models come from where they were published, then the server** (Nov 8, 2026, the owner's choice): each model file
  the phone runs (the photo embedder, Search by words' text model) is downloaded from its pinned Hugging Face or
  Google link first (`ModelFileInfo.source`, from the server's `phoneModels` and `localIndexes`; the app also knows the
  photo model's link and sha256 itself), and from the user's server when that fails or sends other bytes, so a large
  model doesn't take the server's upload. **Privacy:** this is the one time the phone contacts anyone but the user's
  server and the map's tiles: once per model file, to Hugging Face (huggingface.co and its CDN) or Google
  (storage.googleapis.com), a plain download that says nothing about the user, their photos or their server. What
  comes is checked against the server's sha256 before it is used.

**Photos are never modified**
- The app must never change, move or delete a photo. Enforced by `PhotoSafetyTest` (scans all sources for
  MediaStore/PhotoKit write APIs and write permissions) and by the platforms (read-only permissions on Android;
  PhotoKit deletion always asks on iOS). Note file names are made safe before any file operation.

## Data

Everything is **plain files first**: the files are the truth and any database is a rebuildable cache, so the data
folder alone is a complete, portable journal if the server is ever lost.

**On the device today** (notes exist only here until sync is built), in the app's private storage
(Android `files/journal/`, iOS `Application Support/journal/`). Uninstalling the app deletes them.

```
journal/
  media/
    22.json                       ← one note per photo (format v5): title, caption (the paragraph),
                                    writtenAt, history [{title, text, savedAt}], "storyline": false
                                    when removed from it, tags, crops, zoom, Moments set, "highlight"
                                    (its place among the set's highlights; v4's "front" is read as 1)
                                    and on a named moment's cover "setInfo" {name, showOn, description (since Nov 10, 2026)}
                                    (device id; "/" etc. become "_")
```

- `stories/` (story.json, story.md) was deleted with stories on Oct 21, 2026. Set ids in Moments are still the UTC
  time with dashes, strictly increasing on each device (`StoryIds`).
- **Photos in files** are referenced by **device id** (MediaStore id / PHAsset local identifier), with `fileName`
  and `takenAt` kept so they can be matched to their server copy once uploads exist.

**Planned on the server** (not built): a data folder of originals (never modified) under `media/YYYY/YYYY-MM/` with a
JSON sidecar per file (facts, the note, tags; id = sha256 of the content, which also deduplicates), a generated `index.md` table of
contents by date, and a disposable `.cache/` index rebuilt from the files.

## Status

**Built (Android and iOS, logic shared in `sharedLogic`)**
- Photo access screen, the Storyline, Moments and Explore tabs, viewer (photos, video, details).
- Backup to the server with its badges, the bottom pill and Settings → Backup (iOS since Oct 21, 2026; see "Upload
  and sync").
- Writing on a photo: a title and a paragraph, with History (versions, what changed, Restore); the storyline with
  Remove / Add to storyline (Oct 21, 2026, both platforms). Stories, groups and layouts were removed the same day.
- Moments' sets by looks (dHash + native embeddings), merged and split by hand.
- Enrollment by QR code: **Scan QR code** on the code screen and `journal://enroll?code=…&server=…` links, on both
  platforms (iOS Oct 21, 2026).
- Enrolling by id (Nov 7, 2026, both platforms; [screens/enrollment.md](screens/enrollment.md)): a link with the
  server's `fingerprint`, DHT `key` and `address`es pins the key from the first connection, so the phone enrolls at
  an address the certificate doesn't list, trying the other addresses, then the DHT, when the first doesn't answer.
- Tags and notes per photo, Tags screen, tag page, and the tag search picker.
- Explore tab: towns by country, years, a town's or a year's page, and the map (MapLibre with OpenFreeMap tiles on
  both; iOS since Oct 21, 2026).
- Screen docs in `docs/screens/`; shared tests (158 on the JVM host after stories were removed on Oct 21, 2026).

- The server's new features (Oct 24, 2026, both apps; [plans/server-features.md](plans/server-features.md)): People in
  Explore with a person's page and "Who's this?" ([screens/people.md](screens/people.md)), search by person, what the
  server found in a photo in its details (people, text, similar photos), the best-looking photo for a set's front,
  covers and Best first, "Getting search ready…", and Settings › Search › On device search (the server's indexes kept to
  search without it, with the search model on ONNX Runtime). Each is offered only when `serverInfo` says the server
  has it.

- Showing on a TV with Google Cast (Nov 9, 2026, Android; [cast.md](cast.md)): our own receiver page on GitHub
  Pages, originals served from the phone on the Wi-Fi, swipes, the slideshow and a zoom frame on the phone driving the
  TV. Waits for the receiver's app id from the Cast console and a try on the owner's Streamer.

**Verified by hand** on the Android emulator (most flows) and installed on a physical Samsung (SM-S931B) over
wireless debugging; enrollment against the real server on the phone was not yet confirmed. iOS builds and the
shared logic passes on the simulator, but most iOS tap flows haven't been clicked through (WebDriverAgent +
mobile-mcp was being set up for that: `scripts/wda-simulator.sh`).

**In progress by the owner (not from the main build sessions)**: Hebrew localization (`values-iw/`,
`Localizable.xcstrings`, `InfoPlist.xcstrings`), enrollment screen changes, default server address
`journal.example.com:56398` (Oct 21, 2026; before that the server PC at `192.168.50.2:8080`, and the Mac at
`192.168.50.150` before that).

**Not built yet**
- On iOS: "Only on these Wi-Fi networks". Everywhere: note sync.
- More than one photo for a storyline entry (the owner will decide how).
- Multi-select in Moments for tagging (tag several at once; the shared logic already takes lists).
- Renaming/merging tags; month scrubber; similarity search in the app (the server has `searchMedia`).
- Copies on a friend's server (swap), encrypted copies, restore, and encrypted accounts (the server can't read them;
  previews and indexes made on the phone), all optional: [plans/swap-and-encryption.md](plans/swap-and-encryption.md).

## Upload and sync

Agreed with the owner Oct 19–20, 2026. **Built:** server upload and previews (journal-server-go
[docs/media.md](../../journal-server-go/docs/media.md)), and **Android backup** (Settings → Backup, WorkManager, progress
notification; see [screens/settings.md](screens/settings.md)), and **photos only on the server** (Android): the
library adds them from the server's change feed (`changes`, kept in the library database `no_backup/library.db` since Oct 25, 2026; see [plans/library-on-disk.md](plans/library-on-disk.md)) and draws them from
previews the server makes at the tile's exact size (cached in `cache/previews`, up to the limit in Settings, 10 GB by
default). Previews and
downloaded originals are in the app's private storage, which the gallery never scans, and both folders also hold a
`.nomedia` file so they stay out of the gallery if they ever move to shared storage. Tested on the emulator,
including a photo deleted from the device. A photo's details show where its original is, and offer **Download
original** (kept in `files/originals`, never trimmed) and **Delete from this phone** (asked for Oct 20, 2026: only
after the server confirms it and its preview is kept; a gallery photo goes to the phone's trash through Android's
prompt). **Free up space** (Settings → Storage on this phone, Android; agreed Oct 20, 2026): takes the originals the
server has off the phone, older than X (by date taken) or all but the newest X GB, pairs together. In the background,
one file at a time, the server hashes its copy again (`POST /media/{sha256}/verify`) while the phone hashes its own;
only files where both match the sha256 backup uploaded go to the phone's **trash** (30 days), right away with
"Media management" access (`MANAGE_MEDIA`), else with one system prompt at the end. No previews are kept for them on
purpose: they are fetched as you scroll, like any server photo. See [screens/settings.md](screens/settings.md).
Server-only videos stream from their original (HTTP Range, ExoPlayer) into `cache/videos`, keeping what was
fetched, and their tiles show the poster frame the server makes. **iOS** (Oct 21, 2026) has the same photos only on
the server: the list in the library database `Application Support/library/library.db` (refreshed when the app comes to the foreground),
exact-size previews in `Caches/previews` decoded with ImageIO (WebP, HEIF, AVIF, JPEG XL), the viewer's original
card (its Download original and Delete from this phone went Oct 22, 2026; originals downloaded before stay in
`Application Support/originals`, left out of iCloud backup), and Settings →
Storage on this phone and Private media. **iOS backup** is built too (see "iOS backup" below). Server-only videos
stream on iOS too (Oct 21, 2026): `AVPlayer` reads them
through an `AVAssetResourceLoader` that serves byte ranges from `Caches/videos/<sha256>/` (`data`, a sparse file with
the bytes at their own offsets, and `index.json`, which ranges are kept, the length and type, when last played) and
fetches what is missing with Range requests on a URLSession answering the mTLS challenges through the shared
`IosServerStream`; the cache gives way whole videos, least recently played first, within `PhoneStorage.videoBudget`.
**iOS Free up space** (Oct 21, 2026) runs the same checks in the app, but deletes nothing during a run: every file
that passed waits for **Move to trash**, one PhotoKit request with iOS's own prompt, to Recently Deleted (see
[screens/settings.md](screens/settings.md)). **Mark private** (asked for Oct 21, 2026; Android and iOS): the viewer's
original card marks a photo the server has as private (`setPrivate`, kept in its sidecar with its edits and paired
files) or unmarks it. Private photos are hidden on every device while "Show private media" is off (the default); the
phone's own copy is hidden too. The server can also keep private photos from a
device entirely (`journal-server private <serial> withhold`, off by default): they come to it as deleted from the
change feed, and 404. **Hidden collections** (asked for Oct 22, 2026; Android and iOS): Settings → Private media →
Hidden collections, behind the phone's lock, hides all of WhatsApp, or the screenshots of chosen apps (the server
reads the app from a screenshot's name, `Media.app`), and (Oct 23, 2026) what the nudity scan scored at the chosen
score or more (from 50% by default; the apps hid it themselves before), on every device. The server does the hiding: hidden files come
as deleted, aren't counted or found by search, and 404; the app drops them like deleted files, with its cached
previews, originals and videos of them. **Tucked away** ("מוצנע", asked for Nov 8, 2026; Android, iOS to do): a
lighter level than private. Picked photos are tucked on the server (`setTucked`) and leave Moments, the storyline and
Explore, and search never finds them; each day with some says "3 tucked away", and tapping that reveals them for the
session, behind the phone's lock once per session (see [screens/moments.md](screens/moments.md#tucked-away-nov-8-2026)).
**Notes sync** with the server (Nov 8, 2026; see Notes below).

**Server and network**
- A dedicated machine with a fixed IP, reachable from the internet (mTLS on everything but enrollment). It will
  probably run **Windows** (as an application for now, a service later), so the server stays cross-platform: no cgo,
  Windows-safe names, libvips/ffmpeg as separate programs. Backup to another machine and scrubbing come later.
- The server's data folder is the truth: originals byte for byte under `media/YYYY/YYYY-MM/` (photo's local time) with
  a sidecar each (`format`, `sha256`, `facts`, later `note`), and a rebuildable `.cache/`. Sound recordings (Oct 25,
  2026: recordings, phone calls, WhatsApp voice messages, brought in by the server's import) are files of kind
  `audio` like any other, a call's sidecar naming who it was with (`call`); a WAV is kept as FLAC. The user shows each
  kind in Hidden collections; the apps keep them apart from photos (Moments and their page only).
- **JPEG photos are kept as JPEG XL** (the owner's choice, Oct 28, 2026), losslessly: the very same JPEG comes back
  from it byte for byte, checked before each JPEG is replaced. The JPEG XL has its own sha256 (the server's and the
  apps' id from then on); its sidecar's `encodedFrom` keeps the JPEG's sha256, size, type and name, for comparing
  duplicates later. The server keeps uploads so as they arrive and the files already there with `scripts/jxl`
  overnight; the JPEG's sha256 still finds the file (`missingMedia`, `startUpload`, `/media`, verify), and the feed
  sends the JPEG as deleted right before its JPEG XL. The apps (Oct 28, 2026) keep each server file's
  `encodedFrom.sha256` (`server_media.encoded_sha256`, database version 10, whose migration reads the feed again), and
  the library matches a gallery photo to a server file by either sha256 (`Library.sq`): the phone's JPEG shows once,
  private and hidden as its JPEG XL, a JPEG deleted from the phone keeps its id, search answers (the JPEG XL's sha256)
  find the gallery photo, and `ServerLibrary.sha256Of` names it to the server by the JPEG XL's. Android backup (Oct 28,
  2026, asked by the owner) always sends a JPEG photo as the JPEG XL it packs (`JpegXl.kt`, libjxl through jxl-coder;
  `MediaFiles.encodeJxl`), once unpacking it gave the very same JPEG, with the JPEG's sha256, type, size and name as
  `encodedFrom`, and only to a server whose `serverInfo.keepsJpegAsJxl` says it checks such a file; the ledger keeps
  the JPEG's sha256, so the phone's JPEG and the server's JPEG XL stay one photo. A JPEG the phone can't pack, or whose
  JPEG XL the server refuses, goes as it is (the server packs it). The phone keeps its JPEG unless **Photos as JPEG XL**
  in Backup's Keep on this phone card is on (off by default, approved by the owner Oct 28, 2026, as the first write into the photo
  library): once the server took the phone's very bytes and verify confirms them, `PhotoReplacement.kt` writes the
  JPEG XL into the JPEG's folder and moves the JPEG to the gallery's trash (`AndroidPhotoTrash`, "Media management"
  access; when it can't, the JPEG XL it wrote is deleted and the JPEG stays). The gallery and Google Photos can't show
  JPEG XL (checked on the owner's Galaxy S25, Android 17); the app shows it by unpacking the JPEG (`Thumbnails.kt`
  `decodeJxl`, also for JPEG XL originals downloaded from the server). Android keeps no date taken for a JPEG XL and
  takes none set after, so the app keeps the JPEG's date in `files/jxl-dates.json` (`JxlDates`; one not recorded is
  dated from its JPEG's EXIF, once). The JPEG XL is a new photo on the phone with its own id; what the server keeps
  (private, hidden, notes) is by sha256 and stays. Share sends a JPEG XL as the JPEG it was packed from (Settings ›
  "Share JPEG XL as JPEG", on by default). `JpegXlDeviceTest` and `PhotoReplacementDeviceTest` check this on an
  emulator (`ANDROID_SERIAL=emulator-5554`; the second needs `appops set … MANAGE_MEDIA allow` and READ_MEDIA_IMAGES
  granted to `com.chencohensolution.journal.sharedLogic.test`). **Not built yet:** packing on iOS (it sends the JPEG;
  the server packs it); keeping JPEG XL for JPEGs the server packed itself (only the phone's own bytes replace a JPEG);
  EXIF details (place, camera) read on the phone from a JPEG XL kept there. See the server's docs/media.md, "JPEG XL".

**Uploading** (built on the server)
- `missingMedia(hashes)` → `startUpload(input)` → `PUT /upload/{sha256}` in 16 MB pieces with `Content-Range`;
  resumable (`HEAD`, or `startUpload` again), deduplicated by sha256, verified before the file is placed. The server
  keeps the bytes of a piece that breaks off (Oct 23, 2026), so the next piece goes on from where they ended.
- On Android the hash is of the **unredacted** original (`setRequireOriginal`), or GPS is missing from the bytes.
- **Edits**: same device id with new content uploads as a new file with `versionOf` (iOS keeps the original and the
  edit in one asset, so it always knows; on Android only in-place edits are known). Saved copies stay separate photos.
- **Live Photos / RAW**: the second file is uploaded after the first with `pairedWith` naming it. Samsung motion
  photos are one JPEG and upload as is.
- **What uploads**: sources chosen in Settings, **default Camera only** (since Oct 24, 2026 each folder or app is a
  source of its own, and Camera is DCIM/Camera; see [screens/moments.md](screens/moments.md)). **When**: Settings conditions Wi-Fi only,
  only listed Wi-Fi networks (needs location permission to read the network name), only while charging. **Order**:
  oldest first by default, newest first as an option.
- **Several files at once** (Oct 21, 2026, approved by the owner; the same shared `BackupEngine` on both platforms,
  only the amount differs): the run hashes the queue in batches of 100 (`missingMedia` per batch, hashing the next
  batch while the last one uploads) and starts the files the server lacks **in the queue's order** (the order setting
  decides what starts first) while fewer than `PieceSender.maxFilesInFlight` are on their way and their sizes add up
  to at most `maxBytesInFlight` (a larger file goes alone). Each file's pieces still go one after another. Android
  (`KtorPieceSender`): **3 files**, no byte limit: enough to hide each small photo's round trips, and each holds its
  16 MB piece in memory, so at most 48 MB. iOS (`BackgroundPieceSender`): **50 files or 512 MB**, whichever comes
  first (see "iOS backup"). A file that fails (refused, changed, hash mismatch) doesn't stop the others; a network or
  server error starts no more files, lets those on their way finish and then ends the run (retried later); a refused
  certificate (401) stops every file at once. Two device photos with the same bytes are sent once. The ledger is safe
  for completions at once (one lock around its file). Progress: `done` counts finished files, so the text stays
  "Backing up *done + 1* of *total*" (the next file to finish); `bytesDone` adds what has arrived of the files on
  their way, `currentName` is the earliest of them, and each one's ring shows (`BackupProgress.uploading`).
- In the background with the app closed: Android WorkManager with a foreground progress notification; iOS a
  background URLSession (stopped by iOS if the user force-quits the app), progress in the app. iOS users of this app
  keep photos on the device (no iCloud Optimize Storage); a photo only in iCloud is downloaded before it is read.
- **iOS backup (built Oct 21, 2026)**: the shared `BackupEngine`/`BackupController`/`UploadLedger` run unchanged;
  only how a piece travels is the platform's (`PieceSender`: Android keeps its in-process Ktor `PUT`). iOS writes each
  64 MB piece (the server's limit; it asks for 16 MB, but iOS wakes a closed app about once per finished piece, so
  fewer, larger pieces mean fewer waits; `PieceSender.pieceSize`) to `Caches/backup/pieces`, streamed from the
  photo's copy (no piece is held in memory), and hands it to a background URLSession
  (`BackgroundPieceSender`, session `com.chencohensolution.journal.backup`): the client certificate is answered from
  the Keychain and the server's certificate checked against the journal CA, as in the app's own client; Wi-Fi only is
  set on each request (no cellular, no expensive networks), so a piece waits for Wi-Fi. While the app runs, the run
  waits for the piece's answer (status and `Upload-Offset`, read as on Android). Suspended or ended by the system, iOS
  carries the piece on and relaunches the app in the background when it is answered
  (`handleEventsForBackgroundURLSession`): the session reconnects, adopts pieces still on their way
  (`InFlightPieces`: a piece asked for again while it is on its way is awaited, never sent twice), and the run starts
  again from the server's offset (`startUpload`). Up to **50 files (or 512 MB of them) are with iOS at once**, so each
  wake of a closed app leaves iOS a batch rather than one piece: 50 because iOS wakes a closed app about once per
  batch of finished tasks and spaces the wakes out, far below the thousands of tasks Apple warns slow a session, and
  few enough to top up in one wake (a PhotoKit copy and a `startUpload` each); 512 MB because each file on its way has
  its full copy in `Caches/backup/files` and a piece (up to 64 MB) in `Caches/backup/pieces`, so backup holds about
  1 GB of Caches at most (a single larger video goes alone). After a relaunch, pieces still on their way are adopted
  and don't count toward the limit, so for one run iOS may hold up to twice as many. The system's completion handler
  is called once the run has topped the queue back up: new pieces were handed to iOS and none for 2 s, or 50 are with
  iOS (or after 20 s; iOS allows about 30). The ledger is written only from server answers (a piece finished while nobody listened is
  seen as the server's offset, or as `MediaExists`/`missingMedia` when it was the last). Files are read with
  `PHAssetResourceManager.writeData` from the same resource the library reports (edit → full-size render, Live Photo
  → photo, RAW+JPEG → JPEG) into `Caches/backup/files`, one copy per file being hashed or uploaded, checked against
  the reported size (else skipped and read again later) and removed when the engine is done with that file (and any
  left when the run ends). Runs start when the app comes to the foreground, on
  **Back up now** or turning backup on, 10 s after the library changes, when iOS relaunches the app for an answered
  piece, and in `BGProcessingTask` (network required, external power with "Only while charging") and
  `BGAppRefreshTask` runs that iOS schedules when it sees fit (not on the simulator). Before a run, "Only on Wi-Fi"
  is checked with `NWPathMonitor` (not expensive) and "Only while charging" with the battery state; a change that
  breaks them stops the run (a piece already with iOS still arrives). Limits: iOS spaces out background relaunches
  (in a test, the next piece came about a minute after a relaunch, then waited for the app to open), so a large
  library mostly uploads while the app is open or in background task windows; force-quitting the app stops uploads
  until it is opened. Tested against a local server on the simulator: 409 photos and three 0.5–0.7 GB videos, with
  the app killed mid-upload in the foreground and in the background: every piece arrived once and the files matched
  their sha256. Several at once, tested Oct 21, 2026 on the simulator against a server slowed to ~8 s per piece: 116
  small photos; 50 pieces and 50 copies waited with iOS, which sent 6 at a time (its connections per host); with the
  app killed, iOS sent 18 more over 25 s with no app running, relaunched it, the run topped the queue back up within
  3 s and the app was suspended 10 s after the wake; iOS then sent the rest of that batch on its own and spaced
  further wakes out (its request delay grew 60 → 960 s, and tasks made in the background got a 60 s delay), so the
  last 9 went when the app was opened. Every file arrived once (116 stored, 116 distinct sha256, 116 ledger entries
  uploaded).

**Viewing without originals** (built on Android, except videos and originals)
- The app's library is one list of device photos and server photos joined by sha256 (or by file name and size before
  backup has read a device photo, so a new device doesn't show each photo twice); a photo only on the server
  (`MediaItem.isOnDevice` false; it keeps the device id it had, else `server:<sha256>`) shows the server's preview,
  or its downloaded original. Videos stream (see Upload and sync above). A new device downloads only the journal data (paged change
  feed, gzip), then previews as you scroll (cache default **10 GB**, set in Settings). Everything goes over one
  mTLS client per device identity (`ServerHttp`: previews, originals, verify and GraphQL), so the phone keeps one
  HTTP/2 connection to the server instead of a handshake per call (Oct 23, 2026). **Finding the server when its
  address changes** (Nov 7, 2026, Android and iOS; docs/plans/discovery.md "Phones"): after the features, the app asks
  `serverInfo.discovery` on its own (an older server doesn't know it) and keeps the server's key fingerprint, id, DHT
  key and up to 16 addresses (`KeptDiscovery` in `JournalSettings`). From then on the mTLS clients pin the key
  instead of checking the CA and the host name (Android `PinnedTrustManager`, also for the video stream; iOS
  `MtlsChallenges.serverTrust` reading the SPKI from the certificate's DER, `ServerPin`), so any address of the server
  works. A request that fails on the network (not an HTTP answer) makes `ServerFinder` try the kept addresses, 400 ms
  apart, the first to answer winning, then a DHT lookup at most every 2 minutes (`DhtClient`: KRPC `get` over UDP with
  ktor-network; the record opened with HKDF-SHA256 and XChaCha20-Poly1305, `Seal`, all pure Kotlin); the address that
  answers becomes `serverUrl` and the request is sent again (GraphQL and GETs; an upload piece fails once and the next
  goes to the new address). A new enrollment forgets what was kept, unless its link said who the server is (enrolling by id). Settings shows the server's id under its address.
  When the hidden collections or
  the device's private setting change, the server sends only the files that changed visibility, then goes on; a
  cursor it can't read starts the feed again, so the app never has to clear one. Previews are optional (Oct 20,
  2026): off, the originals are downloaded into the same cache instead; on, their format (WebP, HEIF, AVIF, JPEG XL,
  JPEG) and quality (Low–Max) are chosen in Settings. The server keeps no previews unless the user's `preview_cache`
  is on in its config.json.
- **Shared, for both apps** (the platforms only decode, play and draw):
  - What to show for a server photo: `ServerPreviews.tile` (a tile: the original here, else the exact preview, or the
    original downloaded with previews off, a preview when the device can't decode it; a video's poster),
    `ServerPreviews.viewer` (opened alone: the original, downloaded and kept) and `largestPreview` (what shows meanwhile).
    The platform passes its decoders (`ServerPreviews.Decoders`); Android's JPEG XL decoder is a library, iOS 17+ has one.
    iOS reaches all of it through `IosServerFiles` (iosMain: callbacks Swift can await, decoders implemented in
    Swift as `ServerImageDecoding`).
  - What is kept of a photo (info panel): `PhoneStorage.keptHere` (each preview's pixels, format and quality, the
    original, a video's cached bytes); `mediaKind`, `megapixels` and `percentOf` for its wording.
  - Storage on this phone: `PhoneStorage` (one limit for previews, originals and videos; usage, Clear, the videos'
    share). The platform's video cache implements `VideoCache` (Android: Media3's cache; iOS: `ServerVideoCache` in
    Swift; both keyed by sha256); a video streams from `ServerPreviews.mediaUrl` with the device's mTLS client (on
    iOS through `IosServerStream`, which gives Swift's URLSession the same challenge answers as the Ktor client,
    `MtlsChallenges`).
  - Free up space: `FreeUpController` (rules, sources, checks, batches, waiting files) with `FreeUpState.summary`,
    `statusLines` and `needsConfirmation`; the platform implements `PhotoTrash` and runs it (Android in the
    background; iOS in the app, with `IosPhotoTrash` never deleting by itself and PhotoKit's deletion only in
    `PhotoRemoval.swift`, which PhotoSafetyTest allows).
- **Previews are made by the server** at the exact pixel size (and crop) the client asks for, never upscaled.
  Large photos load in two steps (owner, Oct 22, 2026): a quick image first (MediaStore's thumbnail, or for a photo
  only on the server a "first image", a HEIF at medium quality and half the window's width, kept beside its sharp
  preview and loaded ahead of the scroll), and the sharp one only once the list moves slower than about a screen a
  second (`TwoStepPhoto`, `LocalScrollSettled`, `ServerPreviews.firstImage`). HEIF so phones decode it on their video
  chip; the sharp preview stays in the format chosen in Settings.
  **Videos are streamed as the original** (HTTP Range); the server only makes the poster frame.
- Fingerprints for grouping by looks stay a per-device cache, computed from the large preview when there is no
  original.

**Notes** (synced since Nov 8, 2026, asked for by the owner: "sync everything with the server"; stories were dropped
from this plan on Oct 21, 2026)
- What syncs: everything the phone keeps about a photo in `journal/media/<id>.json` (`PhotoNote`): the title and
  paragraph with their earlier versions, tags, crops, Moments sets and the photo a set opens on, "Remove from
  storyline", where the viewer was zoomed. The server keeps it in the photo's sidecar as `note` (journal-server-go
  docs/media.md "Notes"): the file's JSON object without what only the phone knows (`deviceId`, `fileName`,
  `takenAt`), kept as sent, so fields a newer app writes reach the others untouched. Its version is the sha256 of
  what the server keeps.
- **Out** (`NoteSync`, `stories/NoteSync.kt`): each change goes in the library database's outbox (`note_outbox`,
  `Notes.sq`), sent 3 seconds after the last change with `setNote(sha256, note, baseVersion)`, once the photo is on
  the server (the library's `server_sha256` for its id; until then it waits). The first time, every note already on the
  phone goes in the outbox.
- **In**: the change feed brings each file's note (`ChangesNotes`, asked only of a server with `serverInfo.notes`; the
  first such sync reads the feed again from the start) into `server_media.note`. A version that differs from the one
  last brought in (`note_synced`) replaces the note of the photo the library shows for that file, or of its new id when
  that changed, unless the phone's note is still in the outbox: then the two are merged.
- **Conflicts** (both changed since they were last the same; `setNote` answers `NoteConflict` with the server's note):
  `mergeNotes` keeps the writing saved last as the photo's, and the other as an earlier version (the histories
  joined), so nothing written is lost; tags, crops, sets, the storyline mark and zoom are the note changed last's. A
  note deleted on one side and changed on the other keeps the change. The merged note is sent again.
- Hidden with its photo: a file the device may not see comes as deleted, note and all; a tucked photo's writing stays
  out of Moments, the storyline, Explore and search with the photo, as before. Private files' notes come once
  private media is shown.
- Other devices get changes when they sync with the server (opening the app, coming back to it). Later: every 15
  minutes in the background (Android WorkManager's minimum; on iOS when the system allows). Both apps share it all;
  nothing in the UIs changed.
- Later: removing a photo from the journal, search with a shared model.
- **Certificate renewal** (Oct 30, 2026; Android only, iOS not built yet): a device certificate lasts a year. In its
  last 30 days the app renews it at start (`CertificateRenewal`, `enrollment/CertificateRenewal.kt`): a new key under
  a new alias, `renewCertificate` asked with the current certificate, a connection test with the new one, then the
  switch (`JournalController.useRenewedIdentity`) and the old key deleted. The server revokes the old certificate
  once the new one is used, so a renewal that fails part way changes nothing; it is tried at the next start. The
  certificate's end is kept in settings (`certificateExpiresAt`) from enrollment on, and read from the Keystore
  (`DeviceKeyStore.certificateNotAfter`) for a device enrolled before.
