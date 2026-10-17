# Photo access

Asks for the photo library before the app shows anything, and explains why first, so the system prompt isn't a
surprise.

**Shown:** after enrollment, while `LibraryState.access` is `NOT_DETERMINED`. Once the user answers (allow, deny or
"Not now") it never comes back on launch; the Journal offers access again instead.

## Layout

From top to bottom:

1. A picture of three tilted photo cards, the front one with a "back up" badge.
2. **Your journal starts with your photos and videos**, and one line on why access is needed.
3. Two points:
   - **Your moments, day by day:** photos and videos, grouped like a diary.
   - **Backed up to your server:** nothing is uploaded until backup is turned on.
4. **Allow access** (main button) and **Not now**.
5. A note that the user can share only some photos.

## Actions

| Action | Android | iOS |
|---|---|---|
| Allow access | Requests `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO` and `READ_MEDIA_VISUAL_USER_SELECTED`. The system dialog offers "Allow all", "Allow limited access" and "Don't allow". | `PHPhotoLibrary.requestAuthorization(for: .readWrite)` |
| Not now | `LibraryController.skipAccess()`; opens the Journal without photos | Same |

Whatever the answer, the app calls `LibraryController.onAccessRequested()`, which stores that the question was
answered and loads the library if access was given.

## After a denial

The Journal shows a **Journal can't see your photos** card with an **Allow access** button.

- **Android** shows the system dialog again after one denial. After a second denial it stops showing it, so the
  button opens the app's page in Settings instead.
- **iOS** shows the system prompt only once, so the button opens Settings.

## Code

- Android: [PhotoAccessScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/PhotoAccessScreen.kt);
  the permission request is in [LibraryApp.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/LibraryApp.kt).
  Permissions are declared in `AndroidManifest.xml`.
- iOS: [PhotoAccessView.swift](../../iosApp/iosApp/PhotoAccessView.swift); the request is in
  [LibraryViewModel.swift](../../iosApp/iosApp/LibraryViewModel.swift). The prompt text is
  `NSPhotoLibraryUsageDescription` in `Info.plist`.
