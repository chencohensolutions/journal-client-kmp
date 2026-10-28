# Backup — what stays on the phone (2026-10-28, Android)

Tag: `ux/backup/2026-10-28`

Backup sends WAV recordings as FLAC and, since Oct 28, 2026, JPEG photos as JPEG XL; the server keeps those. What stays
on the phone was a WAV / FLAC chip choice and a separate "Keep JPEG XL on this phone" switch. The owner asked for both
to look alike, and for new suggestions.

## Options
- **a — Like WAV / FLAC**: JPEG / JPEG XL chips under WAV / FLAC, in the same shape, with the "Media management" warning
  and its button under them. *Turned down* for b2.
- **b — One card**: Keep on this phone as a card with a Recordings row (WAV | FLAC) and a Photos row (JPEG | JPEG XL),
  chips beside each name and what the choice does under it. *Turned down*: the owner wanted switches, not chips.
- **b2 — One card, switches** (from b): the same card with a switch per kind, "Recordings as FLAC" and "Photos as JPEG
  XL", both off by default, each saying what it does; a row asking for "Media management" access while JPEG XL is on
  without it. *Picked*, promoted in ecb5d00.
- **c — Originals or smaller copies**: one choice like Upload order (Originals, Smaller copies with the space given
  back, Choose for each with chips per kind). *Turned down* for b2.

## What they faked
- All: the settings weren't saved, and "Allow" gave "Media management" access at once instead of opening the system
  page.
- c: the space given back (4.1 GB, made up: nothing works it out yet).

## Reviving an option
    git checkout ux/backup/2026-10-28 -- androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/lab/backup/<b> androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/BackupLab.kt androidApp/src/debug/res/values/strings_backup_<b>.xml androidApp/src/debug/res/values-iw/strings_backup_<b>.xml
Then add `backupOption<B>,` to `LabScreens`' "backup" screen.
Each option is a whole copy of `BackupSettingsScreen` as of the tag (with the JPEG XL switch), its keep-on-phone part
replaced; built against `SettingsCard`, `SwitchRow`, `Chip`, `ModeOption`, `KeepOnPhoneChoice` and `BackupLab`'s made-up
settings. Compile errors after reviving are in the option's copy; fix them there.
