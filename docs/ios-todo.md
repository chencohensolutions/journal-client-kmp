# iOS: not built yet

Work done on the Windows machine is Android only (no Xcode there). Each entry says what Android has and what the
SwiftUI side needs, so it can be finished on the Mac. Remove an entry once iOS has it.

Built on iOS on the Mac (Oct 21, 2026): Moments, Explore, backup and its settings, and the where-the-original-is
badge, plus crop, server-only photos and videos, Storage on this phone, Private media and Free up space; then, the
same day on both platforms, writing with a title, paragraph and history, and the storyline in place of stories. On
Oct 22, 2026: the viewer's zoom (with the minimap, remembered zoom, download cloud and hidden system bars), Explore's
list of countries with a country's page, and the feed's spacing, day lines, chips and hidden-source band matched to
Android's.

Still missing on iOS:

- **Backup → "Only on these Wi-Fi networks"** — Android lists the networks backup may run on. iOS reads the
  network's name only with the Access WiFi Information entitlement plus location permission
  (`NEHotspotNetwork.fetchCurrent`), which needs the owner's Apple developer team and a real iPhone to check; the row
  is hidden on iOS until then. See [screens/settings.md](screens/settings.md).

- **Sound recordings** (Android Oct 25, 2026) — recordings, calls and voice messages from the server, hidden until
  shown. Shared logic is done (`LibraryState.sounds`, `FeedMoment.recording`, `FeedSection.sounds`/`parts`,
  `StoriesState.sounds`, `HiddenCollections.recordings/calls/voiceMessages`, `ServerLibrary.setCall`). iOS needs:
  the recording bar pinned under the title, the photos' "+32:10" offsets, folded calls and voice-message bubbles in
  `MomentsFeedViews.swift`; a recording's page (bars with photo marks, ±15 s, speed, the photo strip, Call and Edit
  contact) beside `MediaViewerView.swift`; the Sound recordings card in `HiddenView.swift`; one player streaming
  `/media/{sha256}` through the mTLS loader in `ServerVideos.swift`, pausing when the app leaves (no background audio).
  AVPlayer plays m4a, aac, mp3, wav and flac but **not Ogg/Opus** (WhatsApp voice messages, some recorders): those need
  another decoder or a server-side stream. Strings: the `audio_*` and `hidden_audio_*` keys in Android's
  `strings.xml`. See [screens/moments.md](screens/moments.md), [screens/viewer.md](screens/viewer.md).

- **Private settings** (Android Oct 25, 2026) — Settings › Private media is one row, **Private settings** (the
  Hidden collections screen renamed). **Show private media** moved into it, at the top (`PrivateMedia.show`, this
  phone's). The sound recordings and nudity are **Show** switches, off at first (on means shown; nudity's slider shows while it is off). Screenshot apps are pills
  (filled with a check while hidden) instead of a switch each. Mirror in `HiddenView.swift` and the Settings view;
  strings `settings_hidden_title`, `settings_hidden_row_body`, `hidden_audio_*`, `hidden_apps_pills`. See
  [screens/settings.md](screens/settings.md).

Planned, not built on either platform yet:

- **Hiding one photo or moment** (screen 4 of [plans/hide-whatsapp.md](plans/hide-whatsapp.md)); needs the server.
