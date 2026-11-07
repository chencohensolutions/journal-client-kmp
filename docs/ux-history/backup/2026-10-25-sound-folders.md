# Backup — calls and recordings from folders (2026-10-25, Android)

Tag: `ux/backup/2026-10-25`

Backing up the phone's calls and sound recordings, also while they are hidden in Private settings. The owner keeps a
`.nomedia` file in those folders to hide them from the gallery, so Android's media index never lists them: the app
can't find them itself (no "Music and audio" permission lookup), and the user picks each folder once with Android's
folder picker, which shows real folders and lets the app keep reading that one.

## Options
- **a — A card of folders**: under What to back up, a Calls and recordings card with each folder (what it holds, where,
  files, size, waiting) and a switch, folders found on the phone to add, and Add a folder. *Turned down* for b2.
- **b — Pills**: sound folders as pills in a row of their own under the photo sources, "+ Add a folder" last. *Turned
  down*: the owner wanted one list.
- **b2 — One list of pills** (from b): photo sources and sound folders in one list, not grouped, the backed-up ones
  first; "+ Add a folder" last. **b2-add**: after the picker, what was found in the folder, what its files are (calls,
  recordings or voice messages, guessed from their names), Back it up. *Picked*, promoted in 4246233.
- **c — A page of its own** (and **c-add**): one row in Backup opening a page with each folder, what it holds
  (changeable), how much is on the server, and Add a folder with the same question. *Turned down* for b2.

## What they faked
- All: the folders, counts and sizes (the app read no sound files), the system folder picker (Add a folder always
  "picked" WhatsApp's voice notes), and backing sound files up (switches and pills moved on screen only).
- a: finding the recorders' folders, which `.nomedia` makes impossible without the picker.
- b2-add, c-add: the guess of what a folder holds.

## Reviving an option
    git checkout ux/backup/2026-10-25 -- androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/lab/backup/<b> androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/lab/backup/BackupLab.kt androidApp/src/debug/res/values/strings_backup_<b>.xml androidApp/src/debug/res/values-iw/strings_backup_<b>.xml
Then add `backupOption<B>,` to `LabScreens` (with `backupOptionCAdding` / `backupOptionB2Adding` for the dialogs).
Built against `BackupSettingsScreen`'s parts (`SettingsCard`, `SwitchRow`, `SourcePills`, `backupStatus`), `kindIcon`
from AudioParts.kt and `backupLabUi()` as of the tag. Compile errors after reviving are in the option's copies and
sample data; fix them there.
