# Settings

The user's choices, kept on this device (SharedPreferences / NSUserDefaults). Opened from the Journal's **⋯** menu.

## Layout

- **Top bar:** back and "Settings".
- **Rows in Moments:** how the Moments tab splits the photos into rows.
  - **By day** (default): one row for each calendar day.
  - **By breaks between photos:** a new row starts after a break without photos, so an evening that runs past
    midnight, or a day out that starts before dawn, stays in one row.
  - With breaks chosen: **New row after a break of** 2, 4, 6, 8, **12** (default), 24 or 48 hours.
- **Suggestions for similar photos:** **How alike** a slider from 50% to 99%, with the value shown: the cosine
  similarity two photos' image embeddings need for a "Look alike" suggestion (see [group.md](group.md)). Higher
  suggests only near-identical photos together; lower makes bigger, looser sets. The default is the platform model's
  own (**60%** Android, **80%** iOS); **Reset to N%** shows when it's changed.

## Behavior

- A change applies right away; the Journal rebuilds its rows from the same photos.
- The break length is kept while **By day** is chosen, so switching back restores it.
- The similarity value is saved when the slider is let go; story and group suggestions update right away (no photo
  is read again). Near-duplicates by dHash are suggested at any value. Only the default is stored as "none", so a
  better default later reaches everyone who didn't choose one.
- The grouping setting changes only the rows of the Moments tab. All photos, tags and the story page stay by day, and so do
  the "+ Add <day>" suggestions on a story page.

## Code

- Shared: `SettingsController`, `TimelineGrouping` and `SimilarityThreshold` in
  [settings/](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/settings/SettingsController.kt);
  the rows are `StoriesState.moments`, built by `groupMoments` in
  [StoriesController.kt](../../sharedLogic/src/commonMain/kotlin/com/chencohensolution/journal/stories/StoriesController.kt).
- Android: [SettingsScreen.kt](../../androidApp/src/main/kotlin/com/chencohensolution/journal/ui/library/SettingsScreen.kt).
- iOS: [SettingsView.swift](../../iosApp/iosApp/SettingsView.swift), [SettingsViewModel.swift](../../iosApp/iosApp/SettingsViewModel.swift).
