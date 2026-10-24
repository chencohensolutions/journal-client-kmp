# Viewer

One photo or video at a time, full screen on black. **Immersive** (asked for Oct 21, 2026): the status bar and the
gesture bar are hidden while the viewer is open (a swipe from the edge shows them for a moment); the bars and buttons
keep their room, so nothing moves when they show. Android tells the user once, the first time, how to bring them back.

## What it swipes through

- Opened from a tag: the photos with that tag.
- Opened from the Storyline tab: the storyline's photos, newest first.
- Opened from a row of the Moments tab: that row's photos as shown.
- Opened from All photos: everything in the current filter.

The list is fixed when the viewer opens, so tagging a photo or removing it from the storyline doesn't pull it out
from under you. If the open item is deleted from the device, the viewer closes.

## Layout

The photo opens alone on black (asked for Oct 21, 2026): a tap on it shows the top bar, the bottom buttons and the
crop chip for 3 seconds, and another tap hides them sooner. They stay while the details panel, a sheet or the note
editor is open, and fade 3 seconds after it closes. A tap while the details panel is open closes it with them.
Swiping is not a tap; a playing video's own controls take its taps.

- **Top bar:** back and the position ("4 of 29 · Yesterday"). When the viewer wasn't opened from Moments, the day
  is a chip, "4 of 29 · [Thu, Oct 8 ›]", that shows the photo in Moments: back to the journal on its Moments tab,
  scrolled to the photo (Oct 22, 2026; chosen in the UI lab over a third pill, a link under the pills, a toolbar and
  an icon).
- **Bottom:** **Write** (the note editor, below), **Details** (the details panel; Oct 22, 2026 it moved here
  from an ⓘ at the top), and a round **Share** button (Oct 22, 2026, chosen in the UI lab): the system share sheet
  with the original. A gallery photo shares the gallery's own file; one only on the server shares the original the
  viewer downloaded (downloaded now when it isn't here, with a spinner on the button), as a copy named as the photo
  (`cache/shared/`, only the latest kept, `ShareOriginal.kt`; iOS through `UIActivityViewController`). Offline
  without the original: "Couldn’t get the original to share." Hidden while the details panel is open; back or a tap on the photo closes the panel. The
  viewer's **Crop** button went the same day: the [crop screen](crop.md) opens from Moments.
- **Crops:** a photo with a portrait (upright) or landscape (sideways) crop fills the screen at it, with
  **Cropped · Show all** at the top.
- **Photos:** a small version appears at once, then the full image (at most 2560 px on the longer side on Android,
  screen size on iOS).
- **Zoom** (asked for Oct 21, 2026, reworked the same day; iOS since Oct 22, see Platform notes):
  - **Double-taps step**: fitted → filling the screen (edges cut off) → the original's size (one photo pixel to one
    screen pixel) → back to fitted, zooming around the tap. A step that would add nothing is skipped (a small photo's
    own size below fitted or within 5% of filling). From a pinched zoom, a double-tap goes to the next step above it.
  - **Pinch** at any zoom; let go within 15% of fitted, filling or the original's size and it snaps there (around
    where the fingers were); pinched below fitted it springs back. At most twice the original size, never less than
    filling the screen.
  - Zoomed in, **one finger moves the photo** once it has moved further than a tap would, and swiping to the next
    photo is off until it is back to fitted. A **tap** still shows and hides the bars; starting to move or pinch hides
    them.
  - **Minimap** (top right, under the bar): the whole photo small with the part on screen outlined and the rest
    dimmed, while zoomed in, as it moves and 1.5 s after, and whenever the bars show. It is **grey until the photo
    on screen is at the original's quality** (the original decoded for zooming, and the part at full detail where
    the screen wants more), then its colour fades in.
  - **Remembered**: leaving a photo (a swipe or closing the viewer) keeps the part on screen in its note
    (`"zoom"`, below). Next time the photo opens as usual (flying out of its tile, fitted or at its crop) and, once
    it has landed (the opening transition, or a swipe, has settled), glides to that zoom in 0.55 s: the scale
    changes evenly in doublings while the middle slides across, so it reads as one camera move. A photo with a crop
    starts the glide from the crop. Touching the photo meanwhile stops it there. Left fitted, the zoom is removed.
  - **Closing while zoomed** flies from the zoomed view straight into the photo's tile: the zoom is laid out (the
    photo's bounds are its zoomed size and place), not only drawn, so the shared element starts from what is seen.
  - Zoomed, a cropped photo shows whole. Once zoomed in the original is decoded at its own size (at most 24
    megapixels and 8192 px a side, under Android's limit for drawing one bitmap); a photo only on the server is
    decoded again from the downloaded original. Videos don't zoom.
  - **Full detail of larger photos** (Oct 21, 2026): when the screen shows more pixels than that decoded image has
    (a photo over 24 MP past about its own size), 150 ms after the photo stops moving the part on screen, plus 15%
    around it, is decoded from the true original (`BitmapRegionDecoder`, with the EXIF turn undone and put back) and
    fades in over it in 150 ms; the part before stays under it meanwhile, so nothing blurs in between. It is placed
    on the original's own pixels, so it lines up with the photo exactly. Originals it can't read in parts (DNG, JPEG
    XL, mirrored EXIF) keep the decoded image. Gallery photos are read through `openInputStream`, as the photo safety
    rules allow (the decoder keeps a copy of the file in memory while the photo is zoomed).
  - **In the note file** (`media/<id>.json`, format 4): `"zoom": {"x", "y", "w", "h", "aspect"}`, the part on screen
    as fractions of the upright photo, like a crop, with the screen's shape. On another screen shape it opens with the
    same middle, zoomed so that part fills the screen as far as it can.
- **Videos:** the first frame with a play button. Play starts the system player (Android `VideoView` with media
  controls, iOS `VideoPlayer`). Swiping away stops it.
- **Details panel** (**Details**, hidden by default; redone Oct 22, 2026). Its cards are a 6% tint of the panel's
  text color, so they read in light and dark.
  - The date ("Friday, October 16, 2026") and time.
  - What the server found in it (Oct 24, 2026; [people.md](people.md)), after the tags and storyline, before the
    map, each only when it has something and the server has that index on: **People in this photo** (their faces and
    names, "Who's this?" with a ? for a group; a tap opens their page), **Text in this photo** (the lines read in it,
    Hebrew right to left, with **Copy**) and **Looks like this** (eight similar photos, a tap opens them in the viewer;
    **More** a page of all of them). Asked when the panel opens (`media(sha256:)`, `similarMedia`); offline, from
    what On device search keeps.
  - The photo's note (title and paragraph) is no longer in the panel: **Write** edits it.
- **Note editor** (asked for Oct 21, 2026; also from Moments' long-press menu): Cancel · "Writing" · Save, a **Title**
  box (one line; Enter moves on) and the **paragraph** box. Cancel with changes asks "Discard changes?". Each save
  keeps what was written before as a version, unless it was saved less than 10 minutes earlier (a quick fix is the
  same version). With versions, a line under the boxes, "Edited 3 times · last Oct 21, 14:02", opens **History**:
  the versions newest first (when, title or "No title", the start of the paragraph). One opens in full, with what
  the version after it (or the writing now) no longer has highlighted, and **Restore this version**, which saves it
  as the writing and keeps what it replaced as a version too, so nothing is lost. Moments doesn't show history.
  - **Tags:** chips with ×, and **+ Tag**, which opens the same kind of search sheet for tags (recent tags before
    typing, **Create tag "…"** for a new one). See [tags.md](tags.md).
  - **Storyline** (Oct 21, 2026; only for a photo with a title or paragraph): "In the storyline" with **Remove**,
    or "Not in the storyline" with **Add back**. Removing keeps the writing, which still shows in Moments. The
    Stories and Group sections, **Add to story**, and the **⧉ 14 · Open** button for a group's cover went with
    stories the same day.
  - **Where it was taken** (when known): a still map with a pin (MapLibre's snapshotter draws it once into a
    picture: a live map view in the viewer's window stopped the screen updating on Android), the place ("Haifa,
    Israel") and coordinates under it. A tap opens the [map](explore.md) on that spot, close up.
  - **The original** (both platforms; iOS since Oct 21, 2026): where it is
    ("On this phone and on your server", "Only on this phone", "Downloaded to this phone, and on your server", "Only
    on your server", or the upload state with % and a bar), then file name, size, dimensions, length for videos,
    type, and where it came from (Camera, WhatsApp, …). For a photo only on the server: **Preview on this phone**
    (the largest kept, "2560 × 1920 · 635 kB", or "None yet"). Then **Mark private**.
  - **Camera details** (photos) or **Video details** (videos), read from the original on this phone (the gallery's
    file, or the one the viewer downloaded; never from the server). A photo: make and model, the lens, and chips for
    ISO, 35 mm focal length, f-number, shutter and exposure bias; **All EXIF (n)** opens every tag, by section
    (Camera, Exposure, Image, Date, GPS, Other). A video: codec · 4K · fps, the profile, HDR transfer and audio codec,
    chips for frame rate, bitrate, HDR and audio; **All video details (n)** opens Video, Audio and File. Until the
    original is here: "Shown when the original has downloaded."
  - **Download original** and **Delete from this phone** were removed Oct 22, 2026: the viewer downloads originals
    by itself, and Settings' Free up space frees the phone. Originals an earlier version downloaded
    (`files/originals`) are still shown and played from there.
- **Photos only on the server:** the best image already on the phone shows at once (the largest decoded in memory,
  else the largest preview kept), while the original downloads into the preview cache (at most 2560 px decoded) and
  fades in. While it **downloads** (asked for Oct 21, 2026, in place of a spinner), a small **cloud** at the top
  left fills from the bottom with the download's progress (`ServerPreviews.originalProgress`, whichever screen
  started it); it shows only after 300 ms, so a quick download never flashes it, and fades when done. An original
  already in the cache is just decoded, with no indicator.
- **Videos only on the server:** the poster with Play, like any video; Play streams the original from the server
  (`GET /media/{sha256}` with Range, seeking fetches from there), and what was fetched stays on the phone for next
  time, under the Storage on this phone limit. Android: ExoPlayer through Media3's cache (`ServerVideos.kt`). iOS
  (Oct 21, 2026): `AVPlayer` reads `journal-media://<sha256>` through a resource loader that answers from
  `Caches/videos` and fetches what is missing with the device's certificate (`ServerVideos.swift`). Not registered:
  "This video can only be played once the phone is registered with your server."; can't reach the server: "The
  video can't be played right now. Is the server reachable?" over the poster, with Play to try again. The info
  panel's Kept on this phone adds "Video · 12 MB of 25 MB cached".

## Platform notes

- **iOS zoom** (`ViewerZoom.swift`): UIKit's `UIScrollView` zooms the photo (pinching, panning and bouncing are its
  own), with the same steps, snapping, minimap, remembered zoom and glide as Android, from the same note field
  (`StoriesController.setZoom`). A cropped photo's crop is where it opens within the same zoomable view, so zooming
  out of a crop and back is one move; at that framing a pan swipes to the next photo. Once zoomed in, the true
  original is decoded whole (up to 48 MP, 16,384 px a side, which iOS draws directly), so there is no detail
  decoding in parts as on Android. While zoomed, a swipe down doesn't close the viewer. Closing while zoomed uses
  iOS's zoom transition, which shrinks the screen as it shows into the tile. The status bar and home indicator are
  hidden while the viewer is open. A server photo's download shows the same cloud filling with progress
  (`IosServerFiles.observeOriginalProgress`), and none when the original is here already (`hasOriginal`).
- **iOS:** file name, size and type come with the library (`IosMediaLibrary`, from each asset's PHAssetResource:
  the edit's render for an edited photo, under the original's name with the render's extension, e.g.
  "IMG_1234.jpg"; a Live Photo is its photo). The size is PhotoKit's `fileSize` (`dataSize` from iOS 27), no file
  is read. Reading them takes a few ms a photo, so they are cached in `Caches/library/files.json` and, when more than
  300 photos are new (the first start), read in the background: until then a photo shows no file name or size.
  Photos stored only in iCloud are downloaded when viewed. Server photos are decoded with ImageIO, which reads every
  format the server makes (WebP, HEIF, AVIF, and JPEG XL from iOS 17); the shared `IosServerFiles` picks what to
  show, `ServerImages.swift` decodes and keeps it in memory. A cropped photo is loaded large enough for its crop to
  stay sharp (the screen size divided by the crop's), and the crop fills the whole screen, under the bars.
- **Android emulator:** 4K HEVC videos from recent phones may show no first frame and fail to play, because the
  emulator's decoder can't handle them. Real devices can.

## Code

- Android: [ViewerScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/ViewerScreen.kt), zoom in
  [ViewerZoom.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/ViewerZoom.kt), full detail in
  [ViewerDetail.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/ViewerDetail.kt)
- iOS: [MediaViewerView.swift](../../iosApp/iosApp/MediaViewerView.swift), the original's card in
  [ViewerOriginalCard.swift](../../iosApp/iosApp/ViewerOriginalCard.swift), loading in
  [PhotoLibraryImages.swift](../../iosApp/iosApp/PhotoLibraryImages.swift) and
  [ServerImages.swift](../../iosApp/iosApp/ServerImages.swift) (with the shared `IosServerFiles`), deletion only in
  [PhotoRemoval.swift](../../iosApp/iosApp/PhotoRemoval.swift)
