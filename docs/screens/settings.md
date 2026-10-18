# Settings

The user's choices, kept on this device (SharedPreferences / NSUserDefaults). Opened from the Journal's **⋯** menu.

## Layout

- **Top bar:** back and "Settings".
- **Unsorted photos on the Journal:** how the Journal splits the photos that are in no story into rows.
  - **By day** (default): one row for each calendar day.
  - **By breaks between photos:** a new row starts after a break without photos, so an evening that runs past
    midnight, or a day out that starts before dawn, stays in one row.
  - With breaks chosen: **New row after a break of** 2, 4, 6, 8, **12** (default), 24 or 48 hours.

## Behavior

- A change applies right away; the Journal rebuilds its rows from the same photos.
- The break length is kept while **By day** is chosen, so switching back restores it.
- The setting changes only the Journal's unsorted rows. All photos, tags and the story page stay by day, and so do
  the "+ Add <day>" suggestions on a story page.

## Code

- Shared: `SettingsController` and `TimelineGrouping` in
  [settings/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/settings/SettingsController.kt);
  the rows are `StoriesState.moments`, built by `groupMoments` in
  [StoriesController.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/StoriesController.kt).
- Android: [SettingsScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/SettingsScreen.kt).
- iOS: [SettingsView.swift](../../iosApp/iosApp/SettingsView.swift), [SettingsViewModel.swift](../../iosApp/iosApp/SettingsViewModel.swift).
