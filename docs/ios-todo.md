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

- **Backup of calls and recordings from folders, Free up space by groups** (Android Oct 25, 2026) — Settings › Backup's
  one list of pills (photo sources and sound folders, on first, **+ Add a folder** last), the add dialog (what was
  found, What are they?, Back it up), **Keep on this phone: WAV / FLAC**, and Free up space's From with the same pills
  and the **Delete for good?** dialog. Shared logic is done (`SoundFolder`, `BackupSettings.soundFolders`/`keepOnPhone`,
  `BackupState.pills`, `FreeUpState.pills`, `FolderLook`, `BackupController.addSoundFolder`/`look`,
  `FreeUpController.toggleFolder`/`confirmSounds`, `SoundRemoval`). Doable on iOS (checked Oct 25, 2026): the Files
  folder picker (`UIDocumentPickerViewController(forOpeningContentTypes: [.folder])`) with a security-scoped bookmark
  keeps read and write access, also from a background task; FLAC encoding is built in (AudioToolbox
  `kAudioFormatFLAC`); deleting and writing through `FileManager`. But an iPhone keeps call recordings in Notes, and
  Voice Memos and WhatsApp's voice notes inside their apps, out of the picker's reach: only recordings saved into Files
  can be backed up. iOS needs `SoundFolders`, `MediaFiles.encodeFlac`/`replaceWithFlac` and a `SoundRemoval` in
  `IosMediaFiles.kt`/`IosJournal.kt`, and the screens in `BackupSettingsView.swift`, `BackupViewModel.swift`,
  `PhotoRemoval.swift` and the Free up space view. Strings: the `backup_*` folder keys and `free_up_sounds*` in
  Android's `strings.xml`. See [screens/settings.md](screens/settings.md).

- **Sound recordings in Ogg or Opus** (some WhatsApp voice messages and recorders): AVPlayer can't play them, so iOS
  says "The recording couldn’t be played" (sound recordings were built on iOS on Oct 26, 2026). They need another
  decoder or a stream the server converts.

- **The library database, what is left** (plan B; built on iOS on Oct 26, 2026: the database and its `-lsqlite3` link
  flag in `Config.xcconfig`, Moments read a page at a time with going to a date and "show in Moments" reading down to
  their row, Explore, its pages and the map from the database through `ExploreReads.swift`, the viewer, similar photos
  and crop keeping the items they were opened with and asking `StoriesController.want(ids:)`, and the debug made-up
  library from the launch arguments, `-madeUpLibrary 300000`). Still to do: PhotoKit is read whole on every load
  (about 11 s for the simulator's 122 photos with 300,000 made-up rows beside them); make `IosMediaLibrary` a
  `ChangingLibrary` with `PHPhotoLibrary.fetchPersistentChanges(since:)` and the token kept as the mark. Not checked
  yet on iOS: that `server/media.json` is read in once and deleted, that face avatars still get their photo's size,
  and that marking a photo private still finds its sha256 (both read from the database now).

Planned, not built on either platform yet:

- **Hiding one photo or moment** (screen 4 of [plans/hide-whatsapp.md](plans/hide-whatsapp.md)); needs the server.
