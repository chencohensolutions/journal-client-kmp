# Backup › Copies — a pass's progress (2026-10-28, Android)

Tag: `ux/backup/2026-10-28-3`

With the copies page in the app, the owner asked for a pass's progress by size out of the total size as the card's
headline (not a line under it), a percentage and the time left, and Stop at the end of its line rather than under the
card. The options change only the copy's card while a pass copies; Copy now moved to the end of its line in all.

## Options
- **a — Size on top**: "141 GB of 412 GB" in large type, the bar, then "34% · About 48 min left · 39,319 of 114,718
  files" with Stop at the end. *Turned down* for c: the line wraps on a phone.
- **b — Percentage on top**: "34%" in large type with "141 GB of 412 GB" beside it, the bar, then the time left and
  the files with Stop. *Turned down* for c: the size, which the owner asked for on top, is secondary.
- **c — Size and percentage on one line**: "141 GB" in large type, "of 412 GB" after it and "34%" at the line's end,
  the bar, then "About 48 min left · 39,285 of 114,718 files" in small type with Stop. *Picked*, with the time left
  in the text color and the files muted (the owner took the suggestion), promoted in d050669.

## What they faked
- All: the copy and its pass (made up, moving on their own), Stop and Copy now doing nothing. The time left was the
  pass's rate since it started, as the app now computes it (`CopyRunning.remainingMillis`).

## Reviving an option
    git checkout ux/backup/2026-10-28-3 -- androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/lab/backup/<b> androidApp/src/debug/res/values/strings_backup_<b>.xml androidApp/src/debug/res/values-iw/strings_backup_<b>.xml
Then add `*backupOption<B>,` to `LabScreens`' "backup" screen.
Each option is a whole copy of `CopiesScreen` as of the tag, its `CopyCard` replaced; built against `CopiesActions`,
`DataCopy`, `CopyRunning` and the app's `copies_*` strings. Compile errors after reviving are in the option's copy.
