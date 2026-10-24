# Plan: the server's new features in the apps

**Built Oct 24, 2026 on the Mac, Android and iOS** (shared logic with tests). What was built is in [screens/people.md](../screens/people.md),
[settings.md](../screens/settings.md#search--on-this-phone), [viewer.md](../screens/viewer.md),
[search.md](../screens/search.md), [explore.md](../screens/explore.md) and [moments.md](../screens/moments.md).

Agreed with the owner on Oct 24, 2026 on the Mac. The server (journal-server-go, up to commit `4ed5669`) has face
search, text in photos, similar photos and duplicates, aesthetic scores, a search warm-up, error codes,
`serverInfo`, and indexes a phone may keep and search itself. Neither app uses any of them yet. This file records
the UX the owner chose; nothing is built. The next step is mockups in the UI lab (docs/previews.md), then the shared
logic, then Android and iOS.

## Decisions

**People (face search)**
- **Explore gets a third pill:** Places / Years / **People**, beside Map. Named people first as round faces with
  their counts, most photos first; under them **Who's this?** with the unnamed groups (the server shows groups of 3
  faces or more) and See all.
- **A person's page** is like a town's page: their photos by day, newest first (`searchMedia(people: [id])` with no
  text). It has:
  - **⋯**: Rename, Merge with… (another person or group), Forget this person (`namePerson`, `mergePeople`,
    `forgetPerson`).
  - **Remove a wrong face:** press and hold a photo, then "Not Mom" (`moveFace(face, to: null)`); the server never
    gives that face to them again.
  - **Search within:** a field on the page, so "beach" finds Mom at the beach (`searchMedia(text, people)`).
  - **Together with:** a row of people often in the same photos; tapping one shows the photos of both
    (`people: [a, b]` finds files with every one of them). How "often with" is counted is still to work out: the
    server has no query for it, so the app counts it from `Media.faces`, or the server gains one.
- **Naming a group:** tap a Who's this? group to see its photos, then **Name**. Typing suggests existing people with
  their counts; picking one joins the group to them (the server does this for the same name, ignoring case).
  "Not a person I know" was in the mockup, but the server has no way to dismiss a group yet: leave it out, or add a
  mutation on the server first.
- **Search:** before anything is typed, the search page shows a row of faces; a tap adds the person as a chip in the
  field, and words can follow ("Mom" + "birthday").

**The viewer: in Details, no new buttons**
- The Details panel gains **People** (the faces in the photo as small round faces, tap to open the person; unnamed
  as "?", opening its group), **Text in photo** (`Media.text`, line by line, with Copy; Hebrew right to left), and
  **Similar** (a row of photos from `similarMedia`, with More › opening them as a page). Each section shows only
  when it has something and the server has the index on.

**Duplicates:** not now. The server finds them (`duplicates`) but can't delete or hide one file yet.

**Aesthetic scores**, used three ways:
- A set in Moments and in search opens on its best-looking photo, unless the user chose one by hand ("front").
- Covers of towns, years and people are their best-looking photos.
- Town, year and person pages get a sort: **by date** (as now) or **best first**.

**Search on the phone** (`localIndexes`, `/index/{name}`, `/index/people`, `/models/…`; server docs/search.md "On
the phone")
- **Settings › Search › On this phone:** a switch per index with its size: Search by words (with the 290 MB 8-bit
  text model; the app picks q8 itself), People, Text in photos, Similar photos, Aesthetic scores. **Only on Wi-Fi**
  (on by default), "Updated 2 h ago · 12 new" with **Update now**, and a progress bar while downloading. Updated
  daily and on Update now; a broken download goes on from its last cursor (and the model file by Range).
- **Which search runs:** the phone, when its copy is up to date (instant, and offline); the server when the copy is
  missing or behind. The page says "Searched on this phone" only when offline or behind. The phone's answers must
  match the server's: test them against `go run ./scripts/local-fixtures`.

**No UX decision needed**
- **Search warm-up:** call `prepareSearch` when the search screen opens and `searchModel(wait: true)` once; while it
  loads, the field says "Getting search ready…" (the server's own wording).
- **`serverInfo`:** read at connect; a feature the server has off has no entry point (no People pill without
  `faces`, no Text or Similar in Details without `textInPhotos` / `similar`, no On this phone without
  `localIndexes`).
- **Error codes:** act on `extensions.code` (UNAUTHORIZED → Register again, SEARCH_OFF, NOT_READ_YET → "Not read
  yet", WITHHELD) instead of matching messages.
- **Schema:** `./gradlew :sharedLogic:syncServerSchema` first; the client's copy is from Oct 23, 2026.

## Not built yet

- The iOS UI lab has no screens for these yet (the Android lab has them).
- Tried on the iPhone 18 Pro simulator against the local test server (text search only there): phone search
  downloaded the index and the 1.13 GB model and found the concert photos with the server stopped. Faces, text in
  photos, similar photos and scores need a server with those models; not tried yet.

## Still open

- Whether to add a server mutation to dismiss a group ("Not a person I know").
- How "Together with" is counted (on the phone from `Media.faces`, or a server query).
- Face boxes in the viewer were not chosen; Details shows faces as small round crops instead.
