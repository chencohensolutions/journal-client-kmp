# People

Photos found by the people in them (asked for Oct 24, 2026; design in [plans/server-features.md](../plans/server-features.md)).
The server finds the faces in each photo and puts alike faces in groups; the user names a group once ("Mom") and her
photos are found from then on, as they come in. See journal-server-go `docs/faces.md`. Built Oct 24, 2026 on Android
and iOS. Shown only when the server has face search on (`serverInfo.faces`).

## Explore › People

- A third pill in Explore's bar: **Places / Years / People**, beside Map.
- The named people, four to a row: their face (the server's clearest, `Person.cover`), name and number of photos,
  by name. Then **Who's this?** ("Name a group once, and their new photos are found too.") with the groups no one has
  named, those in the most photos first: eight, then **See all 11**.
- While the server is still reading files for faces: "Still reading your photos for faces: 12,480 of 14,200" with a
  bar. Loading, offline ("Can't reach your server right now." with Try again; what was loaded stays) and "No faces
  found yet" say so. The list is loaded again each time People opens.
- A face is a square crop of its photo's preview, made by the server (`?crop=x,y,w,h`), 1.5 times the face's box,
  kept like any preview (`…-crop….webp`, never shown as the photo itself).

## A person's page

- Their best-looking photo across the top (the aesthetic score; else the first) with the name and "812 photos ·
  2014 – 2026". **⋯**: **Rename**, **Merge with…** (a sheet of the other people, then the groups) and **Forget this
  person** ("The name goes. The photos stay, and the faces go back to Who's this?").
- **Search Mom's photos**: words narrow the page to the photos of her they describe best (`searchMedia(text,
  people)`), asked once typing stops.
- **Newest / Best first**. Newest without words: Android shows the photos like the Moments tab (by day, sets, times);
  iOS by day in a grid. Best first, or with words: a grid in that order (best-looking, or best match).
- **Often with Mom**: up to 12 faces of people (then groups) found in the same photos, with how many; a tap opens
  theirs. Counted on the phone from the faces of the page's photos.
- Press and hold a photo: **Not Mom** ("This face won't be given to Mom again"): `moveFace(face, to: null)`. The
  photo leaves the page at once, and comes back if the server didn't take it ("Not changed").
- The viewer swipes through the page's photos in the page's order.

## Who's this? (a group)

- "In 54 photos", the group's faces four to a row (a tap opens the photo), and at the bottom a name field with
  **Name**: "Give them a name once. Their photos are found from then on, as they come in."
- Typing lists the named people whose names contain it (an exact match first), each with **Joins Mom**
  (`mergePeople(into: person, from: [group])`), then **New person "Mo"** (`namePerson`; the server joins a group given
  a name someone has). Either way the page becomes that person's page.
- "Not a person I know" was left out: the server has no way to dismiss a group yet.

## Correcting faces

(Oct 24, 2026, owner's choice of UX.) Every correction teaches the server: the faces you confirm become the ones a
person is known by, and a face you say isn't them keeps its lookalikes from them too (journal-server-go
`docs/faces.md`: a face more like a rejected one than like any of their own faces isn't theirs).

- **A face in a photo's details** (People in this photo): a tap opens its sheet — the face large; for a named person
  **This is Mom** (`markFaces(yes)`), **Not Mom**, which asks **Who is it?** (a person: `moveFace(face, to)`; a new
  name: `nameFace`; **Skip: just not them**: `markFaces(no)`), and **Open Mom's page**. A face no one is named for
  opens on **Who is this?**.
- **Edit faces** (a person's ⋯): their faces, four to a row; a tap marks one ✗ ("Tap the faces that aren't Mom. Each
  one you mark also keeps faces like it away from them."), Done sends them in one change (`markFaces(no)`); with one
  marked, its "Who is it?" follows.
- A correction shows at once: the page, the photo's details and People load again (`PeopleState.changes`). On the
  phone it is applied to the people it keeps the same way (`applyCorrection` in `local/FaceCorrections.kt`, with the
  faces' vectors), and while the server can't be reached it waits in `faces/corrections.json`, sent in order before
  People loads next. The server's people stay the record: its next document replaces the phone's.

## Offline

With On device search › People kept ([settings.md](settings.md)), People and a person's page are made from the people
document and faces the phone keeps when the server can't be reached; naming, merging and moving need the server.

## Code

- Shared ([people/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/people/)): `People.kt`
  (`Face`, `Person`, `PeopleList`, `faceCrop`, `PeopleApi` over `graphql/People.graphql`), `PeopleController` (the
  list, one per app), `PersonController` (a person's or group's page: items, faces, together, sort, query, rename,
  join, merge, forget, notThem). Tests: `PeopleTest`.
- Android: `PeopleViews.kt` (`PeopleList`, `PersonScreen`, `GroupScreen`, `SimilarScreen`, `FaceAvatar`),
  `Route.Person` in `LibraryApp.kt`; faces through `rememberFace` (`Thumbnails.kt`). Previews:
  `ServerFeaturesScreenshots.kt`; UI lab: `explore-people`, `person`, `group`.
- iOS: `PeopleViews.swift`, `PeopleViewModels.swift`, `.person` in `LibraryRootView.swift`; faces through
  `ServerImages.face`.
