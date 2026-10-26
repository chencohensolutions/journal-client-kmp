# Settings — Loading Moments (2026-10-26, Android)

Tag: `ux/settings/2026-10-26`

Moments is read from the library database a page at a time (plan B, [library-on-disk.md](../../plans/library-on-disk.md)):
`FeedPager` reads 30 rows, and the next page starts once the list is 40 rows from the end of what is read
(`LOAD_AHEAD_ROWS` in `JournalHomeScreen.kt`). The owner asked for both in an Advanced section at the end of Settings
(phase 5).

## Options
- **a — A page of chips**: an Advanced section after Search with a Loading Moments row ("30 rows at a time · 40
  ahead") opening a page: Rows loaded at a time (15, 30, 60, 120) and Load ahead (10, 20, 40, 80), each saying what
  more of it gives, the default's chip marked, and a reset once either is changed. *Picked*, promoted in a04c01c
  (as a page of Settings, `LoadingMomentsPage`; its note now says a change applies as Moments loads its next rows).
- **b — Sliders on the first page**: the two values as sliders in the Advanced section itself (10–200 rows, 5–100
  ahead), saved when let go, as How alike is. *Turned down* for a.
- **c — Presets**: the Loading Moments row opens Light on memory (15, 20), Balanced (30, 40), Smooth scrolling
  (90, 120) and Custom, with − and + for each value. *Turned down* for a.

## What they faked
- All: the values were kept only while the lab was open (JournalSettings had neither), and Moments didn't read them.
  Settings' first page was a copy of the app's, without Backup and storage, scrolled to its end.

## Reviving an option
    git checkout ux/settings/2026-10-26 -- androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/lab/settings/<b> androidApp/src/debug/res/values/strings_settings_<b>.xml androidApp/src/debug/res/values-iw/strings_settings_<b>.xml
Then add `settingsOption<B>,` to `LabScreens`.
Built against Settings' parts (`SettingsCard`, `PageRow`, `SectionTitle`, `RowDivider`, `Chip`, `ModeOption`,
`TitleBar`, `SearchSettingsRow`) as of the tag. Compile errors after reviving are in the option's copies; fix them
there.
