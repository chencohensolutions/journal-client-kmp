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
menu, and PhotoKit read by its persistent changes (`IosMediaLibrary` is a `ChangingLibrary`).

Still missing on iOS:

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

- **Backup → "Only on these Wi-Fi networks"** — Android lists the networks backup may run on. iOS reads the
  network's name only with the Access WiFi Information entitlement plus location permission
  (`NEHotspotNetwork.fetchCurrent`), which needs the owner's Apple developer team and a real iPhone to check; the row
  is hidden on iOS until then. See [screens/settings.md](screens/settings.md).

- **Sound recordings in Ogg or Opus** (some WhatsApp voice messages and recorders): AVPlayer can't play them, so iOS
  says "The recording couldn’t be played" (sound recordings were built on iOS on Oct 26, 2026). They need another
  decoder or a stream the server converts.

- **Quick image while scrolling, while the list moves fast**: Android shows only the quick image while a list moves
  fast (`Thumbnails.kt`); iOS shows it first and asks for the sharp one once a tile has stayed 0.3 s, so a fling still
  asks the server for the sharp tiles it settles on.

- **Not checked on iOS yet** (no server with these on the simulator on Oct 29, 2026): Copies on other disks, Your
  cameras, Sound folders and Private settings by kind against a server that has them; Share JPEG XL as JPEG with a JPEG
  XL photo; and, from the library database (Oct 26, 2026), that `server/media.json` is read in once and deleted, that
  face avatars still get their photo's size, and that marking a photo private still finds its sha256.

Planned, not built on either platform yet:

- **Hiding one photo or moment** (screen 4 of [plans/hide-whatsapp.md](plans/hide-whatsapp.md)); needs the server.
