package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.library.MediaSource

private val labState = SourcesOptionBState(
    fromPhones = listOf(
        SourcesOptionBState.Source(MediaSource.CAMERA, 1240, true),
        SourcesOptionBState.Source(MediaSource.WHATSAPP, 96, false),
        SourcesOptionBState.Source(MediaSource.SCREENSHOTS, 128, false),
        SourcesOptionBState.Source(MediaSource.folder("Petcube"), 5, true),
        SourcesOptionBState.Source(MediaSource.OTHER, 31, true),
    ),
    importedShown = true,
    importedKinds = listOf(
        SourcesOptionBState.Source(MediaSource.CAMERA, 38410, true),
        SourcesOptionBState.Source(MediaSource.WHATSAPP, 10282, false),
        SourcesOptionBState.Source(MediaSource.SCREENSHOTS, 2140, false),
        SourcesOptionBState.Source(MediaSource.OTHER, 3917, true),
    ),
)

private fun List<SourcesOptionBState.Source>.flip(source: MediaSource) = map { if (it.source == source) it.copy(shown = !it.shown) else it }

@Composable
private fun Live(content: @Composable (SourcesOptionBState, SourcesOptionBActions) -> Unit) {
    var state by remember { mutableStateOf(labState) }
    content(
        state,
        SourcesOptionBActions(
            toggleFromPhones = { state = state.copy(fromPhones = state.fromPhones.flip(it)) },
            toggleImported = { state = state.copy(importedShown = !state.importedShown) },
            toggleImportedKind = { state = state.copy(importedKinds = state.importedKinds.flip(it)) },
        ),
    )
}

// Fakes: the app doesn't know which files were imported (Media.uploadedBy "import" isn't kept in the library yet), so
// the counts are made up; the switches don't change; toggles live only while the option is open.
internal val sourcesOptionB = LabOption("b", "Imported as one source", "One Imported pill; its kinds under it while on") {
    Live { state, actions -> SourcesScreenB(state, actions) }
}

// Fakes: as option b; the "Showing" sheet is drawn open under the bar, not as a sheet.
internal val sourcesOptionBBar = LabOption("b-bar", "Imported as one source: filter bar", "One Imported chip in the bar") {
    Live { state, actions -> SourcesBarB(state, actions) }
}
