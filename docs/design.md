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
| **Story** | A title over a set of photos, with text: a trip, a birthday, "Our dog". **No dates of its own**; the span shown comes from its photos. A photo can be in many stories. Was called "event" until it was renamed. |
| **Group** | Photos of one moment shown as one tile inside a story (a burst, or picked by hand), with its own text. Within a story a photo is in at most one group. |
| **Tag** | A label on a photo, for photos that belong somewhere but don't deserve a story. Free text, compared ignoring case. |
| **Note** | A photo's own text (caption), shown wherever the photo appears. |
| **Unsorted** | Photos in no story **and** without tags. A note alone doesn't sort a photo. |

## Decisions (and why)

**Look and navigation**
- The library screens are immersive: photos edge to edge, controls floating over a fade. They follow the system
  light or dark theme; the photo viewer is always dark.
- Home is the **Journal timeline**, newest first: story cards and unsorted days. A story sits at its **newest
  photo's day**, so adding to an old story moves it up. (The owner didn't choose between newest photo, first photo
  or a separate tab; newest photo is the default and is one line to change: `StoryView.timelineDay`.)
- Top bar: **Tags** button and a **⋯** menu with **All photos**. Bottom bar: Journal / Unsorted, plus "N not backed up".
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
- Ungrouping keeps the photos and moves the group's text into the story's opening text, so no text is lost.

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
      story.json                  ← title, members (tiles), groups, dismissed suggestions; format v1
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
- Groups: burst suggestions, select-and-group with Undo, group page, set cover, remove, ungroup, group text.
- Tags and notes per photo, Tags screen, tag page; tagged photos leave Unsorted.
- Search pickers for stories and tags.
- Screen docs in `docs/screens/`; shared tests (67 on the JVM host, 65 on the iOS simulator).

**Verified by hand** on the Android emulator (most flows) and installed on a physical Samsung (SM-S931B) over
wireless debugging; enrollment against the real server on the phone was not yet confirmed. iOS builds and the
shared logic passes on the simulator, but most iOS tap flows haven't been clicked through (WebDriverAgent +
mobile-mcp was being set up for that: `scripts/wda-simulator.sh`).

**In progress by the owner (not from the main build sessions)**: Hebrew localization (`values-iw/`,
`Localizable.xcstrings`, `InfoPlist.xcstrings`), enrollment screen changes, server address now
`192.168.50.150:8080`.

**Not built yet**
- Upload and sync (next topic, see below), and with it event/story sync to the server.
- Sub-stories; arranging a story's layout (6-column grid with size presets, crop focus, templates; designed);
  choosing a story's cover.
- Story side **+ Add photos** picker and "N new photos from these days" suggestion (canvas screens 11–12).
- Multi-select on the Journal / All photos (tag or add several at once; the shared logic already takes lists).
- Renaming/merging tags; grouping by visual similarity (perceptual hash); month scrubber; Settings.

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
