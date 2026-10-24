# Screen previews (Android)

Android screens can be rendered to PNGs with made-up data, without a phone, an emulator or Android Studio. Use it
to see a screen while changing its look; the real data comes later from the shared controllers.

```bash
./scripts/screenshots.sh          # renders every preview (first run about 1.5 minutes, then seconds)
./scripts/screenshots.sh check    # compares with the committed images; fails if a screen changed
```

The images land in `androidApp/src/screenshotTestDebug/reference/`, one per preview, named after the function and
the preview's name. Open them in any image viewer or in VS Code. They are committed, so a change to how a screen
looks shows up as an image diff in review.

## How it works

- Screens take their data as parameters (`JournalHomeScreen(library, stories, health, actions)`), so a preview
  passes made-up state instead of the controllers' state.
- [PreviewData.kt](../androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/PreviewData.kt) holds that
  state: a library, notes (writing, tags) and the Moments feed, dated relative to today.
- There is no photo library in a screenshot, so every preview is wrapped in `Screen { … }`
  ([Placeholders.kt](../androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/Placeholders.kt)),
  which sets the app's themes and draws a colored placeholder for each photo. The app's only hook for this is
  `LocalThumbnailSource` in [Thumbnails.kt](../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/Thumbnails.kt):
  null in the app, where thumbnails come from MediaStore.
- The previews are in `androidApp/src/screenshotTest/kotlin/`, one file per screen. Each function is marked
  `@PreviewTest` and has one `@Preview` per variant (light, dark, offline…). Google's Compose Preview Screenshot
  Testing plugin (`com.android.compose.screenshot`, still alpha) renders them on the JVM.
- The same `@Preview` functions show live in Android Studio's preview pane, if you use it.

## Adding a screen

1. Add what the screen needs to `PreviewData`.
2. Add `<Screen>Screenshots.kt` next to `JournalHomeScreenshots.kt`, with a `@PreviewTest` function per state.
3. Run `./scripts/screenshots.sh` and look at the images.
4. Add it to `labScreens` in the UI lab (below).

## The UI lab: screens and suggestions (on the phone or emulator)

A library of the app's screens and of suggested designs for them, with the same made-up data, running on the
phone or emulator so they scroll, tap and animate. The first page lists the screens; each screen lists its options,
the first being the screen as the app has it now, then the suggestions. UX suggestions are shown here.

```bash
./scripts/ui-lab.sh                    # build, install on the connected phone (else the emulator), open the list of screens
./scripts/ui-lab.sh moments            # a screen's options
./scripts/ui-lab.sh moments current    # one option (ids in LabScreens.kt)
DEVICE=emulator ./scripts/ui-lab.sh    # the emulator even with a phone connected (or DEVICE=<serial>)
```

The script builds a separate app, **UI Lab** (the `uiLab` build type: the debug build with the id
`com.chencohensolution.journal.uilab`, compiling `src/debug`, with `src/uiLab/AndroidManifest.xml` making the lab its
only launcher entry and dropping Journal's `journal://` links). It sits beside Journal on the phone, so the lab never
replaces it or stops its backup. It builds only the device's processor's native libraries (about 140 MB instead of
335), which over wireless debugging is about 30 seconds instead of 8 minutes.

The list is [LabScreens.kt](../androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/LabScreens.kt).
A suggestion is a composable in `<Screen>Suggestions.kt` beside it, added to its screen's list as a `LabOption`
after `current`. When one is chosen, its code moves into the real screen in `src/main`, and it leaves the list.

After the first build, a change to a screen is on the emulator in about 8 seconds. With Android Studio, run the
debug app's "Journal UI lab" activity and turn on Live Edit: a saved change to a composable shows in about a second,
without a rebuild (changes outside composables, such as new classes or resources, still need one).

The lab is [UiLabActivity.kt](../androidApp/src/debug/kotlin/com/chencohensolution/journal/ui/library/UiLabActivity.kt)
with its own [manifest](../androidApp/src/debug/AndroidManifest.xml), both in `src/debug`, as are `PreviewData` and
`Placeholders`: release builds never compile them. It shows the real screens from `src/main`; taps that would
reach a controller do nothing.

Gradle needs a JDK (17 or newer). The script uses `JAVA_HOME`, else Homebrew's `openjdk@21`
(`brew install openjdk@21`), else the JDK bundled with Android Studio.

## The UI lab on iOS (on the simulator)

The same lab in SwiftUI, with the same screen and option ids, for reviewing designs on the simulator, which is
lighter on the Mac than the emulator.

```bash
./scripts/ui-lab-ios.sh                    # build, install on a booted simulator, open the list of screens
./scripts/ui-lab-ios.sh moments            # a screen's options
./scripts/ui-lab-ios.sh moments current    # one option (ids in LabScreens.swift)
SIMULATOR="iPhone Air" ./scripts/ui-lab-ios.sh   # another booted simulator (default: iPhone 18 Pro, else the first booted)
```

The script builds a separate app, **UI Lab**: the debug app under its own name and bundle id
(`com.chencohensolution.journal.uilab`), compiled with `UI_LAB_APP` so it always opens the lab, and with
`iosApp/Configuration/UiLab-Info.plist` so it doesn't take the simulator's `journal://` enrollment links. It sits
beside Journal on the simulator and doesn't replace it; once installed, tap its icon to open the list of screens.

The first build takes about 40 seconds; later ones only rebuild what changed. Shake (⌃⌘Z in the Simulator, or
Device › Shake) goes back a page. In Xcode, Journal's Debug builds open the lab with launch arguments (`-uiLab YES
-labScreen moments -labOption current`, in the scheme's Run › Arguments), and the `UI lab` preview in `UiLab.swift`
shows it in the canvas with live updates as you type.

The list is [LabScreens.swift](../iosApp/iosApp/Lab/LabScreens.swift). A suggestion is a view in
`<Screen>Suggestions.swift` in `iosApp/iosApp/Lab/`, added to its screen's list as a `LabOption` after `current`.
When one is chosen, its code moves into the real view and it leaves the list. The lab shows the real views, with
`HomeActions.lab`, `ViewerActions.lab`, `SettingsActions.lab` and `FreeUpActions.lab` doing nothing. A screen opens on a tab or with its details showing through
the view's own parameters (`JournalHomeView(tab:)`, `MediaViewerView(chrome:showInfo:)`).

- The made-up data is [LabData.kt](../sharedLogic/src/iosMain/kotlin/com/chencohensolution/journal/lab/LabData.kt) in
  the shared logic's `iosMain`, a copy of Android's `PreviewData` (Swift can't use Kotlin's default arguments or
  `copy`). Keep the two in step when one changes.
- Pictures are drawn by `LabPictures.swift` (the same colors as Android's placeholders): while the lab is on
  (`UiLab.isOn`, also in Xcode previews) `PhotoLibraryImages` returns them and reads nothing from PhotoKit, and
  backup doesn't start.
- Everything in `Lab/` is inside `#if DEBUG`, so release builds don't compile it (`LabData` is in the framework but
  unused).
- A screen the lab shows is drawn from state values, not view models. Settings and Free up space are split that way:
  `SettingsScreen` and `FreeUpScreen` take the states and an actions struct, and the app's `SettingsView` and
  `FreeUpView` are thin wrappers that feed them from the view models and refresh on open. A new screen that reads
  view models needs the same split before it can join the lab; suggestions for Settings are views like
  `SettingsScreen`.
