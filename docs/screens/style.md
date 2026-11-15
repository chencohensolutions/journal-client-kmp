# Style: the rules every screen follows

The library's look, written down after the UX consistency review of Nov 15, 2026 (the owner: "fix it all"). New
screens follow these rules; a screen that breaks one is a bug. Android code: `LibraryStyle.kt` (colors, type, shapes,
icons), `LibraryButtons.kt` (buttons). iOS follows ([ios-todo.md](../ios-todo.md)).

## Colors

One palette, light and dark (`LightLibraryPalette`, `DarkLibraryPalette`), and Material's colors are built from it
(`materialColors`), so every Material piece (dialogs, text fields, sliders, switches, radio buttons, default buttons)
matches the library. There are no wallpaper (Material You) colors anywhere since Nov 16, 2026: `JournalTheme` is
`LibraryTheme`, so enrollment and photo access look like the library. Every screen follows the system's light or dark
theme; the viewer (and Recompose, a sound recording's page) is always dark, with the dark Material colors too.

| Color | Is for | Never for |
|---|---|---|
| Ink (`Inverse` / `OnInverse`: ink on light, paper on dark) | The one main action of a screen, selected chips and tabs, text actions | |
| Teal (`Accent`) | "On" states and progress: a running backup, a check on a picked photo, a progress bar | Buttons and links |
| Red (`Error`) | Actions that remove or forget (Forget person, Remove swap, Revoke, delete confirmations), failures | Anything that only changes a setting |
| Orange (`Warning`) | Warnings: Offline's cloud, "Hidden on all your devices", a backup problem | Links and buttons |
| `Muted` | Second lines, notes, section labels, outlines | |
| `Raised` | Cards and sheets' tiles on `Background` | |

Material mapping: primary = Inverse (onPrimary = OnInverse), secondary and tertiary = Accent, surface and background
= Background, surfaceContainer* = Raised, onSurface = OnBackground, onSurfaceVariant and outline = Muted, error = Error.

## Buttons

- **PrimaryButton**: filled ink, full pill. One per screen or card: Free up, Download all, Make it, Allow access,
  Register again, Continue, Swap's main step. `destructive = true` makes it red (only for removing or forgetting).
- **SecondaryButton**: outlined ink, full pill. A second action (Scan QR code, Paste link, Copy link), or a card's
  action under its body.
- **TextAction**: ink semibold text (red when destructive): actions inside a row or a sheet ("Change", "Copy",
  "+ Tag", Cancel). Never teal or orange.
- On a photo or a dark bar (the picking bar, the viewer) buttons are text in the bar's own ink (`OnInverse`), all
  of the same weight: Mark private and Tuck away look alike.

## Cards on home

Register again, No access and Limited access are one `HomeCard`: `Raised`, a title, one line, one ink action (a
filled button, or a text action at the end of a one-line card). The rejected card has a small warning mark.

## Type

Named sizes in `LibraryType` (`LibraryStyle.kt`); hand-typed sizes went on Nov 16, 2026 (15 and 17 became 16, 18–20
became 22, 28 became 30). A call may still set its own weight or color.

| Style | Size | Weight | For |
|---|---|---|---|
| ScreenTitle | 30 | bold | "Journal", "Search" |
| PageTitle | 22 | bold | A page's or sheet's title (`TitleBar`) |
| SectionLabel | 13 | semibold, Muted | The label over a settings card (`SectionTitle`) |
| RowTitle | 16 | semibold | A row's title |
| Body | 14 | | Text, messages |
| Caption | 13 | | Second lines, notes (`PageNote`) |
| Small | 12 | | Badges and counts over photos |
| Tiny | 11 | | The smallest: a reel's video length, the date ruler's day numbers |

Nothing is drawn below 11 sp, except digits inside an icon (the "15" of the skip buttons). Text fields (the note
editor's serif, search's field) keep their own sizes.

## Shapes

`LibraryShapes`: cards and sheets 16 dp (`Card`; sheets round their top corners, `Sheet`), tiles inside a card and
thumbnails 12 dp (`Tile`), controls a full pill (`Pill`: buttons, chips, segmented choices). Small badges over photos
(2 to 8 dp) keep their own.

## Pages

- A settings-style page is `SettingsScaffold(title, back) { … }` (`LibraryPages.kt`): the page color, the scroll,
  room for the bars and the floating `TitleBar` (back and the title in PageTitle; an optional caption under it, as a
  sound recording's page has its times). Settings and its pages, Backup, Free up, Cameras, Sound folders, Private
  settings, Models, Copies, Swap and Search settings use it.
- Intros and notes on those pages are one `PageNote`: Caption, Muted, 32 dp from the sides.
- Choice chips are one full pill (`Chip`, Explore's bar's `ExploreChip`): ink when selected, outlined when not; a
  check mark only where several can be on at once. A row of choices scrolls sideways (`OptionChips`) instead of
  wrapping.

## Empty, loading, unreachable

- `EmptyState(text, actionLabel?, onAction?)`: a muted line, centered, with an optional outlined button under it.
- `LoadingState()`: a small spinner with "Loading…" under it, centered.
- `UnreachableState(onRetry)`: "Can’t reach your server right now." with Try again.

The same paddings everywhere (32 dp). Used by Cameras, Sound folders, Private settings, Copies, Tags, a tag's page,
the storyline, Moments, Search, People, a person's page and the map.

## Icons

Line icons in `LibraryIcons` (24 dp, stroke 2): no typed characters as icons. Highlight is a star (outline, filled
once highlighted), Recompose and Crop share the crop mark, a video's mark is Play, a moment's count is Stack.

## Sheets and confirmations

There are no AlertDialogs (Nov 16, 2026). `LibrarySheets.kt`:

- `PageSheet`: every bottom sheet (the tag picker, the Showing sheet, search's filters, a person's menus, Make a
  moment, the confirmations), in the colors of the page under it (light on a light page, dark in the viewer and when
  the system is dark), 16 dp top corners, the same grey drag handle.
- `ConfirmSheet(title, body, confirmLabel, destructive, onConfirm, onDismiss)`: a question with Cancel and its
  button. The confirm word is red when it removes or forgets (Forget, Remove, Revoke, Delete, Discard), ink
  otherwise. Cancel can take another word and action ("Later" in Free up's access question, "Keep editing").
- `FormSheet(title, confirmLabel, …) { fields }`: a small form (name a moment, edit a call, add a folder) with the
  same title and buttons.

## Messages

No toasts. One `MessageBar` (`LibraryMessages.kt`), hosted once in `LibraryApp` above the bottom controls (and once
by enrollment): an ink bar with the text, an optional action and a close button. Screens post to `LocalMessages`
(code outside composition, such as casting, to `AppMessages`). A plain note goes after 6 s; one with an action stays
until dismissed. "Not changed: can’t reach your server." comes with **Try again** where the change can be sent again
(Mark private and Tuck away from the picking bar, Mark private and Bring back in the viewer's details).

## Words

- **Hide / hidden** only for Show in the app (this phone) and the kinds hidden on all your devices; **private** only
  for Mark private and Tucked away. Settings' page for both is **Hidden and private**, and **How these differ**
  (`HidingExplainer`) says the four, lightest to strongest.
- **This phone**, not "this device", in the library's strings; enrollment keeps "device", the server's word.
- Curly apostrophes (’) in `values/strings.xml`.
- A long press on a photo always starts picking; anything a long press does also has a visible button (rename a
  moment, remove a backup folder).
- Every changed or new string is in English and Hebrew (Tucked away is "מוצנע").
