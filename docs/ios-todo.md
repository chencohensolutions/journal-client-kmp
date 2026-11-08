# iOS: not built yet

Work done on the Windows machine is Android only (no Xcode there). Each entry says what Android has and what the
SwiftUI side needs, so it can be finished on the Mac. Remove an entry once iOS has it.

Built on iOS on the Mac (Oct 21, 2026): Moments, Explore, backup and its settings, and the where-the-original-is
badge, plus crop, server-only photos and videos, Storage on this phone, Private media and Free up space; then, the
same day on both platforms, writing with a title, paragraph and history, and the storyline in place of stories. On
Oct 22, 2026: the viewer's zoom (with the minimap, remembered zoom, download cloud and hidden system bars), Explore's
list of countries with a country's page, and the feed's spacing, day lines, chips and hidden-source band matched to
Android's. On Oct 26, 2026: the library database (plan B phases 1–6), Private settings, sound recordings (Moments and
their page), and backup of calls and recordings from folders with Free up space by groups. On Oct 29, 2026: Backup › Copies on
other disks, Share JPEG XL as JPEG (the JPEG made again by the server, `GET /media/<the JPEG's sha256>`), Keep on
this phone as a card of switches, the sound kinds' names and Settings › Sound folders, the sources rework (Sent to
you, Imported in the Showing sheets and the Moments bar, the locks of what Private settings hides, search's "12 more
in sources you hide", a photo's "Saved in" and Camera), Show in the app as a status page, Private settings by kind,
Your cameras, Settings › Advanced › Loading Moments, Quick image while scrolling, the note in the feed's long-press
menu, and PhotoKit read by its persistent changes (`IosMediaLibrary` is a `ChangingLibrary`). Later that day: Ogg Opus
recordings decoded on the phone (`OggOpus.swift`: the Ogg pages read here, the Opus packets decoded by AudioToolbox,
checked against ffmpeg's decode on the Mac) and Backup's "Only on these Wi-Fi networks" (`WifiNetworks.swift`; the Access
WiFi Information entitlement is opt-in, `WIFI_ENTITLEMENTS=iosApp/WifiInfo.entitlements` in Local.xcconfig, as the
owner's team must have it).

Still missing on iOS:

- **Settings › Models on this phone** (Android Nov 8, 2026; [settings.md](screens/settings.md#models-on-this-phone),
  round `ux/settings/2026-11-08`). Shared logic is done: `PhoneModels` (`local/PhoneModels.kt`) with `state` /
  `currentState` / `observe` (`PhoneModelsState`: `models` as `PhoneModelRow`s with `kind`, `status`, `bytes`,
  `downloadedBytes`, `downloadingBytes`, `withBytes`; `wifiOnly`, `offline`, `freeBytes`, `here`, `missing`,
  `missingBytes`, `keptBytes`) and `refresh()`, `download(kind, force)`, `pause(kind)`, `delete(kind)`,
  `downloadAll()`, `setWifiOnly(on)`, `close()`. On iOS the photo model doesn't exist (Vision's feature print is
  built in), so build it as `PhoneModels(photos = null, indexes = localIndexes, searchOffered = <serverInfo.localIndexes as a StateFlow>)`:
  the page lists Search by words alone. iOS needs: an `IosJournal` factory for that, a `ModelsView` mirroring
  Android's `ModelsScreen.kt` (intro, the offline line, a row per model with its status and one button, Download
  all, Only on Wi-Fi, the space line), its row in Settings' Search card (`ModelsRow`: "1 of 2 downloaded" /
  "Downloading…", shown when `models` isn't empty), and the `models_*` strings (English and Hebrew in Android's
  `strings.xml`) in `Localizable.xcstrings`. Left to decide: whether a page with one model is worth a row on iOS, or
  Search by words' model is enough in On device search there.

- **Certificate renewal** (Android Oct 30, 2026; [design.md](design.md), "Certificate renewal"). Shared logic is done:
  `CertificateRenewal` (`enrollment/CertificateRenewal.kt`) with `renewIfDue(onDone)`, `expiresAt()` and `close()`.
  iOS needs: an `IosJournal` factory like Android's `createCertificateRenewal` (in `AndroidJournal.kt`: settings, the
  key store, `HttpRenewApi(serverHttp)`, `CertificateRenewal.connectionTest`, `journal::useRenewedIdentity`), a call
  to `renewIfDue` at app start (Android: `JournalViewModel`'s `init`), and `IosDeviceKeyStore.certificateNotAfter`
  (the Keychain certificate's notValidAfter) for iPhones enrolled before Oct 30, 2026, which don't have the end in
  settings. No screen and no strings.

- **Copies on a friend's server** (Android Oct 29, 2026; [screens/settings.md](screens/settings.md), Backup). iOS
  has the Copies page; what it lacks: `DataCopy.onPeer` (name the card with
  `copies_on_peer`), `encrypted` (a lock, `copies_encrypted` for VoiceOver), `CopyProblem.PEER_UNREACHABLE` / `QUOTA_FULL`
  (`copies_peer_unreachable`, `copies_quota_full`), `freeBytes` read with `copies_quota_left` when `onPeer`, and
  `DataCopy.canCopyNow` for whether Copy now shows.

- **JPEG photos packed as JPEG XL on the phone** (Android Oct 28, 2026; see [design.md](design.md), "JPEG photos are
  kept as JPEG XL"). `IosMediaFiles.encodeJxl` needs libjxl (an xcframework), so iPhones send the JPEG and the server
  packs it. **Photos as JPEG XL** in Backup's Keep on this phone card isn't offered: it would put the JPEG XL in the
  photo library in place of the JPEG, a PhotoKit write (PhotoSafetyTest), which needs the owner's approval first.

- **Quick image while scrolling, while the list moves fast**: Android shows only the quick image while a list moves
  fast (`Thumbnails.kt`); iOS shows it first and asks for the sharp one once a tile has stayed 0.3 s, so a fling still
  asks the server for the sharp tiles it settles on.

- **Not checked on iOS yet** (no server with these on the simulator on Oct 29, 2026): an Ogg Opus voice message played
  in the app (the decoder was checked on the Mac only); "Only on these Wi-Fi networks" on a real iPhone (the simulator
  has no Wi-Fi name to read); Copies on other disks, Your
  cameras, Sound folders and Private settings by kind against a server that has them; Share JPEG XL as JPEG with a JPEG
  XL photo; and, from the library database (Oct 26, 2026), that `server/media.json` is read in once and deleted, that
  face avatars still get their photo's size, and that marking a photo private still finds its sha256.

Planned, not built on either platform yet:

- **Hiding one photo or moment** (screen 4 of [plans/hide-whatsapp.md](plans/hide-whatsapp.md)); needs the server.
