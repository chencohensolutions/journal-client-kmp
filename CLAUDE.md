# Journal client (Kotlin Multiplatform)

Android and iOS apps for a private photo journal served by `../journal-server-go`. **Read
[docs/design.md](docs/design.md) first**: what the product is, the decisions behind it, the data format, what is
built and what is next. Each screen is described in [docs/screens/](docs/screens/README.md); update those docs
whenever a screen changes.

## Layout

- `sharedLogic/` — all logic, shared. `commonMain`: controllers with a `StateFlow` state
  (`JournalController` connection, `EnrollmentController`, `LibraryController` photo library, `StoriesController`
  the photos' notes (writing with history, tags, crops, Moments sets), the storyline and the Moments feed,
  `SettingsController` the Settings screen, `BackupController`, `FreeUpController`). Stories were replaced by the
  storyline on Oct 21, 2026; `StoriesController`, `StoriesState` and the `stories/` package keep their names for now.
  The package is split by concern: `StoriesController.kt` (edits and the rebuild), `StoriesState.kt` (what is
  published and how it is built, the storyline among it), `PhotoNote.kt` (the note and its file store),
  `WritingDiff.kt` (what History highlights), `MomentsFeed.kt` and `Moment.kt` (the Moments tab), `Explore.kt`,
  `PhotoCrops.kt`, `TextSearch.kt`, `StoryIds.kt` (ids for Moments sets) and `OldStories.kt` (deletes the old
  stories folders on start). `androidMain` / `iosMain`:
  platform pieces (mTLS client, key store, MediaStore / PhotoKit readers) and the factories (`AndroidJournal.kt`,
  `IosJournal.kt`). The connection check is the `storageUsage` query (`checkConnection` in `JournalApi.kt`).
- `androidApp/` — Jetpack Compose UI; library screens in `ui/library/`, one `<Name>Screen.kt` per screen with its
  `<Name>Actions` class. `LibraryApp.kt` is the routing table, `LibraryRoutes.kt` the back stack and what the viewer
  shows, `LibraryActions.kt` the permission, system-prompt and server wiring the screens share, `Thumbnails.kt` the
  loading and caching of pictures, `JournalViewModel.kt` keeps the controllers alive. Strings in
  `res/values/strings.xml` (Hebrew in `values-iw/`).
- `iosApp/iosApp/` — SwiftUI UI. View models wrap the Kotlin controllers (`observe` + `currentState`). Strings in
  `Localizable.xcstrings`. The Xcode project uses synchronized folders: new Swift files are picked up automatically.

## Conventions

- Logic goes in `sharedLogic` (with tests); the UIs only render state and call controller methods.
- Kotlin → Swift: use `currentState` (StateFlow values are erased in Swift), sealed types become `Foo` + `FooBar`
  classes checked with `is`/`as?`, Kotlin default arguments are not available in Swift.
- The library screens are always dark (`LibraryColors` on both platforms); the photo access and enrollment screens
  follow the system theme.
- Naming: **storyline**, **note** (a photo's title and paragraph, `caption` in code and files), **tag**, **moment**,
  **set** (near-duplicates in Moments). There are no stories or groups any more.
- Never write to the photo library. `PhotoSafetyTest` fails on MediaStore/PhotoKit write APIs or write permissions.
- Files under `journal/` are formats other tools will read: version them (`"v": 1`), write to a temp file and rename,
  and never let a damaged file hide the others.

## Visualizing a screen before building it

When the owner asks to visualize (show, mock up) a screen, build the look only and show screenshots; the logic waits
until they ask for it:

1. Write only the Compose screen in `androidApp/.../ui/library/`: it takes a state object and an actions object, with
   no controllers, loading or navigation. If the data doesn't exist yet, define the state shape next to the screen;
   it is what the shared logic will produce later.
2. Add sample data to `androidApp/src/screenshotTest/.../PreviewData.kt` and a `<Screen>Screenshots.kt` beside it
   with the states worth seeing (light/dark, empty, offline, a menu or dialog open), each wrapped in `Screen { }`.
3. Run `./scripts/screenshots.sh`, look at the PNGs in `androidApp/src/screenshotTestDebug/reference/` (git-ignored),
   fix what renders wrong, then `open` them for the owner and give the paths.

Android only; the SwiftUI version follows once the design settles. Images are still, so each state is its own
preview. See [docs/previews.md](docs/previews.md).

## Working on the Windows machine (Android only)

The owner has a Mac and a Windows machine. On Windows there is no Xcode, so work there is shared logic and Android
only. Whenever a change there leaves iOS behind:

1. Say so in the screen's doc ("Android only; iOS not built yet", as `explore.md` does).
2. Add an entry to [docs/ios-todo.md](docs/ios-todo.md): what Android now does, the shared-logic state and controller
   methods the SwiftUI side should call, the Android files to mirror, new strings, and anything left to decide.
   Write it so a session on the Mac can build it without this conversation.
3. Tell the owner at the end that iOS is still to do.

On the Mac, read `docs/ios-todo.md` before starting iOS work and remove entries once built. Larger features planned
for later are in [docs/plans/](docs/plans/).

## Commands

No system JDK: use Homebrew's `openjdk@21` (or Android Studio's, if installed). `scripts/*.sh` find it themselves.

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home
./gradlew :sharedLogic:testAndroidHostTest          # shared tests on the JVM (includes PhotoSafetyTest)
./gradlew :sharedLogic:iosSimulatorArm64Test        # shared tests on the iOS simulator
./gradlew :androidApp:assembleDebug
./scripts/android-run.sh phone|emulator|<serial>    # build, install on one device, open (also VS Code tasks)
./scripts/screenshots.sh                            # render the screen previews to PNGs (docs/previews.md)
cd iosApp && xcodebuild -project iosApp.xcodeproj -scheme iosApp \
  -destination 'platform=iOS Simulator,name=iPhone 18 Pro' build
```

Test results land in `sharedLogic/build/test-results/<task>/*.xml`.

## Gotchas

- In this zsh, `grep --include=*.kt` fails ("no matches found"); quote the glob or search with Python.
- The Android emulator can't decode the owner's 4K HEVC videos: no thumbnails, no playback. Real devices can.
- Test photos: `~/Downloads/dcim` on the Mac. Copy to the emulator with `adb push -a ... /sdcard/DCIM/Camera/` and
  index with the `MEDIA_SCANNER_SCAN_FILE` broadcast; for the simulator use `xcrun simctl addmedia booted <files>`.
- Driving the iOS simulator needs WebDriverAgent (`scripts/wda-simulator.sh`, VS Code task) for mobile-mcp.
- On a phone, the server address entered at enrollment must be listed in the server certificate; `adb reverse`
  only helps the emulator.
