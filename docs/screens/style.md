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
