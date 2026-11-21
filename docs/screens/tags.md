# Tags

For photos that belong somewhere: a receipt, a recipe, the dog on an ordinary day. A tag gives a photo a place
without writing about it, so the storyline stays for what you wrote. Each photo can also have a **note**: a title
and a paragraph, which put it on the storyline.

## Tagging a photo

In the [viewer](viewer.md), open the details (ⓘ):

- **Note:** tap "Write about this photo…" to write or edit the title and paragraph (the [note editor](viewer.md),
  with History). It shows wherever the photo appears.
- **Tags:** chips with × to remove, and **+ Tag**. The tag sheet is focused and ready to type:
  - before typing it lists your tags, most recently used first, with a check on the ones the photo has;
  - typing narrows them (anywhere in the word, one typo allowed for 4+ letters) and offers **Create tag "…"** when
    no tag has that name;
  - tapping a tag adds or removes it.

Tags are compared ignoring case: typing "dog" when "Dog" exists reuses "Dog". Spaces at the ends are dropped and
runs of spaces become one.

A tag doesn't put a photo on the storyline or take it out of the Moments tab. (Until Oct 21, 2026 a tag also took a
photo out of "Unsorted", which went with stories.)

## The Tags screen

The **tag** button in the Journal's top bar opens every tag, most recently used first, each with the newest photo
carrying it and a count. A search field narrows the list. Tapping a tag opens its photos, by day, newest first;
tapping a photo opens the viewer on that tag's photos. Photos of a source hidden in
[Settings › Show in the app](settings.md) are left out of the photos and the counts.

## How it's saved

One small file per photo, `media/<device id>.json` in the app's private storage (`journal/media`), like the
server's sidecar files: the photo itself is never changed. iOS ids contain "/", so anything but letters, digits,
`-`, `_` and `.` becomes `_` in the file name. A note with no text, history, tags, [crops](crop.md) or Moments set is deleted.
Format v2 added `"crops"`, and `"set"` / `"apart"` for sets merged or split by hand in [Moments](moments.md); v3
(Oct 21, 2026) added `"title"`, `"writtenAt"`, `"history"` (earlier titles and paragraphs, oldest first) and
`"storyline": false` for a photo removed from the storyline. `"caption"` is the paragraph. Saving keeps fields the
app doesn't know.

```json
{
  "v": 3,
  "deviceId": "22",
  "fileName": "20261016_224526.heic",
  "takenAt": 1792179926824,
  "title": "The encore",
  "caption": "They played the first song again.",
  "writtenAt": "2026-10-21T19:02:00Z",
  "history": [{"title": "Encore", "savedAt": "2026-10-17T15:17:59.033Z"}],
  "tags": ["Concert"],
  "updatedAt": "2026-10-17T15:17:59.033Z"
}
```

There is no separate list of tags: the tags are whatever the notes contain.

## Tagging several photos (Nov 19, 2026)

Android; iOS not built yet ([ios-todo.md](../ios-todo.md)). Picked on the design canvas
https://claude.ai/artifact/Qamjv8YgzvTXrHQ87ms7EE (frames 1a, 1b).

- The picking bar (Moments and the storyline) offers **Tag** for any number of photos picked.
- **Quick tags**: above the extras while picking, the 3 most recently used tags as outlined chips (ink text) and
  **More…** (dashed) for the tag sheet. A chip tags every photo picked (`addTag(itemIds, tag)`), stops picking and
  says "Tagged 4 photos Dog" (" · tucked away" when the tag is) with **Undo**, which takes the tag off the photos that
  didn't have it. No tags yet: only the Tag extra. The row scrolls sideways when long.
- **The tag sheet for several**: "Tag 4 photos"; each tag shows a check (all have it), a dash with "2 of 4" (some) or
  nothing (`StoriesState.tagCounts(itemIds)`). Tapping: some → all, all → none, none → all. Create tag "…" gives it to
  all. A tucked tag shows a crossed eye and "tucked away", and its tile is a crossed eye. **Done** closes.

## Tucked-away tags (Nov 19, 2026)

Android; iOS not built yet. Picked on the design canvas above (frames 2a, 2b, 2d, 2e), with the owner's addition that a
highlighted photo shows anyway. "Tucked" belongs to the **tag**, not to each file: no file is marked (`setTucked` is
never called for it).

- A photo is **tucked by tag** when any of its note's tags is tucked away, unless it is **highlighted** (its note's
  `highlight`, the viewer's star): a highlight shows through, in Moments and the storyline. A file tucked away itself
  (the server's mark) or private stays so whatever its tags and highlight.
- Tucked-by-tag photos leave **Moments**, the **storyline** (with their writing) and **Explore**, and the app drops
  them from **search** results (the server's and On this phone's), as it does for tucked files. Tagging photos with a
  tucked tag makes them leave the feed with the usual motion.
- **The Tags screen**: each row has ⋯ (and a long press) opening its sheet: the tag's name and count, then **Tuck
  away this tag** (crossed eye; "Its photos leave Moments, the storyline, Explore and search. Each day says how many it
  holds, and a tap shows them.") or **Bring back**. No Rename or Merge. Tucked tags are listed last, under **Tucked
  away**, with a crossed-eye tile in place of their newest photo.
- **Day pills**: where a day says "1 tucked away" (a Moments row under its divider, a storyline day under its
  heading), each tucked tag with photos that day has its own pill, "3 Dog" (crossed eye, same pill), beside the
  generic pill (shown only when files are tucked that day). A photo with two tucked tags counts under the first
  alphabetically, so the counts never exceed the photos; a photo tucked as a file counts only on the generic pill. A
  day whose photos are all tucked keeps its divider or heading with only the pills.
- **Reveal**: tapping a tag's pill reveals only that tag's photos of that day, in place, each with a corner mark
  (crossed eye and the tag's name); the pill fills (ink) with an open eye. Tapping again hides them. The lock rule of
  [Tucked away](moments.md#tucked-away-nov-8-2026): the first reveal of a session asks for the phone's lock.
- **A tucked tag's page** asks for the lock once per session, and starts with a Raised card: crossed eye and "Tucked
  away", "Its 214 photos stay out of Moments, the storyline, Explore and search. Each day shows a "Dog" pill instead.",
  **Show everywhere for now** (reveals the tag on every day for the session, behind the lock; then **Hide again**) and
  **Bring back**.
- The picking bar's Tuck away and Bring back stay file actions; Bring back isn't offered for photos tucked only by tag.
- The viewer's details say "Tucked away with Dog" for a tag-tucked photo, and "Shown although Dog is tucked away
  (highlighted)" for a highlighted one ([viewer.md](viewer.md)).

### How it's saved and synced

The list of tucked tags, compared ignoring case:

- On the server as the app's data called `tags` (`appData` / `setAppData`, journal-server-go docs/media.md "App
  data"): `{"v": 1, "tucked": ["Dog"], "updatedAt": "…"}`. Fields the app doesn't know are kept. Synced only with a
  server whose `serverInfo.appNotes` is true; otherwise the list stays on the phone.
- On the phone as `journal/tags.json`: `{"v": 1, "content": {the server's object}, "synced": {"version": "…",
  "tucked": [...]}, "pending": true}`; "synced" is what the server had when last in step (the base of a merge),
  "pending" a change still to send. Written to a temp file and renamed; a damaged file is never fatal (the list starts
  empty and the file is replaced on the next change). It works offline; a change is sent when the server can be
  reached (a second after it, at start and when the server's list changes), and the server's list is brought in
  otherwise.
- **Conflict** (`AppDataConflict`: another phone changed it since): this phone's changes since the last sync are laid
  over the server's list: tags tucked here are added, tags brought back here are removed, everything else is as the
  server has it; then it is sent again (`mergeTucked`). Chosen because each change is a single tag's on or off, so
  replaying them keeps both phones' intent.
- What the session reveals (days, tags on days, tags everywhere) is never saved (`TuckedSession.dayTags`, `.tags`).

### Code

`TuckedTags.kt` (the list, its file and its sync; `TagsSyncSource`), `TagTucking` and `TuckedTagCount` in
`TuckedAway.kt` (which photos a tag keeps out, the pills), `FeedSection.tuckedTags`, `StorylineDay.tags`,
`StoriesState.tuckedTags` / `tagged` / `tuckedTagOf` / `shownThroughTag` / `tagCounts`, `StoriesController.setTagTucked`
/ `setHighlighted`, `TuckedReveal.toggleTag` / `showEverywhere` / `hideEverywhere`; `ServerLibrary.appData` /
`setAppData`. Moments' rows kept in `feed_section` count the photos per tag (`FEED_VERSION` 6); the tucked tags are part
of the rows' version, so tucking or bringing back a tag makes them all again, as a hidden source does. Tests:
`TuckedTagsTest`. Android: the Tags screen and a tag's page in
[TagsScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/TagsScreen.kt), pills, corner
mark, quick tags and Highlight in
[TuckedViews.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/TuckedViews.kt), previews
`TagsTuckedScreenshots.kt`.

## Deleting a tag (Nov 21, 2026)

Android; iOS not built yet ([ios-todo.md](../ios-todo.md)). The ⋯ sheet of a tag (on the Tags screen, and the ⋯ at the
end of a tag's page title) has **Delete tag** (red, with a bin). It asks first (`ConfirmSheet`, destructive): "Delete
"Dog"?", "It comes off its 214 photos. The photos stay." Confirming takes the tag off every photo's note, whether or
not the photo is in the library now (`StoriesController.deleteTag`, ignoring case; a note left empty is deleted); the
notes go to the server as any change does ([NoteSync](../design.md)), and the tag leaves the tucked list if it was
there, so its photos come back. From its page, the page closes. One photo at a time: the × on a tag chip in the
viewer's details (`removeTag`); the tag sheet then shows it unchecked, or "1 of 2" for several. Tests:
`TuckedTagsTest`.

## Not built yet

Renaming or merging a tag everywhere at once.

## Code

- Shared: [PhotoNote.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/PhotoNote.kt),
  `addTag`, `removeTag`, `setWriting`, `tagged` and `searchTags` in
  [StoriesController.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/StoriesController.kt)
- Android: [TagsScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/TagsScreen.kt),
  the tag sheet in
  [PickerSheets.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/PickerSheets.kt)
- iOS: [TagsView.swift](../../iosApp/iosApp/TagsView.swift), [PickerSheets.swift](../../iosApp/iosApp/PickerSheets.swift)

## UX review changes (Nov 16, 2026)

Android only; iOS not built yet ([ios-todo.md](../ios-todo.md)). The rules are in [style.md](style.md).

- No tags yet: the empty state has **Go to Moments**, which goes back to the Journal on its Moments tab.
