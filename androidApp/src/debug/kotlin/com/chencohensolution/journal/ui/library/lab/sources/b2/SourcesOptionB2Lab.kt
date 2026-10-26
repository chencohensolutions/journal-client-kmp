package com.chencohensolution.journal.ui.library

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.chencohensolution.journal.library.MediaSource

// Private settings hides all of WhatsApp, and screenshots of chosen apps: 40 of the phone's, 1,600 imported.
private val labState = SourcesOptionB2State(
    fromPhones = listOf(
        SourcesOptionB2State.Source(MediaSource.CAMERA, 1240, true),
        SourcesOptionB2State.Source(MediaSource.WHATSAPP, 0, false, allHiddenEverywhere = true),
        SourcesOptionB2State.Source(MediaSource.SCREENSHOTS, 88, false, hiddenEverywhere = 40),
        SourcesOptionB2State.Source(MediaSource.folder("Petcube"), 5, true),
        SourcesOptionB2State.Source(MediaSource.OTHER, 31, true),
    ),
    importedShown = true,
    importedKinds = listOf(
        SourcesOptionB2State.Source(MediaSource.CAMERA, 38410, true),
        SourcesOptionB2State.Source(MediaSource.WHATSAPP, 0, false, allHiddenEverywhere = true),
        SourcesOptionB2State.Source(MediaSource.SCREENSHOTS, 540, true, hiddenEverywhere = 1600),
        SourcesOptionB2State.Source(MediaSource.OTHER, 3917, true),
    ),
)

private fun List<SourcesOptionB2State.Source>.flip(source: MediaSource) = map { if (it.source == source) it.copy(shown = !it.shown) else it }

@Composable
private fun Live(content: @Composable (SourcesOptionB2State, SourcesOptionB2Actions) -> Unit) {
    var state by remember { mutableStateOf(labState) }
    content(
        state,
        SourcesOptionB2Actions(
            toggleFromPhones = { state = state.copy(fromPhones = state.fromPhones.flip(it)) },
            toggleImported = { state = state.copy(importedShown = !state.importedShown) },
            toggleImportedKind = { state = state.copy(importedKinds = state.importedKinds.flip(it)) },
        ),
    )
}

// Fakes: the app doesn't know which files were imported (Media.uploadedBy "import" isn't kept in the library yet), nor
// how many files of a source Private settings hides (the server leaves them out of the feed; the counts would come from
// hiddenCollections, which counts all of a user's files, not split by phones and imported); the counts are made up.
// "Private settings ›" and the locked pills do nothing; the switches don't change; toggles live only while it is open.
internal val sourcesOptionB2 = LabOption("b2", "Imported as one source, with Private settings", "Locked where Private settings hides on all devices") {
    Live { state, actions -> SourcesScreenB2(state, actions) }
}

// Fakes: as option b2; the "Showing" sheet is drawn open under the bar, not as a sheet.
internal val sourcesOptionB2Bar = LabOption("b2-bar", "Imported as one source, with Private settings: filter bar", "No chip for what is hidden on all devices") {
    Live { state, actions -> SourcesBarB2(state, actions) }
}
