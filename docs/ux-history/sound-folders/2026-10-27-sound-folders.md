# Sound folders — merging folders into Calls and Voice recordings (2026-10-27, Android)

Tag: `ux/sound-folders/2026-10-27`

Sound files keep the folder they came from (`folder:calls`, `folder:voice recorder`, `folder:super_recorder`), and two
kinds were added for them, `call` and `recording`. The owner asked to choose which folders are merged into Calls and
which into Voice recordings, on a page under Settings, saved on the server for every device (`sound-folders.json`,
`setSoundFolder`; the server relabels the files' sidecars in the background and journals it). Each option had its
page, a `-confirm` page and a `-moving` page.

## Options
- **a — One card with three choices**: each folder with its files (and how many were imported) and three pills under
  it, Calls / Voice recordings / Keep as folder; the question in a dialog ("1,623 files will become Calls"), then
  "Moving…" with a bar on the row. *Turned down* for b.
- **b — Grouped by what they are**: folders grouped under Calls, Voice recordings and Kept as folders, each with a
  "Move to…" menu; the question opens inside the row, and a band at the top says what is moving ("Moving 1,140 files
  to Calls…"). *Picked*; not promoted yet.

## What they faked
- Both: the list `soundFolders` gives is made up (Call 1,623 from the phone; Calls and calls as one "Calls" 1,520;
  Voice Recorder 168; super_recorder 7; imported counts made up), `setSoundFolder` isn't called, the move is timed in
  the lab, and Settings had no row for the page.
- b: Voice Recorder starts already merged into Voice recordings.
- Screenshots were taken on the emulator (the phone refused adb that time).

## Reviving an option
    git checkout ux/sound-folders/2026-10-27 -- androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/lab/sound-folders/<b> androidApp/src/debug/res/values/strings_sound_folders_<b>.xml androidApp/src/debug/res/values-iw/strings_sound_folders_<b>.xml
Then add `soundFoldersOption<B>,`, `soundFoldersOption<B>Confirm,` and `soundFoldersOption<B>Moving,` to the
"sound-folders" screen in `LabScreens`.
Built against Settings' parts and `SoundFoldersState` as of the tag. Compile errors after reviving are in the option's
copies; fix them there.
