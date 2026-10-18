# Moments (feed)

The **Moments** tab of the [Journal](journal-home.md) (called Unsorted until Oct 19, 2026): **every photo and video**,
also those already in a story or tagged, read like a story that arranged itself. Nothing is saved for it. The feed is worked out again from the library each time, and only
**+ Story** or **Save as story** turns part of it into a real story. Android only; iOS still shows the earlier
rows of small photos.

## Layout

- **Filter bar** under the title: one toggle per source with its count of photos: **Camera**, **WhatsApp**,
  **Screenshots**, **Other** (downloads, other apps). On means filled, with a ✓. Any mix can be on; Camera and
  WhatsApp are on at first. A source with no photos is hidden unless it is on. The choice is kept
  (`feed_sources` in settings).
- **Rows**, newest first. The grouping setting splits them, by day or by breaks between photos, as before. Each row
  has a heading ("Today", "Sat, Oct 4", or with breaks "Today · 9:14 – 21:40"), the towns visited in order and a
  count ("Tel Aviv → Jaffa · 21 photos"), and **+ Story**.
- **Moments** within a row, newest first, each with a small label and then its photos, oldest first:
  - Camera: "📍 Jaffa Port · 18:10 – 19:05" (place · time; only the time until the place is known).
  - Other sources: "WhatsApp · 6 · 14:02 – 17:30": one moment per source per row.
- **Photos** are edge to edge and full width, at their own shape (never cropped to squares). A video shows its length.
- **Near-duplicates** are one photo at the first one's shape. Swipe sideways for the others; a "2 / 4" badge and
  dots show where you are, with no peek of the next one.
- **Trip card** ("Looks like a trip · Haifa · Oct 4 – 6 · 3 days · 128 photos") above the newest day of a trip, with
  **Save as story** (asks for a title, starting with the town) and **Not now** (never offered again for that trip).

**Videos play in the feed**, muted and looping. Only one plays: of the videos at least 60% in view between the top bar
and the bottom controls, the one closest to the middle. It stops when it scrolls away, the tab changes or the app
leaves the screen. Its photo stays until the first frame is drawn, and a video that can't be played stays a photo.
A speaker button at its corner turns the sound on. That's one setting for the feed, so the next video plays with sound
too, until it is turned off or the app is closed. A muted video doesn't take audio focus, so music from another app
keeps playing. With sound it takes focus while it plays, and mutes itself when another app takes it back. Built on
the platform `MediaPlayer` and a `TextureView` ([FeedVideo.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/FeedVideo.kt)).

Tapping a photo opens the [viewer](viewer.md) on its row's photos, in the order shown. When the viewer closes, the
feed scrolls to that photo, and a near-duplicate set turns to it.

## How it is grouped

Shared logic in [MomentsFeed.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/MomentsFeed.kt)
(`buildFeed`, tested in `FeedTest`):

- **Source** comes from the folder: `DCIM/` is Camera; a path containing "screenshot" or "screen record" is
  Screenshots; one containing "WhatsApp" is WhatsApp; everything else is Other. On iOS, PhotoKit marks screenshots;
  everything else counts as Camera for now.
- **Camera moments** split after 2 hours without a photo, or after 20 minutes when the next photo is more than 1 km
  from the last located one. A walk stays one moment; a drive to another town doesn't. Photos without GPS stay with
  their neighbors.
- **Near-duplicates:** a photo joins the set before it when it looks like the set's first photo (the same
  fingerprints and threshold as group suggestions, see [group.md](group.md)) or is from the same camera burst.
  Photos not fingerprinted yet join when taken within 10 seconds. Videos stand alone.
- **Places:** the moment's label is the name most of its photos share: the neighborhood, else the street, else the
  town. A row lists towns.
- **Trips:** home is the 10 km square where most located photos were taken (with 20 or more). Two or more days in a
  row with most located camera photos more than 50 km from home are a trip. A day without located photos in the
  middle doesn't break it.
- **+ Story / Save as story** make a story of all those photos (a photo can be in many stories), with each
  near-duplicate set already a group.
- A trip is offered only while some of its photos are in no story and untagged, so a saved trip isn't offered again.

## Where locations come from

`PhotoPlaces` ([places/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/places/)) works in the
background, unsorted photos first, and caches results in `cache/places/places.json`. Like the fingerprints, the
cache can be deleted at any time.

- **Android:** MediaStore hides GPS unless the app holds `ACCESS_MEDIA_LOCATION` and opens the original file
  (`MediaStore.setRequireOriginal`). Both are read-only. The owner allowed them on Oct 18, 2026, and
  `PhotoSafetyTest` permits them only in the files that need them. The permission is asked for with photo access,
  and once on launch for users who allowed photos before. Place names come from `Geocoder` (needs a network
  connection; names it can't get are asked again next launch). Photos are read from EXIF, videos from their metadata
  (`MediaMetadataRetriever`, the ISO 6709 location cameras write); until Oct 19, 2026 videos weren't read, so they
  took the place of the photos around them.
- **iOS:** PhotoKit gives each photo's and video's location with the library; names come from `CLGeocoder`, about one a second.
- Names are looked up once per ~500 m square.
