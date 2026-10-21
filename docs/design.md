# Journal: design and status

What the app is, what was decided and why, what is built, and what comes next. Screen-level detail is in
[screens/](screens/README.md). Mockups: the design canvas at https://claude.ai/artifact/QKsfaEgFs3bCC4Qeqt4qPf
(screens 1–12; 9–12 are not built yet).

## The product

A private journal built from the owner's own photos and videos, served by their own server (journal-server-go,
mTLS, one user per certificate). Like Google Photos or iCloud, everything taken on the phone should eventually be
backed up automatically, with the option to keep originals only on the server. Unlike them, it is a journal: photos
are gathered into **stories** with the owner's writing, grouped into moments, and tagged.

## Concepts

| Concept | What it is |
|---|---|
| **Photo library** | Every photo and video on the device, read through MediaStore (Android) / PhotoKit (iOS). Read only, always. |
| **Story** | A title (optional; an untitled story is named by its dates) over a set of photos, with text: a trip, a birthday, "Our dog". **No dates of its own**; the span shown comes from its photos. A photo can be in many stories. Was called "event" until it was renamed. |
| **Group** | Photos of one moment shown as one tile inside a story (a burst, or picked by hand), with its own text. Groups nest: a group's tiles are its photos and its subgroups. Within a story a photo is directly in at most one group. |
| **Tag** | A label on a photo, for photos that belong somewhere but don't deserve a story. Free text, compared ignoring case. |
| **Note** | A photo's own text (caption), shown wherever the photo appears. |
| **Unsorted** | Photos in no story **and** without tags. A note alone doesn't sort a photo. Neighboring days on a story page offer only these. |

## Decisions (and why)

**Look and navigation**
- The library screens are immersive: photos edge to edge, controls floating over a fade. They follow the system
  light or dark theme; the photo viewer is always dark.
- Home has two tabs, **Stories** and **Moments** (renamed from Journal / Unsorted on Oct 19, 2026). Stories is the
  timeline, newest first, with **only story cards**. Moments shows **every photo**, also those in stories or tagged
  (asked Oct 19, 2026; Android; iOS still shows only unsorted photos there). A story sits at its **newest photo's day**, so adding to an old story moves it up. (The owner didn't choose between newest photo, first photo
  or a separate tab; newest photo is the default and is one line to change: `StoryView.timelineDay`.)
- Moments are **by day** by default; Settings can switch to **by breaks between photos**
  (a new row after 12 h without photos by default, 2–48 h), so a night out that crosses midnight stays together.
- Moments reads **like a story that arranged itself** (asked Oct 18, 2026; Android first): full-width photos at
  their own shape, moments split by time, GPS place and source folder ("place · time" labels), near-duplicates as
  one photo you swipe (no peek), a filter bar of source toggles (Camera and WhatsApp on at first), and trip
  suggestions. **Stories are never made automatically**; only + Story or Save as story does. See
  [screens/moments.md](screens/moments.md).
- Top bar: **Tags** button and a **⋯** menu with **All photos** and **Settings**. Bottom bar: Stories / Moments, plus "N not backed up".
- Server status shows only when something is wrong: an **Offline** pill, or a **Register again** card on 401.
- Sub-stories (stories inside stories) were agreed in principle; when built, they show only inside their parent
  (with a Settings option later), never on the home timeline.
- "Day 2" labels and **+ Add <day>** buttons only for **trip-like** stories (photos within 14 days).

**Choosing among many stories and tags**
- Never a full list. Pickers are a search sheet focused for typing, with suggestions before typing (stories with
  photos **on these days**, **nearby** within 7 days, 3 **recent**), title search (substring, one typo allowed for
  4+ letters), and **Create "…"** first.

**Groups**
- **Burst suggestions** are automatic: photos within **10 s** of each other, or sharing the camera's burst id (iOS
  `burstIdentifier`; Samsung `20261008_140901_092.jpg` names), runs of **3+** photos, videos excluded. "Not now" is
  remembered per story.
- **Suggestions go by looks, at any time** (asked Oct 18, 2026): in a story, and inside a group (as subgroups),
  photos that look alike are suggested together however far apart they were taken, and quick shots of different
  things are split; two are enough. Time (10 s, 3+) is only the fallback for photos not read yet. Looks: a dHash for
  near-duplicates plus each platform's own image embedding for the same scene (Vision feature print on iOS,
  MediaPipe MobileNet-V3 large on Android), zoom-aware (each photo's zoomed centers are compared with the other's
  whole frame); a new story or group is read first. The owner chose native per platform for this, and **one shared open model**
  (commercial use allowed, e.g. SigLIP 2 or DINOv2; not MobileCLIP, whose weights are research-only) later for
  search across the library, to be decided with upload and sync. Fingerprints are a per-device cache, never synced.
- **Group** puts the selected tiles (photos and groups) in a new group, in the story or, from a group's page, as a
  subgroup: **groups nest**, to any depth. Undo restores the story as it was.
- **Merge** (offered when a group is selected) joins the selected tiles into the first selected group instead; the
  others' photos, subgroups and text move into it.
- Ungrouping, or a group left with one tile, moves its photos, subgroups and text one level up: into the group around
  it, or the story (its opening text), so nothing is lost.

**Layout**
- A story's days are grids **6 square cells wide**; tiles take size presets (Small 2×2, Wide 4×2, Tall 2×4, Half
  3×3, Large 4×4, Full 6×4, or Original: full width at the photo's own ratio) and are placed by **dense packing** in
  the story's order. Any tile can be zoomed (up to 4×) around its focus point. **The automatic size is Original**,
  so an unarranged story is its photos full width, uncropped, one under the other. A group is one tile in its
  story's layout; the group page itself stays automatic.
- Arranging stores only what the owner chose (size, focus point, zoom, order) in story.json; everything else stays
  automatic. (Before Oct 18, 2026 the automatic layout was a large tile and rows of small ones; unarranged stories
  now show as Original.)
- Order changes only **within a day**, so a story still reads oldest day first.

**Photos are never modified**
- The app must never change, move or delete a photo. Enforced by `PhotoSafetyTest` (scans all sources for
  MediaStore/PhotoKit write APIs and write permissions) and by the platforms (read-only permissions on Android;
  PhotoKit deletion always asks on iOS). Story ids are validated before any file operation.

## Data

Everything is **plain files first**: the files are the truth and any database is a rebuildable cache, so the data
folder alone is a complete, portable journal if the server is ever lost.

**On the device today** (stories and notes exist only here until sync is built), in the app's private storage
(Android `files/journal/`, iOS `Application Support/journal/`). Uninstalling the app deletes them.

```
journal/
  stories/
    2026-10-17T12-52-22.257Z/     ← id = UTC creation time; folder never renamed
      story.json                  ← title, tiles in order (with size/focus when arranged), groups,
                                    dismissed suggestions; format v1
      story.md                    ← "# Title", opening text, then <!-- group:g1 --> sections
  media/
    22.json                       ← one note per photo: caption, tags, crops, Moments set and the
                                    photo a set opens on ("front") (device id; "/" etc. become "_")
```

- **Ids**: the UTC creation time with dashes (valid folder name everywhere). Each device issues strictly increasing
  ids (`max(now, last + 1 ms)`, last value kept in settings). The owner chose timestamp only, **no device part**;
  the rare cross-device same-millisecond case is handled at sync: the server refuses to overwrite an id with
  different content and the client re-saves under a new timestamp. Older stories keep their UUID ids.
- **Photos in files** are referenced by **device id** (MediaStore id / PHAsset local identifier), with `fileName`
  and `takenAt` kept so they can be matched to their server copy once uploads exist.
- **story.md** is one file per story (sub-stories get their own). Group text lives in it under hidden markers so it
  reads as one story outside the app. Sections whose group is gone are kept.
- Old `events/<id>/event.json` folders are migrated to `stories/<id>/story.json` on load; old dates are ignored.

**Planned on the server** (not built): a data folder of originals (never modified) under `media/YYYY/YYYY-MM/` with a
JSON sidecar per file (facts, caption, tags; id = sha256 of the content, which also deduplicates), `stories/<id>/`
folders as above but referencing photos by sha256, a generated `index.md` table of contents by date, and a
disposable `.cache/` index rebuilt from the files.

## Status

**Built (Android and iOS, logic shared in `sharedLogic`)**
- Photo access screen, Journal timeline, All photos (2×2 hero per day, filters), viewer (photos, video, details).
- Backup status model and badges (everything shows "Only on this device": uploads don't exist).
- Stories: create from a day or from the viewer, add neighboring days, rename, delete, story text.
- Groups: burst suggestions (by time, and by looks: dHash + native embeddings), select-and-group with Undo, merging groups (with photos or each other), group page,
  set cover, remove, ungroup, group text; groups inside groups (select on a group's page).
- Arranging a story's layout (**Android only** so far): sizes, Earlier/Later within a day, focus point and zoom,
  reset. iOS
  shows the automatic layout (every tile full width at its ratio) and doesn't read the arrangement yet.
- Tags and notes per photo, Tags screen, tag page; tagged photos leave Unsorted.
- Search pickers for stories and tags.
- Screen docs in `docs/screens/`; shared tests (83 on the JVM host, 81 on the iOS simulator).

**Verified by hand** on the Android emulator (most flows) and installed on a physical Samsung (SM-S931B) over
wireless debugging; enrollment against the real server on the phone was not yet confirmed. iOS builds and the
shared logic passes on the simulator, but most iOS tap flows haven't been clicked through (WebDriverAgent +
mobile-mcp was being set up for that: `scripts/wda-simulator.sh`).

**In progress by the owner (not from the main build sessions)**: Hebrew localization (`values-iw/`,
`Localizable.xcstrings`, `InfoPlist.xcstrings`), enrollment screen changes, default server address
`journal.example.com:56398` (Oct 21, 2026; before that the server PC at `192.168.50.2:8080`, and the Mac at
`192.168.50.150` before that).

**Not built yet**
- On iOS: backup and server-only photos. Everywhere: playing server-only videos, downloading an original, story/note
  sync (Android backup and server-only photos are built, see below).
- Sub-stories; layout on iOS (render the shared bands, then the arrange mode); layout templates and drag-and-drop
  reordering; choosing a story's cover.
- Story side **+ Add photos** picker and "N new photos from these days" suggestion (canvas screens 11–12).
- Multi-select on the Journal / All photos (tag or add several at once; the shared logic already takes lists).
- Renaming/merging tags; month scrubber; similarity search across the library (shared model, see Groups).

## Upload and sync

Agreed with the owner Oct 19–20, 2026. **Built:** server upload and previews (journal-server-go
[docs/media.md](../../journal-server-go/docs/media.md)), and **Android backup** (Settings → Backup, WorkManager, progress
notification; see [screens/settings.md](screens/settings.md)), and **photos only on the server** (Android): the
library adds them from the server's change feed (`changes`, kept in `files/server/media.json`) and draws them from
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
fetched, and their tiles show the poster frame the server makes. Not yet: iOS, story/note sync.

**Server and network**
- A dedicated machine with a fixed IP, reachable from the internet (mTLS on everything but enrollment). It will
  probably run **Windows** (as an application for now, a service later), so the server stays cross-platform: no cgo,
  Windows-safe names, libvips/ffmpeg as separate programs. Backup to another machine and scrubbing come later.
- The server's data folder is the truth: originals byte for byte under `media/YYYY/YYYY-MM/` (photo's local time) with
  a sidecar each (`format`, `sha256`, `facts`, later `note`), `stories/<id>/`, and a rebuildable `.cache/`.

**Uploading** (built on the server)
- `missingMedia(hashes)` → `startUpload(input)` → `PUT /upload/{sha256}` in 16 MB pieces with `Content-Range`;
  resumable (`HEAD`, or `startUpload` again), deduplicated by sha256, verified before the file is placed.
- On Android the hash is of the **unredacted** original (`setRequireOriginal`), or GPS is missing from the bytes.
- **Edits**: same device id with new content uploads as a new file with `versionOf` (iOS keeps the original and the
  edit in one asset, so it always knows; on Android only in-place edits are known). Saved copies stay separate photos.
- **Live Photos / RAW**: the second file is uploaded after the first with `pairedWith` naming it. Samsung motion
  photos are one JPEG and upload as is.
- **What uploads**: sources chosen in Settings, **default Camera only**. **When**: Settings conditions Wi-Fi only,
  only listed Wi-Fi networks (needs location permission to read the network name), only while charging. **Order**:
  oldest first by default, newest first as an option.
- In the background with the app closed: Android WorkManager with a foreground progress notification; iOS a
  background URLSession (stopped by iOS if the user force-quits the app), progress in the app. iOS users of this app
  keep photos on the device (no iCloud Optimize Storage).

**Viewing without originals** (built on Android, except videos and originals)
- The app's library is one list of device photos and server photos joined by sha256 (or by file name and size before
  backup has read a device photo, so a new device doesn't show each photo twice); a photo only on the server
  (`MediaItem.isOnDevice` false; it keeps the device id it had, else `server:<sha256>`) shows the server's preview,
  or its downloaded original. Videos stream (see Upload and sync above). A new device downloads only the journal data (paged change
  feed, gzip), then previews as you scroll (cache default **10 GB**, set in Settings). Previews are optional (Oct 20,
  2026): off, the originals are downloaded into the same cache instead; on, their format (WebP, HEIF, AVIF, JPEG XL,
  JPEG) and quality (Low–Max) are chosen in Settings. The server keeps no previews unless the user's `preview_cache`
  is on in its config.json.
- **Shared, for both apps** (the platforms only decode, play and draw):
  - What to show for a server photo: `ServerPreviews.tile` (a tile: the original here, else the exact preview, or the
    original downloaded with previews off, a preview when the device can't decode it; a video's poster),
    `ServerPreviews.viewer` (opened alone: the original, downloaded and kept) and `largestPreview` (what shows meanwhile).
    The platform passes its decoders (`ServerPreviews.Decoders`); Android's JPEG XL decoder is a library, iOS 17+ has one.
  - What is kept of a photo (info panel): `PhoneStorage.keptHere` (each preview's pixels, format and quality, the
    original, a video's cached bytes); `mediaKind`, `megapixels` and `percentOf` for its wording.
  - Storage on this phone: `PhoneStorage` (one limit for previews, originals and videos; usage, Clear, the videos'
    share). The platform's video cache implements `VideoCache` (Android: Media3's cache, keyed by sha256); a video
    streams from `ServerPreviews.mediaUrl` with the device's mTLS client.
  - Free up space: `FreeUpController` (rules, sources, checks, batches, waiting files) with `FreeUpState.summary`,
    `statusLines` and `needsConfirmation`; the platform implements `PhotoTrash` (iOS: PhotoKit, which PhotoSafetyTest must
    allow) and runs it in the background.
- **Previews are made by the server** at the exact pixel size (and crop) the client asks for, never upscaled.
  **Videos are streamed as the original** (HTTP Range); the server only makes the poster frame.
- Fingerprints for grouping by looks stay a per-device cache, computed from the large preview when there is no
  original.

**Stories and notes** (not built)
- File sync: `putFile(path, content, baseVersion)` (version = sha256 of the content; conflict returns the current
  file), `changes(since: cursor)` pages of up to ~2,000 entries. story.json conflicts are merged by the client;
  story.md and notes: newer wins, the other kept as a conflict copy. Notes sync as `notes/<sha256>` and are stored
  inside the photo's sidecar. Files reference photos by sha256; a story syncs once its photos are uploaded.
- Other devices check for changes on open and **every 15 minutes** (Android WorkManager's minimum; on iOS when the
  system allows).
- Deleted stories go to a server trash for **6 months**. Stories made before sync may simply be deleted rather than
  migrated (never photos).
- Later: removing a photo from the journal, certificate renewal, search with a shared model.
