# Screens

One page per screen: what it's for, what it shows, how it behaves, and where the code is on each platform.
Clickable mockups of every screen are in [mockups/](mockups/README.md): open `mockups/index.html` in a browser.
The earlier design canvas (https://claude.ai/artifact/QKsfaEgFs3bCC4Qeqt4qPf) predates stories and is no longer
updated.

| Screen | Shown when | Doc |
|---|---|---|
| Enrollment wizard | The device has no identity yet | `docs/authentication.md` in journal-server-go |
| Photo access | After enrollment, until the user answers once | [photo-access.md](photo-access.md) |
| Journal (home) | Every launch after that | [journal-home.md](journal-home.md) |
| Moments (feed) | The Moments tab of the Journal (Android) | [moments.md](moments.md) |
| Story | Tapping a story card, or right after creating a story | [story page](story-page.md) |
| Group | Tapping a group tile in a story | [group.md](group.md) |
| Tags | The tag button on the Journal; a tag opens its photos | [tags.md](tags.md) |
| All photos | ⋯ menu → All photos | [all-photos.md](all-photos.md) |
| Settings | ⋯ menu → Settings | [settings.md](settings.md) |
| Viewer | Tapping any photo or video | [viewer.md](viewer.md) |
| Crop | Viewer → Crop, or long press in Moments → Crop | [crop.md](crop.md) |

```
Enrollment ──► Photo access ──► Journal ──► All photos, Settings
                (once)            │  │           │
                                  │  └─► Story ─► Group
                                  │        │     │    │
                                  └──► Viewer ◄──┴────┘
```

## Shared logic

All screens after enrollment read from `LibraryController`
([library/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/library/)):

- `LibraryState.access`: `NOT_DETERMINED`, `DENIED`, `LIMITED` or `FULL`. Android can't tell "never asked" from
  "denied", so the controller remembers whether the question was answered (`photoAccessRequested` in settings).
- `LibraryState.days`: every photo and video, grouped by the day it was taken (device time zone), newest first.
  `days(filter)` narrows it for All photos.
- `notBackedUpCount` / `notBackedUpBytes`: what the backup summary shows.
- `refresh()` re-checks the permission and re-reads the library. Both apps call it when they come to the foreground.

The platform part is `MediaLibrary`: MediaStore on Android
([AndroidMediaLibrary.kt](../../sharedLogic/src/androidMain/kotlin/com/chencohensolution/journal/library/AndroidMediaLibrary.kt)),
PhotoKit on iOS ([IosMediaLibrary.kt](../../sharedLogic/src/iosMain/kotlin/com/chencohensolution/journal/library/IosMediaLibrary.kt)).
Thumbnails and full images are loaded by the UIs, by item id.

Stories come from `StoriesController`
([stories/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/)): it keeps stories on the
device, along with each photo's caption and tags (`PhotoNote`), and combines them with the library into
`StoriesState.timeline` (stories and unsorted moments, newest first), `unsortedDays` (photos in no story and without
tags), `moments` (the same photos split as the grouping setting says, see [settings.md](settings.md)), `feed` (the
Moments tab, every photo, see [moments.md](moments.md)) and `tags`. See [story page](story-page.md) for the file format.

Server state comes from `JournalState.health`:

| `ServerHealth` | When | Shown |
|---|---|---|
| `OK` | Connected, or still checking | Nothing |
| `OFFLINE` | Network error, timeout, any HTTP error but 401 | "Offline" pill in the top bar |
| `REJECTED` | HTTP 401 (certificate unknown or revoked), or no identity | "Register again" card on the Journal |

## Backup badges

The bottom-right badge (on grid tiles and on the Moments tab's photos) says where the original is (asked for Oct 20, 2026, Android; iOS still shows a check for
backed up):

| Badge | State | Meaning |
|---|---|---|
| Phone, green | `BackedUp`, original here | On this phone (in the gallery, or downloaded and kept) and on the server |
| Cloud with a slash, dark | `NotBackedUp` | Only on this phone, not uploaded yet |
| Dashed ring, dark | `Queued` | Waiting to upload (for example, for Wi-Fi) |
| Ring filling up, green | `Uploading(progress)` | Uploading; exact % in the viewer |
| `!`, orange | `Failed(reason)` | Upload failed; the viewer shows why |
| none | `BackedUp`, not here (`MediaItem.hasOriginalHere` false) | Only on the server: shown from its preview |

Videos also have a chip in the top-right corner: a play icon and the length, or only the play icon when the
length is unknown.

## Look

The library screens follow the system light or dark theme, like the enrollment wizard and the photo access screen;
the photo viewer is always dark, like a photo app. The colors are in `LibraryColors`
([Android](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/LibraryStyle.kt),
[iOS](../../iosApp/iosApp/LibraryStyle.swift)): page colors change with the theme, while anything written over a
photo (labels, badges, the calendar block) is white or light on a dark scrim in both. Photos run edge to edge, under
the status bar; controls float on top with a fade of the page color behind them.
