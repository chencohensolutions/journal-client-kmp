# Settings

The user's choices, kept on this device (SharedPreferences / NSUserDefaults). Opened from the Journal's **⋯** menu.

## Layout

- **Top bar:** back and "Settings".
- **Backup** (Android; iOS later):
  - **Back up to your server** switch (off until turned on), and a status line: "Off. Nothing is uploaded.",
    "Backing up 3 of 120", "12 photos waiting to upload", "Everything chosen is backed up", "Waiting: …" (Wi-Fi network
    not on the list, location needed to check it, register again, photo access), or "Stopped: … It tries again
    later." **Back up now** beside it while not running.
  - **On your server**: what the user's originals take up there, "4.2 GB · 1,234 photos, 56 videos" (server query
    `storageUsage`), also with backup off. Asked when Settings opens and again when a backup run ends; "Can't reach the
    server right now" until it answers.
  - With backup on: **What to back up**, a toggle per source (**Camera** on by default, WhatsApp, Screenshots, Other);
    **When**: **Only on Wi-Fi** (on by default; never on mobile data), **Only while charging**, and, under Wi-Fi only,
    **Only on these Wi-Fi networks** with **Add this network** (asks for location, then lists the network the phone is
    on; × removes one). With a network listed and no "All the time" location, a note asks for it, since the network
    can't be checked in the background otherwise. **Order**: **Oldest first** (default) or **Newest first**.
- **Storage on this phone** (Android; asked for Oct 20, 2026):
  - **Previews from your server**: "2.3 GB of 10 GB used", a limit of **1, 2, 5, 10 (default), 20 or 50 GB**, and
    **Clear previews**. Previews of photos that are only on the server are kept up to the limit (the oldest go first)
    and fetched again when scrolled to; none are pinned, also for photos removed from the phone.
  - **Free up space**: takes originals the server has off the phone. A rule: **Older than** 1, 3, 6 months, **1 year**
    (default) or 2 years (by date taken), or **Keep the newest** 1, 2, 5, **10**, 20 or 50 GB (by date taken; everything
    on the phone counts toward what is kept, backed up or not). Then what it would remove, "Removes 14 GB from this
    phone: 1,240 photos, 38 videos", or "Nothing to remove", and **Free up space**. Only photos this phone backed up, as
    they are now, are removed; a pair (same file name apart from the extension, taken within 2 s: a RAW and its JPEG)
    leaves together or not at all. While it runs, "Checking 3 of 120 · IMG_1234.jpg"; afterwards what it did ("120
    files (3.2 GB) moved to the trash", "2 changed on this phone: backed up again", "1 not on your server: backed up
    again", "1 damaged on your server: kept on this phone"), and "120 files (3.2 GB) checked, waiting for you" with
    **Move to trash** when files wait for the system prompt.
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
- A lowered preview limit applies at once (the oldest previews are deleted); **Clear previews** deletes them all.

## Code

- Shared: `SettingsController`, `TimelineGrouping` and `SimilarityThreshold` in
  [settings/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/settings/SettingsController.kt);
  the rows are `StoriesState.moments`, built by `groupMoments` in
  [StoriesController.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/StoriesController.kt).
- Backup: [backup/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/backup/BackupController.kt)
  (`BackupController`, `BackupEngine`, `UploadLedger`, `ServerBackupApi`); Android reads originals in
  `AndroidMediaFiles.kt`, and runs it from [work/](../../androidApp/src/main/kotlin/com/chencohensolution/journal/work/BackupWorker.kt)
  (`BackupWorker`, `WorkBackupScheduler`, `currentWifiName`).
- Storage: [storage/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/storage/FreeUpController.kt)
  (`FreeUpController`, `freeUpPlan`, `FreeUpChecker`, `ServerVerifyApi`); previews in `ServerPreviews`. Android:
  [FreeUpWorker.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/work/FreeUpWorker.kt), and the trash
  only in [PhotoRemoval.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/PhotoRemoval.kt)
  (`AndroidPhotoTrash`; see PhotoSafetyTest).
- Android: [SettingsScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/SettingsScreen.kt).
- iOS: [SettingsView.swift](../../iosApp/iosApp/SettingsView.swift), [SettingsViewModel.swift](../../iosApp/iosApp/SettingsViewModel.swift).
