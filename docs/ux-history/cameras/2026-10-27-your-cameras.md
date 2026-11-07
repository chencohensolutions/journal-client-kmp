# Your cameras — which cameras were mine (2026-10-27, Android)

Tag: `ux/cameras/2026-10-27`

The server now keeps each photo's camera (`facts.camera`, read from its tags) and lets the user mark a camera model
"not mine" (`setCamera`): its photos then move to the kind "Sent to you" (`received`). The owner asked to see which
camera took a photo, imported ones too, and to set which camera models were his. Each option had its page, a
`-confirm` page (turning a camera off) and a `-moving` page (the server moving its photos).

## Options
- **a — One list with Mine switches**: every camera in one card, with its photos and years ("Samsung Galaxy S25 ·
  1,240 · 2025–2026") and a "Mine" switch; turning one off asks in a dialog ("312 photos will move to Sent to you"),
  and the row then says "Moving 312 photos…" with a bar. *Picked*, promoted in 68ad3db (the lab now has the real screen as `cameras current|confirm|moving`).
- **b — Yours and Sent to you**: two groups, each camera with a "Not mine" or "Mine" button; the question opens inside
  the row, and a band at the top says what is moving. *Turned down* for a.
- **c — Cameras over the years**: a card per camera, newest first, with a strip of the library's years it was used in,
  and Mine / Sent to you to choose; the question is a panel at the bottom. *Turned down* for a.

## What they faked
- All: the list `cameras` gives is made up, and `setCamera` isn't called; the move is timed in the lab (the server
  moves one file at a time), and the sync of the moved photos isn't shown; no "Your cameras" row was drawn in Settings.
- All: phones are named by their model as the tags give it ("SM-S931B"); the lab's "Galaxy S25" needs a table of
  model numbers that isn't built.
- c: the strip shows the years only, not photos per year.

## Reviving an option
    git checkout ux/cameras/2026-10-27 -- androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/lab/cameras/<b> androidApp/src/debug/res/values/strings_cameras_<b>.xml androidApp/src/debug/res/values-iw/strings_cameras_<b>.xml
Then add `camerasOption<B>,`, `camerasOption<B>Confirm,` and `camerasOption<B>Moving,` to the "cameras" screen in
`LabScreens`.
Built against Settings' parts (`SettingsCard`, `SwitchRow`, `SectionTitle`, `TitleBar`), `CamerasState` and
`LibraryIcons` as of the tag. Compile errors after reviving are in the option's copies; fix them there.
