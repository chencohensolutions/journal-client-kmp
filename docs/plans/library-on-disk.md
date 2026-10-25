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

5. **The whole library is in the database, except what is hidden** (owner, Oct 25, 2026, twice; this replaces the
   first version of this decision, which kept full entries for private files in the phone's copy of the server's
   list, `server_media`).
   - What the server hides (hidden collections, nudity) it never sends. `server_hidden` keeps only those files'
     sha256s, to leave out the phone's own copies.
   - While "Show private media" is off (`PrivateMedia`), a private file has no row in `server_media`,
     `device_item` or `library_item`. `server_private` keeps only its sha256, name and size, to hide the phone's
     copy. The `ledger` keeps its record of what backup read and sent (id, size, sha256), so the phone's copy
     isn't hashed and offered again.
   - Turning the switch off deletes those rows at once. Turning it on reads the server's feed again from the start
     (and, from phase 2, the phone's library), and the rows of Moments on their days are rebuilt.

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
   - **Built Oct 25, 2026.**
     - `MadeUpLibrary`: start Journal with `adb shell am start -S -n com.chencohensolution.journal/.MainActivity
       --ei madeUpLibrary 300000` (debug builds; `0` turns it off; the size is kept for later starts). Half of the
       photos are on the phone and half only on the server.
     - Made-up files never reach the server, which is the owner's real one: backup and Free up space read only
       MediaStore, `backupQueue` drops anything `MadeUpLibrary.isMadeUp`, and the server's list never saves them.
     - `Measure` logs each merge, each Moments rebuild and the first Moments frame under the logcat tag
       `JournalMeasure`.
     - `LibraryLoadTest` holds the guard. Backing up 2,000 calls on a library of 20,000 starts 2,001 merges today
       and 1 rebuild (the merged list comes out equal). Phase 4 lowers its merge limit to 5.
   - **Baseline on the owner's phone (Oct 25, 2026, Galaxy S25, debug build, phone locked):**

     | Library | Merge with the server's list | Moments rebuild | Dalvik heap peak | First Moments frame |
     |---|---|---|---|---|
     | Real: 10,678 on the phone + 102,725 on the server | 2.5 s | 1.1 s | about 370 MB (settles at 305 MB) | 10.9 s |
     | Real + 100k made-up: 60,678 + 202,725 | 1.3–2.7 s | up to 2.0 s | about 508 MB of the 512 MB limit | 6.4 s* |

     *Before the server's list had loaded.

     Janky frames were not measured: they need the screen unlocked and scrolling. Measure them, and 300k and 1M, with
     the owner at the phone (1M will not fit in today's heap).
1. **Database and the server's list.**
   - SQLDelight setup; `server_media` and `sync_state`; `ServerLibrary` reads and writes them.
   - `media.json` is imported once and then deleted.
   - The rest still gets a `List<ServerMedia>` from a query (temporarily) so nothing else changes.
   - **Built Oct 25, 2026.**
     - `Server.sq`: `server_media`, `server_hidden`, `server_private`, `sync_state`. `ServerMediaStore` writes each
       feed page with its cursor in one transaction, on the IO dispatcher.
     - On Android the database is `no_backup/library.db` (WAL). On iOS it is `Application Support/library/library.db`,
       kept out of iCloud backup; not compiled yet (docs/ios-todo.md).
     - On the owner's phone, the first start read the 37 MB `media.json` into a 33 MB database and deleted it.
     - The whole library goes into the database except what is hidden (owner, Oct 25, 2026). The server sends
       nothing of what it hides, and `server_hidden` keeps only those files' sha256s. While "Show private media" is
       off, private files keep only sha256, name and size (`server_private`, `ServerLibrary.privateFiles`), to hide
       the phone's copies of them. Turning the switch on reads the feed again from the start.
     - Fixed on the way: a change that came during a merge was never merged again (since `0c1a97c`). The merge
       job called `publish()` while it still counted as active.
2. **Device items.** `device_item` with incremental MediaStore / PhotoKit sync; `MediaLibrary.loadItems()` callers
   switch to queries one by one.
   - **Built Oct 25, 2026.**
     - `Device.sq` (database version 2, migration `1.sqm`): `device_item`, and `device_left_out` with only the ids
       of what the user hides.
     - `StoredMediaLibrary` wraps the platform's library. `AndroidMediaLibrary.changesSince` reads only the rows
       whose `GENERATION_MODIFIED` is past the kept generation, per volume, plus an ids-only pass for deletions.
       It reads whole when MediaStore's version, the time zone, the access or `READER_VERSION` changed, and
       always with access to selected photos only.
     - What the user hides leaves the table (`hiddenOnPhone`, `LibraryController` calls `leaveOut` once the
       server's list is loaded) and is read again by id once shown.
     - On the owner's phone: the first start read 10,678 items whole in 279 ms. Later reads found "0 changed" in
       8–22 ms (before: every start read all of MediaStore).
     - iOS reads PhotoKit whole each time and stores it the same way; change tokens are on docs/ios-todo.md.
     - Not changed yet: backup and Free up space still read MediaStore themselves (phase 3).
     - Seen once, right after an install: the phone's photos dropped to none for a while, as if photo access
       had gone. A `Photo access:` line under `JournalMeasure` now records each change, to catch it next time.
3. **Ledger.** `UploadLedger` on the `ledger` table; `BackupEngine` takes its queue from a query; `BackupState` no
   longer carries `entries` (the status is a join).
   - **Built Oct 25, 2026, in part.**
     - `Ledger.sq` (database version 3, migration `2.sqm`). `UploadLedger` reads the table into memory at first use
       and writes each change through, so backup runs exactly as fast as before.
     - `ledger.jsonl` is read in once, then deleted. On the owner's phone that was 1,749 entries from 3,498 lines;
       a copy of the file was kept off the phone first.
     - Moved to phase 4: `BackupState.entries` (the library's merge and the statuses still read it), and backup's
       queue from a query. Backup and Free up space keep reading MediaStore themselves, not `device_item`. A
       backup must never skip a real photo, and `device_item` leaves out photos matched to private files by name
       and size alone.
4. **The merged table.**
   - `library_item` kept by upserts; `LibraryState.days` is replaced by queries.
   - Until phase 5 lands, a temporary adapter reads the whole list for the screens. Memory is no better yet, but
     the merges are gone.
   - **Built Oct 25, 2026.**
     - `Library.sq` (database version 5, migrations `3.sqm`, `4.sqm`).
       - `library_item` is made inside SQLite from `device_item`, `ledger` and `server_media`, by the rules of
         `mergeWithServer`, with indexes on lowercase name and size and on the ledger's sha256.
       - Triggers on `device_item`, `server_media`, `server_hidden`, `server_private` and `ledger` note each write
         in `library_dirty`: a phone item's id, a sha256, or everything (private files, a new database). They fire
         before an `INSERT OR REPLACE` too, which fires no update or delete trigger, so a replaced row's old matches
         are noted.
       - `LibraryTable.refresh` widens the notes to every item they can change (`takeKeys`) and makes only those
         again in `library_fresh`. It writes and returns only the rows that came out different. With nothing noted
         it is one count query.
       - `LibraryTableTest` checks it equals `mergeWithServer` over 40 generated libraries. Every case appears:
         private, kept originals, scores, photos deleted from the phone, matched by name and size.
         `refreshingOnlyWhatChangedStaysTheMerge` changes 30 libraries in 5 random rounds each (the phone, the
         ledger, the server, hidden and private files) and checks each incremental refresh against the full merge.
         It found two holes, now closed: an `INSERT OR REPLACE` fired no trigger for the row it replaced, and a phone
         photo could match a server sound recording by name and size.
     - `LibraryController` with a `table`:
       - It shows what the database kept at once.
       - It makes the rows again only when the phone's library, the server's list or what is hidden changed, or a
         ledger change was written (the notes decide whether there is anything to make).
       - It patches only the changed rows' days. Days whose statuses didn't change stay the same objects (also on
         the old path).
       - Scores and kept originals are laid over in memory (`shown`).
     - The guard (`LibraryLoadTest`) now uses the database: backing up 2,000 calls starts 0 merges and 0 Moments
       rebuilds (2,001 merges before). Its limit is 5.
     - The made-up library goes into the database beside the real rows (marked, written once, deleted when turned
       off), so measurements go the real way. Backup still reads MediaStore and drops made-up items.
     - On the owner's phone (113,331 rows):
       - The first build after the migration took 11 s; it happens once.
       - Before the notes, every refresh took 3.8–4.9 s even with nothing changed.
       - With the notes, a refresh with nothing noted takes 4 ms and one changed ledger row 62 ms (measured on a copy
         of the phone's database on the PC).
       - A second start draws its first Moments frame after 4.3–4.8 s (5.3–6 s before phase 4). What is left is
         reading every row into memory (about 2 s) and building the whole feed (0.6–1 s): phase 5's work.
       - The owner saw two spinners at start: the phone's library read, then the same read again (`leaveOut` re-read
         it even when nothing changed), while every row was read and the whole feed built. Fixed the same day:
         `leaveOut` says whether it changed anything, the spinner stays until the library's first rows are in, and
         the newest 600 rows are read first by the day index (57–151 ms) so Moments shows its top at once; the rest
         (2.4 s) loads behind it. First Moments frame: 1.4–1.9 s after start (4.3–4.8 s before).
       - The owner then saw Moments draw three times: a "nothing to show" line, the feed, then the feed again with
         its places and counts. Now it draws once (asked by the owner): `StoriesState.ready` waits for the library's
         newest rows with the whole library's counts per source (`LibraryState.counts`, by SQL), and for the place
         names and fingerprints kept on the phone (`PhotoPlaces.loaded`, `PhotoSimilarity.loaded`, read at start).
         Until then the feed is empty and Moments shows one spinner; the rest of the library loads behind it without
         changing the top. First Moments frame still 1.4–1.9 s.
     - Still in memory: `LibraryState.days` (the adapter), `BackupState.entries` (statuses), backup's queue.
       `mergeWithServer` stays as the tests' reference until phase 9.
5. **Moments paged.**
   - **On app start** (owner, Oct 25, 2026): the first page of Moments is shown at once from the database. The
     sync with the server (and the phone's library) goes on in the background, off the main thread, and updates
     only the rows it changes.
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
