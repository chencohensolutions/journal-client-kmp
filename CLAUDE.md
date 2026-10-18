# Journal client (Kotlin Multiplatform)

Android and iOS apps for a private photo journal served by `../journal-server-go`. **Read
[docs/design.md](docs/design.md) first**: what the product is, the decisions behind it, the data format, what is
built and what is next. Each screen is described in [docs/screens/](docs/screens/README.md); update those docs
whenever a screen changes.

## Layout

- `sharedLogic/` — all logic, shared. `commonMain`: controllers with a `StateFlow` state
  (`JournalController` connection, `EnrollmentController`, `LibraryController` photo library, `StoriesController`
  stories/groups/tags/notes and the journal's moments, `SettingsController` the Settings screen). `androidMain` / `iosMain`: platform pieces (mTLS client, key store, MediaStore /
  PhotoKit readers) and the factories (`AndroidJournal.kt`, `IosJournal.kt`).
- `androidApp/` — Jetpack Compose UI; library screens in `ui/library/`. Strings in `res/values/strings.xml`
  (Hebrew in `values-iw/`).
- `iosApp/iosApp/` — SwiftUI UI. View models wrap the Kotlin controllers (`observe` + `currentState`). Strings in
  `Localizable.xcstrings`. The Xcode project uses synchronized folders: new Swift files are picked up automatically.

## Conventions

- Logic goes in `sharedLogic` (with tests); the UIs only render state and call controller methods.
- Kotlin → Swift: use `currentState` (StateFlow values are erased in Swift), sealed types become `Foo` + `FooBar`
  classes checked with `is`/`as?`, Kotlin default arguments are not available in Swift.
- The library screens are always dark (`LibraryColors` on both platforms); the photo access and enrollment screens
  follow the system theme.
- Naming: **story** (never "event"), **group**, **tag**, **note**. Stories have no dates; derive from photos.
- Never write to the photo library. `PhotoSafetyTest` fails on MediaStore/PhotoKit write APIs or write permissions.
- Files under `journal/` are formats other tools will read: version them (`"v": 1`), write to a temp file and rename,
  and never let a damaged file hide the others.

## Commands

No system JDK: use Android Studio's.

```sh
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew :sharedLogic:testAndroidHostTest          # shared tests on the JVM (includes PhotoSafetyTest)
./gradlew :sharedLogic:iosSimulatorArm64Test        # shared tests on the iOS simulator
./gradlew :androidApp:assembleDebug
./scripts/android-run.sh phone|emulator|<serial>    # build, install on one device, open (also VS Code tasks)
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
