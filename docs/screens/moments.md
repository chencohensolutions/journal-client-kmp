# Moments (feed)

The **Moments** tab of the [Journal](journal-home.md) (called Unsorted until Oct 19, 2026): **every photo and video**,
also those tagged or written about, read like a story that arranged itself. Nothing is saved for it: the feed is
worked out again from the library each time. On Android and, since Oct 21, 2026, on iOS (see
[iOS](#ios) for what differs).

## Layout

- **Filter bar** under the title (Oct 24, 2026, option h2 of the UI lab, when each folder and app became a source and
  a chip per source ran far off the screen): one row that scrolls sideways. First **+N**, the sources hidden ("+2"; a
  plus alone while none is), then a filled chip with a ✓ and its count for each source **shown** that has photos, the
  most photos first ("✓ Camera 1,240", "✓ Petcube 5"; a folder or app by its name with a folder icon). Tapping a chip
  hides its source and it leaves the row. **+N** opens the **Showing** sheet: every source as a pill with its count,
  the most photos first, filled when shown, tapped to show or hide (also a hidden folder with no photos left). Shared:
  `MomentsFeed.bar`, `hiddenCount`, `sheet`. Everything but WhatsApp and Screenshots is shown at first (those two off
  for everyone since Oct 21, 2026), and a folder seen for the first time is shown. Settings › Show in the app › **Filter bar in
  Moments** turns the bar off (Oct 24, 2026). It is the same choice as [Settings › Show in the app](settings.md), so it hides a source
  everywhere (`ShownSources`, kept as `hidden_sources`; an older `shown_sources` or `feed_sources` keeps its choice).
- **What's hidden (Oct 21, 2026):** with **Say what's hidden** on, a row's divider ends with a chip per hidden
  source, "+6 WhatsApp", "+3 Screenshots", for the hidden photos of its days (each counted once, on the row of its
  day nearest in time). Days with no shown photos say nothing. Tapping a chip shows those photos on that row only, in
  a tinted band after its moments: "WhatsApp · 6 · 14:02 – 17:30", a grid of three and **Always show this day** (its
  **Add to a story** went with stories on Oct 21, 2026). The chip turns into "Hide WhatsApp", and a note "Showing 6 from
  WhatsApp on Oct 4 · Hide" shows for 6 seconds. A band's photo opens the viewer on the band. The setting doesn't
  change and nothing is saved: bands are forgotten when the app closes. **Always show this day** saves those days as
  shown for that source (`always_shown_days`); the photos join the row as moments.
- **A timeline, newest first all the way down (asked Oct 20, 2026):** rows, the moments in a row and the photos in a
  moment are all newest first. Only a near-duplicate set keeps its photos oldest first, to swipe through.
- **Rows**: the grouping setting splits them, by day or by breaks between photos, as before. Each row starts with a
  divider: a line with its heading on a pill in the middle ("Today · Tue, Oct 20", "Sat, Oct 4", or "Oct 3 – Oct 4" for a row over two days;
  never times), and under it the towns visited in order and a count ("Tel Aviv → Jaffa · 21 photos").
  There is no **+ Story** on rows any more (removed Oct 20, 2026).
- **Moments** within a row, each with a label and then its photos:
  - Camera: the place in bold ("📍 Jaffa Port") only where it differs from the camera moment above it in the row (the
    first one always shows it), then the time ("18:10 – 19:05"). Only the time until the place is known.
  - Other sources: "WhatsApp · 6 · 14:02 – 17:30": one moment per source per row.
- **Photos** are edge to edge and full width, at their own shape (never cropped to squares), or at their Moments
  [crop](crop.md). A video shows its length.
- **Writing** (Oct 21, 2026): a photo's title is above it (semibold), its paragraph under it in serif, up to 4 lines
  and then **more**. In a near-duplicate set, the writing of the photo shown.
- **Long press** a photo for **Write about it** (or **Edit what you wrote**), **Crop**, **Tag**, **Select**, in a
  set **Take out of this set**, and for a photo with writing **Remove from storyline** (or, once removed, **Add to
  storyline**; see [journal-home.md](journal-home.md)). **Add to story** went with stories on Oct 21, 2026.
- **Merging (asked Oct 19, 2026):** **Select** starts picking: tapping a photo or set picks it (a check at its
  corner), back or **Cancel** stops. **Merge**, with two or more picked, makes them one set you swipe, shown where its
  oldest photo is, wherever the others were. **Take out of this set** removes a photo from a set (made by hand or by
  itself) and it isn't put in one by itself again. A [cropped](crop.md) photo is never put in a set by itself either.
  Saved in each photo's note file (`"set"`, `"apart"`). A set never shows in the storyline: only the photo of it you
  wrote on does.
- Each photo has the **backup badge** in its bottom-right corner, as on the grids (see [README.md](README.md)): a phone
  when it is on this phone and on the server, a crossed-out cloud while it isn't uploaded, nothing when it is only on
  the server; a set shows the badge of the photo it is turned to. A playing video shows its sound button there instead.
- **Near-duplicates** are one photo at the first one's shape. Swipe sideways for the others; a "2 / 4" badge and
  dots show where you are, with no peek of the next one.
  The photos beside the one shown are loaded ahead, so a swipe either way shows a ready photo; for photos only on the
  server, only those two neighbours' previews are downloaded (never the rest of the set) until a swipe brings more
  next to the one shown.
- A set **opens on the photo it was last turned to**, also after scrolling away or restarting the app (kept on this
  device in settings, `feed_page|<first photo id>`; turning back to the first forgets it). A set never turned opens on
  its best-looking photo (the server's aesthetic score, kept by On device search; Oct 24, 2026), else the first
  (`StoriesState.frontOf`).
- There are no trip cards any more: "Looks like a trip" with **Save as story** went with stories on Oct 21, 2026.

**Videos play in the feed**, muted and looping. Only one plays: of the videos at least 60% in view between the top bar
and the bottom controls, the one closest to the middle. It stops when it scrolls away, the tab changes or the app
leaves the screen. Its photo stays until the first frame is drawn, and a video that can't be played stays a photo.
A speaker button at its corner turns the sound on. That's one setting for the feed, so the next video plays with sound
too, until it is turned off or the app is closed. A muted video doesn't take audio focus, so music from another app
keeps playing. With sound it takes focus while it plays, and mutes itself when another app takes it back. Built on
the platform `MediaPlayer` and a `TextureView` ([FeedVideo.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/FeedVideo.kt)).

**Sound recordings** (Android Oct 25, 2026; iOS Oct 26, 2026): the server's
recordings, phone calls and WhatsApp voice messages, once the user shows them in
[Hidden collections](settings.md) (all three are hidden until then). They are never photos: not counted, not in All
photos, Explore, the Storyline or search; Moments and their [page](viewer.md#a-sound-recordings-page) only.
- **A recording** is a slim bar where its moment starts, in place of the moment's label (chosen in the UI lab as
  "header strip"): play, "Recording · Jaffa Port" (the place of the photos taken during it), its times "18:10 –
  19:55 · 1:45:12" (while it plays: "34:12 of 1:45:12 · at 18:44"), and a line for how far it has played. Under it,
  the camera photos taken while it ran, each with "+32:10" (how far into the recording it was taken). While those
  photos scroll by, the bar stays **pinned under the title** (an overlay in `JournalHomeScreen`, since the list's top
  padding sits under the title bar). Tapping the bar opens the recording's page.
- **Long press** on a photo taken during a recording plays the recording from when the photo was taken (instead of
  the photo's menu).
- **Calls** one after another (less than 2 hours apart) are one card, "📞 4 calls · 09:10 – 11:40", folded until
  tapped; then each call with who (the contact, or the number left to right), when, how long and play. A single call
  shows open.
- **Voice messages** are a bubble with play, bars and length; they show while WhatsApp does.
- **Playing:** nothing plays until play is tapped. One recording at a time, through the app's player
  ([AudioPlayer.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/AudioPlayer.kt), Media3
  streaming `/media/{sha256}` with Range). It takes audio focus, so a feed video playing aloud falls silent. It
  pauses when the app leaves the screen: no background playing and no lock-screen controls (the owner's choice).
  Calls and voice messages up to 10 minutes are kept in the video cache once played; longer recordings aren't, so one
  can't push out every kept preview. On iOS the player is AVPlayer reading through the videos' mTLS loader
  ([AudioPlayer.swift](../../iosApp/iosApp/AudioPlayer.swift)); it plays m4a, aac, mp3, wav and flac but not Ogg or
  Opus (some WhatsApp voice messages and recorders): those say "The recording couldn’t be played".

Tapping a photo opens the [viewer](viewer.md) on its row's photos, in the order shown. When the viewer closes, the
feed scrolls to that photo, and a near-duplicate set turns to it.

## Going to a date (Oct 24, 2026)

A **month pill** above the tabs, "‹ 24 October 2026 ›", says which day the top of the feed is in (with its month and
year, since Oct 24, 2026; just the month while it goes to another month, and in the ruler), and goes to another year,
month or day with one finger. Android and iOS.

- **The arrows** go to the month with photos before or after (months without photos are skipped). Held, they keep
  going: after 0.4 s, then every 0.25 s, faster and faster. At the oldest or newest month that arrow is dimmed.
- **Swiping along the month's name** steps a month every 22 points, toward the newer end being newer (right, or left in
  Hebrew).
- **Tapping the name** grows the pill into the **ruler**, still above the tabs:
  - the month and its counts ("88 photos · 14 days") and a ⌄ to fold it;
  - the month's **days** in a line, lit where there are photos: tap one or slide along it, and the feed goes to the lit
    day nearest the finger;
  - a **dial of months**, oldest to newest, each a bar as tall as its photos (square root), years under January, a
    needle in the middle. Drag it and the feed follows; a flick keeps it turning, and it settles on a month.
- **Folding it:** a touch anywhere outside the ruler folds it and does nothing else (no photo opens by accident), as
  do ⌄, back (Android), changing tabs, search and picking photos. The backup summary hides while it is open.
- **Where it lands:** a month lands on its newest day with photos, the top of the month in a feed read newest first; a
  day lands on its row's divider, just under the sources bar. A row over two days (grouping by gaps) is landed on for
  either day.
- A light tick per month or day, a firmer one when the year changes.
- It hides while picking photos, while a "Showing 6 from WhatsApp" note shows, and when the feed is empty.
- Nothing is saved: the months and days are worked out from the feed shown (`MomentsFeed.timeline`, a
  `FeedTimeline` in [FeedTimeline.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/FeedTimeline.kt),
  tested in `FeedTimelineTest`), which also does the stepping, landing, nearest day and month, and the year ticks.
- The UI lab's **Moments over seven years** (`moments-years`) has a feed long enough to try it. Other designs for it
  (an edge scrubber, a pill at the top with its ruler, a hold-and-slide date pad) are suggestions in the iOS lab
  (`moments`).

## iOS

The same feed, from the same shared `StoriesState.feed`, with these differences:

- **Long press** opens the system context menu: the photo lifted as its preview and the day and time as the menu's
  heading, then **Write about it** (or **Edit what you wrote**), **Crop** (photos only), **Tag**, **Select**, in a set
  **Take out of this set**, and **Remove from storyline** or **Add to storyline** for a photo with writing. **Crop** opens the [crop screen](crop.md) on the photo's
  Moments crop.
- While picking, the bottom bar is **Cancel · N selected · Merge**; changing tabs stops picking.
- **Sets** are a horizontal paging scroll view; the photos beside the one shown are cached ahead
  (`PHCachingImageManager`). There are no photos only on the server on iOS yet, so nothing is downloaded.
- **Videos** play with `AVQueuePlayer` and `AVPlayerLooper` in an `AVPlayerLayer`. Muted, the audio session is
  `ambient` with `mixWithOthers`, so music from another app keeps playing; with sound it is `playback`, and an
  interruption (another app taking the audio) mutes the feed again. The video that plays is worked out from where each
  video is on screen (the part between the top bar and the bottom controls), with the same 60% and settle delay. It
  stops on the Storyline tab, while the viewer or another screen is open, and in the background. A video only on the
  server plays too (Oct 21, 2026), streamed through the same video cache as the viewer's (`ServerVideos.swift`), as
  on Android; offline it stays its poster.
- A new feed (places and fingerprints arriving) shows at once, also mid-scroll; Android waits for the scroll to stop.
- The backup badge is the iOS one (see [README.md](README.md)).

## Code

- Sound recordings: [MomentsAudioViews.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/MomentsAudioViews.kt)
  (the bar), [AudioParts.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/AudioParts.kt)
  (calls, voice messages, play button, bars), [AudioPlayer.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/AudioPlayer.kt);
  shared `SoundRecording` and `LibraryState.sounds` (kept apart from the photos), tested in `SoundFeedTest` and
  `SoundLibraryTest`. iOS: [AudioViews.swift](../../iosApp/iosApp/AudioViews.swift) (the bar, calls, voice messages,
  the page), [AudioPlayer.swift](../../iosApp/iosApp/AudioPlayer.swift); the bar is pinned in `JournalHomeView.swift`.
- Android: [MomentsFeedViews.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/MomentsFeedViews.kt),
  [FeedVideo.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/FeedVideo.kt), the
  screen's state and menus in [JournalHomeScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/JournalHomeScreen.kt).
- iOS: [MomentsFeedViews.swift](../../iosApp/iosApp/MomentsFeedViews.swift) (the rows, made to be reused for any
  feed, such as `StoriesState.feedOf(items)`), [FeedVideo.swift](../../iosApp/iosApp/FeedVideo.swift), the screen's
  state, menus and sheets in [JournalHomeView.swift](../../iosApp/iosApp/JournalHomeView.swift).
- Going to a date: [FeedTimelineViews.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/FeedTimelineViews.kt)
  and [FeedTimelineViews.swift](../../iosApp/iosApp/FeedTimelineViews.swift), on the shared `FeedTimeline`.

## How it is grouped

Shared logic in [MomentsFeed.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/MomentsFeed.kt)
(`buildFeed`, tested in `FeedTest`):

- **Source** (`MediaSource`: Camera, WhatsApp, Screenshots, a folder by its name, or Other; asked for Oct 24, 2026,
  when every DCIM folder was Camera and Other was too broad). On Android it comes from the folder
  (`sourceOfFolder`): a path containing "screenshot" or "screen record" is Screenshots; one containing "WhatsApp" is
  WhatsApp; DCIM/Camera and files right in DCIM are Camera; any other folder inside DCIM, Pictures, Movies, Download
  (and Android's other shared folders) is a source by its name, so DCIM/Flowers is "Flowers" and Movies/Petcube is
  "Petcube", and the same name in two places (Pictures/Telegram, Download/Telegram) is one source. A file loose in
  Pictures or Download, or in an app's folder under Android/, is the source of the app that saved it (MediaStore's
  `OWNER_PACKAGE_NAME`, shown by the name under its icon: "ChatGPT", "Chrome"; the manifest's `<queries>` lets the app
  see apps with an icon), unless that app only copied it (Smart Switch from an old phone, the files app): then it is
  Other. iOS has no folders (`sourceOfAsset`): PhotoKit marks screenshots and screen recordings; photos in the album
  "WhatsApp" (WhatsApp saves there) are WhatsApp; files the camera can write (HEIC, JPEG, DNG, MOV) in the user's
  library are Camera, so a JPEG saved from Safari counts as Camera, whatever albums they are filed in. The rest
  (synced from a computer, in shared albums, PNG, GIF, WebP, MP4 saved from apps) is the source of its album when it
  is in exactly one (often one an app made, like "Petcube"), else Other. Photos only on the server keep the source
  the phone told the server when it uploaded them (folders since Oct 24, 2026; earlier DCIM folders are Camera).
- **Camera moments** split after 2 hours without a photo, or after 20 minutes when the next photo is more than 1 km
  from the last located one. A walk stays one moment; a drive to another town doesn't. Photos without GPS stay with
  their neighbors.
- **Near-duplicates:** a photo joins the set before it when it looks like the set's first photo or is from the same
  camera burst. Photos not fingerprinted yet join when taken within 10 seconds (`BURST_GAP_MILLIS`).
  - Two photos look alike when their **dHash** (64-bit difference hash of a 9×8 grayscale copy) differs in at most 10
    bits, when the cosine similarity of the platform's image embedding reaches the **How alike** threshold in
    [Settings](settings.md), or when they share the camera's burst id (iOS `burstIdentifier`; Samsung
    `20261008_140901_092.jpg` names).
  - **Zoom-aware:** a camera's zoom is a center crop, so each photo is also fingerprinted at its center zoomed in
    (embeddings at 1.4× and 2×, dHashes at nine steps from 1.15× to 3×); each one's whole frame is compared with the
    other's zoomed centers, so either can be the zoomed shot.
  - Embeddings: **Vision feature print** on iOS (threshold 0.80, on the Neural Engine), **MediaPipe image embedder**
    with **MobileNet-V3 large** on Android (Apache 2.0, 10.9 MB, bundled in `sharedLogic/src/androidMain/assets`; on
    the CPU, about 10 ms a run). Read from a 512 px copy, one photo at a time in the background, the feed's newest
    first. Fingerprints are cached per device and model in the caches folder (`similarity/fingerprints.json`), never
    synced: the two platforms' embeddings can't be compared. Videos aren't fingerprinted. Code in
    [similarity/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/similarity/).
- **Videos from one place** (within 150 m, in one moment) are one set you swipe, where the first of them is, even
  with photos between (asked Oct 19, 2026). A video without a location stands alone. In a set, the video turned to
  plays like any other in the feed.
- **Sound recordings** (Oct 25, 2026) join the row their start is in: a recording takes the camera photos taken
  between its start and end into one moment (`FeedMoment.recording`), however far apart they are, so the 2-hour split
  doesn't cut it; by gaps, its whole length counts as photos, so a row isn't cut through it. Calls less than 2 hours
  apart are one row (`FeedSound.Calls`); a voice message is `FeedSound.Voice`. A row of only sounds is a row. They are
  placed among the moments by time (`FeedSection.parts`).
- **Places:** the moment's label is the name most of its photos share: the neighborhood, else the street, else the
  town. A row lists towns.
- **Home** is the 10 km square where most located photos were taken (with 20 or more); [Explore](explore.md) uses it.
  Trips (days in a row far from home, offered as a story) were removed with stories on Oct 21, 2026.

## Where locations come from

`PhotoPlaces` ([places/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/places/)) works in the
background, and caches results in `cache/places/places.json`. Like the fingerprints, the
cache can be deleted at any time.

- **Android:** MediaStore hides GPS unless the app holds `ACCESS_MEDIA_LOCATION` and opens the original file
  (`MediaStore.setRequireOriginal`). Both are read-only. The owner allowed them on Oct 18, 2026, and
  `PhotoSafetyTest` permits them only in the files that need them. The permission is asked for with photo access,
  and once on launch for users who allowed photos before. Place names come from `Geocoder` (needs a network
  connection; names it can't get are asked again next launch). Photos are read from EXIF, videos from their metadata
  (`MediaMetadataRetriever`, the ISO 6709 location cameras write); until Oct 19, 2026 videos weren't read, so they
  took the place of the photos around them.
- **iOS:** PhotoKit gives each photo's and video's location with the library; names come from `CLGeocoder`, about one a second.
- Names are looked up once per ~500 m square: the area, the town and the country.
