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

## Not built yet

Renaming, merging or deleting a tag everywhere at once; tagging several photos in one go (the shared logic already
takes a list of photos); filtering All photos by tag.

## Code

- Shared: [PhotoNote.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/PhotoNote.kt),
  `addTag`, `removeTag`, `setWriting`, `tagged` and `searchTags` in
  [StoriesController.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/StoriesController.kt)
- Android: [TagsScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/TagsScreen.kt),
  the tag sheet in
  [PickerSheets.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/PickerSheets.kt)
- iOS: [TagsView.swift](../../iosApp/iosApp/TagsView.swift), [PickerSheets.swift](../../iosApp/iosApp/PickerSheets.swift)
