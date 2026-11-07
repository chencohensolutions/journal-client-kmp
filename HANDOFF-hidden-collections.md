# Handoff: Hidden collections (finish on the Mac)

For a Claude Code session on the owner's Mac. The server side and the Android screen are done, and their tests pass
(Windows, Oct 22, 2026). Nothing has run on a real phone yet. What's left: build iOS, move the screen's logic into
shared code, build the iOS screen, and try it end to end. Delete this file once Hidden collections works on both
apps.

## What it is

The owner keeps thousands of screenshots they don't want to see, mostly from dating apps (Bumble about 1,458,
OkCupid 476, Tinder 108), and doesn't want them deleted.

**Settings → Private media → Hidden collections** hides them on every device:
- **WhatsApp**: one switch for every WhatsApp photo and video.
- **Screenshots**: a switch for "Without an app name", a "Find an app" filter, then a switch for each app with its
  count, most files first.
- Switching a collection off shows its files again.

Decisions the owner made (don't reopen them):
- **Per user**, so the choice applies on every device. It is stored on the server in `<data_folder>/settings.json`.
- **The server does the hiding**, and the app isn't told a file is hidden. A hidden file comes in the change feed as
  deleted. It isn't counted in `storageUsage` or found by `searchMedia`, and `/media` and its preview answer 404.
  `missingMedia`, `startUpload` and `/verify` still see it, so a file is never uploaded again.
- **Phone copies:** the app deletes only its own cached copies of a file the server drops (previews, downloaded
  originals, kept originals, streamed videos). It never touches the phone's gallery. A hidden screenshot still in the
  gallery can still show; that gap is accepted.
- **Lock:** the screen asks for the phone's lock (fingerprint, face or PIN, whatever the system uses) every time it
  opens and again after the app was in the background. Being unlocked is never saved. A phone without a screen lock
  can't open it.
- **Android first**; iOS follows.

## What exists

**Server** (journal-server-go, commit `874ce84`). Read its `docs/media.md`, sections "Hidden collections" and
"Fixing sources".
- `facts.app` in each sidecar is the app named in a screenshot's file name (`Screenshot_20240301_030715_Bumble.jpg` →
  `Bumble`), read by `media.ScreenshotApp`. It is filled for new uploads and imports, and exposed as `Media.app`.
- `media/hidden.go`: the `Hidden` setting, `media.View` / `Store.Hides` (one check for every path a device reads
  through), and `Store.Collections()` for the counts. The feed cursor carries a key of the setting, so a change makes
  every device read the whole feed again.
- GraphQL (`graph/schema/hidden.graphql`): `hiddenCollections` and `setHiddenCollections(input:)`.

**Client, shared** (this repo):
- `server/HiddenCollections.kt` is the model, with `with(collection, hidden)`; it matches by identity, because a
  screenshot app can be called "WhatsApp".
- `ServerLibrary.hiddenCollections()` / `setHiddenCollections(choice)` call the server. The second one syncs after
  saving.
- `ServerLibrary(removed = …)` is called with the sha256s a sync dropped. It is wired in `AndroidJournal.kt` and
  `IosJournal.kt` to `PhoneStorage.remove` (previews, plus videos through `attachVideoRemoval`) and
  `KeptOriginals.delete`.
- The operations are in `graphql/Hidden.graphql`. They use fragments, so the server's answers carry `__typename`, and
  fake answers in tests need it too.
- Tests: `HiddenCollectionsTest`, and `PhoneStorageTest.removingAFileDeletesOnlyWhatIsKeptOfIt`.

**Client, Android:**
- `ui/library/HiddenScreen.kt` is the screen. Its state (loading, unreachable, saving, going back on a failed save,
  the filter) is still in Compose: see step 2.
- `ui/library/PhoneLock.kt` (`PhoneLocked`) uses the framework `BiometricPrompt` with
  `BIOMETRIC_WEAK or DEVICE_CREDENTIAL`, and `KeyguardManager.isDeviceSecure`. It needs no new dependency, and works
  because `MainActivity` is a `ComponentActivity`.
- The entry point: a row in `PrivateMediaCard` (`SettingsScreen.kt`), `Route.Hidden` ("hidden"), and the entry in
  `LibraryApp.kt`. `ServerVideos.kt` attaches the video removal.
- Strings are in `values/strings.xml` and `values-iw/strings.xml` (`settings_hidden_*`, `hidden_*`, `phone_lock_*`).
- Docs: `docs/screens/settings.md` (Private media, Hidden collections, Behavior, Code) and `docs/design.md`.

## Done on the Mac (Oct 22, 2026)

Steps 1 to 3 are built, not committed yet. The iOS app builds, and the shared tests and the Android build pass.
`HiddenCollectionsController` and `ScreenLock` are shared, with tests in `HiddenCollectionsTest`. The Android
`HiddenScreen` now only draws the state. iOS has `HiddenView.swift`, a row in Settings → Private media, a UI lab
screen called "hidden", Hebrew strings, `NSFaceIDUsageDescription`, and `ServerVideoCache.remove`, which is attached
at start. Tried on the iPhone 18 Pro simulator against the local test server: the Face ID lock, loading, saving to
`settings.json`, "Not changed" while the server was off, and locking again after going to the background. That
server has no WhatsApp files or screenshots, so the per-app list and the library dropping files were not seen yet.
**Left: steps 4 and 5** (the owner's data folder, and real phones).

## Steps

1. **Pull both repos and build iOS first.** The changes to `IosJournal.kt` (the `removed` wiring) were never compiled:
   Kotlin/Native for iOS doesn't build on Windows. Fix anything there before going on. Also run
   `./gradlew :sharedLogic:allTests :androidApp:assembleDebug`.

2. **Move the screen's logic into shared code**, so iOS reuses it:
   - Add `settings/HiddenCollectionsController.kt` (or `server/`), following `SettingsController` / `FreeUpController`:
     - a `StateFlow<HiddenCollectionsState>`, with `Loading`, `Unreachable`, and `Ready(collections, query,
       shownApps, saving, notChanged)`
     - `load()`, `retry()`, `set(collection, hidden)` (change right away, ignore taps while saving, go back when the
       save fails), and `setQuery(text)`
     - `observe()` / `currentState` for Swift, like `SettingsController`
   - Add a small shared `ScreenLock` state (`isUnlocked`, `unlocked()`, `wentToBackground()`). The prompt itself stays
     per platform.
   - Reduce `HiddenScreen.kt` to drawing the state.
   - Test the controller with the fake server in `HiddenCollectionsTest`.

3. **Build the iOS screen.** Commit `19df0e8` (ui lab) changed iOS Settings so screens draw from state plus an
   actions struct (`SettingsScreen`, `SettingsActions` in `iosApp/iosApp/SettingsView.swift`). Follow that pattern:
   - A **Hidden collections** row in `privateMediaSection`, with a `NavigationLink` like `freeUpSection`, opening a
     `HiddenView` / `HiddenScreen` over the shared controller.
   - The lock: `LAContext().evaluatePolicy(.deviceOwnerAuthentication, localizedReason:)`. When
     `canEvaluatePolicy(.deviceOwnerAuthentication)` is false, show the no-screen-lock text. Lock again on
     `scenePhase` `.background`.
   - Add `NSFaceIDUsageDescription` (in `InfoPlist.xcstrings` too, with Hebrew).
   - Add the strings to `Localizable.xcstrings` with Hebrew. Copy the wording from the Android strings above.
   - The video cache: the Swift `ServerVideos` cache (`Caches/videos/<sha256>`) should call
     `phoneStorage().attachVideoRemoval { sha in … }`, so a dropped file's video goes too.
   - Add the screen to the UI lab (`Lab/LabScreens.swift` / `LabData.kt`) with made-up collections, if that is how
     the other screens are reviewed now.
   - Update `docs/screens/settings.md` (it says "Android first") and `docs/design.md`.

4. **Run the backfill** on the machine that holds the data folder (the server repo). Without it, existing
   screenshots have no `app` yet, so the per-app list is almost empty.
   ```bash
   go run ./scripts/fix-sources --user hen --dry-run   # Bumble ~1,458, Microsoft Remote Desktop 842, OkCupid 476, …
   go run ./scripts/fix-sources --user hen
   ```
   This also sets `source=whatsapp` on about 1,011 files WhatsApp named; the owner knows. About 5,000 files then come
   again in each device's feed.

5. **Try it end to end** on a real phone, or on the emulator or simulator against a local server:
   - Open Hidden collections. It asks for the lock.
   - Hide Bumble. The library and "On your server" drop by about 1,458, search no longer finds them, and
     `previews/<sha[0..2]>/<sha>` for a Bumble file is gone.
   - A second device drops them after its next sync.
   - Show Bumble again, and they come back.
   - Turn the server off and flip a switch: it goes back, with "Not changed".

## Known costs and gaps

- Every change to the choice makes every device read the whole change feed again: about 95,000 files, in pages of
  2,000.
- On Android, a streamed video is deleted only if the video cache was opened in that app session; otherwise it waits
  for the cache to trim it.
- Hidden screenshots still in the phone's gallery can still show. "Show in the app → Screenshots" is off by default,
  which usually covers it.
