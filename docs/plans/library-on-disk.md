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

## Decisions to confirm with the owner first

1. **SQLDelight** (KMP: Android driver, native driver on iOS) as the database. It is the usual choice for KMP and
   needs no cgo-like native build. Room KMP is the alternative.
2. **Where the database lives:** Android `noBackupFilesDir/library.db`; iOS Application Support, excluded from iCloud
   backup.
3. **The backup ledger moves into the database** (`ledger.jsonl` is read once, then removed). Alternatively it stays a
   file and is copied into a table at start; one source is simpler.
4. **Paging library:** AndroidX Paging (`paging-common` is multiplatform; `paging-compose` on Android). On iOS a
   small Swift adapter asks the shared pager for pages as rows appear.
5. **What Moments may lose for a while:** the hidden-sources band and the near-duplicate sets are computed per
   section from now on, never across the whole library (they already are per row today, so nothing visible should
   change).

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
5. **Moments paged.** `feed_section` and the pager; Android `LazyColumn` with `paging-compose`, keeping today's look
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
- Uncommitted on client `main`: temporary `println("TIMING …")` lines in `LibraryController.kt`,
  `StoriesController.kt` and `ServerLibrary.kt`, added to measure start-up. Use them for phase 0's baseline, then
  revert them (`git checkout -- sharedLogic/src/commonMain`). The Journal build on the owner's phone has them.
