# Plan: hiding WhatsApp, screenshots (and other sources) everywhere

(Stories, which this plan mentions, were replaced by the storyline later on Oct 21, 2026; written photos always show
on the storyline instead.)

Planned Oct 21, 2026 on the Windows machine; **built Oct 21, 2026 on the Mac, Android and iOS**, without "Not private"
(the owner chose Hide / All only) and without screen 4 (hiding one photo, which needs the server). WhatsApp and
Screenshots are off by default for everyone. The server's part was the ~1,100 WhatsApp-named files:
`scripts/fix-sources` there (imports now tell the source from the name too). What was built is in
[screens/moments.md](../screens/moments.md), [explore.md](../screens/explore.md)
and [settings.md](../screens/settings.md); this plan is kept for screen 4.
Mockups (6 phone screens): https://claude.ai/artifact/ARgmUkhoGNDRYQeJ7g5Dh7

## Why

The server's data folder has 18,458 files marked `source: whatsapp`, all brought in by `scripts/import` from one
backup (`D:\Archive\Hen\archive\toimport2\toimport\2025\WhatsApp\WhatsApp\Media\`):

| Folder | Files |
|---|---|
| WhatsApp Images\Private | 10,110 |
| WhatsApp Video\Private | 5,781 |
| WhatsApp Images | 2,344 |
| WhatsApp Video | 212 |
| WhatsApp Video\Sent | 8 |
| other (gifs, documents) | 3 |

"Private" is WhatsApp's folder for chats set to stay out of the phone's gallery: 86% of them. Almost nothing came from
"Sent", so a "received only" choice is not worth having.

## What exists today

- Moments has a filter bar of source toggles (`FeedSourceBar` in `FeedChrome.kt`, `MomentsFeed.shown`, kept as
  `feed_sources` in settings). Camera and WhatsApp are on at first.
- Explore uses the same sources as the Moments bar but shows no sign of it.
- Server photos carry `source` (`ServerMedia.source` → `MediaSource`); they do **not** carry their folder, so the
  client can't tell Private from the rest.

## The design (screens in the mockup)

1. **One setting, everywhere** (screen 3, Settings › *Show in the app*): a switch per source; WhatsApp has
   **Hide / Not private / All**. It replaces `feed_sources` (migrate the saved value). It applies to Moments, All
   photos, Explore (places, years, map, town and year pages), Tags and the story photo picker. **Photos already in a
   story always show in that story.** Hiding never deletes and doesn't change backup.
2. **WhatsApp and Screenshots off by default** (owner to confirm: for everyone or only them).
   **Screenshots** (asked Oct 21, 2026) works exactly like WhatsApp: its own switch, "+3 Screenshots" chips on a day,
   counts in Explore, the same reveal-this-day. It covers screen recordings too (`sourceOfFolder` on the phone,
   `sourceOf` in the server's `scripts/import/facts.go`, both match "screenshot", "screenrecord", "screen record").
   On iOS, PhotoKit's screenshot flag already maps to it. No server change is needed for it.
3. **Say what's hidden** (screens 1, 2): a day divider ends with a "+6 WhatsApp" chip; tapping it shows that day's
   WhatsApp photos in a tinted band (Add to a story, Always show this day) with a "Showing 6 from WhatsApp on Oct 4 ·
   Hide" snackbar. The setting doesn't change. A Settings switch *Say what's hidden* turns the chips off.
4. **Explore** (screens 5, 6): a line under Places / Years / Map, "Camera · Other · WhatsApp hidden — Change", opening a
   *Showing* sheet with the same switches and "N here" counts. Each year shows "+2,410 WhatsApp hidden"; a year with
   only hidden photos stays, with "Only WhatsApp photos this year (412) — Show them". Covers come only from shown
   photos. The map changes little (WhatsApp strips locations).
5. **Later** (screen 4, needs the server): Hide this photo / this moment from the photo menu, found again in All
   photos › Hidden. A user decision, so a new user field on the sidecar (never `detected`).

## Steps

**Server (`journal-server-go`)**: nothing is needed for hiding by source (Hide / All, Screenshots, the chips,
Explore): `facts.source` is in every sidecar and already reaches the app as `Media.source`. Only these need it:
- **"Not private"** (skip it and the server needs no change): a new additive sidecar field, e.g.
  `facts.whatsappPrivate`, a `private: Boolean` on `Media`, and on `StartUploadInput` so phones send it from their
  path (`.../WhatsApp Images/Private/`). Fill it for files already imported with a one-time pass over the sidecars,
  from `facts.folders` / `importedFrom`, through `rewriteSidecar` inside `Store.Turn`. Rewriting matters: the change
  feed sends a file again only when its sidecar changes, so a value worked out at read time would never reach phones
  that already have the file. Regenerate with `go tool gqlgen generate`; update `docs/media.md`.
- **The ~1,100 files named like WhatsApp** (`IMG-…-WA…`, `VID-…-WA…`) whose `source` isn't `whatsapp`: find out
  why first, then fix them with the same kind of one-time sidecar rewrite.
- **Optional:** accept only the known `source` values (camera, whatsapp, screenshots, other) in `startUpload`.
- **Later, screen 4 (hide one photo or moment):** a user field in the sidecar (not `detected`), a mutation to set it
  for many files at once, `Media.hidden`, and the change feed carries it.

**Shared logic (`sharedLogic`, with tests)**
- A `ShownSources` setting (sources + WhatsApp mode + say-what's-hidden) in `SettingsController`, migrating
  `feed_sources`.
- One filter used by `MomentsFeed`, `Explore.kt` (`placesOf`, `yearsOf`, map scope), Tags and the picker;
  keep story members regardless.
- Per row/day and per year: counts of hidden items, and a "reveal this day" set in the feed state.
- `MediaItem` gains `private` (from the server field, and from `sourceOfFolder` for device photos).

**Android** (`androidApp/.../ui/library/`): Settings section, Moments chips + tinted band + snackbar, Explore line,
sheet and year counts; screenshot previews per `docs/previews.md`.

**iOS** (`iosApp/iosApp/`): the same screens in SwiftUI.

**Docs**: `docs/screens/moments.md`, `explore.md`, `settings.md`; on the server, `docs/media.md` for
the new field.
