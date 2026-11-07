# Plan: the library on disk, read in pages (plan B)

**Not built yet.** Written Oct 25, 2026 on Windows, after the app ran out of memory while backing up 1,709 calls
(fixed for now in `79c973a`: library merges run one at a time). The owner asked how the app would cope with a
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
     - Fixed on the way: a change that came during a merge was never merged again (since `79c973a`). The merge
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
   - **5a built Oct 25, 2026: the rows in the database.**
     - `windowsFor` and `buildWindow` (`FeedWindows.kt`) build Moments a window of whole days at a time, with
       `buildFeed` itself. A window is widened until its edges are breaks between rows: "by breaks" runs across
       midnight, recordings running into or starting just past it, sets merged by hand across days.
     - `FeedTable` keeps the rows in `feed_section` (version 6, `5.sqm`), by ids as JSON. Triggers on
       `library_item` note changed days in `feed_dirty`; a refresh rebuilds their windows. It rebuilds everything
       when everything is noted or the context's version (settings, threshold) changed. Rows are read a page at a
       time with their items by id.
     - Tests: windows equal the whole build over 24 generated libraries; the kept rows equal it after each of 5
       rounds of random changes over 12 more. They caught a swapped query parameter (SQLDelight orders named
       parameters by first use) and recordings outside the photos' days.
     - On a copy of the owner's database (PC): every row built in 2.3 s (2,176 rows, once), nothing noted 6 ms,
       one day 31 ms, the first page of 30 rows (668 items) 47 ms.
     - **Every row a span at a time (Oct 26, 2026).** When every row is made again (a new database, a migration, a
       large library read in, other settings), `FeedTable.refreshAll` makes them from the newest day down in spans
       of about 4,000 items (`SPAN_ITEMS`), each widened like a window so its edges are breaks between rows, and
       writes each span as it is made. Only a span's items are in memory. Before, all items were read in one query.
       On the emulator with 300,000 made-up photos and a new database: 212 s with the Dalvik heap rising from about
       183 to 305 MB before; 75 spans in 21 s with the heap staying near 183–197 MB after. `FeedSpansTest`: the
       rows made span by span are byte for byte those made all at once (12 generated libraries, spans of 1, 20 and
       100 items), also when photos go while the spans are made.
   - **5b built Oct 25, 2026: Moments reads its rows a page at a time.** The owner left the choice open, so it is the
     growing list (recommended) rather than AndroidX Paging: rows are read as the list nears its end and kept while
     the app runs. Switching to Paging later stays possible.
     - `FeedPager` refreshes `FeedTable` in the background and reads again the rows read so far after each change.
       It reads the next page (30 rows) as the list nears its end (`LOAD_AHEAD_ROWS`), and down to a row for going
       to a date or showing a photo in Moments. A row with shown hidden photos is made again in memory with them.
     - Each stored row keeps its photos per day (`day_counts`), so the timeline covers the whole feed from one
       query, and the filter bar's counts come from SQL.
     - `StoriesController` with a pager doesn't build the whole feed. Note changes mark their photos' days, and so do
       newly read places and fingerprints and recordings that came or went. Settings, grouping and the look-alike
       threshold are in the rows' version, so a change makes them all again once.
     - Items are laid over by `LibraryController.shownRow` (scores, kept originals, backup statuses), so they look
       the same before and after the full library is read.
     - Tests: the timeline from the rows equals the whole feed's, and pages read in order equal the whole build's
       first rows.
     - On the owner's phone: the first start after the update makes every row once (about 10 s). Later starts
       make nothing again, and the first Moments frame comes 1.5–1.7 s after start, without waiting for the
       library's full read (2.1 s).
     - The owner scrolled far and went to dates with the ruler on the phone: it looked right, and the log showed no
       errors (Oct 25, 2026).
     - Found on the phone: fingerprinting every photo of the library (also hidden sources) rebuilt windows every
       3 seconds. Now it fingerprints only the photos Moments shows, as before.
     - The Advanced settings for page size are built since Oct 26, 2026 (below). The heap is not lower yet
       (about 410 MB just after start): the whole library list is still read for the other screens until phase 6.
   - **On app start** (owner, Oct 25, 2026): the first page of Moments is shown at once from the database. The
     sync with the server (and the phone's library) goes on in the background, off the main thread, and updates
     only the rows it changes.
   - **Page size in Settings** (asked by the owner, Oct 25, 2026): an **Advanced** section at the end of Settings
     with **Rows loaded at a time** (how many rows of Moments one page reads) and **Load ahead** (how many rows before
     the end of what is loaded the next page starts).
     - **Built on Android, Oct 26, 2026** (UI lab round `ux/settings/2026-10-26`, option a: an Advanced section
       with a Loading Moments page of chips, see docs/screens/settings.md). Both are kept in `JournalSettings`
       (`MomentsPaging`). There is no new pager: `FeedPager` reads its page size for every page, so a change applies
       from the next page. The shared pager reads the setting on iOS too; iOS's page and its load-ahead are in
       docs/ios-todo.md.
     - The defaults are the values used since phase 5 (30 rows, 40 ahead), not yet measured on the owner's phone.
     - The other paged screens of phase 6 use the same two values, scaled to their item size (a grid page is that
       many rows of photos).
     - The section's look is tried in the UI lab first (the `ui-lab` skill), and docs/screens/settings.md gets it. `feed_section` and the pager; Android `LazyColumn` with `paging-compose`, keeping today's look
   (UX unchanged; check it in the UI lab per docs/previews.md).
6. **The other screens:**
   - day grids, the viewer's neighbours, Explore (places, years, people), the map, search results;
   - Free up space and backup counts, storyline, tags, crop.
   - The temporary adapter of phase 4 is removed here.
   - **Surveyed Oct 25, 2026.** What holds or walks the whole library today, and the steps, biggest memory first:
     - 6a. Items by id and by sha256 from the database: `LibraryTable` reads (by ids, by sha256s, a day range, a
       photo's neighbours, counts). `StoriesState.itemsById` (storyline, tags, notes, the viewer's lists, menus)
       becomes lookups; search's `LibraryResolver`, `PersonController` and `PhotoFindings` resolve sha256s with them.
     - 6b. The background readers: fingerprints and places are given what to read from the database a batch at a
       time instead of the whole list; "home" (Explore) is a grouped query on located photos.
     - 6c. Explore (places, years, a place's or year's page) and the map (counts, clusters by the visible bounds)
       as grouped queries; their pages through `FeedTable`'s windows or `feedOf` over a query's items.
     - 6d. The viewer's neighbours, backup's and Free up space's counts. (All photos, its day grids and filters, are
       gone: the screen was removed Oct 24, 2026, and its last code, strings and docs on Oct 25, 2026, asked by the owner.)
     - 6e. `ServerLibrary.media` (103k entries) read from `server_media` where it is used (search, people, local
       search, findings) instead of held as a list.
     - 6f. `LibraryState.days` and the newest-rows read go; `mergeWithServer` and `groupByDay` stay only as the tests'
       reference until phase 9. iOS: its 18 uses in Swift, on the Mac (docs/ios-todo.md).
   - **6a built Oct 25, 2026: items by id and by sha256 from the database.**
     - `LibraryTable` reads rows by ids, by sha256s, located rows in a box, and the days of rows without a location
       (queries in `Library.sq`; no migration, as no table changed). `bySha` finds what `libraryResolver` finds in the
       whole list: the row with that sha256, else a phone photo backup hasn't read with the server file's name and size
       (through `device_item`'s name index), and where several qualify the last in the screens' order, as the lookup in
       memory keeps.
     - `LibraryController.items(ids)` and `itemsBySha` / `itemsOfShas` read them on the IO dispatcher with
       `shownRow` laid over. Without a table (tests) they look in the whole list as before.
     - With the pager, `StoriesState.itemsById` is no longer the whole library: it holds Moments' rows read so far,
       the notes' photos (storyline, tags) and the photos screens asked for with `StoriesController.want(ids)` (at most
       2,000, the oldest let go). They are read again from the database after each library change, and those the
       library no longer has go to `StoriesState.missing`; `StoriesState.gone(id)` tells a screen to drop a photo. A
       note on a photo in no row read reads it from the database first. Without the pager it is built from the whole
       library as before, so the existing tests run unchanged.
     - `markChangedDays` takes the days of moved and fingerprinted photos from `FeedTable.daysOf`, and of photos in
       newly named places from the located rows in the box around those cells plus the places found for photos
       without a location of their own (`FeedPager.daysInCells`).
     - Search, a person's page and a photo's similar photos resolve the server's sha256s through
       `LibraryController.itemsOfShas` (wired in `AndroidJournal.kt` and `IosJournal.kt`); `libraryResolver` stays for
       the tests and as their reference. A person's page shows what it read before at once ("Not Mom"), then reads
       again.
     - Screens: the viewer keeps the items it was opened with and asks for the photo shown and 50 neighbours each way
       as it is swiped; similar photos and a page's list (`PageLists` now keeps the items) do the same; Crop waits while
       its photo is read and closes only when it is gone; showing a photo in Moments looks its day up in the database
       (`loadMomentsToItem`).
     - Tests (`LibraryTableTest`): over 30 generated libraries changed in 4 rounds each (shared sha256s, renames to a
       server file's name and size), every sha256 and the whole list resolve as `libraryResolver` does, and ids as the
       state's items; the days in named places equal the whole list's over 20 libraries; a `StoriesController` with
       the pager holds only part of the library, reads a noted and a tagged photo for the storyline and tags, `want`
       reads photos and marks one gone, and `loadMomentsToItem` reads down to an old photo's row. Reversing the
       screens' order in `bySha` fails the first test.
     - Not tried on the phone yet: a Journal backup was running (its notification stayed up for more than 10 minutes),
       so the build was not installed over it.
     - Left for later steps: `Inputs.build` still reads the whole list for the photos to fingerprint and to locate
       (6b), search's map points and a town's box (6c), the viewer's Explore and map lists (6c),
       `serverSha` over the server's list (6e), and `LibraryState.days` itself (6f).
   - **6b built Oct 25, 2026: the background readers fed from the database.**
     - `PhotoSimilarity.requestFrom` and `PhotoPlaces.requestFrom` read the library a batch at a time, newest first, in
       the screens' order (a keyset on `epoch_day`, `taken_millis`, `id` through the day index: `BatchWalk`,
       `LibraryKey`). Fingerprints read the phone's photos (500 at a time) and keep those Moments shows. Places read
       the phone's photos and videos without a location, then every item's own location (2,000 at a time, a few numbers
       each) with the places found for the rest, so the cells are named in the same order as before. Known and failed
       ids are skipped in memory as before, `PUBLISH_EVERY` is unchanged, and `request(items)` still serves the tests
       and the path without a database. Asked again while reading, a reader starts over from the newest.
     - `StoriesController` with the pager no longer builds `toCompare` and `toLocate` from `library.days`. It asks the
       readers once the library's first whole read is done, and again when rows came, went or changed their location,
       source, type or place on the phone (`LibraryController.rowsChanged`; a backup's new sha256s don't), or the
       sources shown changed (fingerprints).
     - Explore's home: `home(table, found, shown)` counts located rows per 0.1° square and source in SQL (per day on
       the days a hidden source is always shown on), adds the places found for photos without their own, and reads
       the winning square's rows (and, on a tie, each tied square's) in order, so the square and the average are
       exactly `home(library, places)`'s. It is worked out in the background when places, the sources shown or the
       rows change, and published as `StoriesState.home`; Android's Explore passes it to `placesOf`. Without the
       database Explore works it out from the items as before. iOS: docs/ios-todo.md.
     - Tests (`BackgroundReadersTest`): over generated libraries that grow in 3 rounds (ties in time, days that aren't
       their time's, server-only rows under former ids, points across the equator and the meridian), the photos
       fingerprinted and located and the places named, in order, equal the whole list's with batches of 1 to 7; home
       equals the in-memory one over 60 libraries with random shown sources and found places, ties included; a
       `StoriesController` with the pager feeds both readers from the database and publishes the same home, and reads
       only the new photos once more come. Reversing the id order in the queries or the tie-break fails them.
     - On the owner's phone (debug build, phone locked): Moments made no windows again once settled (40 s watched),
       the library's whole read took 3.9 s, home took 1.7 s in the background, Dalvik heap about 330–370 MB (the whole
       list is still read for the other screens). Starting the build before this one began a Journal backup, which the
       install ended.
     - Left for later steps: Explore's summaries and pages still read `LibraryState.days` (6c); home's query reads the
       winning square's rows each time (about 1.7 s on the phone with every IO going on at start), which a cached
       count per square could make cheaper if it matters.
   - **6c built Oct 25, 2026: Explore and the map from the database.** No table changed, so no migration; the new
     queries are in `Library.sq`, the code in `ExploreFromDatabase.kt` and `ExploreMap.kt`.
     - Explore's lists (`exploreOf(table, …)`, equal to `exploreOf(items, …)`, which replaces the Android screen's own
       summary): the years' counts and what each hides come from a count per day, source and type in SQL. The towns,
       countries, home town and every cover come from one walk through the rows in the screens' order, 2,000 at a time
       and a few numbers each (`ExploreRow`). They can't be grouped in SQL: a cover is the best-looking photo by this
       phone's scores before the server's (`LibraryController.shownScore`), and only memory has those. A second walk
       runs only when a country has fewer than four towns, for the photos its row spreads across them. The covers are
       then read by id with `shownRow` laid over. `PlaceSummary` and `YearSummary` keep `count`, `cover`, years and
       months as fields, and their `items` are empty from the database.
     - `StoriesController.explore(state)` keeps the lists until the rows (`LibraryController.rowsWritten`, any change),
       the places, the scores, the sources shown or home change. Otherwise it only reads the covers again, so their
       statuses are current. `scopeItems(scope, state)` reads a town's, a year's, a month's or the map's photos:
       - a year or month is a range on the day index;
       - a town or an area is the located rows in its box, plus the photos a place was found for there, each checked
         with `ExploreScope.matches`.
       Android's pages build their feed from them with `feedOf` as before.
     - The map (`MapOverview`, `MapArea`): its years and where it opens come from the located rows' box per day and
       source, plus the photos placed by a place found (read by id once per places and rows). Each time it settles, it
       reads only the photos around what is in view (a screen further each way). They come counted per square of about
       12 points at that zoom (`mapCells`, floored as `cellKey` floors), with the count in view (`mapCount`). The
       sources shown go into SQL as the spellings the source column holds, and the days some are shown on anyway.
       MapLibre still clusters, now the squares: a cluster adds up their `photos`, a square of more than one is a circle,
       and tapping one zooms two levels in.
     - The viewer's Explore and map lists are the photos the page or the map opened it with (`PageLists`, by scope and
       row). After the process was killed they are read again from the database (`scopeViewerItems`), and nothing is
       shown meanwhile.
     - Search's town box is `townArea(table, …)`: the place squares named so, boxed in SQL, plus the photos placed
       there (`LibraryController.townArea`, wired on both platforms).
     - Tests (`ExploreDatabaseTest`), over generated libraries of several years:
       - towns, home town, countries with spread covers, years with only hidden photos, ties in score, phone scores
         over the server's, points on square edges south and west of zero and across the date line;
       - the summaries over 40 libraries in 3 rounds (home known, null or worked out);
       - towns', years', months', areas' photos over 30, a town's box over 30;
       - the map's years, counts, bounds, squares and count in view over 30;
       - a `StoriesController` with the pager, before and after photos come.
       Each of these mutations fails them: dropping the floor for negative squares, reversing the home town's
       tie-break, counting a town's cover among the photos its country spreads.
     - Not tried on the phone yet: it was locked and dozing, so the build was not installed.
     - Left for later: the Android pages still recompute on every library state (as before, now with a read), which
       6f's change signal can narrow. Explore's lists cost two walks of light rows when a country has few towns.
   - **6d built Oct 25, 2026: backup's counts from the database.** No table changed, so no migration.
     - `LibraryState.itemCount`, `notBackedUpCount` and `notBackedUpBytes` (the backup bar in `LibraryChrome.kt`,
       Settings › Backup's summary, the limited-access banner; the same names in Swift) come from `LibraryState.totals`
       when there is a database. `backupCounts` counts per source in SQL the items, the phone's items and those the
       ledger doesn't hold as uploaded as they are now (same size and modified time, as `BackupState.status` checks), with
       their bytes. Queued and failed photos wait either way; the files on their way are laid over from memory: those of
       them the ledger holds as uploaded (`backedUpAmong`) wait too, as their status is Uploading. Only the sources
       backup takes count (`countedSources`; all when backup is off or absent). `libraryTotals` makes the three numbers.
     - `LibraryController.recount` counts again, one count at a time, when rows were written, a ledger entry of one of
       the library's items changed (a call's entry counts nothing again), or the files on their way or the sources
       taken changed; the files on their way alone only read those few rows. `LibraryState.isEmpty` replaces Moments'
       `days.isEmpty()` checks. Without a database everything is counted over `days` as before.
     - Tests (`LibraryTableTest`): over 30 generated libraries changed in 4 rounds (sizes and modified times unknown,
       photos read, uploaded, failed and forgotten), with random sources, backup on, off or absent, queued and
       uploading files (some backed up already), the counts equal LibraryState's over the whole list; the controller
       with the database shows the database's counts, equal to the in-memory ones, through backup's changes, and a call
       counts nothing again. `LibraryLoadTest` keeps the counts few while 2,000 calls are backed up. Dropping the
       overlay of files on their way fails the first test.
     - On the emulator (made-up library of 50,000, debug build, not enrolled, so the enrollment screen stays up and
       the phone's library isn't read): no crash; the log showed "50000 items, 0 not backed up" (every row the server's,
       as nothing of the phone was read), counted in 1.5 s at start while every other read ran, then 71–195 ms; Dalvik
       heap about 93–107 MB. The backup bar itself can't be seen without enrolling, so it was not looked at.
     - The viewer's neighbours needed nothing more: since 6a and 6c every list it opens with comes from a page, a
       Moments row, the storyline, a tag or search, and it reads the photo shown and 50 neighbours each way from the
       database (`want`). No list of the viewer comes from the whole library any more.
     - Free up space reads no `LibraryState`: its plan and counts come from MediaStore and the ledger
       (`FreeUpController`, `freeUpPlan`), on purpose since phase 3 (a run must see every real photo, and
       `device_item` leaves out photos matched to private files by name and size). Making its plan a query is later
       work, not 6f's.
     - Readers of `LibraryState.days` left, outside Moments, Explore, the map and search (6f):
       - `LibraryController` builds `days` itself (the adapter), and `items`, `itemsOfShas` and `townArea` read it
         without a table (tests).
       - `StoriesState.build` (`itemsById`, the whole feed) and `StoriesController.everything` / `itemsById` / the
         readers' lists read it only without the pager (tests).
       - `SearchController.areaOf` and `libraryResolver`, only without `townArea` / `itemsOfShas` (tests).
       - `ItemsExploreReads` (previews and screenshots), `PreviewData.kt` and `LabData.kt` (the UI labs).
       - iOS: `JournalHomeView.swift` (four emptiness checks, docs/ios-todo.md), `ExploreViews.swift` and
         `ExploreMapView.swift` (6c's Swift side).
       `StoriesState.itemsById` is read by the viewer, similar photos, page lists, Crop and Moments' menu, all of which
       ask for what they show (`want`) since 6a; it holds the whole library only without the pager.
   - **6e built Oct 26, 2026: the server's list stays in the database.** No table changed, so no migration; the new
     queries are in `Server.sq`, read only by `ServerMediaStore`.
     - `ServerLibrary.media` (the whole list in memory) is gone. `mediaVersion` goes up each time the list in the
       database changed (read at start, a sync, "Show private media"), and readers read what they need:
       - `sha256Of(item)` (suspend): the first file written with that name and size, by the name index, as `serverSha`
         found it in the list (rows are read in the order they were written, `ORDER BY rowid`). Names are compared
         with SQLite's `lower()`, as `library_item` matches them.
       - `file(sha256)`: a face avatar's photo size (Android `LocalServerLibrary`, iOS `IosServerFiles.face`).
       - `sounds()`: the sound recordings for `LibraryState.sounds`, read when the version changes. A new list of
         them now publishes the state even when no row changed (it could be left unshown before).
       - `forEachFile`: a few facts of every file, one row at a time from one query, for search and people on the
         phone (`shownFiles`): only the files kept, with their time, are in memory, and only while one runs.
       - `all()`: the whole list, only for the library merged in memory (tests) and tests.
     - The viewer's findings (`PhotoFindingsController`) look the photo's sha256 up with `sha256Of` and its similar
       photos with `itemsOfShas`; search and a person's page get no server list in the app (they had used it only
       without the database).
     - Tests (`ServerListReadsTest`): over 30 generated lists changed in 5 rounds (files written again, deleted,
       private ones made keys and back, shared names and sizes, unreadable times), `sha256Of`, `file`, `sounds` and
       what search (private or not, years, an area) and people keep equal the old code over the whole list; a
       controller with the database shows the same sound recordings as the merge in memory, through "Show private
       media" off and on, over 12. Reading names in reverse order fails the first. The tests of `ServerLibrary`
       read `all()` where they read `media`.
     - On the emulator (made-up library of 50,000, debug build, a placeholder identity that matches no key): the
       Offline pill, only key failures in the log, no crash on Moments, Explore, search (the server's key error, no
       index on the phone) and the viewer; Dalvik heap about 93 MB. A person's page couldn't be opened: there are no
       people without the server or the phone's index.
     - Left for 6f: `LibraryState.days` and the newest-rows read; the library merged in memory still reads `all()`
       (tests only).
   - **6f built Oct 26, 2026: no list of the library in memory.** Database version 7 (`6.sqm`: `feed_sound`).
     - `LibraryController` with a table keeps nothing of the rows: `rows`, `idsByDay`, the merged days, the newest-rows
       read and `table.all()` at start are gone, and `LibraryState.days` stays empty. `LibraryTable.update` makes the
       noted rows like `refresh` but only counts what changed (rows changed, those moved or new, rows gone, in SQL), so
       the first build of every row is never in memory either. `StoredMediaLibrary.update` (`KeepsItems`) writes the
       phone's changes without reading every item back, and `leaveOut` walks the phone's rows one at a time.
     - `LibraryState.version` goes up when what the screens read from the database looks different: rows written,
       scores or kept originals, sound recordings, the sources backup takes, or a status that may have changed (a ledger
       entry, the queue, a file on its way or its ring moving a step), the database asked first whether any of those
       ids is a library item, so a backup of calls changes nothing shown. `complete` is the first refresh done (the
       readers wait for it); the spinner goes once the library is counted and has rows or is made. `LibraryState.counts`
       and `buildFeed`'s `counts` are gone (Moments' pager counts in SQL since 5b).
     - The in-memory path (no table: `mergeWithServer`, `groupByDay`, `days`) stays for the tests, previews and the
       UI lab until phase 9.
     - Fixed, found by the owner on the phone (6d build):
       - Calls and recordings turned on (the server's hidden collections) didn't show in Moments: the rows had been
         made before the recordings were read, and `StoriesController` marked recordings' days only against what it
         had seen in this run. `FeedTable` now keeps the recordings its rows were made with (`feed_sound`: sha256, day,
         kind, start, length, private, source) and, on every refresh, also the first after a start, marks the days of
         those that came, went or changed, with the days of the rows they are in. Recordings aren't in the rows'
         version, so a new call makes only its day again.
       - Showing a photo in Moments (the viewer's date, from the storyline, a tag, search or Explore) didn't land on
         it when its row wasn't read yet: `LandOn` tried once, before `loadMomentsToItem` had read down to the row. It
         now waits for the row (up to 15 s) on every screen that lands on a photo.
     - Tests: `theControllerHoldsNoWholeList` (3,000 photos: no days held, items by id and counts as the merge, a
       backup's sha256 and status read after a new version); `anUpdateCountsWhatARefreshReturns` (20 generated
       libraries in 4 rounds of moves, deletions and new photos: the counts equal the refresh's rows, the rows equal);
       `FeedSoundsTest` (10 generated libraries: rows made without recordings, then a new table on the same database
       with them equals the whole build; the same recordings again make nothing; some hidden again and one longer, then
       all hidden, equal the whole build). The tests that read the controller's days (readers, Explore, the stories'
       items, backup's counts) compare with `mergeWithServer` over the same phone library instead. `LibraryLoadTest`
       still sees 0 merges and 0 Moments rebuilds for 2,000 calls.
     - On the emulator (made-up library of 300,000, debug build, Offline identity), Dalvik heap allocated: before (6e)
       about 420 MB a minute after start, 490 MB once settled; after about 190–200 MB, also after Explore and the map.
       Moments, the viewer, the storyline, Explore, the map and search opened without a crash. A photo noted on Dec 31,
       2025, opened from the storyline after a restart, landed on its row in Moments. First Moments frame about 18 s
       after start on both builds (the emulator, with every read at start).
     - Left: phase 8 (iOS: the Swift side of 6c–6f, docs/ios-todo.md), phase 9
       (removing the in-memory path). Free up space still plans from MediaStore and the ledger (on purpose, 6d). The
       made-up library's own lists (debug only) are still built in memory when it is on.
7. **Local search vectors on disk.** Today they are `FloatArray`s in memory (75 MB for 95k photos, about 800 MB at a
   million). Read them memory-mapped from the index files in chunks while scoring, or keep int8 copies; the top
   results are kept in a small heap.
   - **Built Oct 26, 2026** (shared, so Android and iOS). They were already 8-bit numbers; now `VectorTable` (text
     search and Similar) keeps them in `vectors-<n>.bin` and holds only an index of rows (sha256s in one array, an
     open-addressing hash, which rows are current). `top()` reads the file in 1 MB chunks through okio's `FileHandle`
     (no memory mapping in common code) with a min-heap of the best; a download appends rows every 4,096 and publishes
     a new immutable index, so a search during a download needs no lock; `save` writes the file again once an eighth
     of it is replaced or dropped. Measured on the PC (`VectorTableMeasureTest`, `JOURNAL_MEASURE=1`): 95,000 rows of
     768, a 76 MB file, 5 MB held, opened in 0.12 s, a search 0.17 s. Not yet checked on the owner's phone.
   - **Text in photos, Oct 26, 2026.** `TextTable` keeps each file's lines in `text-<n>.bin` beside `text.json` (rows
     of sha256, length and lines; appended, compacted and opened as `VectorTable`'s, sharing its row index) and holds
     only the row index; a photo's details read its lines from the file. Search's words (`TextWords`, made from the
     file when the table changes) keep each word's letters once in one array and its files as numbers with a byte for
     how often, instead of maps of strings; matching is unchanged and checked against the plain maps
     (`LocalIndexTest`) and the server (`LocalFixturesTest`). A `table.bin` of jsonl rows from before is moved into the
     new file once. Measured on the PC (`TextTableMeasureTest`, `JOURNAL_MEASURE=1`; 95,000 photos, 28,469 with text,
     314,000 lines): held 45 MB of lines and 97 MB of words before, 6 MB and 14 MB now; a search 50 ms before, 17 ms
     now; the words made in about 2.5 s at the first search after a change, as before; a photo's lines 0.2 ms. Not yet
     checked on the owner's phone.
   - **The faces table, built Oct 26, 2026** the same way. `FaceTable` keeps each file's row (sha256, how many faces,
     their boxes, then their full-precision SFace vectors) in `faces-<n>.bin` beside `faces.json`, and holds only the
     boxes (all in one array, 16 bytes a face) and the same index of rows; `vector()` reads one face's 128 numbers
     from the file (only a face correction asks for one). Appends every 4,096 rows or 8,192 faces, an immutable index
     published each time, the rewrite once an eighth is dead, half a row cut off at open, and a `table.bin` of version
     2 moved into the new files once, as for the vectors. Measured on the PC (`FaceTableMeasureTest`,
     `JOURNAL_MEASURE=1`): 120,000 faces in 40,000 files, a 64 MB file, 5.4 MB held (63 to 68 MB held as before),
     opened in 0.27 s (0.15 s warm; the whole file is read once to find the boxes), a face's box and vector about
     0.1 ms. Not yet checked on the owner's phone.
8. **iOS.** The same shared logic is already in place. Port Moments and the grids to the pager in SwiftUI
   (`MomentsFeedViews.swift`, `DayGrid.swift`, `ExploreViews.swift`, `LibraryRootView.swift`, the map). Add an entry
   in docs/ios-todo.md for whatever is left when a phase ships Android-first.
   *Built (Oct 26, 2026, on the Mac):* phases 1–6 on iOS. Moments asks for the next page as it nears the end of what
   is read (`HomeActions.loadMoreMoments`, 40 rows ahead), and going to a date or showing a photo in Moments reads
   down to its row first (`loadMomentsTo`, `loadMomentsToItem`; "show in Moments" waits up to 15 s for the row).
   Explore, its pages and the map read through `ExploreReads.swift` (the stories controller's `explore`,
   `scopeItems`, `mapOverview`, `mapArea`; the UI lab keeps the items'). The map draws `MapCell` squares with a
   `photos` property that its clusters add up. The viewer, similar photos and crop keep the items they were opened
   with and ask `want(ids:)` for the photo shown and 50 each way. The four older shared compile errors are fixed
   (`LocalSearchApi` takes its `FileSystem`), and the app links `-lsqlite3`. A debug build takes the made-up library's
   size from its launch arguments (`-madeUpLibrary 300000`) and logs `Measure` lines to the console. Left: PhotoKit's
   change tokens (docs/ios-todo.md).
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
- Client `main` is at `79c973a` (sound folders, WAV as FLAC, Free up by groups, merges one at a time); server `main`
  is at `d75a438`. Neither is pushed.
- The temporary start-up timing lines used on Oct 25 were discarded, not committed. Phase 0 adds its own
  measurements. The Journal build on the owner's phone still has them, until the next install.
