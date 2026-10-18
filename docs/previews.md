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
- [PreviewData.kt](../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/PreviewData.kt) holds that
  state: a library, stories and unsorted days, dated relative to today.
- In a preview there is no photo library, so `rememberThumbnail` draws a colored placeholder at the photo's aspect
  ratio instead ([MediaTile.kt](../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/MediaTile.kt)).
- The previews are in `androidApp/src/screenshotTest/kotlin/`, one file per screen. Each function is marked
  `@PreviewTest` and has one `@Preview` per variant (light, dark, offline…). Google's Compose Preview Screenshot
  Testing plugin (`com.android.compose.screenshot`, still alpha) renders them on the JVM.
- The same `@Preview` functions show live in Android Studio's preview pane, if you use it.

## Adding a screen

1. Add what the screen needs to `PreviewData`.
2. Add `<Screen>Screenshots.kt` next to `JournalHomeScreenshots.kt`, with a `@PreviewTest` function per state.
3. Run `./scripts/screenshots.sh` and look at the images.

Gradle needs a JDK (17 or newer). The script uses `JAVA_HOME`, else Homebrew's `openjdk@21`
(`brew install openjdk@21`), else the JDK bundled with Android Studio.
