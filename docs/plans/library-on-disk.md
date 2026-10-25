# Plan: the library on disk, read in pages (plan B)

**Not built yet.** Written Oct 25, 2026 on Windows, after the app ran out of memory while backing up 1,709 calls
(fixed for now in `0c1a97c`: library merges run one at a time). The owner asked how the app would cope with a
million items. Today it can't: this plan makes the app's memory independent of the library's size.

## Why

Today every screen reads one list held in memory:
- `LibraryController` keeps the device's items (`readItems`, all of MediaStore / PhotoKit) and the server's list
  (`ServerLibrary.media`, all of `server/media.json`). On every change it merges them into `LibraryState.days`, a
  new copy of every item.
  - Changes are frequent: a backup ledger change, a server feed page, hidden or private, aesthetic scores, kept
    originals.
- `StoriesController` builds the whole Moments feed (`buildFeed`) from that list again on each change, and the
  screens recompose from the new objects.
- About 50k items already peak near the 512 MB heap (`largeHeap` is on, so there is no more to ask for).
  - A heap dump on the owner's phone held about 280k `MediaItem`s, several generations of the merged list, plus
    75 MB of local search vectors.
  - At a million items one copy alone would not fit.
- The same design makes start-up slow and scrolling janky for the first minute.
  - The device library, `media.json`, the feed sync, places, looks, Free up space's plan and backup's run each
    trigger a full merge and a full feed rebuild.
  - Each new feed object recomposes Moments while the garbage collector pauses the app (300–700 ms "blocking GC"
    pauses in logcat).
  - This was being measured when the plan was written; see "Before starting".

## The goal

- Memory scales with what is on screen, not with the library: at most about 150 MB of heap for any library size.
- A change touches only the rows it changes. One upload finishing updates one row, never a merge of everything.
- Start-up shows Moments from disk at once; syncing goes on behind it without rebuilding what is shown.
- The data format of the design stays (docs/design.md, "plain files first"): the database is a per-device,
  rebuildable cache.
  - Deleting it only means reading MediaStore / PhotoKit and the server's feed again.
  - Nothing in it is the only copy of anything, except what is already a per-device cache today (the backup ledger).

## Decisions

Confirmed by the owner on Oct 25, 2026:

1. **SQLDelight** (KMP: Android driver, native driver on iOS) as the database, not Room KMP.
2. **Where the database lives:** Android `noBackupFilesDir/library.db`; iOS Application Support, excluded from iCloud
   backup.
3. **The backup ledger moves into the database**: `ledger.jsonl` is read once, then removed. One source, no copy kept
   in step.
4. **Paging library:** AndroidX Paging (`paging-common` is multiplatform; `paging-compose` on Android). On iOS a
   small Swift adapter asks the shared pager for pages as rows appear.

5. **Private media stays out of what screens read** (owner, Oct 25, 2026). While "Show private media" is off
   (`PrivateMedia`), photos marked private, by the user or by the server, get no row in `library_item`, so no
   `feed_section`, count, grid or search sees them. They keep rows only where backup needs them:
   - `device_item` and `ledger`, so a private photo on the phone isn't hashed and offered for upload again;
   - `server_media`, with its `private` column, so turning the setting on fills `library_item` from these tables at
     once, without reading the server's feed again.

   Changing the setting adds or removes those rows of `library_item` in one transaction, and rebuilds the rows of
   Moments on their days.

6. **Moments looks exactly as it does now** (owner, Oct 25, 2026). The rows built one at a time must match what
   `buildFeed` makes over the whole library today, photo for photo, set for set. Phase 5 checks this with a host test
   that compares both builders on the same library: the fake one, and a copy of the owner's.
   - The owner will refine the near-images logic (`blocksOf`, `PhotoLooks.similar`) later. So the row builder calls
     it as it is, per moment. `feed_section` stores the version of the grouping logic, and a new version rebuilds
     every row in the background while the old rows stay on screen.
   - Sets merged by hand get a `feed_set` table (photo id to set id, from `PhotoNote.feedSet`). A set is shown in
     the row of its oldest photo, as today. Changing a set rebuilds every row its photos are in.

   What `buildFeed` reads today beyond one row (checked Oct 25, 2026):
   - Near-duplicate and burst sets (`blocksOf`) are made within one moment, and hidden-source chips go to the nearest
     row of the photo's own day (`withHidden`). Both stay the same when built per row.
   - **Sets merged by hand** (`PhotoNote.feedSet`) can hold photos from any days. The set is shown where its oldest
     photo is, and its other photos leave their own rows. Built per row, this needs a set-membership table, and a
     change to a set rebuilds every row it touches.
   - Rows depend on their neighbours: "by breaks" grouping, and a long recording's marks (`soundMarks`), can run a row
     past midnight (see Risks).
   - The whole library is used for the filter bar's counts per source (a `GROUP BY source`), the timeline's months
     and days (a query on `feed_section`), and Explore's `home` (a grouped query on located photos). None of these
     needs the items in memory.

## Design

### Tables (shared, `sharedLogic/src/commonMain/sqldelight/`)
- `device_item`: one row per MediaStore / PhotoKit item. It holds MediaItem's fields: id, type, taken_at, day, size,
  modified, file name, mime, source, width, height, duration, burst, location, and the sha256 once known. Updated
  incrementally:
  - Android: `MediaStore.getGeneration` and `GENERATION_MODIFIED` read only what changed since the last generation
    kept; a full pass only when the volume's version changes.
  - iOS: `PHPhotoLibrary` change tokens (`fetchPersistentChanges(since:)`).
- `server_media`: the server's list, written straight from each feed page in one transaction per page.
  - This replaces `server/media.json` and the in-memory `known` map in `ServerLibrary.sync`. The cursor goes in a
    small `sync_state` table.
  - Hidden shas, private flags, aesthetic scores, call facts and `encodedFrom` are columns here, not separate maps.
- `ledger`: today's `LedgerEntry` (device id, size, modified, sha256, uploaded, versionOf, rejected).
- `library_item`: the merged library, the one table screens read. A device row, or a server-only row.
  - It is kept up to date by the writers above, never rebuilt whole. A device row learns its sha256 from the ledger
    and matches a server row. A server row with no device row is server-only.
  - Its backup status comes from a join on `ledger` when read, so an upload changes one ledger row and nothing else.
  - Indexes: `(day, taken_at)`, `sha256`, `source`, `(lat, lon)` for the map, `place` for Explore.
- `feed_section`: Moments' rows (today's `FeedSection`), precomputed per day or per break.
  - When items of a day change, only that day's sections (and, with "by breaks" grouping, its neighbours) are built
    again from its rows, with today's `buildFeed` logic per range.
  - Each section stores its key, range, title data and the ids of the photos it shows; photos are read by id when
    drawn.
- `sound`: the server's sound recordings (today `LibraryState.sounds`), read by day range for Moments.
- Later, the same way: `place` (from `places/`), `look` (fingerprints from `similarity/`), `note` and `tag` (from
  `stories/`), each moved to a table when its screen moves to paging.

### Reading
- `LibraryState` loses `days`. It keeps counts and flags (`itemCount`, `notBackedUpCount`, `notBackedUpBytes`,
  access, loading), now computed by SQL `COUNT`/`SUM`.
  - These are kept as flows that re-run only when their tables change (SQLDelight's `asFlow`).
- Moments: a `PagingSource` over `feed_section` (newest first), with the photos of each page read by id.
  Scrolling to a date (the timeline) is a key lookup, not an index into a list.
- A day's grid, a place's page, a person's page, Explore's years: paged queries on `library_item`.
- The viewer: the item, and its neighbours by `(day, taken_at)` before and after, never the whole list.
- The map: clusters by a grouped query on the visible bounds.
- Search (`LibraryResolver`): the server's answers (sha256s) are looked up by `sha256` in pages.
- Free up space: the plan is a query (`SUM` and `COUNT` per source and per sound folder, then the groups page by
  page while a run goes). Backup's queue is a query too, oldest or newest first, in batches of 1,000.

### Writing
- One writer at a time per table, on `Dispatchers.IO`, in transactions. Readers see a consistent snapshot (WAL mode).
- The merge rules of `mergeWithServer` (match by sha256, else by file name and size; hidden and private; scores;
  kept originals; former ids) become the upsert logic of `library_item`. They keep their tests, rewritten against a
  temporary database: in-memory SQLite on the host, the native driver in iOS tests.

## Phases (each one ships on its own and keeps the app working)

0. **Measure and guard.**
   - A debug-only "fake library" switch that serves N made-up items (50k, 300k, 1M) through `MediaLibrary` and
     `ServerLibrary`.
   - A host test that fails if a backup of 2,000 small files starts more than a few merges or feed rebuilds.
   - Record heap peak, time to first Moments frame, and janky frames during the first minute, on the owner's phone
     (`dumpsys meminfo`, `dumpsys gfxinfo`), as the baseline for every later phase.
1. **Database and the server's list.**
   - SQLDelight setup; `server_media` and `sync_state`; `ServerLibrary` reads and writes them.
   - `media.json` is imported once and then deleted.
   - The rest still gets a `List<ServerMedia>` from a query (temporarily) so nothing else changes.
2. **Device items.** `device_item` with incremental MediaStore / PhotoKit sync; `MediaLibrary.loadItems()` callers
   switch to queries one by one.
3. **Ledger.** `UploadLedger` on the `ledger` table; `BackupEngine` takes its queue from a query; `BackupState` no
   longer carries `entries` (the status is a join).
4. **The merged table.**
   - `library_item` kept by upserts; `LibraryState.days` is replaced by queries.
   - Until phase 5 lands, a temporary adapter reads the whole list for the screens. Memory is no better yet, but
     the merges are gone.
5. **Moments paged.**
   - **Page size in Settings** (asked by the owner, Oct 25, 2026): an **Advanced** section at the end of Settings
     with **Rows loaded at a time** (how many rows of Moments one page reads) and **Load ahead** (how many rows before
     the end of what is loaded the next page starts).
     - Both are kept in `JournalSettings`, and a change starts a new pager at the same position.
     - The defaults come from the phase 0 measurements on the owner's phone.
     - The other paged screens of phase 6 use the same two values, scaled to their item size (a grid page is that
       many rows of photos).
     - The section's look is tried in the UI lab first (the `ui-lab` skill), and docs/screens/settings.md gets it. `feed_section` and the pager; Android `LazyColumn` with `paging-compose`, keeping today's look
   (UX unchanged; check it in the UI lab per docs/previews.md).
6. **The other screens:**
   - day grids, the viewer's neighbours, Explore (places, years, people), the map, search results;
   - Free up space and backup counts, storyline, tags, crop.
   - The temporary adapter of phase 4 is removed here.
7. **Local search vectors on disk.** Today they are `FloatArray`s in memory (75 MB for 95k photos, about 800 MB at a
   million). Read them memory-mapped from the index files in chunks while scoring, or keep int8 copies; the top
   results are kept in a small heap.
8. **iOS.** The same shared logic is already in place. Port Moments and the grids to the pager in SwiftUI
   (`MomentsFeedViews.swift`, `DayGrid.swift`, `ExploreViews.swift`, `LibraryRootView.swift`, the map). Add an entry
   in docs/ios-todo.md for whatever is left when a phase ships Android-first.
9. **Clean up and docs.**
   - Remove `mergeWithServer`, `groupByDay` over the whole library, and `LibraryState.days`.
   - docs/design.md gets a "Library on the phone" section: tables, rebuildable, what triggers what.
   - docs/screens/*.md are updated where a behaviour changed (should be none visible).

## Checks for every phase
- `./gradlew :sharedLogic:testAndroidHostTest`, `:androidApp:assembleRelease`; on the Mac the iOS build and tests.
- On the owner's phone, with the real library and with the fake 1M library:
  - heap peak under the goal;
  - no crash through a full backup run of a sound folder (1,700 files);
  - time to first Moments frame;
  - janky frames in the first minute.
- Measure Journal itself, `com.chencohensolution.journal`. The UI Lab app's package starts with the same name
  (`com.chencohensolution.journal.uilab`), and `monkey -p` or `pidof` can pick the wrong one. Start Journal with
  `adb shell am start -n com.chencohensolution.journal/.MainActivity` and check the pid.

## Risks
- Moments' sections depend on neighbouring days ("by breaks" grouping, a row running past midnight). The section
  builder must re-cut a window around a changed day, not just the day.
- The timeline's scroll position and jumps to a date with paging (placeholders, or a key-based scroll).
- Migrations: the database has a version. A schema change may simply drop and rebuild it, since everything in it is
  rebuildable, except the ledger, which gets a real migration.
- iOS: SQLDelight's native driver and threading. Keep the database on one dispatcher.

## Before starting (state on Oct 25, 2026)
- Client `main` is at `0c1a97c` (sound folders, WAV as FLAC, Free up by groups, merges one at a time); server `main`
  is at `17d02b6`. Neither is pushed.
- The temporary start-up timing lines used on Oct 25 were discarded, not committed. Phase 0 adds its own
  measurements. The Journal build on the owner's phone still has them, until the next install.
