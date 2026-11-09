# A set of photos — viewing and making sets (2026-11-09, iOS and Android)

Tag: `ux/moment-set/2026-11-09`

Asked: a set merged from many photos can't really be swiped through; a moment screen showing the set's photos, a main
photo, a new composition per photo, swiping by a fast cross-fade instead of a slide, a button that plays the set as
a quick slideshow; then (same morning) picking photos from neighbouring days and making them one moment.

## Options
- **a — Set page**: tapping a set opens its page: the main photo large with Play, then every photo in a grid; holding a
  tile gives Make main, Recompose, Take out. *Turned down* (b's grid button covers seeing them all).
- **b — Viewer with a scrubber**: a set opens straight in the viewer on its main photo; swipes cross-fade in 0.12 s;
  a strip of every photo under it flips as fast as the finger slides; a grid button; Make main · Play · Recompose;
  the slideshow with Fast / Normal / Slow; Recompose with the set's strip and Same frame for all. *Picked*.
- **c — Flip in the feed**: the set stays one photo in Moments at its main photo; hold and slide to flip through it in
  place, a small Play plays it there, a tap opens b. *Picked*.
- **d — Collect a weekend**: picking in Moments with a tray counting photos and days, Make a moment: a name, the main
  photo, the day it shows on (first, last, or every day with a link), then its page. *Picked* (its page becomes b's
  viewer, or a header over the set in the feed).

## What they faked
- All: made-up pictures (one run along a beach), state kept only while the option is open, no notes written.
- b: the slideshow speeds and Same frame for all are not in the crop logic; corners can't be dragged in Recompose.
- c: hold-and-slide competes with scrolling only in the lab's simple gesture handling.
- d: the set's name and "show it on" have no storage yet; the tray's count of days counts only days with picks.

## Reviving an option
    git checkout ux/moment-set/2026-11-09 -- iosApp/iosApp/Lab/MomentSet androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/MomentSetLab.kt
Then add the `moment-set` entries back to `LabScreens.swift` / `LabScreens.kt` (as at the tag).
Built against `LibraryColors` and the lab's own stand-in pictures as of the tag; nothing else of the app.
