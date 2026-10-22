package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import com.chencohensolution.journal.connection.ServerHealth
import com.chencohensolution.journal.settings.SettingsState
import com.chencohensolution.journal.stories.CropKind
import com.chencohensolution.journal.stories.PhotoCrops

// The UI lab's library (UiLabActivity): every screen, each with the app's current version first and then the
// suggestions for it. A suggestion is a composable in <Screen>Suggestions.kt next to this file, added to its
// screen's list after `current`; when one is chosen it moves to src/main and replaces the current version.

/** The screen as the app draws it now, always the first option. */
private fun current(content: @Composable () -> Unit) = LabOption("current", "Current", content = content)

internal val labScreens: List<LabScreen> = listOf(
    LabScreen("storyline", "Storyline", listOf(
        current { JournalHomeScreen(PreviewData.library, PreviewData.feedStories, ServerHealth.OK, HomeActions()) },
    )),
    LabScreen("moments", "Moments", listOf(
        current {
            JournalHomeScreen(PreviewData.library, PreviewData.feedStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS)
        },
    )),
    LabScreen("moments-hidden", "Moments, sources hidden", listOf(
        current {
            JournalHomeScreen(PreviewData.library, PreviewData.hiddenStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.MOMENTS)
        },
    )),
    LabScreen("explore", "Explore", listOf(
        current {
            JournalHomeScreen(PreviewData.placesLibrary, PreviewData.placesStories, ServerHealth.OK, HomeActions(), initialTab = HomeTab.EXPLORE)
        },
    )),
    LabScreen("explore-years", "Explore by years", listOf(
        current {
            JournalHomeScreen(
                PreviewData.exploreLibrary,
                PreviewData.exploreStories,
                ServerHealth.OK,
                HomeActions(),
                initialTab = HomeTab.EXPLORE,
                initialExploreView = ExploreView.YEARS,
            )
        },
    )),
    LabScreen("country", "Country", listOf(
        current { CountryScreen("France", PreviewData.placesLibrary, PreviewData.placesStories, onOpenPlace = {}, onBack = {}) },
    )),
    LabScreen("offline", "Offline", listOf(
        current { JournalHomeScreen(PreviewData.library, PreviewData.stories, ServerHealth.OFFLINE, HomeActions()) },
    )),
    LabScreen("viewer", "Viewer", listOf(
        current {
            LibraryTheme(dark = true) {
                ViewerScreen(
                    listOf(PreviewData.tripPhoto, PreviewData.notedPhoto),
                    PreviewData.tripPhoto.id,
                    PreviewData.feedStories,
                    ViewerActions(),
                    chromeAtStart = true,
                )
            }
        },
    )),
    LabScreen("viewer-info", "Viewer info panel", listOf(
        current {
            LabInfoBackdrop { modifier ->
                InfoPanel(
                    labInfoItem,
                    note = labInfoNote,
                    onAddTag = {},
                    onRemoveTag = {},
                    setInStoryline = {},
                    location = labInfoLocation,
                    setPrivate = { _, _ -> true },
                    openMap = {},
                    modifier = modifier,
                )
            }
        },
    )),
    LabScreen("crop", "Crop", listOf(
        current {
            val crops = PreviewData.marketCrops
            CropScreen(PreviewData.croppedPhoto, CropKind.MOMENTS, crops.moments!!, PhotoCrops(moments = crops.moments), PreviewData.SCREEN_RATIO, CropActions())
        },
    )),
    LabScreen("settings", "Settings", listOf(
        current { SettingsScreen(SettingsState(), SettingsActions()) },
    )),
)
