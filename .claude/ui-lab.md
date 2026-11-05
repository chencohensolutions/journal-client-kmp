# UI lab notes (for the ui-lab skill)

How the lab itself works: [docs/previews.md](../docs/previews.md).

## Platform

| Machine (`uname -s`) | Lab app | Lab code | Open |
|---|---|---|---|
| Mac (Darwin) | iOS UI Lab on the simulator | `iosApp/iosApp/Lab/` | `./scripts/ui-lab-ios.sh <screen> [option]` |
| Windows | Android UI Lab on the owner's phone when `adb devices` lists it, else the emulator | `androidApp/src/debug/` | `./scripts/ui-lab.sh <screen> [option]` in Git Bash (`DEVICE=emulator` or `DEVICE=<serial>` to choose) |

Mocks go on the other platform when the owner asks for it. Both UI Lab apps (`com.chencohensolution.journal.uilab`)
install beside Journal, which keeps running and backing up.

## Option folders

- Android: `androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/lab/<screen>/<b>/`, in the
  screens' package (`com.chencohensolution.journal.ui.library`). Its strings files are in `src/debug/res/values/`
  and `values-iw/`, since Android resources can't sit in subfolders.
- iOS: `iosApp/iosApp/Lab/<Screen>/Option<B>/`, strings included.

## Files

- Files are `.kt` / `.swift`. The screen uses strings from resources and `LibraryColors`.
- State is shaped as the controller would produce it, and moves to `sharedLogic` (from Swift, rewritten in Kotlin).
- Strings: Android `strings_<screen>_<b>.xml` (names prefixed `<screen>_<b>_`); iOS `<Screen>Option<B>.xcstrings`
  (`table: "<Screen>Option<B>"`).

## Sample data

`PreviewData` (Android) and `LabData` (iOS) as they are.

## Fakes and edge cases

- Usual fakes: data the server or shared logic lacks; timing, the photo library, the server.
- Edge cases: Hebrew (RTL), long titles and notes, empty, offline.

## Screenshots

- iOS: `xcrun simctl io <udid> screenshot <file>.png`. `open -a Simulator` fails on this Mac; the launch works anyway.
- Android: `adb -s <serial> exec-out screencap -p > <file>.png`, with the serial the script printed.

## Close the round

Link the history file from the screen's doc in `docs/screens/` under "Design history". History title platform: the
lab's (iOS or Android).

## Promote

- Other platform: on the Mac the Android screen, on Windows an entry in `docs/ios-todo.md`. Update `docs/screens/`.
- Screen target: Android `src/main/.../ui/library/`; iOS `iosApp/iosApp/`, without its `#if DEBUG`.
- Strings: Android `src/main/res/`; iOS `Localizable.xcstrings`.
- State: into `sharedLogic`, produced by the controller with tests.
- Release builds: `./gradlew :androidApp:assembleRelease`, and on the Mac
  `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -configuration Release -destination 'generic/platform=iOS Simulator' build`.
