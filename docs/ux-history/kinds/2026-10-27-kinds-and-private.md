# Show in the app and Private settings — by kind, no Imported, no duplicates (2026-10-27, Android)

Tag: `ux/kinds/2026-10-27`

Once every file had a kind (Camera, WhatsApp, Screenshots, Sent to you, folder kinds, Other; sound: Calls, Voice
recordings, Voice messages), the owner found Show in the app's "Imported" section useless (where a file is, phone or
server, doesn't matter; what isn't backed up does) and Private settings repetitive (WhatsApp beside its voice messages,
recordings/calls switches beside the sound kinds, "Screenshots in Bumble" labels). Two lab screens: "kinds" (Show in
the app) and "private" (Private settings).

## Options
- **kinds a — A list with switches**: one row per kind, count, Show switch, "12 not backed up" under the count.
  *Turned down*.
- **kinds b — Pills with a badge**: today's pills with an orange "N not backed up" badge. *Turned down*.
- **kinds c — A status page**: no switches; per kind its files, how many on this phone, "not backed up", and "Shown" or
  "Hidden on this phone"; kinds hidden on all devices unnamed. *Turned down*: the owner didn't want this phone's hiding
  shown in the list.
- **kinds c2 — c without shown/hidden on this phone**. *Turned down* for a more compact list.
- **kinds c3 — A list with totals**: one line per kind, its total in a column at the end, "N not backed up ›" under the
  name where it applies; a summary at the top ("87,343 files · 15 not backed up"); a Sound group; one unnamed line for
  kinds hidden on all devices; a note that hiding on this phone is the Moments filter bar's (a saved choice per phone).
  *Picked*, promoted in 5f663b1.
- **private a — One flat list by kind**. *Turned down*.
- **private b — Groups that fold** (Apps and folders, Screenshots, Sound, Sent to you, Nudity). *Turned down*.
- **private c — Choose what shows, no lock to hide**: opens without the lock, hidden kinds unnamed until unlocked, and
  showing one again asks for the lock. *Turned down*: the owner wants Private settings never open without the lock.
- **private c2 — Choose what shows, behind the lock**: the phone's lock first (as today); then every kind with a Show
  switch (on = shown), hidden ones included, Screenshots as chips of just the app name, Sound kinds, Sent to you,
  Nudity with its slider; no second prompt. *Picked*, promoted in 5f663b1.

Decided along the way: Show in the app is a status page only; choosing what shows is Private settings' (all devices);
the Moments filter bar stays a saved choice per phone.

## What they faked
- kinds: the per-kind "not backed up" counts and files on this phone, one list of kinds including Sent to you and the
  sound kinds, and the counts; taps did nothing.
- private: one merged list of kinds (the server's hidden collections keep apps, screenshot apps, sound and nudity
  apart, and can't hide Sent to you or the sound kinds by kind yet); the counts; nothing sent to the server.
- Screenshots were taken on the emulator (the phone was locked); c2's lock was the app's real `PhoneLocked`.

## Reviving an option
    git checkout ux/kinds/2026-10-27 -- androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/lab/<kinds|private>/<b> androidApp/src/debug/res/values/strings_<kinds|private>_<b>.xml androidApp/src/debug/res/values-iw/strings_<kinds|private>_<b>.xml
Then add its `LabOption` values to the "kinds" or "private" screen in `LabScreens`.
Built against Settings' parts, `PhoneLocked`, `sourceName`/`MediaSource.icon` and `LibraryColors` as of the tag.
