# Settings

The user's choices, kept on this device (SharedPreferences / NSUserDefaults). Opened from the Journal's **⋯** menu.

## Layout

- **Top bar:** back and "Settings".
- **Backup** (Android; iOS later):
  - **Back up to your server** switch (off until turned on), and a status line: "Off. Nothing is uploaded.",
    "Backing up 3 of 120", "12 photos waiting to upload", "Everything chosen is backed up", "Waiting: …" (Wi-Fi network
    not on the list, location needed to check it, register again, photo access), or "Stopped: … It tries again
    later." **Back up now** beside it while not running.
  - With backup on: **What to back up**, a toggle per source (**Camera** on by default, WhatsApp, Screenshots, Other);
    **When**: **Only on Wi-Fi** (on by default; never on mobile data), **Only while charging**, and, under Wi-Fi only,
    **Only on these Wi-Fi networks** with **Add this network** (asks for location, then lists the network the phone is
    on; × removes one). With a network listed and no "All the time" location, a note asks for it, since the network
    can't be checked in the background otherwise. **Order**: **Oldest first** (default) or **Newest first**.
- **Rows in Moments:** how the Moments tab splits the photos into rows.
  - **By day** (default): one row for each calendar day.
  - **By breaks between photos:** a new row starts after a break without photos, so an evening that runs past
    midnight, or a day out that starts before dawn, stays in one row.
  - With breaks chosen: **New row after a break of** 2, 4, 6, 8, **12** (default), 24 or 48 hours.
- **Suggestions for similar photos:** **How alike** a slider from 50% to 99%, with the value shown: the cosine
  similarity two photos' image embeddings need for a "Look alike" suggestion (see [group.md](group.md)). Higher
  suggests only near-identical photos together; lower makes bigger, looser sets. The default is the platform model's
  own (**60%** Android, **80%** iOS); **Reset to N%** shows when it's changed.

## Behavior

- A change applies right away; the Journal rebuilds its rows from the same photos.
- The break length is kept while **By day** is chosen, so switching back restores it.
- The similarity value is saved when the slider is let go; story and group suggestions update right away (no photo
  is read again). Near-duplicates by dHash are suggested at any value. Only the default is stored as "none", so a
  better default later reaches everyone who didn't choose one.
- The grouping setting changes only the rows of the Moments tab. All photos, tags and the story page stay by day, and so do
  the "+ Add <day>" suggestions on a story page.
- Backup runs in the background with WorkManager, also with the app closed: every 15 minutes, 10 s after new photos
  appear in MediaStore, and right away when turned on or on **Back up now**. All wait for the network (and charging)
  condition; a listed network is checked when a run starts. Progress shows in a notification ("Backing up 3 of 120",
  with a Stop action) and in the bottom pill of the Journal and All photos ("Backing up 3 of 120", else "N not backed
  up", counting only the chosen sources while backup is on). Each photo's badge follows (waiting, uploading, backed up,
  failed). Turning backup on asks for notification permission.
- What was read and uploaded is remembered per device in `files/backup/ledger.jsonl` (sha256 per photo), so a photo is
  read once and never sent twice; the server also deduplicates. An edit saved in place uploads as a new version
  (`versionOf`). Uploads resume where they stopped. See [design.md](../design.md) "Upload and sync".

## Code

- Shared: `SettingsController`, `TimelineGrouping` and `SimilarityThreshold` in
  [settings/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/settings/SettingsController.kt);
  the rows are `StoriesState.moments`, built by `groupMoments` in
  [StoriesController.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/StoriesController.kt).
- Backup: [backup/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/backup/BackupController.kt)
  (`BackupController`, `BackupEngine`, `UploadLedger`, `ServerBackupApi`); Android reads originals in
  `AndroidMediaFiles.kt`, and runs it from [work/](../../androidApp/src/main/kotlin/com/chencohensolution/journal/work/BackupWorker.kt)
  (`BackupWorker`, `WorkBackupScheduler`, `currentWifiName`).
- Android: [SettingsScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/SettingsScreen.kt).
- iOS: [SettingsView.swift](../../iosApp/iosApp/SettingsView.swift), [SettingsViewModel.swift](../../iosApp/iosApp/SettingsViewModel.swift).
