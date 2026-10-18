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
| **Unsorted** | Photos in no story **and** without tags. A note alone doesn't sort a photo. |

## Decisions (and why)

**Look and navigation**
- The library screens are immersive: photos edge to edge, controls floating over a fade. They follow the system
  light or dark theme; the photo viewer is always dark.
- Home is the **Journal timeline**, newest first, with **only story cards**; unsorted photos are only in the
  **Unsorted** tab. A story sits at its **newest photo's day**, so adding to an old story moves it up. (The owner didn't choose between newest photo, first photo
  or a separate tab; newest photo is the default and is one line to change: `StoryView.timelineDay`.)
- Unsorted photos are **by day** by default; Settings can switch to **by breaks between photos**
  (a new row after 12 h without photos by default, 2–48 h), so a night out that crosses midnight stays together.
- Top bar: **Tags** button and a **⋯** menu with **All photos** and **Settings**. Bottom bar: Journal / Unsorted, plus "N not backed up".
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
  MediaPipe MobileNet-V3 on Android); a new story or group is read first. The owner chose native per platform for this, and **one shared open model**
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
    22.json                       ← one note per photo: caption, tags (device id; "/" etc. become "_")
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
`Localizable.xcstrings`, `InfoPlist.xcstrings`), enrollment screen changes, server address now
`192.168.50.150:8080`.

**Not built yet**
- Upload and sync (next topic, see below), and with it event/story sync to the server.
- Sub-stories; layout on iOS (render the shared bands, then the arrange mode); layout templates and drag-and-drop
  reordering; choosing a story's cover.
- Story side **+ Add photos** picker and "N new photos from these days" suggestion (canvas screens 11–12).
- Multi-select on the Journal / All photos (tag or add several at once; the shared logic already takes lists).
- Renaming/merging tags; month scrubber; similarity search across the library (shared model, see Groups).

## Next: upload and sync

The owner wants to discuss the upload design before anything is built. Questions to settle:
- What uploads: all photos and videos, or camera folders only (Android shows screenshots/downloads too).
- When: automatic in the background (WorkManager / BGTaskScheduler), Wi-Fi only vs mobile data, charging.
- How: content hash (sha256) for identity and dedupe; resumable uploads for large videos (some are 1–2 GB);
  a GraphQL mutation vs a plain HTTP upload endpoint (mTLS either way).
- Mapping device ids to server ids, then moving story/notes files to reference sha256.
- "Keep only on the server / free up space": what deleting a local copy means, and how to do it without ever
  risking a photo that isn't safely on the server (it would also need a deliberate, reviewed change to
  `PhotoSafetyTest`).
- Sync of stories and notes between devices: last write wins per file, the id collision rule above.
